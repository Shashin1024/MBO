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

# Configure logging for debugging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

class MT5PriceProvider:
    """Class to fetch current MT5 prices"""
    def __init__(self, symbol="XAUUSD"):
        self.symbol = symbol
        self.mt5_initialized = False
        self.last_price = None
        self._initialize()
        
    def _initialize(self):
        """Initialize MT5 connection"""
        try:
            if not mt5.initialize():
                logging.error(f"MT5 initialization failed: {mt5.last_error()}")
                return False
            
            # Check if symbol is available
            symbol_info = mt5.symbol_info(self.symbol)
            if symbol_info is None:
                logging.error(f"Symbol {self.symbol} not found")
                return False
            
            # Enable symbol if not visible
            if not symbol_info.visible:
                if not mt5.symbol_select(self.symbol, True):
                    logging.error(f"Failed to select symbol {self.symbol}")
                    return False
            
            self.mt5_initialized = True
            logging.info(f"MT5 Price Provider initialized for {self.symbol}")
            return True
            
        except Exception as e:
            logging.error(f"Error initializing MT5: {e}")
            return False
    
    def get_current_price(self):
        """Get current bid/ask price from MT5"""
        try:
            if not self.mt5_initialized:
                if not self._initialize():
                    return None
            
            tick = mt5.symbol_info_tick(self.symbol)
            if tick is None:
                logging.error(f"Failed to get tick data: {mt5.last_error()}")
                return None
            
            self.last_price = {
                'bid': tick.bid,
                'ask': tick.ask,
                'last': tick.last,
                'time': datetime.fromtimestamp(tick.time)
            }
            
            return self.last_price
            
        except Exception as e:
            logging.error(f"Error getting MT5 price: {e}")
            return None
    
    def get_price_string(self, is_bid=None):
        """Get formatted price string"""
        price_data = self.get_current_price()
        if not price_data:
            return "N/A"
        
        if is_bid is None:
            return f"{price_data['last']:.2f}"
        elif is_bid:
            return f"{price_data['bid']:.2f}"
        else:
            return f"{price_data['ask']:.2f}"
    
    def shutdown(self):
        """Shutdown MT5 connection"""
        if self.mt5_initialized:
            mt5.shutdown()
            logging.info("MT5 connection closed")

class TelegramBot:
    def __init__(self, bot_token, chat_id, message_thread_id=None):
        """
        Initializes the Telegram Bot.
        
        Args:
            bot_token (str): The token for your Telegram bot.
            chat_id (str): The ID of the target chat group.
            message_thread_id (int, optional): The ID of the target topic (thread). Defaults to None.
        """
        self.bot_token = bot_token
        self.chat_id = chat_id
        self.message_thread_id = message_thread_id  # Store the topic ID
        self.base_url = f"https://api.telegram.org/bot{bot_token}"
        self.message_queue = deque()
        self.last_message_time = {}
        self.message_cooldown = 1  # seconds between similar messages
        
    def send_message(self, text, parse_mode="HTML"):
        """Send message to a specific topic in a Telegram group with rate limiting"""
        try:
            # Rate limiting to prevent spam
            current_time = time.time()
            message_hash = hash(text[:100])  # Use first 100 chars as hash
            
            if message_hash in self.last_message_time:
                if current_time - self.last_message_time[message_hash] < self.message_cooldown:
                    return
            
            self.last_message_time[message_hash] = current_time
            
            url = f"{self.base_url}/sendMessage"
            payload = {
                'chat_id': self.chat_id,
                'text': text,
                'parse_mode': parse_mode,
                'disable_web_page_preview': True
            }

            if self.message_thread_id:
                payload['message_thread_id'] = self.message_thread_id
            
            response = requests.post(url, json=payload, timeout=10)
            if response.status_code == 200:
                logging.info(f"Message sent successfully to topic {self.message_thread_id}")
            else:
                logging.error(f"Failed to send message: {response.status_code} - {response.text}")
                
        except Exception as e:
            logging.error(f"Error sending Telegram message: {e}")

