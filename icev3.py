import bookmap as bm
from collections import deque, defaultdict
import time
import math
import threading
import requests
import json
from datetime import datetime, timedelta
import logging
import MetaTrader5 as mt5

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

class MT5PriceProvider:
    def __init__(self, symbol="XAUUSD"):
        self.symbol = symbol
        self.mt5_initialized = False
        self.last_price = None
        self._initialize()
        
    def _initialize(self):
        try:
            if not mt5.initialize():
                logging.error(f"MT5 initialization failed: {mt5.last_error()}")
                return False
            symbol_info = mt5.symbol_info(self.symbol)
            if symbol_info is None:
                return False
            if not symbol_info.visible:
                mt5.symbol_select(self.symbol, True)
            self.mt5_initialized = True
            return True
        except Exception as e:
            logging.error(f"Error MT5: {e}")
            return False
    
    def get_current_price(self):
        try:
            if not self.mt5_initialized: self._initialize()
            tick = mt5.symbol_info_tick(self.symbol)
            if tick:
                self.last_price = {'bid': tick.bid, 'ask': tick.ask, 'last': tick.last}
                return self.last_price
            return None
        except: return None
    
    def get_price_string(self, is_bid=None):
        p = self.get_current_price()
        if not p: return "N/A"
        if is_bid is None: return f"{p['last']:.2f}"
        return f"{p['bid']:.2f}" if is_bid else f"{p['ask']:.2f}"

    def shutdown(self):
        if self.mt5_initialized: mt5.shutdown()

class TelegramBot:
    def __init__(self, bot_token, chat_id, message_thread_id=None):
        self.bot_token = bot_token
        self.chat_id = chat_id
        self.message_thread_id = message_thread_id
        self.base_url = f"https://api.telegram.org/bot{bot_token}"
        self.last_message_time = {}
        self.message_cooldown = 1
        
    def send_message(self, text, parse_mode="HTML"):
        try:
            current_time = time.time()
            message_hash = hash(text[:100])
            if message_hash in self.last_message_time:
                if current_time - self.last_message_time[message_hash] < self.message_cooldown: return
            self.last_message_time[message_hash] = current_time
            
            payload = {'chat_id': self.chat_id, 'text': text, 'parse_mode': parse_mode, 'disable_web_page_preview': True}
            if self.message_thread_id: payload['message_thread_id'] = self.message_thread_id
            requests.post(f"{self.base_url}/sendMessage", json=payload, timeout=10)
        except Exception as e: logging.error(f"Telegram error: {e}")

class IcebergOrder:
    def __init__(self, order_id, price, initial_size, is_bid, timestamp, trader_id=None):
        self.order_id = order_id
        self.trader_id = trader_id
        self.initial_size = initial_size
        self.current_size = initial_size
        self.max_visible_size = initial_size
        self.is_bid = is_bid
        self.timestamp = timestamp
        self.last_update = timestamp
        self.last_reported_filled = 0 
        
        # Multilevel tracking
        self.price_history = [price]
        self.current_price = price
        self.price_changes = 0
        
        # Volume Metrics (V3 Core)
        self.total_filled = 0
        self.active_filled = 0   # Volume when the iceberg order was the AGGRESSOR
        self.passive_filled = 0  # Volume when the iceberg order was the PASSIVE side
        
        self.refill_count = 0
        self.size_decrease_count = 0
        self.replace_events = []
        self.is_confirmed_iceberg = False
        self.iceberg_start_time = None
        self.completion_time = None
        self.min_size_seen = initial_size
        self.execution_percentage = 0.0

    def add_replace_event(self, new_size, timestamp, new_price=None):
        old_size = self.current_size
        old_price = self.current_price
        size_change = new_size - old_size
        
        if new_price is not None and new_price != old_price:
            self.current_price = new_price
            if new_price not in self.price_history: self.price_history.append(new_price)
            self.price_changes += 1
            
        self.replace_events.append({'ts': timestamp, 'old_s': old_size, 'new_s': new_size, 'old_p': old_price, 'new_p': new_price})
        
        if size_change < 0:
            # We don't know if this replace-based decrease is active/passive without trade correlation, 
            # so we count it generally, but handle_trades will refine the specific volumes.
            self.size_decrease_count += 1
            
        if new_size < self.min_size_seen: self.min_size_seen = new_size
        if new_size > self.max_visible_size: self.max_visible_size = new_size
        if size_change > 0 and old_size < self.initial_size: self.refill_count += 1
            
        self.current_size = new_size
        self.last_update = timestamp
        
    def add_execution(self, filled_size, timestamp, is_active=False):
        """V3: Track volume based on role (Active vs Passive)"""
        if is_active:
            self.active_filled += filled_size
        else:
            self.passive_filled += filled_size
            
        self.total_filled += filled_size
        self.last_update = timestamp
        
        est_total = max(self.total_filled + self.current_size, self.max_visible_size)
        if est_total > 0: self.execution_percentage = self.total_filled / est_total

    def get_execution_ratio(self):
        return self.total_filled / self.max_visible_size if self.max_visible_size > 0 else 0.0

    def get_iceberg_score(self):
        score = 0.0
        if self.max_visible_size > 0 and (self.min_size_seen / self.max_visible_size) > 0.3: score += 0.3
        if self.refill_count >= 2: score += min(0.4, self.refill_count * 0.1)
        exec_ratio = self.get_execution_ratio()
        if exec_ratio > 1.5: score += min(0.3, exec_ratio * 0.1)
        return min(score, 1.0)

