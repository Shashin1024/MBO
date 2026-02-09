package com.shashin.icebergs;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks an individual MBO order through its lifecycle for iceberg detection.
 * Ported from the Python IcebergOrder class.
 */
public class IcebergOrder {

    public final String orderId;
    public final double price;
    public final double initialSize;
    public final boolean isBid;
    public final long timestamp;

    public double currentSize;
    public double maxVisibleSize;
    public double currentPrice;

    // price tracking
    public final List<Double> priceHistory = new ArrayList<>();
    public int priceChanges = 0;

    // iceberg tracking
    public double totalFilled = 0;
    public int refillCount = 0;
    public int sizeDecreaseCount = 0;
    public int replaceEventCount = 0;
    public boolean isConfirmedIceberg = false;
    public long icebergStartTime = 0;
    public long completionTime = 0;

    // detection state
    public double executionPercentage = 0.0;
    public int consecutiveRefills = 0;
    public double minSizeSeen;

    public long lastUpdate;

    public IcebergOrder(String orderId, double price, double initialSize, boolean isBid, long timestamp) {
        this.orderId = orderId;
        this.price = price;
        this.initialSize = initialSize;
        this.currentSize = initialSize;
        this.maxVisibleSize = initialSize;
        this.isBid = isBid;
        this.timestamp = timestamp;
        this.lastUpdate = timestamp;
        this.currentPrice = price;
        this.minSizeSeen = initialSize;
        this.priceHistory.add(price);
    }

    public void addReplaceEvent(double newSize, long ts, Double newPrice) {
        double oldSize = this.currentSize;

        // track price change
        if (newPrice != null && newPrice != currentPrice) {
            currentPrice = newPrice;
            if (!priceHistory.contains(newPrice)) {
                priceHistory.add(newPrice);
            }
            priceChanges++;
        }

        replaceEventCount++;
        double sizeChange = newSize - oldSize;

        // size decrease = execution
        if (sizeChange < 0) {
            double executionSize = Math.abs(sizeChange);
            addExecution(executionSize, ts);
            sizeDecreaseCount++;
        }

        if (newSize < minSizeSeen) {
            minSizeSeen = newSize;
        }
        if (newSize > maxVisibleSize) {
            maxVisibleSize = newSize;
        }

        // detect refill
        if (sizeChange > 0 && oldSize < initialSize) {
            refillCount++;
            consecutiveRefills++;
        } else {
            consecutiveRefills = 0;
        }

        currentSize = newSize;
        lastUpdate = ts;
    }

    public void addExecution(double filledSize, long ts) {
        totalFilled += filledSize;
        lastUpdate = ts;

        double estimatedTotal = Math.max(totalFilled + currentSize, maxVisibleSize);
        if (estimatedTotal > 0) {
            executionPercentage = totalFilled / estimatedTotal;
        }
    }

    public double getExecutionRatio() {
        if (maxVisibleSize > 0) {
            return totalFilled / maxVisibleSize;
        }
        return 0.0;
    }

    public double getIcebergScore() {
        double score = 0.0;

        // size consistency
        if (maxVisibleSize > 0) {
            double sizeRatio = minSizeSeen / maxVisibleSize;
            if (sizeRatio > 0.3) {
                score += 0.3;
            }
        }

        // refill pattern
        if (refillCount >= 2) {
            score += Math.min(0.4, refillCount * 0.1);
        }

        // execution volume
        double execRatio = getExecutionRatio();
        if (execRatio > 1.5) {
            score += Math.min(0.3, execRatio * 0.1);
        }

        return Math.min(score, 1.0);
    }
}