class IcebergOrder:
    def __init__(self, order_id, price, initial_size, is_bid, timestamp, trader_id=None):
        self.order_id = order_id
        self.trader_id = trader_id
        self.price = price
        self.initial_size = initial_size
        self.current_size = initial_size
        self.max_visible_size = initial_size
        self.is_bid = is_bid
        self.timestamp = timestamp
        self.last_update = timestamp
        
        # --- NEW ADDITION FOR THROTTLING ---
        # Track the volume at the last update to prevent spam
        self.last_reported_filled = 0 
        # -----------------------------------
        
        # Multi-price tracking for native icebergs
        self.price_history = [price]  # Track all prices this order has been at
        self.current_price = price
        self.price_changes = 0
        
        # Iceberg tracking
        self.total_filled = 0
        self.refill_count = 0
        self.size_decrease_count = 0
        self.replace_events = []
        self.execution_events = []
        self.is_confirmed_iceberg = False
        self.iceberg_start_time = None
        self.completion_time = None
        
        # Detection state
        self.execution_percentage = 0.0
        self.consecutive_refills = 0
        self.min_size_seen = initial_size
        
    def add_replace_event(self, new_size, timestamp, new_price=None):
        """Track replace events for iceberg detection with price changes"""
        old_size = self.current_size
        old_price = self.current_price
        size_change = new_size - old_size
        
        # Track price change for native iceberg
        if new_price is not None and new_price != old_price:
            self.current_price = new_price
            if new_price not in self.price_history:
                self.price_history.append(new_price)
            self.price_changes += 1
            
        self.replace_events.append({
            'timestamp': timestamp,
            'old_size': old_size,
            'new_size': new_size,
            'size_change': size_change,
            'old_price': old_price,
            'new_price': new_price if new_price else old_price,
            'price_changed': new_price is not None and new_price != old_price
        })
        
        # Track size decreases (likely executions)
        if size_change < 0:
            execution_size = abs(size_change)
            self.add_execution(execution_size, timestamp)
            self.size_decrease_count += 1
            
        # Track minimum size seen
        if new_size < self.min_size_seen:
            self.min_size_seen = new_size
            
        # Track maximum visible size
        if new_size > self.max_visible_size:
            self.max_visible_size = new_size
            
        # Detect refill (size increase after being partially filled)
        if size_change > 0 and old_size < self.initial_size:
            self.refill_count += 1
            self.consecutive_refills += 1
        else:
            self.consecutive_refills = 0
            
        self.current_size = new_size
        self.last_update = timestamp
        
    def add_execution(self, filled_size, timestamp):
        """Track execution events"""
        self.execution_events.append({
            'timestamp': timestamp,
            'filled_size': filled_size,
            'remaining_size': self.current_size
        })
        
        self.total_filled += filled_size
        self.last_update = timestamp
        
        # Update execution percentage based on estimated total size
        estimated_total_size = max(self.total_filled + self.current_size, self.max_visible_size)
        if estimated_total_size > 0:
            self.execution_percentage = self.total_filled / estimated_total_size
            
    def calculate_total_volume(self):
        """Calculate total volume that passed through this iceberg"""
        return self.total_filled
        
    def get_execution_ratio(self):
        """Get execution ratio vs maximum visible size"""
        if self.max_visible_size > 0:
            return self.total_filled / self.max_visible_size
        return 0.0
        
    def get_iceberg_score(self):
        """Calculate iceberg probability score (0-1)"""
        score = 0.0
        
        # Size consistency score
        if self.max_visible_size > 0:
            size_ratio = self.min_size_seen / self.max_visible_size
            if size_ratio > 0.3:
                score += 0.3
                
        # Refill pattern score
        if self.refill_count >= 2:
            score += min(0.4, self.refill_count * 0.1)
            
        # Execution volume score
        execution_ratio = self.get_execution_ratio()
        if execution_ratio > 1.5:
            score += min(0.3, execution_ratio * 0.1)
            
        return min(score, 1.0)
    
    def get_price_levels_summary(self):
        """Get summary of price levels this order visited"""
        if len(self.price_history) <= 1:
            return "Single price"
        
        sorted_prices = sorted(self.price_history)
        return f"{len(self.price_history)} levels: {sorted_prices[0]:.2f}-{sorted_prices[-1]:.2f}"

class MarketMetrics:
    """Class to track market metrics for percentage-based thresholds"""
    def __init__(self, window_size=100):
        self.window_size = window_size
        self.order_sizes = deque(maxlen=window_size)
        self.trade_sizes = deque(maxlen=window_size)
        self.volume_history = deque(maxlen=50)
        self.timestamp_history = deque(maxlen=window_size)
        
        # Cached metrics (updated periodically)
        self._avg_order_size = 0
        self._avg_trade_size = 0
        self._volume_rate = 0
        self._last_update = 0
        self._update_interval = 5
        
    def add_order(self, size):
        """Add new order size to tracking"""
        self.order_sizes.append(size)
        self.timestamp_history.append(time.time())
        self._maybe_update_metrics()
        
    def add_trade(self, size):
        """Add new trade size to tracking"""
        self.trade_sizes.append(size)
        self._maybe_update_metrics()
        
    def add_volume_sample(self, volume):
        """Add volume sample for rate calculation"""
        self.volume_history.append(volume)
        
    def _maybe_update_metrics(self):
        """Update cached metrics if enough time has passed"""
        current_time = time.time()
        if current_time - self._last_update >= self._update_interval:
            self._update_metrics()
            self._last_update = current_time
            
    def _update_metrics(self):
        """Update all cached metrics"""
        if self.order_sizes:
            self._avg_order_size = sum(self.order_sizes) / len(self.order_sizes)
        else:
            self._avg_order_size = 50
            
        if self.trade_sizes:
            self._avg_trade_size = sum(self.trade_sizes) / len(self.trade_sizes)
        else:
            self._avg_trade_size = 30
            
        if len(self.volume_history) >= 2:
            recent_volume = sum(list(self.volume_history)[-10:])
            self._volume_rate = recent_volume / min(10, len(self.volume_history))
        else:
            self._volume_rate = 100
            
    def get_avg_order_size(self):
        """Get average order size"""
        return max(self._avg_order_size, 10)
        
    def get_avg_trade_size(self):
        """Get average trade size"""
        return max(self._avg_trade_size, 5)
        
    def get_volume_rate(self):
        """Get current volume rate"""
        return max(self._volume_rate, 50)
        
    def get_percentile_order_size(self, percentile=75):
        """Get percentile-based order size threshold"""
        if not self.order_sizes:
            return 100
        sorted_sizes = sorted(self.order_sizes)
        index = int((percentile / 100) * len(sorted_sizes))
        index = min(index, len(sorted_sizes) - 1)
        return max(sorted_sizes[index], 20)

def get_timestamp_ms():
    """Get current timestamp with milliseconds"""
    now = datetime.now()
    return now.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