class RealMBOIcebergDetector:
    def __init__(self, alias, size_multiplier, pips, telegram_bots, mt5_provider):
        self.alias = alias
        self.size_multiplier = size_multiplier
        self.pips = pips
        self.telegram_bots = telegram_bots
        self.mt5_provider = mt5_provider
        self.active_orders = {}
        self.confirmed_icebergs = {}
        self.orders_by_price = defaultdict(list)
        self.execution_threshold = 0.7
        self.alert_execution_ratio_threshold = 40.0
        self.alert_total_filled_threshold = 80
        self.best_bid = None
        self.best_ask = None

    def get_side_indicator(self, is_bid):
        return "Side: BUY 🟢" if is_bid else "Side: SELL 🔴"

    def update_depth(self, is_bid, price, size):
        # Simplistic best bid/ask update
        if is_bid: self.best_bid = price
        else: self.best_ask = price

    def process_new_order(self, order_id, is_bid, price, size, trader_id=None):
        actual_size = size / self.size_multiplier if self.size_multiplier > 0 else size
        order = IcebergOrder(order_id, price, actual_size, is_bid, time.time(), trader_id)
        self.active_orders[order_id] = order
        self.orders_by_price[price].append(order_id)

    def process_replace_order(self, order_id, price, size):
        if order_id not in self.active_orders: return
        order = self.active_orders[order_id]
        actual_size = size / self.size_multiplier if self.size_multiplier > 0 else size
        
        if price != order.current_price:
            if order.current_price in self.orders_by_price:
                if order_id in self.orders_by_price[order.current_price]:
                    self.orders_by_price[order.current_price].remove(order_id)
            self.orders_by_price[price].append(order_id)
            
        order.add_replace_event(actual_size, time.time(), price)
        self._analyze_iceberg(order)

    def _analyze_iceberg(self, order):
        if order.is_confirmed_iceberg: return
        ratio = order.get_execution_ratio()
        if ratio >= self.alert_execution_ratio_threshold or order.total_filled >= self.alert_total_filled_threshold:
            order.is_confirmed_iceberg = True
            order.iceberg_start_time = time.time()
            order.last_reported_filled = order.total_filled
            self.confirmed_icebergs[order.order_id] = order
            self._send_notification(order, "detections", "Initial V3 Detection")

    def process_order_execution(self, order_id, executed_size, is_active=False):
        if order_id not in self.active_orders: return
        order = self.active_orders[order_id]
        order.add_execution(executed_size, time.time(), is_active)
        
        if order.is_confirmed_iceberg:
            if order.total_filled - order.last_reported_filled >= 25: # Throttle updates
                self._send_notification(order, "updates", "Execution Update")
                order.last_reported_filled = order.total_filled

    def process_cancel_order(self, order_id):
        if order_id not in self.active_orders: return
        order = self.active_orders[order_id]
        if order.is_confirmed_iceberg:
            order.completion_time = time.time()
            self._send_notification(order, "full_executions", "Full Execution")
        
        # Cleanup
        for p in order.price_history:
            if p in self.orders_by_price and order_id in self.orders_by_price[p]:
                self.orders_by_price[p].remove(order_id)
        del self.active_orders[order_id]

    def _send_notification(self, order, topic_key, reason):
        try:
            mt5_p = self.mt5_provider.get_price_string(order.is_bid)
            side = self.get_side_indicator(order.is_bid)
            
            # Multi-level price display
            price_display = f"💲 {order.current_price:.2f}"
            if len(order.price_history) > 1:
                price_display += f" (Levels: {len(order.price_history)})"

            msg = f"""
⏰ <b>{datetime.now().strftime('%H:%M:%S.%f')[:-3]}</b>
🧊 <b>V3 NATIVE ICEBERG - {reason.upper()}</b>
💰 <b>{side}</b>
🆔 <b>Order ID:</b> <code>{order.order_id}</code>
👤 <b>Trader ID:</b> <code>{order.trader_id or 'N/A'}</code>
💲 <b>Price (MT5):</b> {mt5_p}
📊 <b>Price (MBO):</b> {price_display}
🔢 <b>Current Size:</b> {order.current_size:,.0f}
📈 <b>Total Filled:</b> {order.total_filled:,.0f}
⚡ <b>Active Filled:</b> {order.active_filled:,.0f} 
🛡️ <b>Passive Filled:</b> {order.passive_filled:,.0f}
📊 <b>Exec Ratio:</b> {order.get_execution_ratio():.2f}x
🔄 <b>Refills:</b> {order.refill_count}
🔀 <b>Price Moves:</b> {order.price_changes}
🎯 <b>Status:</b> {"In Progress" if not order.completion_time else "COMPLETED"}
"""
            self.telegram_bots[topic_key].send_message(msg.strip())
        except Exception as e: logging.error(f"Notify Error: {e}")

