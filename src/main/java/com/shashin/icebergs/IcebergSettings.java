package com.shashin.icebergs;

import java.awt.Color;

public class IcebergSettings {

    // --- Detection Parameters ---
    public volatile double triggerSizePercentage = 10.0;
    public volatile double maxVisiblePercentage = 300.0;
    public volatile double minVisiblePercentage = 1.0;
    public volatile double volumeThresholdPercentage = 30.0;

    // --- Alert Thresholds ---
    public volatile double alertExecutionRatioThreshold = 5.0;
    public volatile double alertTotalFilledThreshold = 10;

    // --- Pattern Settings ---
    public volatile int minRefillCount = 1;
    public volatile double minScoreThreshold = 0.4;
    public volatile int minSizeDecreaseForVolume = 2;
    public volatile int minSizeDecreaseForPartial = 3;
    public volatile double executionThreshold = 0.7;
    public volatile double hiddenLiquiditySizeRatio = 0.6;

    // --- Timing ---
    public volatile long timeWindowSeconds = 6000;
    public volatile int visibleSeconds = 60;

    // --- Chart Colors ---
    public volatile Color bidLineColor = new Color(0, 150, 255);
    public volatile Color askLineColor = new Color(255, 70, 70);
    public volatile Color spreadFillColor = new Color(120, 100, 220, 25);
    public volatile Color chartBackground = new Color(20, 20, 30);
    public volatile Color gridColor = new Color(40, 40, 50);

    // --- Iceberg Diamond Colors ---
    public volatile Color iceBidFill = new Color(0, 220, 255, 160);
    public volatile Color iceBidEdge = new Color(0, 255, 255, 230);
    public volatile Color iceAskFill = new Color(255, 50, 200, 160);
    public volatile Color iceAskEdge = new Color(255, 80, 230, 230);

    // --- Diamond Sizing (shared by V2 and V3) ---
    public volatile double diamondMinRadius = 8.0;
    public volatile double diamondMaxRadius = 50.0;
    public volatile double diamondMinVolume = 10.0;
    public volatile double diamondMaxVolume = 1500.0;
    public volatile int diamondLabelFontSize = 12;

    // --- Distance Filter ---
    public volatile double maxDistancePips = 50.0;

    // --- V3 Detection Thresholds ---
    public volatile double v3ExecRatioThreshold = 10.0;
    public volatile double v3TotalFilledThreshold = 10;

    // --- Delta Table ---
    public volatile int deltaTableFontSize = 10;

    // --- Depth Table ---
    public volatile double depthVolumeThreshold = 50.0;
    public volatile int depthTableFontSize = 14;

    // --- Big Volume Bubbles ---
    public volatile double bigVolumeThreshold = 5.0;
    public volatile Color bigVolumeBuyFill = new Color(0, 200, 100, 140);
    public volatile Color bigVolumeBuyEdge = new Color(0, 255, 120, 220);
    public volatile Color bigVolumeSellFill = new Color(255, 80, 80, 140);
    public volatile Color bigVolumeSellEdge = new Color(255, 120, 120, 220);
    public volatile double bubbleMinRadius = 4.0;
    public volatile double bubbleMaxRadius = 30.0;
    public volatile double bubbleMinVolume = 10.0;
    public volatile double bubbleMaxVolume = 500.0;

    // --- V3 Diamond Colors ---
    public volatile Color v3BidFill = new Color(50, 205, 50, 160);
    public volatile Color v3BidEdge = new Color(0, 255, 0, 230);
    public volatile Color v3AskFill = new Color(255, 165, 0, 160);
    public volatile Color v3AskEdge = new Color(255, 200, 0, 230);

    // --- Depth Heatmap ---
    public volatile double heatmapVolumeThreshold = 10.0;
    public volatile Color heatmapBidAppearColor = new Color(0, 200, 120, 180);
    public volatile Color heatmapBidDisappearColor = new Color(0, 120, 80, 130);
    public volatile Color heatmapAskAppearColor = new Color(255, 80, 80, 180);
    public volatile Color heatmapAskDisappearColor = new Color(150, 50, 50, 130);
    public volatile int heatmapLabelFontSize = 15;

    // Label color tiers (numbers only): volumes below tier1 use default bid/ask colors.
    // Volumes at or above each tier get a unified color (same for bid and ask).
    public volatile double heatmapTier1 = 20.0;   // yellow — noticeable
    public volatile double heatmapTier2 = 50.0;   // orange — significant
    public volatile double heatmapTier3 = 100.0;  // magenta — major
    public volatile Color heatmapTier1Color = new Color(255, 220, 80);
    public volatile Color heatmapTier2Color = new Color(255, 140, 30);
    public volatile Color heatmapTier3Color = new Color(255, 80, 200);

    public long getTimeWindowMs() {
        return timeWindowSeconds * 1000L;
    }
}