class RealMBOIcebergDetector:
    def __init__(self, alias, size_multiplier, pips, telegram_bots, mt5_provider):
        self.alias = alias
        self.size_multiplier = size_multiplier
        self.pips = pips
        self.telegram_bots = telegram_bots  # Dictionary of topic -> bot
        self.mt5_provider = mt5_provider
        
        # Market metrics for percentage-based thresholds
        self.market_metrics = MarketMetrics()
        
        # Percentage-based detection parameters
        self.trigger_size_percentage = 10.0
        self.max_visible_percentage = 150.0  
        self.min_visible_percentage = 10.0
        self.volume_threshold_percentage = 30.0
        
        # Alert thresholds
        self.alert_execution_ratio_threshold = 5.0
        self.alert_total_filled_threshold = 80
        
        # Fixed parameters
        self.time_window = 6000
        self.execution_threshold = 0.7
        self.iceberg_score_threshold = 0.6
        
        # Real MBO tracking - NATIVE ICEBERG ONLY (same order_id)
        self.active_orders = {}
        self.potential_icebergs = {}
        self.confirmed_icebergs = {}
        self.completed_icebergs = {}
        
        # Order tracking by price level
        self.orders_by_price = defaultdict(list)
        
        # Market context
        self.recent_trades = deque(maxlen=200)
        self.order_book_snapshot = {'bids': {}, 'asks': {}}
        self.best_bid = None
        self.best_ask = None
        
        # Statistics
        self.total_icebergs_detected = 0
        self.total_icebergs_completed = 0
        
        # Debug tracking
        self.debug_mode = True
        
        # Volume tracking
        self.recent_volume = 0
        self.volume_sample_interval = 60
        self.last_volume_sample = time.time()
        
    def get_side_indicator(self, is_bid):
        """Get side indicator with emoji"""
        if is_bid:
            return "Side: BID 🟢 --BUY--"
        else:
            return "Side: ASK 🔴 --SELL--"
        
    def get_dynamic_thresholds(self):
        """Calculate dynamic thresholds based on market metrics"""
        avg_order_size = self.market_metrics.get_avg_order_size()
        volume_rate = self.market_metrics.get_volume_rate()
        percentile_size = self.market_metrics.get_percentile_order_size(75)
        
        trigger_size = max(
            avg_order_size * (self.trigger_size_percentage / 100.0),
            percentile_size * 0.5
        )
        
        max_visible_size = avg_order_size * (self.max_visible_percentage / 100.0)
        min_visible_size = avg_order_size * (self.min_visible_percentage / 100.0)
        volume_threshold = volume_rate * (self.volume_threshold_percentage / 100.0)
        
        trigger_size = max(min(trigger_size, 500), 20)
        max_visible_size = max(min(max_visible_size, 1000), 50)
        min_visible_size = max(min(min_visible_size, 100), 10)
        
        return {
            'trigger_size': trigger_size,
            'max_visible_size': max_visible_size,
            'min_visible_size': min_visible_size,
            'volume_threshold': volume_threshold,
            'avg_order_size': avg_order_size
        }
        
    def update_depth(self, is_bid, price, size):
        """Update order book depth for context"""
        side = 'bids' if is_bid else 'asks'
        if size > 0:
            self.order_book_snapshot[side][price] = size
        elif price in self.order_book_snapshot[side]:
            del self.order_book_snapshot[side][price]
            
        if self.order_book_snapshot['bids']:
            self.best_bid = max(self.order_book_snapshot['bids'].keys())
        if self.order_book_snapshot['asks']:
            self.best_ask = min(self.order_book_snapshot['asks'].keys())
            
    def process_new_order(self, order_id, is_bid, price, size, trader_id=None):
        """Process new order from MBO - Native iceberg tracking"""
        timestamp = time.time()
        
        actual_size = size / self.size_multiplier if self.size_multiplier > 0 else size
        
        self.market_metrics.add_order(actual_size)
        
        thresholds = self.get_dynamic_thresholds()
        
        if actual_size >= thresholds['min_visible_size']:
            iceberg_order = IcebergOrder(order_id, price, actual_size, is_bid, timestamp, trader_id)
            self.active_orders[order_id] = iceberg_order
            
            self.orders_by_price[price].append(order_id)
            
            distance_pips = self._get_distance_from_best(price, is_bid)
            
            if (actual_size >= thresholds['trigger_size'] and
                actual_size <= thresholds['max_visible_size'] and
                distance_pips <= 50):
                
                self.potential_icebergs[order_id] = iceberg_order
                
                if self.debug_mode:
                    logging.info(f"[NATIVE ICEBERG] Potential tracked: {order_id} (Trader: {trader_id}) at {price:.2f}, "
                               f"size: {actual_size}, trigger: {thresholds['trigger_size']:.1f}")
                    
    def process_replace_order(self, order_id, price, size):
        """Process order replace/modify from MBO - NATIVE ICEBERG (same order_id, potentially different price)"""
        if order_id not in self.active_orders:
            return
            
        timestamp = time.time()
        order = self.active_orders[order_id]
        
        actual_size = size / self.size_multiplier if self.size_multiplier > 0 else size
        old_size = order.current_size
        old_price = order.current_price
        
        # Check if price changed (native iceberg moving across levels)
        price_changed = (price != old_price)
        
        if actual_size < old_size:
            execution_size = old_size - actual_size
            if self.debug_mode:
                logging.info(f"[NATIVE ICEBERG] Execution detected for {order_id} (Trader: {order.trader_id}): "
                           f"{execution_size} filled at {old_price:.2f}")
        
        if price_changed:
            # Remove from old price level
            if old_price in self.orders_by_price and order_id in self.orders_by_price[old_price]:
                self.orders_by_price[old_price].remove(order_id)
                if not self.orders_by_price[old_price]:
                    del self.orders_by_price[old_price]
            
            # Add to new price level
            self.orders_by_price[price].append(order_id)
            
            if self.debug_mode:
                logging.info(f"[NATIVE ICEBERG] Price change: {order_id} moved from {old_price:.2f} to {price:.2f}")
        
        # Update order with new price
        order.add_replace_event(actual_size, timestamp, price if price_changed else None)
        
        self._analyze_iceberg_pattern_percentage(order, old_size, actual_size, timestamp)
        
    def _analyze_iceberg_pattern_percentage(self, order, old_size, new_size, timestamp):
        """Percentage-based iceberg pattern analysis for NATIVE icebergs"""
        
        thresholds = self.get_dynamic_thresholds()
        
        iceberg_score = order.get_iceberg_score()
        execution_ratio = order.get_execution_ratio()
        
        should_alert = (execution_ratio >= self.alert_execution_ratio_threshold or 
                       order.total_filled >= self.alert_total_filled_threshold)
        
        if not should_alert:
            return
            
        # Pattern 1: Multiple refills (native iceberg characteristic)
        if order.refill_count >= 1:
            if not order.is_confirmed_iceberg and iceberg_score >= 0.4:
                reason = f"Native iceberg refill pattern (score: {iceberg_score:.2f}, exec_ratio: {execution_ratio:.2f}x, prices: {len(order.price_history)})"
                self._confirm_iceberg(order, reason)
                
        # Pattern 2: High execution ratio
        if execution_ratio >= self.alert_execution_ratio_threshold and order.current_size >= order.min_size_seen * 0.8:
            if not order.is_confirmed_iceberg:
                reason = f"Native iceberg high execution ratio: {execution_ratio:.2f}x across {len(order.price_history)} price(s)"
                self._confirm_iceberg(order, reason)
                
        # Pattern 3: Volume threshold breach
        if (order.total_filled >= self.alert_total_filled_threshold and
            order.size_decrease_count >= 2 and
            not order.is_confirmed_iceberg):
            reason = f"Native iceberg large volume: {order.total_filled:.1f} across {len(order.price_history)} price(s)"
            self._confirm_iceberg(order, reason)
            
        # Pattern 4: Large order with consistent partial executions
        if (order.total_filled >= self.alert_total_filled_threshold and
            order.size_decrease_count >= 3 and
            not order.is_confirmed_iceberg):
            reason = f"Native iceberg with partials: {order.total_filled:.1f} across {len(order.price_history)} price(s)"
            self._confirm_iceberg(order, reason)
            
        # Pattern 5: Hidden liquidity detection with price changes
        if (execution_ratio >= self.alert_execution_ratio_threshold and
            order.current_size >= order.initial_size * 0.6 and
            not order.is_confirmed_iceberg):
            reason = f"Native iceberg hidden liquidity: exec_ratio {execution_ratio:.2f}x, {order.price_changes} price changes"
            self._confirm_iceberg(order, reason)

    def process_order_execution(self, order_id, executed_size):
        """Process order execution with market metrics update"""
        if order_id not in self.active_orders:
            return
            
        timestamp = time.time()
        order = self.active_orders[order_id]
        
        order.add_execution(executed_size, timestamp)
        
        if (order.is_confirmed_iceberg and 
            (order.get_execution_ratio() >= self.alert_execution_ratio_threshold or 
             order.total_filled >= self.alert_total_filled_threshold)):
            
            if order.execution_percentage >= self.execution_threshold:
                self._handle_iceberg_near_completion(order)
            
    def process_cancel_order(self, order_id):
        """Process order cancellation from MBO - Native iceberg completion"""
        if order_id not in self.active_orders:
            return
            
        order = self.active_orders[order_id]
        
        # Remove from all price levels it was at
        for price in order.price_history:
            if price in self.orders_by_price:
                if order_id in self.orders_by_price[price]:
                    self.orders_by_price[price].remove(order_id)
                    if not self.orders_by_price[price]:
                        del self.orders_by_price[price]
        
        if order.is_confirmed_iceberg:
            order.completion_time = time.time()
            self.completed_icebergs[order_id] = order
            self._send_iceberg_completion_notification(order)
            self.total_icebergs_completed += 1
            
        self._cleanup_order(order_id)
        
    def add_trade(self, price, size, is_bid):
        """Add trade for market context and execution tracking"""
        timestamp = time.time()
        trade_info = {
            'timestamp': timestamp,
            'price': price,
            'size': size,
            'is_bid': is_bid
        }
        self.recent_trades.append(trade_info)
        
        self.market_metrics.add_trade(size)
        
        self.recent_volume += size
        if timestamp - self.last_volume_sample >= self.volume_sample_interval:
            self.market_metrics.add_volume_sample(self.recent_volume)
            self.recent_volume = 0
            self.last_volume_sample = timestamp
        
        self._match_trade_to_orders_v2(trade_info)
        
    def _match_trade_to_orders_v2(self, trade):
        """Enhanced trade matching to orders for execution analysis"""
        trade_price = trade['price']
        trade_size = trade['size']
        trade_is_bid = trade['is_bid']
        
        if trade_price in self.orders_by_price:
            potential_orders = []
            
            for order_id in self.orders_by_price[trade_price]:
                if order_id in self.active_orders:
                    order = self.active_orders[order_id]
                    if order.is_bid != trade_is_bid:
                        potential_orders.append(order)
                        
            if potential_orders:
                execution_per_order = trade_size / len(potential_orders)
                for order in potential_orders:
                    actual_execution = min(execution_per_order, order.current_size)
                    if actual_execution > 0:
                        self.process_order_execution(order.order_id, actual_execution)
                        
                        if self.debug_mode:
                            logging.info(f"[NATIVE ICEBERG] Matched trade execution: {order.order_id} (Trader: {order.trader_id}) "
                                       f"filled {actual_execution} at {trade_price:.2f}")
        
    def _confirm_iceberg(self, order, reason):
        """Confirm an order as a NATIVE iceberg and send notification"""
        order.is_confirmed_iceberg = True
        order.iceberg_start_time = time.time()
        
        # --- NEW ADDITION FOR THROTTLING ---
        # Initialize baseline so we don't send an Update immediately after Confirmation
        order.last_reported_filled = order.total_filled
        # -----------------------------------

        self.confirmed_icebergs[order.order_id] = order
        self.total_icebergs_detected += 1
        
        logging.info(f"[NATIVE ICEBERG CONFIRMED] {order.order_id} (Trader: {order.trader_id}), Reason: {reason}")
        self._send_iceberg_detection_notification(order, reason)
        
    def _get_distance_from_best(self, price, is_bid):
        """Calculate distance from best bid/ask in pips"""
        if self.pips > 0:
            if is_bid and self.best_bid:
                return abs(price - self.best_bid) / self.pips
            elif not is_bid and self.best_ask:
                return abs(price - self.best_ask) / self.pips
        return 0
        
    def _format_price(self, price):
        """Format price with proper decimal places - DO NOT CHANGE"""
        return f"{price:.2f}"
        
    def _send_iceberg_detection_notification(self, order, reason):
        """Send notification when NATIVE iceberg is first detected - DETECTIONS TOPIC"""
        try:
            side_indicator = self.get_side_indicator(order.is_bid)
            distance = self._get_distance_from_best(order.current_price, order.is_bid)
            iceberg_score = order.get_iceberg_score()
            execution_ratio = order.get_execution_ratio()
            
            mt5_price = self.mt5_provider.get_price_string(order.is_bid)
            timestamp = get_timestamp_ms()
            
            # Price history display
            price_info = f"💲 {self._format_price(order.current_price)}"
            if len(order.price_history) > 1:
                sorted_prices = sorted(order.price_history)
                price_range = f"{self._format_price(sorted_prices[0])}-{self._format_price(sorted_prices[-1])}"
                price_info += f"\n📊 <b>Price Range:</b> {price_range} ({len(order.price_history)} levels)"
            
            message = f"""
💰 <b>{side_indicator}</b>            
⏰ <b>{timestamp}</b>
🧊 <b>V2NATIVE ICEBERG DETECTED @ XAUUSD @ {mt5_price}</b>
🆔 <b>Order ID:</b> <code>{order.order_id}</code>
👤 <b>Trader ID:</b> <code>{order.trader_id or 'N/A'}</code>
💲 <b>Price (MT5):</b> {mt5_price}
{price_info}
📏 <b>Distance:</b> {distance:.1f} pips from best
🔢 <b>Current Size:</b> {order.current_size:,.0f}
📈 <b>Max Visible:</b> {order.max_visible_size:,.0f}
📊 <b>Total Filled:</b> {order.total_filled:,.0f}
📊 <b>Execution Ratio:</b> {execution_ratio:.2f}x
🔄 <b>Refills:</b> {order.refill_count}
📉 <b>Size Decreases:</b> {order.size_decrease_count}
🔄 <b>Replace Events:</b> {len(order.replace_events)}
🔀 <b>Price Changes:</b> {order.price_changes}
🎯 <b>Score:</b> {iceberg_score:.2f}

💡 <b>Reason:</b> {reason}
"""
            
            # Send to DETECTIONS topic
            self.telegram_bots['detections'].send_message(message.strip())
            
        except Exception as e:
            logging.error(f"Error sending native iceberg detection notification: {e}")
            
    def _handle_iceberg_near_completion(self, order):
        """Handle NATIVE iceberg approaching full execution"""
        if order.is_confirmed_iceberg and order.execution_percentage >= self.execution_threshold:
            # --- UPDATED LOGIC FOR THROTTLING ---
            # Only trigger update if total volume has increased by >= 20 since last report
            if order.total_filled - order.last_reported_filled >= 20:
                self._send_iceberg_execution_update(order)
                # Update the baseline
                order.last_reported_filled = order.total_filled
            # ------------------------------------
            
    def _send_iceberg_execution_update(self, order):
        """Send update when NATIVE iceberg shows significant execution - UPDATES TOPIC"""
        try:
            side_indicator = self.get_side_indicator(order.is_bid)
            execution_ratio = order.get_execution_ratio()
            
            mt5_price = self.mt5_provider.get_price_string(order.is_bid)
            timestamp = get_timestamp_ms()
            
            # Price history display
            price_info = f"💲 {self._format_price(order.current_price)}"
            if len(order.price_history) > 1:
                sorted_prices = sorted(order.price_history)
                price_range = f"{self._format_price(sorted_prices[0])}-{self._format_price(sorted_prices[-1])}"
                price_info += f"\n📊 <b>Price Range:</b> {price_range} ({len(order.price_history)} levels)"
            
            message = f"""
💰 <b>{side_indicator}</b>            
⏰ <b>{timestamp}</b>
⚡ <b>V2NATIVE ICEBERG EXECUTION UPDATE @ XAUUSD @ {mt5_price}</b>
🆔 <b>Order ID:</b> <code>{order.order_id}</code> (Native)
👤 <b>Trader ID:</b> <code>{order.trader_id or 'N/A'}</code>
💲 <b>Price (MT5):</b> {mt5_price}
{price_info}
📊 <b>Progress:</b> {order.execution_percentage*100:.1f}% executed
📈 <b>Total Filled:</b> {order.total_filled:,.0f}
🔢 <b>Current Size:</b> {order.current_size:,.0f}
📊 <b>Execution Ratio:</b> {execution_ratio:.2f}x visible size
🔄 <b>Refills:</b> {order.refill_count}
🔄 <b>Replace Events:</b> {len(order.replace_events)}
🔀 <b>Price Changes:</b> {order.price_changes}
🎯 <b>Status:</b>Being consumed
"""
            
            # Send to UPDATES topic
            self.telegram_bots['updates'].send_message(message.strip())
            
        except Exception as e:
            logging.error(f"Error sending native iceberg execution update: {e}")
            
    def _send_iceberg_completion_notification(self, order):
        """Send notification when NATIVE iceberg is completed - FULL EXECUTIONS TOPIC"""
        try:
            side_indicator = self.get_side_indicator(order.is_bid)
            duration = (order.completion_time - order.iceberg_start_time) / 60 if order.iceberg_start_time else 0
            execution_ratio = order.get_execution_ratio()
            iceberg_score = order.get_iceberg_score()
            distance = self._get_distance_from_best(order.current_price, order.is_bid)
            
            mt5_price = self.mt5_provider.get_price_string(order.is_bid)
            timestamp = get_timestamp_ms()
            
            # Add SOS emoji if volume is critical (> 600)
            volume_display = f"{order.total_filled:,.0f}"
            if order.total_filled > 400:
                volume_display = f"🆘 {volume_display}"
            
            # Price history display
            price_info = f"💲 {self._format_price(order.current_price)}"
            if len(order.price_history) > 1:
                sorted_prices = sorted(order.price_history)
                price_range = f"{self._format_price(sorted_prices[0])}-{self._format_price(sorted_prices[-1])}"
                price_info += f"\n📊 <b>Price Range:</b> {price_range} ({len(order.price_history)} levels)"
            
            message = f"""
💰 <b>{side_indicator}</b>
⏰ <b>{timestamp}</b>
✅ <b>V2NATIVE ICEBERG FULLY EXECUTED @ XAUUSD @ {mt5_price}</b>
🆔 <b>Order ID:</b> <code>{order.order_id}</code>
👤 <b>Trader ID:</b> <code>{order.trader_id or 'N/A'}</code>
💲 <b>Price (MT5):</b> {mt5_price}
{price_info}
📏 <b>Distance:</b> {distance:.1f} pips from best

📊 <b>Final Statistics:</b>
📈 <b>Total Filled:</b> {volume_display}
📈 <b>Max Visible:</b> {order.max_visible_size:,.0f}
📊 <b>Execution Ratio:</b> {execution_ratio:.2f}x
🔄 <b>Refills:</b> {order.refill_count}
📉 <b>Size Decreases:</b> {order.size_decrease_count}
🔄 <b>Replace Events:</b> {len(order.replace_events)}
🔀 <b>Price Changes:</b> {order.price_changes} moves
🎯 <b>Final Score:</b> {iceberg_score:.2f}
⏱️ <b>Duration:</b> {duration:.1f} minutes
"""
            
            # Send to FULL EXECUTIONS topic
            self.telegram_bots['full_executions'].send_message(message.strip())
            
        except Exception as e:
            logging.error(f"Error sending native iceberg completion notification: {e}")

            
            
    def _cleanup_order(self, order_id):
        """Clean up order from all tracking structures"""
        if order_id in self.active_orders:
            order = self.active_orders[order_id]
            # Clean up from all price levels
            for price in order.price_history:
                if price in self.orders_by_price:
                    if order_id in self.orders_by_price[price]:
                        self.orders_by_price[price].remove(order_id)
                        if not self.orders_by_price[price]:
                            del self.orders_by_price[price]
            del self.active_orders[order_id]
            
        if order_id in self.potential_icebergs:
            del self.potential_icebergs[order_id]
        if order_id in self.confirmed_icebergs:
            del self.confirmed_icebergs[order_id]
            
    def cleanup_old_orders(self):
        """Remove old orders that are no longer relevant"""
        current_time = time.time()
        expired_orders = []
        
        for order_id, order in list(self.active_orders.items()):
            if current_time - order.last_update > self.time_window:
                expired_orders.append(order_id)
                
        for order_id in expired_orders:
            self._cleanup_order(order_id)
            
        if self.debug_mode and expired_orders:
            logging.info(f"Cleaned up {len(expired_orders)} expired native iceberg orders")
            
    def get_statistics(self):
        """Get current detection statistics"""
        thresholds = self.get_dynamic_thresholds()
        return {
            'active_orders': len(self.active_orders),
            'potential_icebergs': len(self.potential_icebergs),
            'confirmed_icebergs': len(self.confirmed_icebergs),
            'completed_icebergs': len(self.completed_icebergs),
            'total_detected': self.total_icebergs_detected,
            'total_completed': self.total_icebergs_completed,
            'current_trigger_size': thresholds['trigger_size'],
            'avg_order_size': thresholds['avg_order_size']
        }