# --- Bookmap Event Handlers ---

def handle_trades(addon, alias, price, size, is_otc, is_bid, is_execution_start, is_execution_end, aggressor_order_id, passive_order_id):
    if alias in alias_to_detector:
        detector = alias_to_detector[alias]
        actual_size = size / detector.size_multiplier if detector.size_multiplier > 0 else size
        
        # V3 Logic: Check if the iceberg is the active (aggressor) or passive side
        if passive_order_id in detector.active_orders:
            detector.process_order_execution(passive_order_id, actual_size, is_active=False)
            
        if aggressor_order_id in detector.active_orders:
            detector.process_order_execution(aggressor_order_id, actual_size, is_active=True)

def handle_mbo_event(addon, alias, event_type, order_id, price, size, trader_id=None):
    if alias in alias_to_detector:
        detector = alias_to_detector[alias]
        if event_type in ["ASK_NEW", "BID_NEW"]:
            detector.process_new_order(order_id, "BID" in event_type, price, size, trader_id)
        elif event_type == "REPLACE":
            detector.process_replace_order(order_id, price, size)
        elif event_type == "CANCEL":
            detector.process_cancel_order(order_id)

# --- Global Boilerplate ---
alias_to_detector = {}
mt5_price_provider = None
BOT_TOKEN = ""
CHAT_ID = ""
TOPICS = {'detections': 12355, 'updates': 12359, 'full_executions': 12362}

def handle_subscribe_instrument(addon, alias, full_name, is_crypto, pips, size_multiplier, instrument_multiplier, supported_features):
    global mt5_price_provider
    if mt5_price_provider is None: mt5_price_provider = MT5PriceProvider("XAUUSD")
    bots = {k: TelegramBot(BOT_TOKEN, CHAT_ID, v) for k, v in TOPICS.items()}
    detector = RealMBOIcebergDetector(alias, size_multiplier, pips, bots, mt5_price_provider)
    alias_to_detector[alias] = detector
    bm.subscribe_to_mbo(addon, alias, 1)
    bm.subscribe_to_trades(addon, alias, 2)
    bots['detections'].send_message("🚀 <b>V3 Native Iceberg System Online</b>")

def main():
    addon = bm.create_addon()
    bm.add_trades_handler(addon, handle_trades)
    bm.add_mbo_handler(addon, handle_mbo_event)
    bm.start_addon(addon, handle_subscribe_instrument, lambda a, b: None)
    bm.wait_until_addon_is_turned_off(addon)

if __name__ == "__main__":
    main()
