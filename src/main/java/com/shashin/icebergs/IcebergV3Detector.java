package com.shashin.icebergs;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * V3 iceberg detector — different mechanism from V2:
 * - No size filtering on new orders (tracks ALL orders)
 * - Replace events do NOT contribute to totalFilled
 * - All fills come from trade-to-order price matching
 * - Active/passive fill distinction
 * - Simple single-threshold detection (exec_ratio >= threshold OR total_filled
 * >= threshold)
 * - Higher default thresholds (40x exec ratio, 80 total filled)
 */
public class IcebergV3Detector {

    private final double sizeMultiplier;
    private final double pips;
    private final IcebergSettings settings;

    private final Map<String, IcebergV3Order> activeOrders = new ConcurrentHashMap<>();
    private final Set<String> confirmedIds = ConcurrentHashMap.newKeySet();
    private final Map<Integer, List<String>> ordersByPrice = new ConcurrentHashMap<>();

    private Integer bestBidTick = null;
    private Integer bestAskTick = null;

    private final Consumer<CompletedIcebergV3> onIcebergCompleted;

    public IcebergV3Detector(double sizeMultiplier, double pips, IcebergSettings settings,
            Consumer<CompletedIcebergV3> onIcebergCompleted) {
        this.sizeMultiplier = sizeMultiplier;
        this.pips = pips;
        this.settings = settings;
        this.onIcebergCompleted = onIcebergCompleted;
    }

    public static class CompletedIcebergV3 {
        public final double price;
        public final double totalFilled;
        public final double activeFilled;
        public final double passiveFilled;
        public final double maxVisibleSize;
        public final double executionRatio;
        public final boolean isBid;
        public final long timestamp;

        public CompletedIcebergV3(IcebergV3Order order, double pips) {
            this.price = order.currentPrice * pips;
            this.totalFilled = order.totalFilled;
            this.activeFilled = order.activeFilled;
            this.passiveFilled = order.passiveFilled;
            this.maxVisibleSize = order.maxVisibleSize;
            this.executionRatio = order.getExecutionRatio();
            this.isBid = order.isBid;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public void setBestBidAsk(Integer bestBidTick, Integer bestAskTick) {
        this.bestBidTick = bestBidTick;
        this.bestAskTick = bestAskTick;
    }

    // V3: No size filtering — ALL orders tracked
    public void processNewOrder(String orderId, boolean isBid, int priceTicks, int size) {
        long ts = System.currentTimeMillis();
        double actualSize = sizeMultiplier > 0 ? size / sizeMultiplier : size;
        IcebergV3Order order = new IcebergV3Order(orderId, priceTicks, actualSize, isBid, ts);
        activeOrders.put(orderId, order);
        ordersByPrice.computeIfAbsent(priceTicks, k -> new ArrayList<>()).add(orderId);
    }

    public void processReplaceOrder(String orderId, int priceTicks, int size) {
        IcebergV3Order order = activeOrders.get(orderId);
        if (order == null)
            return;

        long ts = System.currentTimeMillis();
        double actualSize = sizeMultiplier > 0 ? size / sizeMultiplier : size;
        int oldPriceTicks = (int) order.currentPrice;
        boolean priceChanged = (priceTicks != oldPriceTicks);

        if (priceChanged) {
            List<String> oldList = ordersByPrice.get(oldPriceTicks);
            if (oldList != null) {
                oldList.remove(orderId);
                if (oldList.isEmpty())
                    ordersByPrice.remove(oldPriceTicks);
            }
            ordersByPrice.computeIfAbsent(priceTicks, k -> new ArrayList<>()).add(orderId);
        }

        // V3: always pass price (matches Python behavior)
        order.addReplaceEvent(actualSize, ts, (double) priceTicks);

        // Detection check runs on replace (accumulated fills from trade matching are
        // checked here)
        analyzeIceberg(order);
    }

    public void processCancelOrder(String orderId) {
        IcebergV3Order order = activeOrders.remove(orderId);
        if (order == null)
            return;

        for (double priceD : order.priceHistory) {
            int priceTick = (int) priceD;
            List<String> list = ordersByPrice.get(priceTick);
            if (list != null) {
                list.remove(orderId);
                if (list.isEmpty())
                    ordersByPrice.remove(priceTick);
            }
        }

        // Final check: order might qualify at cancel time even if not detected earlier
        if (!order.isConfirmedIceberg) {
            double ratio = order.getExecutionRatio();
            if (ratio >= settings.v3ExecRatioThreshold || order.totalFilled >= settings.v3TotalFilledThreshold) {
                order.isConfirmedIceberg = true;
            }
        }

        if (order.isConfirmedIceberg) {
            order.completionTime = System.currentTimeMillis();
            confirmedIds.remove(orderId);
            if (onIcebergCompleted != null) {
                onIcebergCompleted.accept(new CompletedIcebergV3(order, pips));
            }
        }
    }

    // V3: Trade matching with active/passive distinction
    // Python gets order IDs directly from trade events (passive_order_id, aggressor_order_id).
    // Java API doesn't provide order IDs in trades, so we match by price + side.
    // We attribute the FULL trade size to each matched order (same as Python).
    public void addTrade(int priceTicks, double actualSize, boolean isBidAggressor) {
        List<String> ordersAtPrice = ordersByPrice.get(priceTicks);
        if (ordersAtPrice == null || ordersAtPrice.isEmpty())
            return;

        long ts = System.currentTimeMillis();

        // Match passive orders: resting on the opposite side of the aggressor
        // Match aggressor orders: on the same side as the aggressor
        for (String oid : new ArrayList<>(ordersAtPrice)) {
            IcebergV3Order order = activeOrders.get(oid);
            if (order == null) continue;

            if (order.isBid != isBidAggressor) {
                // This order is PASSIVE (resting, got hit by aggressor)
                order.addExecution(actualSize, ts, false);
                analyzeIceberg(order);
            } else {
                // This order is the AGGRESSOR (it initiated the trade)
                order.addExecution(actualSize, ts, true);
                analyzeIceberg(order);
            }
        }
    }

    // V3: Simple threshold detection — marks as confirmed but does NOT emit diamond yet.
    // Diamond is emitted on cancel (completion) with the full accumulated volume.
    private void analyzeIceberg(IcebergV3Order order) {
        if (order.isConfirmedIceberg)
            return;
        double ratio = order.getExecutionRatio();
        if (ratio >= settings.v3ExecRatioThreshold || order.totalFilled >= settings.v3TotalFilledThreshold) {
            order.isConfirmedIceberg = true;
            order.icebergStartTime = System.currentTimeMillis();
            confirmedIds.add(order.orderId);
            // Don't emit diamond here — wait for cancel to get full volume
        }
    }

    public void cleanupOldOrders() {
        long now = System.currentTimeMillis();
        activeOrders.entrySet().removeIf(e -> {
            if (now - e.getValue().lastUpdate > settings.getTimeWindowMs()) {
                String orderId = e.getKey();
                IcebergV3Order order = e.getValue();
                confirmedIds.remove(orderId);
                for (double priceD : order.priceHistory) {
                    int priceTick = (int) priceD;
                    List<String> list = ordersByPrice.get(priceTick);
                    if (list != null) {
                        list.remove(orderId);
                        if (list.isEmpty())
                            ordersByPrice.remove(priceTick);
                    }
                }
                return true;
            }
            return false;
        });
    }
}