# Global variables
alias_to_detector = {}
alias_to_order_book = {}
alias_to_mbo_book = {}
mt5_price_provider = None

# Configuration - UPDATE WITH YOUR CREDENTIALS
BOT_TOKEN = "7654018525:AAFUdkMmAcxjr46PI2SHcC5t5TC07tDXPVo"
CHAT_ID = "-1002971134101"

# Topic IDs for different message types
TOPIC_DETECTIONS = 12355    # Iceberg Detections
TOPIC_UPDATES = 12359        # Execution Updates
TOPIC_FULL_EXECUTIONS = 12362  # Full Executions

MT5_SYMBOL = "XAUUSD"

def handle_subscribe_instrument(addon, alias, full_name, is_crypto, pips, size_multiplier, instrument_multiplier, supported_features):
    """Handle instrument subscription"""
    global mt5_price_provider
    
    try:
        # Create MT5 price provider (only once)
        if mt5_price_provider is None:
            mt5_price_provider = MT5PriceProvider(MT5_SYMBOL)
        
        # Create separate Telegram bot instances for each topic
        telegram_bots = {
            'detections': TelegramBot(BOT_TOKEN, CHAT_ID, TOPIC_DETECTIONS),
            'updates': TelegramBot(BOT_TOKEN, CHAT_ID, TOPIC_UPDATES),
            'full_executions': TelegramBot(BOT_TOKEN, CHAT_ID, TOPIC_FULL_EXECUTIONS)
        }
        
        # Create NATIVE iceberg detector with MT5 provider
        detector = RealMBOIcebergDetector(alias, size_multiplier, pips, telegram_bots, mt5_price_provider)
        alias_to_detector[alias] = detector
        
        # Create order books
        alias_to_order_book[alias] = bm.create_order_book()
        alias_to_mbo_book[alias] = bm.create_mbo_book()
        
        # Subscribe to data feeds
        req_id = int(time.time() * 1000) % 1000000
        bm.subscribe_to_depth(addon, alias, req_id)
        bm.subscribe_to_trades(addon, alias, req_id + 1)
        bm.subscribe_to_mbo(addon, alias, req_id + 2)
        
        logging.info(f"Successfully subscribed to {alias} with NATIVE ICEBERG detection and MT5 price integration")
        
        # Send startup message to detections topic
        startup_msg = f"""
⏰ <b>{get_timestamp_ms()}</b>
🚀 <b>Native Iceberg Detector Started @ XAUUSD</b>
"""
        telegram_bots['detections'].send_message(startup_msg.strip())
        
    except Exception as e:
        logging.error(f"Error in handle_subscribe_instrument: {e}")

