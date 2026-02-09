package com.shashin.icebergs;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * MBO-based native iceberg detector.
 * Ported from the Python RealMBOIcebergDetector class.
 * All prices stored as RAW TICK values (matching Python behavior).
 */
public class IcebergDetector {

    private final double sizeMultiplier;
    private final double pips;
    private final IcebergSettings settings;

    private final MarketMetrics marketMetrics = new MarketMetrics();

    // order tracking
    private final Map<String, IcebergOrder> activeOrders = new ConcurrentHashMap<>();
    private final Set<String> confirmedIds = ConcurrentHashMap.newKeySet();

    // order tracking by raw tick price level (for trade matching — matches Python orders_by_price)
    private final Map<Integer, List<String>> ordersByPrice = new ConcurrentHashMap<>();

    // order book context — raw tick prices (matches Python best_bid/best_ask)
    private Integer bestBidTick = null;
    private Integer bestAskTick = null;

    // volume sampling
    private double recentVolume = 0;
    private long lastVolumeSampleMs = System.currentTimeMillis();
    private static final long VOLUME_SAMPLE_INTERVAL_MS = 60_000;

    // callback for completed icebergs
    private final Consumer<CompletedIceberg> onIcebergCompleted;

    public IcebergDetector(double sizeMultiplier, double pips, IcebergSettings settings,
                           Consumer<CompletedIceberg> onIcebergCompleted) {
        this.sizeMultiplier = sizeMultiplier;
        this.pips = pips;
        this.settings = settings;
        this.onIcebergCompleted = onIcebergCompleted;
    }

    // --- public data class for completed iceberg events ---
    public static class CompletedIceberg {
        public final double price;
        public final double totalFilled;
        public final double maxVisibleSize;
        public final double executionRatio;
        public final boolean isBid;
        public final long timestamp;
        public final int refillCount;
        public final int priceChanges;
        public final double icebergScore;

        public CompletedIceberg(IcebergOrder order, double pips) {
            // Convert raw tick price to real price for display on chart
            this.price = order.currentPrice * pips;
            this.totalFilled = order.totalFilled;
            this.maxVisibleSize = order.maxVisibleSize;
            this.executionRatio = order.getExecutionRatio();
            this.isBid = order.isBid;
            this.timestamp = System.currentTimeMillis();
            this.refillCount = order.refillCount;
            this.priceChanges = order.priceChanges;
            this.icebergScore = order.getIcebergScore();
        }
    }

    // --- threshold calculation (matches Python get_dynamic_thresholds) ---
    private double[] getDynamicThresholds() {
        double avgOrderSize = marketMetrics.getAvgOrderSize();
        double volumeRate = marketMetrics.getVolumeRate();
        double percentileSize = marketMetrics.getPercentileOrderSize(75);

        double triggerSize = Math.max(
                avgOrderSize * (settings.triggerSizePercentage / 100.0),
                percentileSize * 0.5
        );
        double maxVisibleSize = avgOrderSize * (settings.maxVisiblePercentage / 100.0);
        double minVisibleSize = avgOrderSize * (settings.minVisiblePercentage / 100.0);

        triggerSize = Math.max(Math.min(triggerSize, 500), 20);
        maxVisibleSize = Math.max(Math.min(maxVisibleSize, 1000), 50);
        minVisibleSize = Math.max(Math.min(minVisibleSize, 100), 10);

        return new double[]{triggerSize, maxVisibleSize, minVisibleSize};
    }

    // --- best bid/ask tracking (raw tick prices, matches Python) ---
    public void setBestBidAsk(Integer bestBidTick, Integer bestAskTick) {
        this.bestBidTick = bestBidTick;
        this.bestAskTick = bestAskTick;
    }

