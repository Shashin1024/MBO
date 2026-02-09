package com.shashin.icebergs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Tracks running market statistics for percentage-based adaptive thresholds.
 * Ported from the Python MarketMetrics class.
 */
public class MarketMetrics {

    private final int windowSize;
    private final Deque<Double> orderSizes;
    private final Deque<Double> tradeSizes;
    private final Deque<Double> volumeHistory;

    private double avgOrderSize = 0;
    private double avgTradeSize = 0;
    private double volumeRate = 0;
    private long lastUpdateMs = 0;
    private static final long UPDATE_INTERVAL_MS = 5000;

    public MarketMetrics(int windowSize) {
        this.windowSize = windowSize;
        this.orderSizes = new ArrayDeque<>(windowSize);
        this.tradeSizes = new ArrayDeque<>(windowSize);
        this.volumeHistory = new ArrayDeque<>(50);
    }

    public MarketMetrics() {
        this(100);
    }

    public synchronized void addOrder(double size) {
        if (orderSizes.size() >= windowSize) orderSizes.pollFirst();
        orderSizes.addLast(size);
        maybeUpdate();
    }

    public synchronized void addTrade(double size) {
        if (tradeSizes.size() >= windowSize) tradeSizes.pollFirst();
        tradeSizes.addLast(size);
        maybeUpdate();
    }

    public synchronized void addVolumeSample(double volume) {
        if (volumeHistory.size() >= 50) volumeHistory.pollFirst();
        volumeHistory.addLast(volume);
    }

    private void maybeUpdate() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs >= UPDATE_INTERVAL_MS) {
            update();
            lastUpdateMs = now;
        }
    }

    private void update() {
        if (!orderSizes.isEmpty()) {
            double sum = 0;
            for (double s : orderSizes) sum += s;
            avgOrderSize = sum / orderSizes.size();
        } else {
            avgOrderSize = 50;
        }

        if (!tradeSizes.isEmpty()) {
            double sum = 0;
            for (double s : tradeSizes) sum += s;
            avgTradeSize = sum / tradeSizes.size();
        } else {
            avgTradeSize = 30;
        }

        if (volumeHistory.size() >= 2) {
            List<Double> list = new ArrayList<>(volumeHistory);
            int start = Math.max(0, list.size() - 10);
            double sum = 0;
            for (int i = start; i < list.size(); i++) sum += list.get(i);
            volumeRate = sum / Math.min(10, list.size());
        } else {
            volumeRate = 100;
        }
    }

    public synchronized double getAvgOrderSize() {
        return Math.max(avgOrderSize, 10);
    }

    public synchronized double getAvgTradeSize() {
        return Math.max(avgTradeSize, 5);
    }

    public synchronized double getVolumeRate() {
        return Math.max(volumeRate, 50);
    }

    public synchronized double getPercentileOrderSize(double percentile) {
        if (orderSizes.isEmpty()) return 100;
        List<Double> sorted = new ArrayList<>(orderSizes);
        Collections.sort(sorted);
        int index = (int) ((percentile / 100.0) * sorted.size());
        index = Math.min(index, sorted.size() - 1);
        return Math.max(sorted.get(index), 20);
    }
}