def handle_unsubscribe_instrument(addon, alias):
    """Handle instrument unsubscription"""
    global mt5_price_provider
    
    try:
        if alias in alias_to_detector:
            detector = alias_to_detector[alias]
            stats = detector.get_statistics()
            
            timestamp = get_timestamp_ms()
            final_msg = f"""
⏰ <b>{timestamp}</b>
🛑 <b>Native Iceberg Detector Stopped @ XAUUSD</b>

📈 <b>Session Statistics:</b>
• Total Detected: {stats['total_detected']}
• Total Completed: {stats['total_completed']}
• Currently Active: {stats['active_orders']}
• Potential Icebergs: {stats['potential_icebergs']}
• Final Trigger Size: {stats['current_trigger_size']:.1f}
• Avg Order Size: {stats['avg_order_size']:.1f}

💡 <b>Status:</b> Native iceberg detection terminated
"""
            # Send to detections topic
            detector.telegram_bots['detections'].send_message(final_msg.strip())
            del alias_to_detector[alias]
            
        if alias in alias_to_order_book:
            del alias_to_order_book[alias]
        if alias in alias_to_mbo_book:
            del alias_to_mbo_book[alias]
        
        # Shutdown MT5 if no more detectors
        if not alias_to_detector and mt5_price_provider:
            mt5_price_provider.shutdown()
            mt5_price_provider = None
            
        logging.info(f"Unsubscribed from {alias}")
        
    except Exception as e:
        logging.error(f"Error in handle_unsubscribe_instrument: {e}")