    // --- MBO events (all prices are RAW TICK values, matching Python) ---
    public void processNewOrder(String orderId, boolean isBid, int priceTicks, int size) {
        long ts = System.currentTimeMillis();
        double actualSize = sizeMultiplier > 0 ? size / sizeMultiplier : size;

        marketMetrics.addOrder(actualSize);
        double[] thresholds = getDynamicThresholds();
        double minVisible = thresholds[2];
        double triggerSize = thresholds[0];
        double maxVisible = thresholds[1];

        if (actualSize >= minVisible) {
            // Store RAW tick price (not converted) — matches Python
            IcebergOrder order = new IcebergOrder(orderId, priceTicks, actualSize, isBid, ts);
            activeOrders.put(orderId, order);

            // Track by price level for trade matching (matches Python orders_by_price)
            ordersByPrice.computeIfAbsent(priceTicks, k -> new ArrayList<>()).add(orderId);

            double distPips = getDistanceFromBest(priceTicks, isBid);
            if (actualSize >= triggerSize && actualSize <= maxVisible && distPips <= settings.maxDistancePips) {
                // potential iceberg — tracked in activeOrders, checked on replace
            }
        }
    }

    public void processReplaceOrder(String orderId, int priceTicks, int size) {
        IcebergOrder order = activeOrders.get(orderId);
        if (order == null) return;

        long ts = System.currentTimeMillis();
        double actualSize = sizeMultiplier > 0 ? size / sizeMultiplier : size;

        // Compare raw tick prices (integer comparison, no floating-point issues)
        int oldPriceTicks = (int) order.currentPrice;
        boolean priceChanged = (priceTicks != oldPriceTicks);

        if (priceChanged) {
            // Remove from old price level (matches Python process_replace_order)
            List<String> oldList = ordersByPrice.get(oldPriceTicks);
            if (oldList != null) {
                oldList.remove(orderId);
                if (oldList.isEmpty()) {
                    ordersByPrice.remove(oldPriceTicks);
                }
            }
            // Add to new price level
            ordersByPrice.computeIfAbsent(priceTicks, k -> new ArrayList<>()).add(orderId);
        }

        // Pass raw tick price to order (matches Python)
        order.addReplaceEvent(actualSize, ts, priceChanged ? (double) priceTicks : null);

        analyzeIcebergPattern(order);
    }

    public void processCancelOrder(String orderId) {
        IcebergOrder order = activeOrders.remove(orderId);
        if (order == null) return;

        // Clean up from all price levels (matches Python process_cancel_order)
        for (double priceD : order.priceHistory) {
            int priceTick = (int) priceD;
            List<String> list = ordersByPrice.get(priceTick);
            if (list != null) {
                list.remove(orderId);
                if (list.isEmpty()) {
                    ordersByPrice.remove(priceTick);
                }
            }
        }

        if (order.isConfirmedIceberg) {
            order.completionTime = System.currentTimeMillis();
            confirmedIds.remove(orderId);
            if (onIcebergCompleted != null) {
                onIcebergCompleted.accept(new CompletedIceberg(order, pips));
            }
        }
    }

    // --- trade matching (matches Python add_trade + _match_trade_to_orders_v2) ---
    public void addTrade(int priceTicks, double actualSize, boolean isBidAggressor) {
        long ts = System.currentTimeMillis();
        marketMetrics.addTrade(actualSize);

        recentVolume += actualSize;
        if (ts - lastVolumeSampleMs >= VOLUME_SAMPLE_INTERVAL_MS) {
            marketMetrics.addVolumeSample(recentVolume);
            recentVolume = 0;
            lastVolumeSampleMs = ts;
        }

        // Price-based trade-to-order matching (matches Python _match_trade_to_orders_v2)
        matchTradeToOrders(priceTicks, actualSize, isBidAggressor);
    }

    private void matchTradeToOrders(int tradePriceTicks, double tradeSize, boolean isBidAggressor) {
        List<String> ordersAtPrice = ordersByPrice.get(tradePriceTicks);
        if (ordersAtPrice == null || ordersAtPrice.isEmpty()) return;

        // Find passive orders at this price (opposite side of aggressor)
        List<IcebergOrder> potentialOrders = new ArrayList<>();
        for (String oid : ordersAtPrice) {
            IcebergOrder order = activeOrders.get(oid);
            if (order != null && order.isBid != isBidAggressor) {
                potentialOrders.add(order);
            }
        }

        if (!potentialOrders.isEmpty()) {
            double executionPerOrder = tradeSize / potentialOrders.size();
            for (IcebergOrder order : potentialOrders) {
                double actualExecution = Math.min(executionPerOrder, order.currentSize);
                if (actualExecution > 0) {
                    processOrderExecution(order.orderId, actualExecution);
                }
            }
        }
    }

