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
    public volatile int depthTableFontSize = 10;

    // --- V3 Diamond Colors ---
    public volatile Color v3BidFill = new Color(50, 205, 50, 160);
    public volatile Color v3BidEdge = new Color(0, 255, 0, 230);
    public volatile Color v3AskFill = new Color(255, 165, 0, 160);
    public volatile Color v3AskEdge = new Color(255, 200, 0, 230);

    public long getTimeWindowMs() {
        return timeWindowSeconds * 1000L;
    }
}