def handle_depth_info(addon, alias, is_bid, price, size):
    """Handle depth/order book updates"""
    try:
        if alias in alias_to_order_book:
            bm.on_depth(alias_to_order_book[alias], is_bid, price, size)
            
        if alias in alias_to_detector:
            detector = alias_to_detector[alias]
            detector.update_depth(is_bid, price, size)
            
    except Exception as e:
        logging.error(f"Error in handle_depth_info: {e}")

def handle_trades(addon, alias, price, size, is_otc, is_bid, is_execution_start, is_execution_end, aggressor_order_id, passive_order_id):
    """Handle trade executions with order ID tracking"""
    try:
        if alias in alias_to_detector:
            detector = alias_to_detector[alias]
            actual_size = size / detector.size_multiplier if detector.size_multiplier > 0 else size
            
            # Add trade to general tracking
            detector.add_trade(price, actual_size, is_bid)
            
            # Track execution with order IDs (native iceberg)
            if passive_order_id and passive_order_id in detector.active_orders:
                detector.process_order_execution(passive_order_id, actual_size)
                if detector.debug_mode:
                    order = detector.active_orders[passive_order_id]
                    logging.info(f"[NATIVE ICEBERG] Direct execution: {passive_order_id} (Trader: {order.trader_id}) "
                               f"filled {actual_size} at {price:.2f}")
            
            # Also track aggressor if available
            if aggressor_order_id and aggressor_order_id in detector.active_orders:
                detector.process_order_execution(aggressor_order_id, actual_size)
                if detector.debug_mode:
                    order = detector.active_orders[aggressor_order_id]
                    logging.info(f"[NATIVE ICEBERG] Aggressor execution: {aggressor_order_id} (Trader: {order.trader_id}) "
                               f"filled {actual_size} at {price:.2f}")
            
    except Exception as e:
        logging.error(f"Error in handle_trades: {e}")