    // --- process execution from trade matching (matches Python process_order_execution) ---
    public void processOrderExecution(String orderId, double executedSize) {
        IcebergOrder order = activeOrders.get(orderId);
        if (order == null) return;

        long ts = System.currentTimeMillis();
        order.addExecution(executedSize, ts);

        // Check if confirmed iceberg is nearing completion
        if (order.isConfirmedIceberg &&
                (order.getExecutionRatio() >= settings.alertExecutionRatioThreshold ||
                        order.totalFilled >= settings.alertTotalFilledThreshold)) {
            if (order.executionPercentage >= settings.executionThreshold) {
                // Iceberg near completion — update fires on next confirmation callback
            }
        }
    }

    // --- iceberg pattern analysis (matches Python _analyze_iceberg_pattern_percentage) ---
    private void analyzeIcebergPattern(IcebergOrder order) {
        double execRatio = order.getExecutionRatio();
        double score = order.getIcebergScore();

        boolean shouldAlert = (execRatio >= settings.alertExecutionRatioThreshold ||
                order.totalFilled >= settings.alertTotalFilledThreshold);
        if (!shouldAlert) return;

        // Pattern 1: refill pattern
        if (order.refillCount >= settings.minRefillCount && !order.isConfirmedIceberg
                && score >= settings.minScoreThreshold) {
            confirmIceberg(order);
            return;
        }

        // Pattern 2: high execution ratio
        if (execRatio >= settings.alertExecutionRatioThreshold
                && order.currentSize >= order.minSizeSeen * 0.8
                && !order.isConfirmedIceberg) {
            confirmIceberg(order);
            return;
        }

        // Pattern 3: volume threshold breach
        if (order.totalFilled >= settings.alertTotalFilledThreshold
                && order.sizeDecreaseCount >= settings.minSizeDecreaseForVolume
                && !order.isConfirmedIceberg) {
            confirmIceberg(order);
            return;
        }

        // Pattern 4: consistent partial executions
        if (order.totalFilled >= settings.alertTotalFilledThreshold
                && order.sizeDecreaseCount >= settings.minSizeDecreaseForPartial
                && !order.isConfirmedIceberg) {
            confirmIceberg(order);
            return;
        }

        // Pattern 5: hidden liquidity with price changes
        if (execRatio >= settings.alertExecutionRatioThreshold
                && order.currentSize >= order.initialSize * settings.hiddenLiquiditySizeRatio
                && !order.isConfirmedIceberg) {
            confirmIceberg(order);
        }
    }

    private void confirmIceberg(IcebergOrder order) {
        order.isConfirmedIceberg = true;
        order.icebergStartTime = System.currentTimeMillis();
        confirmedIds.add(order.orderId);
        // Fire callback immediately on confirmation (not just on cancel)
        if (onIcebergCompleted != null) {
            onIcebergCompleted.accept(new CompletedIceberg(order, pips));
        }
    }

    // Distance from best bid/ask (matches Python _get_distance_from_best exactly)
    // Both price and bestBid/bestAsk are RAW tick values
    private double getDistanceFromBest(int priceTicks, boolean isBid) {
        if (pips > 0) {
            if (isBid && bestBidTick != null) return Math.abs(priceTicks - bestBidTick) / pips;
            if (!isBid && bestAskTick != null) return Math.abs(priceTicks - bestAskTick) / pips;
        }
        return 0;
    }

    // --- periodic cleanup ---
    public void cleanupOldOrders() {
        long now = System.currentTimeMillis();
        activeOrders.entrySet().removeIf(e -> {
            if (now - e.getValue().lastUpdate > settings.getTimeWindowMs()) {
                String orderId = e.getKey();
                IcebergOrder order = e.getValue();
                confirmedIds.remove(orderId);

                // Clean up from ordersByPrice
                for (double priceD : order.priceHistory) {
                    int priceTick = (int) priceD;
                    List<String> list = ordersByPrice.get(priceTick);
                    if (list != null) {
                        list.remove(orderId);
                        if (list.isEmpty()) {
                            ordersByPrice.remove(priceTick);
                        }
                    }
                }
                return true;
            }
            return false;
        });
    }
}
