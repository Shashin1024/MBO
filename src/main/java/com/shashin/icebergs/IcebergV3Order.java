package com.shashin.icebergs;

import java.util.ArrayList;
import java.util.List;

/**
 * V3 order tracking with active/passive fill distinction.
 * Replace events do NOT contribute to totalFilled —
 * all fills come exclusively from trade matching.
 */
public class IcebergV3Order {

    public final String orderId;
    public final double price;          // raw tick price
    public final double initialSize;
    public final boolean isBid;
    public final long timestamp;

    public double currentSize;
    public double maxVisibleSize;
    public double currentPrice;         // raw tick price

    public final List<Double> priceHistory = new ArrayList<>();
    public int priceChanges = 0;

    // V3 core: active vs passive fill tracking
    public double totalFilled = 0;
    public double activeFilled = 0;     // filled when order was the AGGRESSOR
    public double passiveFilled = 0;    // filled when order was the PASSIVE side

    public int refillCount = 0;
    public int sizeDecreaseCount = 0;
    public int replaceEventCount = 0;
    public boolean isConfirmedIceberg = false;
    public long icebergStartTime = 0;
    public long completionTime = 0;
    public double minSizeSeen;
    public double executionPercentage = 0.0;
    public long lastUpdate;

    public IcebergV3Order(String orderId, double price, double initialSize, boolean isBid, long timestamp) {
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

    /**
     * V3: replace events track size changes but do NOT add to totalFilled.
     * All fills come from trade matching via addExecution().
     */
    public void addReplaceEvent(double newSize, long ts, Double newPrice) {
        double oldSize = this.currentSize;

        if (newPrice != null && newPrice != currentPrice) {
            currentPrice = newPrice;
            if (!priceHistory.contains(newPrice)) priceHistory.add(newPrice);
            priceChanges++;
        }

        replaceEventCount++;
        double sizeChange = newSize - oldSize;

        // V3: only increment counter, do NOT call addExecution
        if (sizeChange < 0) {
            sizeDecreaseCount++;
        }

        if (newSize < minSizeSeen) minSizeSeen = newSize;
        if (newSize > maxVisibleSize) maxVisibleSize = newSize;
        if (sizeChange > 0 && oldSize < initialSize) refillCount++;

        currentSize = newSize;
        lastUpdate = ts;
    }

    /**
     * V3: track execution with active/passive distinction.
     */
    public void addExecution(double filledSize, long ts, boolean isActive) {
        if (isActive) {
            activeFilled += filledSize;
        } else {
            passiveFilled += filledSize;
        }
        totalFilled += filledSize;
        lastUpdate = ts;

        double estimatedTotal = Math.max(totalFilled + currentSize, maxVisibleSize);
        if (estimatedTotal > 0) {
            executionPercentage = totalFilled / estimatedTotal;
        }
    }

    public double getExecutionRatio() {
        return maxVisibleSize > 0 ? totalFilled / maxVisibleSize : 0.0;
    }

    public double getIcebergScore() {
        double score = 0.0;
        if (maxVisibleSize > 0 && (minSizeSeen / maxVisibleSize) > 0.3) score += 0.3;
        if (refillCount >= 2) score += Math.min(0.4, refillCount * 0.1);
        double execRatio = getExecutionRatio();
        if (execRatio > 1.5) score += Math.min(0.3, execRatio * 0.1);
        return Math.min(score, 1.0);
    }
}