def handle_mbo_event(addon, alias, event_type, order_id, price, size, trader_id=None):
    """Handle Market By Order events - NATIVE ICEBERG tracking (same order_id, multiple prices possible)"""
    try:
        # Update MBO book first
        if alias in alias_to_mbo_book:
            book = alias_to_mbo_book[alias]
            
            if event_type == "ASK_NEW":
                bm.on_new_order(book, order_id, False, price, size)
            elif event_type == "BID_NEW":
                bm.on_new_order(book, order_id, True, price, size)
            elif event_type == "REPLACE":
                bm.on_replace_order(book, order_id, price, size)
            elif event_type == "CANCEL":
                bm.on_remove_order(book, order_id)
                
        # Process for NATIVE iceberg detection with trader ID
        if alias in alias_to_detector:
            detector = alias_to_detector[alias]
            
            if detector.debug_mode:
                actual_size = size / detector.size_multiplier if detector.size_multiplier > 0 else size
                thresholds = detector.get_dynamic_thresholds()
                logging.info(f"[NATIVE ICEBERG] MBO Event: {event_type} | Order: {order_id} | Trader: {trader_id} | "
                           f"Price: {price:.2f} | Size: {actual_size} | Alert: exec_ratio>=5x OR filled>=50")
            
            if event_type == "ASK_NEW":
                detector.process_new_order(order_id, False, price, size, trader_id)
            elif event_type == "BID_NEW":
                detector.process_new_order(order_id, True, price, size, trader_id)
            elif event_type == "REPLACE":
                # CRITICAL: For native icebergs, REPLACE with same order_id can have different price
                detector.process_replace_order(order_id, price, size)
            elif event_type == "CANCEL":
                detector.process_cancel_order(order_id)
                
    except Exception as e:
        logging.error(f"Error in handle_mbo_event: {e}")

def on_interval(addon, alias):
    """Periodic cleanup and maintenance"""
    try:
        if alias in alias_to_detector:
            detector = alias_to_detector[alias]
            detector.cleanup_old_orders()
            
            # Periodic statistics logging
            if detector.debug_mode:
                stats = detector.get_statistics()
                if stats['active_orders'] > 0:
                    thresholds = detector.get_dynamic_thresholds()
                    logging.info(f"[NATIVE ICEBERG] Stats for {alias}: Active: {stats['active_orders']}, "
                               f"Alert Conditions: exec_ratio>=5x OR filled>=50, "
                               f"Confirmed: {stats['confirmed_icebergs']}")
                    
                    # Log some active orders for debugging
                    for i, (order_id, order) in enumerate(list(detector.active_orders.items())[:3]):
                        exec_ratio = order.get_execution_ratio()
                        meets_alert = (exec_ratio >= 5.0 or order.total_filled >= 50)
                        logging.info(f"  [NATIVE] Active Order {i+1}: {order_id} (Trader: {order.trader_id}) | "
                                   f"Prices: {len(order.price_history)} levels | Current: {order.current_price:.2f} | "
                                   f"Size: {order.current_size} | Filled: {order.total_filled} | "
                                   f"ExecRatio: {exec_ratio:.2f}x | AlertMet: {meets_alert}")
            
    except Exception as e:
        logging.error(f"Error in on_interval: {e}")

def main():
    """Main function to start the addon"""
    try:
        # Validate configuration
        if "YOUR_BOT_TOKEN_HERE" in BOT_TOKEN or "YOUR_CHAT_ID_HERE" in CHAT_ID:
            print("ERROR: Please configure your BOT_TOKEN and CHAT_ID in the script")
            return
            
        logging.info("Starting NATIVE Iceberg Detector with Multi-Price Tracking and MT5 Integration...")
        
        # Create addon
        addon = bm.create_addon()
        
        # Register handlers
        bm.add_depth_handler(addon, handle_depth_info)
        bm.add_trades_handler(addon, handle_trades)
        bm.add_mbo_handler(addon, handle_mbo_event)
        bm.add_on_interval_handler(addon, on_interval)
        
        # Start addon
        bm.start_addon(addon, handle_subscribe_instrument, handle_unsubscribe_instrument)
        
        logging.info("NATIVE MBO Iceberg Detector started successfully. Tracking same order_id across multiple prices...")
        bm.wait_until_addon_is_turned_off(addon)
        
    except Exception as e:
        logging.error(f"Error in main: {e}")
    finally:
        # Cleanup MT5 connection
        global mt5_price_provider
        if mt5_price_provider:
            mt5_price_provider.shutdown()
        logging.info("Addon terminated.")

if __name__ == "__main__":
    main()