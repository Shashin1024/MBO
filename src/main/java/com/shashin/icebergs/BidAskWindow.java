package com.shashin.icebergs;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.Composite;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BidAskWindow extends JFrame {

    private final double pips;
    private final String priceFormat;
    private final IcebergSettings settings;
    private final BidAskPanel chartPanel;
    private final JLabel bidLabel;
    private final JLabel askLabel;
    private final JLabel spreadLabel;
    private SettingsWindow settingsWindow;

    // order book depth references (shared with plugin, synchronized on each map)
    private TreeMap<Integer, Integer> bidBook;
    private TreeMap<Integer, Integer> askBook;

    private final List<Long> timestamps = new ArrayList<>();
    private final List<Double> bidPrices = new ArrayList<>();
    private final List<Double> askPrices = new ArrayList<>();

    // completed iceberg data (V2)
    private final List<Long> icebergTimes = new ArrayList<>();
    private final List<Double> icebergPrices = new ArrayList<>();
    private final List<Double> icebergVolumes = new ArrayList<>();
    private final List<Double> icebergExecRatios = new ArrayList<>();
    private final List<Boolean> icebergIsBid = new ArrayList<>();

    // completed iceberg data (V3)
    private final List<Long> v3Times = new ArrayList<>();
    private final List<Double> v3Prices = new ArrayList<>();
    private final List<Double> v3Volumes = new ArrayList<>();
    private final List<Double> v3ExecRatios = new ArrayList<>();
    private final List<Boolean> v3IsBid = new ArrayList<>();

    // individual trade data for delta/volume aggregation
    private final List<Long> tradeTimes = new ArrayList<>();
    private final List<Double> tradeSizes = new ArrayList<>();
    private final List<Boolean> tradeIsBuy = new ArrayList<>(); // true = buy aggressor

    // big volume trade bubbles
    private final List<Long> bigTradeTimes = new ArrayList<>();
    private final List<Double> bigTradePrices = new ArrayList<>();
    private final List<Double> bigTradeVolumes = new ArrayList<>();
    private final List<Boolean> bigTradeIsBuy = new ArrayList<>();

    // Depth heatmap bars: each bar spans from when a level crossed above the threshold
    // until it was completely removed (volume = 0). Active bars have endTime = Long.MAX_VALUE.
    private static class DepthBar {
        final long startTime;
        final double price;
        final double startVol;
        final boolean isBid;
        long endTime = Long.MAX_VALUE; // Long.MAX_VALUE = still active
        double endVol = 0;

        DepthBar(long startTime, double price, double startVol, boolean isBid) {
            this.startTime = startTime;
            this.price = price;
            this.startVol = startVol;
            this.isBid = isBid;
        }

        boolean isActive() { return endTime == Long.MAX_VALUE; }
    }

    // All depth bars (completed + active), ordered by startTime for trimming.
    // Also protected by synchronized(depthBars).
    private final List<DepthBar> depthBars = new ArrayList<>();
    // Quick lookup of the currently active bar for each price level.
    private final Map<Double, DepthBar> activeDepthBars = new java.util.HashMap<>();

    // enough for a full trading session (~8+ hours at high frequency)
    private static final int MAX_POINTS = 1_000_000;
    private static final int TRIM_BATCH = 50_000; // trim in bulk for efficiency
    private static final int DOWNSAMPLE_FACTOR = 10; // keep every Nth point when trimming
    private static final long MAX_RETENTION_MS = 24L * 3600 * 1000; // keep at most 24 hours of data

    private final Timer repaintTimer;

    public BidAskWindow(String alias, double pips, IcebergSettings settings) {
        super("Bid/Ask Lines - " + alias);
        this.pips = pips;
        this.settings = settings;
        this.priceFormat = buildPriceFormat(pips);

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(900, 450);
        setLocationRelativeTo(null);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        topPanel.setBackground(settings.chartBackground);

        bidLabel = new JLabel("Bid: --");
        bidLabel.setForeground(new Color(0, 150, 255));
        bidLabel.setFont(new Font("Monospaced", Font.BOLD, 14));

        askLabel = new JLabel("Ask: --");
        askLabel.setForeground(new Color(255, 70, 70));
        askLabel.setFont(new Font("Monospaced", Font.BOLD, 14));

        spreadLabel = new JLabel("Spread: --");
        spreadLabel.setForeground(new Color(180, 180, 190));
        spreadLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JButton gearButton = new JButton("\u2699");
        gearButton.setFont(new Font("SansSerif", Font.PLAIN, 18));
        gearButton.setForeground(new Color(180, 185, 190));
        gearButton.setBackground(new Color(45, 48, 55));
        gearButton.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        gearButton.setFocusPainted(false);
        gearButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gearButton.addActionListener(e -> openSettings());

        topPanel.add(bidLabel);
        topPanel.add(askLabel);
        topPanel.add(spreadLabel);
        topPanel.add(gearButton);

        chartPanel = new BidAskPanel();

        setLayout(new BorderLayout());
        add(topPanel, BorderLayout.NORTH);
        add(chartPanel, BorderLayout.CENTER);

        repaintTimer = new Timer(33, e -> chartPanel.repaint());
        repaintTimer.start();
    }

    private static String buildPriceFormat(double pips) {
        if (pips <= 0) return "%.2f";
        int decimals = 0;
        double v = pips;
        while (v < 1.0 && decimals < 10) {
            v *= 10;
            decimals++;
        }
        return "%." + decimals + "f";
    }

    public void updatePrices(double bid, double ask) {
        long now = System.currentTimeMillis();

        synchronized (timestamps) {
            timestamps.add(now);
            bidPrices.add(bid);
            askPrices.add(ask);

            // trim entries older than 24 hours
            long cutoff = now - MAX_RETENTION_MS;
            if (!timestamps.isEmpty() && timestamps.get(0) < cutoff) {
                int idx = lowerBoundAge(timestamps, cutoff);
                if (idx > 0) {
                    timestamps.subList(0, idx).clear();
                    bidPrices.subList(0, idx).clear();
                    askPrices.subList(0, idx).clear();
                }
            }

            if (timestamps.size() > MAX_POINTS) {
                downsamplePriceData(TRIM_BATCH);
            }
        }

        SwingUtilities.invokeLater(() -> {
            bidLabel.setText("Bid: " + String.format(priceFormat, bid));
            askLabel.setText("Ask: " + String.format(priceFormat, ask));
            double spread = ask - bid;
            long spreadTicks = Math.round(spread / pips);
            spreadLabel.setText("Spread: " + String.format(priceFormat, spread)
                    + " (" + spreadTicks + " tick" + (spreadTicks != 1 ? "s" : "") + ")");
        });
    }

    /**
     * Downsample the oldest 'count' points by keeping every Nth,
     * preserving min/max per group so the chart line shape is maintained.
     * Must be called while holding the timestamps lock.
     */
    private void downsamplePriceData(int count) {
        int end = Math.min(count, timestamps.size());
        List<Long> kept = new ArrayList<>();
        List<Double> keptBid = new ArrayList<>();
        List<Double> keptAsk = new ArrayList<>();

        for (int i = 0; i < end; i += DOWNSAMPLE_FACTOR) {
            // For each group of DOWNSAMPLE_FACTOR points, keep the one
            // with the widest bid-ask range (preserves price extremes)
            int groupEnd = Math.min(i + DOWNSAMPLE_FACTOR, end);
            int bestIdx = i;
            double bestRange = 0;
            for (int j = i; j < groupEnd; j++) {
                double range = Math.abs(askPrices.get(j) - bidPrices.get(j));
                if (range > bestRange || j == i) {
                    bestRange = range;
                    bestIdx = j;
                }
            }
            kept.add(timestamps.get(bestIdx));
            keptBid.add(bidPrices.get(bestIdx));
            keptAsk.add(askPrices.get(bestIdx));
        }

        // Remove the original batch, then prepend downsampled data
        timestamps.subList(0, end).clear();
        bidPrices.subList(0, end).clear();
        askPrices.subList(0, end).clear();
        timestamps.addAll(0, kept);
        bidPrices.addAll(0, keptBid);
        askPrices.addAll(0, keptAsk);
    }

    /** Downsample 3-column data (trades): keep every Nth point, picking the largest size per group. */
    private static <T> void downsampleList3(List<Long> times, List<Double> values, List<T> flags, int count) {
        int end = Math.min(count, times.size());
        List<Long> kT = new ArrayList<>();
        List<Double> kV = new ArrayList<>();
        List<T> kF = new ArrayList<>();

        for (int i = 0; i < end; i += DOWNSAMPLE_FACTOR) {
            int groupEnd = Math.min(i + DOWNSAMPLE_FACTOR, end);
            int bestIdx = i;
            double bestVal = 0;
            for (int j = i; j < groupEnd; j++) {
                if (values.get(j) > bestVal) {
                    bestVal = values.get(j);
                    bestIdx = j;
                }
            }
            kT.add(times.get(bestIdx));
            kV.add(values.get(bestIdx));
            kF.add(flags.get(bestIdx));
        }
        times.subList(0, end).clear();
        values.subList(0, end).clear();
        flags.subList(0, end).clear();
        times.addAll(0, kT);
        values.addAll(0, kV);
        flags.addAll(0, kF);
    }

    /** Downsample 4-column data (big trades): keep every Nth, picking largest volume per group. */
    private static <T> void downsampleList4(List<Long> times, List<Double> prices, List<Double> volumes, List<T> flags, int count) {
        int end = Math.min(count, times.size());
        List<Long> kT = new ArrayList<>();
        List<Double> kP = new ArrayList<>();
        List<Double> kV = new ArrayList<>();
        List<T> kF = new ArrayList<>();

        for (int i = 0; i < end; i += DOWNSAMPLE_FACTOR) {
            int groupEnd = Math.min(i + DOWNSAMPLE_FACTOR, end);
            int bestIdx = i;
            double bestVol = 0;
            for (int j = i; j < groupEnd; j++) {
                if (volumes.get(j) > bestVol) {
                    bestVol = volumes.get(j);
                    bestIdx = j;
                }
            }
            kT.add(times.get(bestIdx));
            kP.add(prices.get(bestIdx));
            kV.add(volumes.get(bestIdx));
            kF.add(flags.get(bestIdx));
        }
        times.subList(0, end).clear();
        prices.subList(0, end).clear();
        volumes.subList(0, end).clear();
        flags.subList(0, end).clear();
        times.addAll(0, kT);
        prices.addAll(0, kP);
        volumes.addAll(0, kV);
        flags.addAll(0, kF);
    }

    /**
     * Binary search: returns the first index i where times.get(i) >= cutoff.
     * Used to find how many leading entries are older than cutoff.
     */
    private static int lowerBoundAge(List<Long> times, long cutoff) {
        int lo = 0, hi = times.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times.get(mid) < cutoff) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    public void addIceberg(double price, double totalFilled, double executionRatio, boolean isBid, long timestamp) {
        synchronized (icebergTimes) {
            icebergTimes.add(timestamp);
            icebergPrices.add(price);
            icebergVolumes.add(totalFilled);
            icebergExecRatios.add(executionRatio);
            icebergIsBid.add(isBid);

            long cutoff = timestamp - MAX_RETENTION_MS;
            int idx = lowerBoundAge(icebergTimes, cutoff);
            if (idx > 0) {
                icebergTimes.subList(0, idx).clear();
                icebergPrices.subList(0, idx).clear();
                icebergVolumes.subList(0, idx).clear();
                icebergExecRatios.subList(0, idx).clear();
                icebergIsBid.subList(0, idx).clear();
            }
        }
    }

    public void addIcebergV3(double price, double totalFilled, double executionRatio, boolean isBid, long timestamp) {
        synchronized (v3Times) {
            v3Times.add(timestamp);
            v3Prices.add(price);
            v3Volumes.add(totalFilled);
            v3ExecRatios.add(executionRatio);
            v3IsBid.add(isBid);

            long cutoff = timestamp - MAX_RETENTION_MS;
            int idx = lowerBoundAge(v3Times, cutoff);
            if (idx > 0) {
                v3Times.subList(0, idx).clear();
                v3Prices.subList(0, idx).clear();
                v3Volumes.subList(0, idx).clear();
                v3ExecRatios.subList(0, idx).clear();
                v3IsBid.subList(0, idx).clear();
            }
        }
    }

    public void addBigTrade(long time, double price, double size, boolean isBuy) {
        synchronized (bigTradeTimes) {
            bigTradeTimes.add(time);
            bigTradePrices.add(price);
            bigTradeVolumes.add(size);
            bigTradeIsBuy.add(isBuy);

            long cutoff = time - MAX_RETENTION_MS;
            int idx = lowerBoundAge(bigTradeTimes, cutoff);
            if (idx > 0) {
                bigTradeTimes.subList(0, idx).clear();
                bigTradePrices.subList(0, idx).clear();
                bigTradeVolumes.subList(0, idx).clear();
                bigTradeIsBuy.subList(0, idx).clear();
            }

            if (bigTradeTimes.size() > MAX_POINTS) {
                downsampleList4(bigTradeTimes, bigTradePrices, bigTradeVolumes, bigTradeIsBuy, TRIM_BATCH);
            }
        }
    }

    public void addTrade(long time, double size, boolean isBuy) {
        synchronized (tradeTimes) {
            tradeTimes.add(time);
            tradeSizes.add(size);
            tradeIsBuy.add(isBuy);

            long cutoff = time - MAX_RETENTION_MS;
            int idx = lowerBoundAge(tradeTimes, cutoff);
            if (idx > 0) {
                tradeTimes.subList(0, idx).clear();
                tradeSizes.subList(0, idx).clear();
                tradeIsBuy.subList(0, idx).clear();
            }

            if (tradeTimes.size() > MAX_POINTS) {
                downsampleList3(tradeTimes, tradeSizes, tradeIsBuy, TRIM_BATCH);
            }
        }
    }

    public void setDepthBooks(TreeMap<Integer, Integer> bidBook, TreeMap<Integer, Integer> askBook) {
        this.bidBook = bidBook;
        this.askBook = askBook;
    }

    public void addDepthEvent(long time, double price, double volume, boolean isBid, boolean appeared) {
        synchronized (depthBars) {
            if (appeared) {
                DepthBar bar = new DepthBar(time, price, volume, isBid);
                depthBars.add(bar);
                activeDepthBars.put(price, bar);
            } else {
                DepthBar bar = activeDepthBars.remove(price);
                if (bar != null) {
                    bar.endTime = time;
                    bar.endVol = volume;
                }
            }

            // Trim completed bars older than 24 hours from the front
            // (active bars are never trimmed regardless of age)
            long cutoff = time - MAX_RETENTION_MS;
            int trim = 0;
            while (trim < depthBars.size()
                    && depthBars.get(trim).startTime < cutoff
                    && !depthBars.get(trim).isActive()) {
                trim++;
            }
            if (trim > 0) depthBars.subList(0, trim).clear();
        }
    }

    private void openSettings() {
        if (settingsWindow == null || !settingsWindow.isDisplayable()) {
            settingsWindow = new SettingsWindow(settings);
        }
        settingsWindow.setVisible(true);
        settingsWindow.toFront();
    }

    @Override
    public void dispose() {
        repaintTimer.stop();
        if (settingsWindow != null) settingsWindow.dispose();
        super.dispose();
    }

    private class BidAskPanel extends JPanel {

        private static final int PAD_LEFT = 60;
        private static final int PAD_RIGHT = 15;
        private static final int PAD_TOP = 15;
        private static final int PAD_BOTTOM = 85;

        private static final double ZOOM_STEP = 1.15;
        private static final double MIN_ZOOM = 1e-6;
        private static final double MAX_ZOOM = 1e6;

        private double verticalZoom = 1.0;
        private double horizontalZoom = 1.0;

        // pan offsets: timeOffset in ms (positive = looking at past), priceOffset in price units
        private long timeOffset = 0;
        private double priceOffset = 0.0;

        // price-axis drag state
        private boolean draggingPriceAxis = false;
        private int dragStartY;
        private double dragStartVZoom;

        // time-axis drag state
        private boolean draggingTimeAxis = false;
        private int dragStartX;
        private double dragStartHZoom;

        // chart pan drag state
        private boolean draggingChart = false;
        private int panStartX;
        private int panStartY;
        private long panStartTimeOffset;
        private double panStartPriceOffset;

        // mouse tracking for hover tooltip
        private int mouseX = -1;
        private int mouseY = -1;

        BidAskPanel() {
            setBackground(settings.chartBackground);

            addMouseWheelListener(this::onMouseWheel);

            MouseAdapter mouseHandler = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int h = getHeight();
                    if (e.getX() < PAD_LEFT) {
                        // price axis drag -> vertical zoom
                        draggingPriceAxis = true;
                        dragStartY = e.getY();
                        dragStartVZoom = verticalZoom;
                        setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                    } else if (e.getY() > h - PAD_BOTTOM) {
                        // time axis drag -> horizontal zoom
                        draggingTimeAxis = true;
                        dragStartX = e.getX();
                        dragStartHZoom = horizontalZoom;
                        setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                    } else {
                        // chart area drag -> pan
                        draggingChart = true;
                        panStartX = e.getX();
                        panStartY = e.getY();
                        panStartTimeOffset = timeOffset;
                        panStartPriceOffset = priceOffset;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (draggingPriceAxis) {
                        draggingPriceAxis = false;
                        setCursor(Cursor.getDefaultCursor());
                    }
                    if (draggingTimeAxis) {
                        draggingTimeAxis = false;
                        setCursor(Cursor.getDefaultCursor());
                    }
                    if (draggingChart) {
                        draggingChart = false;
                        setCursor(Cursor.getDefaultCursor());
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (draggingPriceAxis) {
                        int dy = dragStartY - e.getY();
                        double sensitivity = 0.005;
                        double factor = Math.exp(dy * sensitivity);
                        verticalZoom = clampZoom(dragStartVZoom * factor);
                        repaint();
                    } else if (draggingTimeAxis) {
                        int dx = e.getX() - dragStartX;
                        double sensitivity = 0.005;
                        double factor = Math.exp(dx * sensitivity);
                        horizontalZoom = clampZoom(dragStartHZoom * factor);
                        repaint();
                    } else if (draggingChart) {
                        int w = getWidth();
                        int chartW = w - PAD_LEFT - PAD_RIGHT;
                        int h = getHeight();
                        int chartH = h - PAD_TOP - PAD_BOTTOM;
                        if (chartW <= 0 || chartH <= 0) return;

                        int dx = e.getX() - panStartX; // positive = dragged right = look at past
                        int dy = e.getY() - panStartY; // positive = dragged down = look at lower prices

                        // convert pixel delta to time delta
                        long visibleMs = (long) (settings.visibleSeconds * 1000L / horizontalZoom);
                        long timeDelta = (long) ((double) dx / chartW * visibleMs);
                        timeOffset = Math.max(0, panStartTimeOffset + timeDelta);

                        // convert pixel delta to price delta
                        // need current price range to map pixels; use an approximate range
                        double approxRange = estimateVisiblePriceRange();
                        double priceDelta = (double) dy / chartH * approxRange;
                        priceOffset = panStartPriceOffset + priceDelta;

                        repaint();
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    // double-click to reset to live view
                    if (e.getClickCount() == 2 && e.getX() >= PAD_LEFT) {
                        timeOffset = 0;
                        priceOffset = 0.0;
                        repaint();
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    mouseX = e.getX();
                    mouseY = e.getY();
                    repaint();
                }
            };

            addMouseListener(mouseHandler);
            addMouseMotionListener(mouseHandler);
        }

        private double estimateVisiblePriceRange() {
            long now = System.currentTimeMillis();
            long visibleMs = (long) (settings.visibleSeconds * 1000L / horizontalZoom);
            long endTime = now - timeOffset;
            long startTime = endTime - visibleMs;

            double minP = Double.MAX_VALUE;
            double maxP = -Double.MAX_VALUE;
            synchronized (timestamps) {
                int idxStart = lowerBound(timestamps, startTime);
                int idxEnd = Math.min(lowerBound(timestamps, endTime + 1), timestamps.size());
                for (int i = idxStart; i < idxEnd; i++) {
                    double bid = bidPrices.get(i);
                    double ask = askPrices.get(i);
                    minP = Math.min(minP, Math.min(bid, ask));
                    maxP = Math.max(maxP, Math.max(bid, ask));
                }
            }
            if (minP >= maxP) return pips * 20;
            double autoRange = maxP - minP;
            double zoomedRange = autoRange / verticalZoom;
            return Math.max(zoomedRange, pips * 2) * 1.1;
        }

        private void onMouseWheel(MouseWheelEvent e) {
            int clicks = e.getWheelRotation();
            double factor = Math.pow(ZOOM_STEP, -clicks);

            horizontalZoom = clampZoom(horizontalZoom * factor);
            repaint();
        }

        private double clampZoom(double z) {
            return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int chartW = w - PAD_LEFT - PAD_RIGHT;
            int chartH = h - PAD_TOP - PAD_BOTTOM;

            if (chartW <= 0 || chartH <= 0) return;

            synchronized (timestamps) {
                if (timestamps.size() < 2) {
                    g2.setColor(Color.GRAY);
                    g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
                    g2.drawString("Waiting for market data...", w / 2 - 80, h / 2);
                    return;
                }

                long now = System.currentTimeMillis();
                long visibleMs = (long) (settings.visibleSeconds * 1000L / horizontalZoom);
                // Shift endTime one column (1/7 of visible range) past now so live data
                // ends at column 6, leaving column 7 as blank right-side padding.
                long endTime = now - timeOffset + visibleMs / 7;
                long startTime = endTime - visibleMs;

                // find auto-fit price range over visible window (binary search for range)
                int idxStart = lowerBound(timestamps, startTime);
                int idxEnd = Math.min(lowerBound(timestamps, endTime + 1), timestamps.size());

                double minPrice = Double.MAX_VALUE;
                double maxPrice = -Double.MAX_VALUE;

                // scan all visible bid/ask points
                for (int i = idxStart; i < idxEnd; i++) {
                    double bid = bidPrices.get(i);
                    double ask = askPrices.get(i);
                    minPrice = Math.min(minPrice, Math.min(bid, ask));
                    maxPrice = Math.max(maxPrice, Math.max(bid, ask));
                }

                if (minPrice >= maxPrice) {
                    // No data points visible — fall back to the last known bid/ask
                    double lastBid = bidPrices.isEmpty() ? 0 : bidPrices.get(bidPrices.size() - 1);
                    double lastAsk = askPrices.isEmpty() ? 0 : askPrices.get(askPrices.size() - 1);
                    double mid = (lastBid > 0 && lastAsk > 0)
                            ? (lastBid + lastAsk) / 2.0
                            : Math.max(lastBid, lastAsk);
                    if (mid <= 0) mid = pips * 50;
                    minPrice = mid - pips * 10;
                    maxPrice = mid + pips * 10;
                }

                // apply vertical zoom around the midpoint
                double midPrice = (minPrice + maxPrice) / 2.0;
                double autoRange = maxPrice - minPrice;
                double zoomedRange = autoRange / verticalZoom;
                zoomedRange = Math.max(zoomedRange, pips * 2);
                double paddedRange = zoomedRange * 1.1;

                // shift midpoint by price offset from panning
                midPrice += priceOffset;

                minPrice = midPrice - paddedRange / 2.0;
                maxPrice = midPrice + paddedRange / 2.0;
                double priceRange = maxPrice - minPrice;

                drawGrid(g2, chartW, chartH, startTime, endTime, minPrice, maxPrice, priceRange);
                drawSpreadFill(g2, chartW, chartH, startTime, endTime, minPrice, priceRange);

                drawPriceLine(g2, chartW, chartH, startTime, endTime, minPrice, priceRange,
                        bidPrices, settings.bidLineColor);
                drawPriceLine(g2, chartW, chartH, startTime, endTime, minPrice, priceRange,
                        askPrices, settings.askLineColor);

                // depth heatmap events: appear/disappear markers
                drawHeatmapEvents(g2, chartW, chartH, startTime, endTime, minPrice, priceRange);

                // draw completed iceberg diamonds first (V2 then V3)
                List<DiamondInfo> allDiamonds = new ArrayList<>();
                allDiamonds.addAll(drawIcebergBubbles(g2, chartW, chartH, startTime, endTime, minPrice, priceRange));
                allDiamonds.addAll(drawV3IcebergBubbles(g2, chartW, chartH, startTime, endTime, minPrice, priceRange));

                // draw big volume trade bubbles on top of diamonds so they are always visible
                drawBigVolumeBubbles(g2, chartW, chartH, startTime, endTime, minPrice, priceRange);

                // collision-avoidance labels, then hover tooltip
                drawLabelsWithCollisionAvoidance(g2, allDiamonds);
                drawHoverTooltip(g2, allDiamonds);

                // current price markers on the right edge
                if (!bidPrices.isEmpty()) {
                    double lastBid = bidPrices.get(bidPrices.size() - 1);
                    double lastAsk = askPrices.get(askPrices.size() - 1);
                    int yBid = toY(lastBid, minPrice, priceRange, chartH);
                    int yAsk = toY(lastAsk, minPrice, priceRange, chartH);

                    g2.setFont(new Font("Monospaced", Font.BOLD, 10));

                    g2.setColor(settings.bidLineColor);
                    g2.fillRect(PAD_LEFT + chartW + 1, yBid - 6, PAD_RIGHT - 1, 12);
                    g2.setColor(Color.WHITE);
                    g2.drawString(String.format(priceFormat, lastBid),
                            PAD_LEFT + chartW + 2, yBid + 4);

                    g2.setColor(settings.askLineColor);
                    g2.fillRect(PAD_LEFT + chartW + 1, yAsk - 6, PAD_RIGHT - 1, 12);
                    g2.setColor(Color.WHITE);
                    g2.drawString(String.format(priceFormat, lastAsk),
                            PAD_LEFT + chartW + 2, yAsk + 4);
                }

                // delta/volume table below chart
                drawDeltaVolumeTable(g2, chartW, chartH, startTime, endTime);

                // high-volume depth table at top-right
                drawDepthTable(g2);

                // grid column width indicator (lower-left)
                long colMs = (endTime - startTime) / 7;
                String colLabel = formatDuration(colMs);
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.setColor(new Color(140, 140, 150, 180));
                g2.drawString(colLabel, PAD_LEFT + 4, PAD_TOP + chartH - 4);

                // show "HISTORY" indicator when panned away from live
                if (timeOffset > 0) {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                    g2.setColor(new Color(255, 200, 50, 200));
                    g2.drawString("HISTORY (double-click to return to live)",
                            PAD_LEFT + 5, PAD_TOP + 14);
                }
            }
        }

        // info collected per visible diamond for label placement and tooltip
        private static class DiamondInfo {
            int cx, cy, r;
            String label;
            boolean isV3;
            boolean isBid;
            double volume;
            double execRatio;

            DiamondInfo(int cx, int cy, int r, String label, boolean isV3, boolean isBid, double volume, double execRatio) {
                this.cx = cx; this.cy = cy; this.r = r;
                this.label = label; this.isV3 = isV3; this.isBid = isBid;
                this.volume = volume; this.execRatio = execRatio;
            }
        }

        private int diamondRadius(double volume) {
            double minVol = settings.diamondMinVolume;
            double maxVol = settings.diamondMaxVolume;
            double minR = settings.diamondMinRadius;
            double maxR = settings.diamondMaxRadius;
            double clamped = Math.max(minVol, Math.min(maxVol, volume));
            // log-scale: minVol -> 0.0, maxVol -> 1.0
            double norm = Math.log(clamped / minVol) / Math.log(maxVol / minVol);
            return (int) (minR + norm * (maxR - minR));
        }

        private int bubbleRadius(double volume) {
            double minVol = settings.bubbleMinVolume;
            double maxVol = settings.bubbleMaxVolume;
            double minR = settings.bubbleMinRadius;
            double maxR = settings.bubbleMaxRadius;
            double clamped = Math.max(minVol, Math.min(maxVol, volume));
            double norm = Math.log(clamped / minVol) / Math.log(maxVol / minVol);
            return (int) (minR + norm * (maxR - minR));
        }

        private void drawBigVolumeBubbles(Graphics2D g2, int chartW, int chartH,
                                           long startTime, long endTime,
                                           double minPrice, double priceRange) {
            long timeRange = endTime - startTime;
            if (timeRange <= 0) return;

            synchronized (bigTradeTimes) {
                if (bigTradeTimes.isEmpty()) return;

                int idxStart = lowerBound(bigTradeTimes, startTime);
                int idxEnd = Math.min(lowerBound(bigTradeTimes, endTime + 1), bigTradeTimes.size());

                Composite origComposite = g2.getComposite();

                double maxPrice = minPrice + priceRange;
                for (int i = idxStart; i < idxEnd; i++) {
                    long t = bigTradeTimes.get(i);
                    double price = bigTradePrices.get(i);
                    if (price < minPrice || price > maxPrice) continue;
                    double volume = bigTradeVolumes.get(i);
                    boolean isBuy = bigTradeIsBuy.get(i);

                    int cx = toX(t, startTime, timeRange, chartW);
                    int cy = toY(price, minPrice, priceRange, chartH);
                    int r = bubbleRadius(volume);

                    // fill
                    g2.setColor(isBuy ? settings.bigVolumeBuyFill : settings.bigVolumeSellFill);
                    g2.fillOval(cx - r, cy - r, r * 2, r * 2);
                    // edge
                    g2.setColor(isBuy ? settings.bigVolumeBuyEdge : settings.bigVolumeSellEdge);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                }

                g2.setComposite(origComposite);
            }
        }

        /**
         * Returns the label color for a heatmap number based on its volume tier.
         * Volumes below tier1 keep the default bid/ask directional colors.
         * Volumes at or above each tier use a unified color (same for bid and ask)
         * so traders can gauge size at a glance without needing to distinguish direction.
         */
        private Color heatmapLabelColor(double volume, boolean isBid) {
            if (volume >= settings.heatmapTier3) return settings.heatmapTier3Color;
            if (volume >= settings.heatmapTier2) return settings.heatmapTier2Color;
            if (volume >= settings.heatmapTier1) return settings.heatmapTier1Color;
            // default: preserve bid/ask direction distinction
            return isBid ? new Color(150, 255, 180) : new Color(255, 160, 160);
        }

        private void drawHeatmapEvents(Graphics2D g2, int chartW, int chartH,
                                       long startTime, long endTime,
                                       double minPrice, double priceRange) {
            long timeRange = endTime - startTime;
            if (timeRange <= 0) return;

            double maxPrice = minPrice + priceRange;
            long now = System.currentTimeMillis();

            g2.setFont(new Font("Monospaced", Font.BOLD, settings.heatmapLabelFontSize));
            FontMetrics fm = g2.getFontMetrics();
            int barH = 4;

            synchronized (depthBars) {
                for (DepthBar bar : depthBars) {
                    if (bar.price < minPrice || bar.price > maxPrice) continue;

                    long barEnd = bar.isActive() ? now : bar.endTime;

                    // Skip bars that don't overlap with the visible window
                    if (bar.startTime > endTime || barEnd < startTime) continue;

                    int cy = toY(bar.price, minPrice, priceRange, chartH);

                    // Clamp bar to visible window for drawing
                    long drawStart = Math.max(bar.startTime, startTime);
                    long drawEnd = Math.min(barEnd, endTime);

                    int x1 = toX(drawStart, startTime, timeRange, chartW);
                    int x2 = toX(drawEnd, startTime, timeRange, chartW);
                    int barWidth = Math.max(2, x2 - x1);

                    // Draw the continuous horizontal bar
                    // Active bars use the "appear" color; completed bars use a slightly faded color
                    Color barColor = bar.isBid
                            ? (bar.isActive() ? settings.heatmapBidAppearColor : settings.heatmapBidDisappearColor)
                            : (bar.isActive() ? settings.heatmapAskAppearColor : settings.heatmapAskDisappearColor);
                    g2.setColor(barColor);
                    g2.fillRect(x1, cy - barH / 2, barWidth, barH);

                    // +N label to the LEFT of the bar's start point (only if start is in visible window)
                    if (bar.startTime >= startTime && bar.startTime <= endTime) {
                        String plusLabel = "+" + formatVolumeDouble(bar.startVol);
                        int labelX = toX(bar.startTime, startTime, timeRange, chartW);
                        int labelW = fm.stringWidth(plusLabel);
                        Color plusColor = heatmapLabelColor(bar.startVol, bar.isBid);
                        g2.setColor(plusColor);
                        g2.drawString(plusLabel, labelX - labelW - 3, cy + fm.getAscent() / 2);
                    }

                    // -N label to the RIGHT of the bar's end point (only if bar is completed and end is in window)
                    if (!bar.isActive() && bar.endTime >= startTime && bar.endTime <= endTime) {
                        String minusLabel = "-" + formatVolumeDouble(bar.endVol);
                        int labelX = toX(bar.endTime, startTime, timeRange, chartW);
                        Color minusColor = heatmapLabelColor(bar.endVol, bar.isBid);
                        g2.setColor(minusColor);
                        g2.drawString(minusLabel, labelX + 3, cy + fm.getAscent() / 2);
                    }
                }
            }
        }

        private List<DiamondInfo> drawIcebergBubbles(Graphics2D g2, int chartW, int chartH,
                                         long startTime, long endTime,
                                         double minPrice, double priceRange) {
            List<DiamondInfo> result = new ArrayList<>();
            long timeRange = endTime - startTime;

            synchronized (icebergTimes) {
                if (icebergTimes.isEmpty()) return result;
                double maxPrice = minPrice + priceRange;

                for (int i = 0; i < icebergTimes.size(); i++) {
                    long t = icebergTimes.get(i);
                    if (t < startTime || t > endTime) continue;

                    double price = icebergPrices.get(i);
                    if (price < minPrice || price > maxPrice) continue;
                    double volume = icebergVolumes.get(i);
                    double execRatio = icebergExecRatios.get(i);
                    boolean isBid = icebergIsBid.get(i);

                    int cx = toX(t, startTime, timeRange, chartW);
                    int cy = toY(price, minPrice, priceRange, chartH);
                    int r = diamondRadius(volume);

                    // draw diamond shape
                    int[] xPts = {cx, cx + r, cx, cx - r};
                    int[] yPts = {cy - r, cy, cy + r, cy};
                    Polygon diamond = new Polygon(xPts, yPts, 4);

                    g2.setColor(isBid ? settings.iceBidFill : settings.iceAskFill);
                    g2.fill(diamond);
                    g2.setColor(isBid ? settings.iceBidEdge : settings.iceAskEdge);
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(diamond);

                    String volStr = formatVolumeDouble(volume) + (isBid ? "B" : "S");
                    String ratioStr = String.format("%.1fx", execRatio);
                    String label = volStr + " | " + ratioStr;
                    result.add(new DiamondInfo(cx, cy, r, label, false, isBid, volume, execRatio));
                }
            }
            return result;
        }

        private List<DiamondInfo> drawV3IcebergBubbles(Graphics2D g2, int chartW, int chartH,
                                            long startTime, long endTime,
                                            double minPrice, double priceRange) {
            List<DiamondInfo> result = new ArrayList<>();
            long timeRange = endTime - startTime;

            synchronized (v3Times) {
                if (v3Times.isEmpty()) return result;
                double maxPrice = minPrice + priceRange;

                for (int i = 0; i < v3Times.size(); i++) {
                    long t = v3Times.get(i);
                    if (t < startTime || t > endTime) continue;

                    double price = v3Prices.get(i);
                    if (price < minPrice || price > maxPrice) continue;
                    double volume = v3Volumes.get(i);
                    double execRatio = v3ExecRatios.get(i);
                    boolean isBid = v3IsBid.get(i);

                    int cx = toX(t, startTime, timeRange, chartW);
                    int cy = toY(price, minPrice, priceRange, chartH);
                    int r = diamondRadius(volume);

                    // draw diamond shape (V3 colors)
                    int[] xPts = {cx, cx + r, cx, cx - r};
                    int[] yPts = {cy - r, cy, cy + r, cy};
                    Polygon diamond = new Polygon(xPts, yPts, 4);

                    g2.setColor(isBid ? settings.v3BidFill : settings.v3AskFill);
                    g2.fill(diamond);
                    g2.setColor(isBid ? settings.v3BidEdge : settings.v3AskEdge);
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(diamond);

                    String volStr = formatVolumeDouble(volume) + (isBid ? "B" : "S");
                    String ratioStr = String.format("%.1fx", execRatio);
                    String label = "V3 " + volStr + " | " + ratioStr;
                    result.add(new DiamondInfo(cx, cy, r, label, true, isBid, volume, execRatio));
                }
            }
            return result;
        }

        private void drawLabelsWithCollisionAvoidance(Graphics2D g2, List<DiamondInfo> diamonds) {
            if (diamonds.isEmpty()) return;

            // sort by volume descending — largest diamonds get label priority
            diamonds.sort((a, b) -> Double.compare(b.volume, a.volume));

            g2.setFont(new Font("SansSerif", Font.BOLD, settings.diamondLabelFontSize));
            FontMetrics fm = g2.getFontMetrics();
            int fontH = fm.getHeight();

            // placed label rectangles for collision checking
            List<Rectangle> placed = new ArrayList<>();

            for (DiamondInfo d : diamonds) {
                int labelW = fm.stringWidth(d.label);
                int labelX = d.cx - labelW / 2;

                // try positions: above diamond, below diamond, then nudge further up/down
                int[] offsets = new int[]{
                    -(d.r + 4 + fontH),           // above diamond
                    d.r + 4 + fm.getAscent(),     // below diamond
                    -(d.r + 4 + fontH + fontH),   // further above
                    d.r + 4 + fm.getAscent() + fontH, // further below
                    -(d.r + 4 + fontH + fontH * 2),
                    d.r + 4 + fm.getAscent() + fontH * 2
                };

                for (int offsetY : offsets) {
                    int labelY = d.cy + offsetY;
                    Rectangle rect = new Rectangle(labelX, labelY, labelW, fontH);

                    boolean overlaps = false;
                    for (Rectangle existing : placed) {
                        if (rect.intersects(existing)) {
                            overlaps = true;
                            break;
                        }
                    }

                    if (!overlaps) {
                        g2.setColor(Color.WHITE);
                        g2.drawString(d.label, labelX, labelY + fm.getAscent());
                        placed.add(rect);
                        break;
                    }
                }
                // if no position found, label is hidden — hover tooltip still works
            }
        }

        private void drawHoverTooltip(Graphics2D g2, List<DiamondInfo> diamonds) {
            if (mouseX < 0 || mouseY < 0 || diamonds.isEmpty()) return;

            // find closest diamond within its bounding area
            DiamondInfo hovered = null;
            for (DiamondInfo d : diamonds) {
                // diamond hit test: Manhattan distance (diamond shape)
                int dx = Math.abs(mouseX - d.cx);
                int dy = Math.abs(mouseY - d.cy);
                if (dx + dy <= d.r + 4) { // small tolerance
                    hovered = d;
                    break;
                }
            }

            if (hovered == null) return;

            String type = hovered.isV3 ? "V3" : "V2";
            String side = hovered.isBid ? "BID" : "ASK";
            String vol = formatVolumeDouble(hovered.volume);
            String ratio = String.format("%.1fx", hovered.execRatio);

            String[] lines = {
                type + " Iceberg",
                "Side: " + side,
                "Volume: " + vol,
                "Exec Ratio: " + ratio
            };

            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = fm.getHeight();
            int pad = 6;
            int maxW = 0;
            for (String line : lines) {
                maxW = Math.max(maxW, fm.stringWidth(line));
            }
            int boxW = maxW + pad * 2;
            int boxH = lineH * lines.length + pad * 2;

            // offset tooltip from cursor; keep within chart bounds
            int tx = mouseX + 15;
            int ty = mouseY - boxH - 5;
            int panelW = getWidth();
            int panelH = getHeight();
            if (tx + boxW > panelW - 5) tx = mouseX - boxW - 15;
            if (ty < 5) ty = mouseY + 15;
            if (ty + boxH > panelH - 5) ty = panelH - boxH - 5;

            // dark semi-transparent background
            g2.setColor(new Color(20, 20, 30, 220));
            g2.fillRoundRect(tx, ty, boxW, boxH, 8, 8);
            g2.setColor(new Color(100, 100, 120));
            g2.drawRoundRect(tx, ty, boxW, boxH, 8, 8);

            // text lines
            g2.setColor(Color.WHITE);
            for (int i = 0; i < lines.length; i++) {
                g2.drawString(lines[i], tx + pad, ty + pad + fm.getAscent() + i * lineH);
            }
        }

        private void drawDeltaVolumeTable(Graphics2D g2, int chartW, int chartH,
                                          long startTime, long endTime) {
            long timeRange = endTime - startTime;
            if (timeRange <= 0) return;

            int numV = 7; // same as drawGrid
            int tableTop = PAD_TOP + chartH + 18; // below time axis labels
            int rowH = 14;

            g2.setFont(new Font("Monospaced", Font.BOLD, settings.deltaTableFontSize));
            FontMetrics fm = g2.getFontMetrics();

            String[] rowLabels = {"Delta", "Max \u0394", "Min \u0394", "Vol"};
            Color labelColor = new Color(140, 140, 150);
            Color posColor = new Color(0, 200, 100);
            Color negColor = new Color(255, 70, 70);
            Color zeroColor = new Color(120, 120, 130);

            // draw row labels in PAD_LEFT area
            for (int r = 0; r < rowLabels.length; r++) {
                g2.setColor(labelColor);
                g2.drawString(rowLabels[r], 2, tableTop + r * rowH + fm.getAscent());
            }

            // draw separator line between time axis and table
            g2.setColor(new Color(60, 60, 70));
            g2.drawLine(PAD_LEFT, tableTop - 3, PAD_LEFT + chartW, tableTop - 3);

            synchronized (tradeTimes) {
                for (int col = 0; col < numV; col++) {
                    long colStart = startTime + timeRange * col / numV;
                    long colEnd = startTime + timeRange * (col + 1) / numV;

                    int idxStart = lowerBound(tradeTimes, colStart);
                    int idxEnd = Math.min(lowerBound(tradeTimes, colEnd), tradeTimes.size());

                    double volume = 0;
                    double runningDelta = 0;
                    double maxDelta = 0;
                    double minDelta = 0;

                    for (int i = idxStart; i < idxEnd; i++) {
                        double sz = tradeSizes.get(i);
                        volume += sz;
                        runningDelta += tradeIsBuy.get(i) ? sz : -sz;
                        maxDelta = Math.max(maxDelta, runningDelta);
                        minDelta = Math.min(minDelta, runningDelta);
                    }
                    double delta = runningDelta;

                    // column center X
                    int colCenterX = PAD_LEFT + chartW * (2 * col + 1) / (2 * numV);

                    String[] values = {
                        formatDelta(delta),
                        formatDelta(maxDelta),
                        formatDelta(minDelta),
                        formatVolumeDouble(volume)
                    };

                    for (int r = 0; r < values.length; r++) {
                        double val = (r == 0) ? delta : (r == 1) ? maxDelta : (r == 2) ? minDelta : volume;
                        if (r == 3) { // volume row — always white
                            g2.setColor(Color.WHITE);
                        } else {
                            g2.setColor(val > 0 ? posColor : val < 0 ? negColor : zeroColor);
                        }
                        int strW = fm.stringWidth(values[r]);
                        g2.drawString(values[r], colCenterX - strW / 2,
                                tableTop + r * rowH + fm.getAscent());
                    }

                    // draw vertical column separators (left edge of each column)
                    int colX = PAD_LEFT + chartW * col / numV;
                    g2.setColor(new Color(50, 50, 60));
                    g2.drawLine(colX, tableTop - 3, colX, tableTop + rowH * 4);
                }
                // draw the closing right-edge separator to match the grid's numV+1 lines
                g2.setColor(new Color(50, 50, 60));
                g2.drawLine(PAD_LEFT + chartW, tableTop - 3, PAD_LEFT + chartW, tableTop + rowH * 4);
            }
        }

        private String formatDelta(double val) {
            String s = formatVolumeDouble(Math.abs(val));
            if (val > 0) return "+" + s;
            if (val < 0) return "-" + s;
            return "0";
        }

        private String formatDuration(long ms) {
            long sec = ms / 1000;
            if (sec < 60) return sec + "s";
            long min = sec / 60;
            if (min < 60) return min + "m";
            long hr = min / 60;
            long remMin = min % 60;
            if (remMin == 0) return hr + "h";
            return hr + "h" + remMin + "m";
        }

        private void drawDepthTable(Graphics2D g2) {
            if (bidBook == null || askBook == null) return;

            double threshold = settings.depthVolumeThreshold;
            int fontSize = settings.depthTableFontSize;
            g2.setFont(new Font("Monospaced", Font.BOLD, fontSize));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = fm.getHeight();

            // collect high-volume bid levels (sorted by price descending — closest to market first)
            List<int[]> bidLevels = new ArrayList<>(); // {priceTick, volume}
            synchronized (bidBook) {
                for (Map.Entry<Integer, Integer> e : bidBook.descendingMap().entrySet()) {
                    if (e.getValue() >= threshold) {
                        bidLevels.add(new int[]{e.getKey(), e.getValue()});
                    }
                    if (bidLevels.size() >= 10) break; // cap at 10 rows
                }
            }

            // collect high-volume ask levels (sorted by price ascending — closest to market first)
            List<int[]> askLevels = new ArrayList<>();
            synchronized (askBook) {
                for (Map.Entry<Integer, Integer> e : askBook.entrySet()) {
                    if (e.getValue() >= threshold) {
                        askLevels.add(new int[]{e.getKey(), e.getValue()});
                    }
                    if (askLevels.size() >= 10) break;
                }
            }

            if (bidLevels.isEmpty() && askLevels.isEmpty()) return;

            int maxRows = Math.max(bidLevels.size(), askLevels.size());
            int colW = 130; // width per side
            int tableW = colW * 2 + 10;
            int tableH = lineH * (maxRows + 1) + 8; // +1 for header

            // position: top-right of chart area
            int w = getWidth();
            int tx = w - PAD_RIGHT - tableW - 5;
            int ty = PAD_TOP + 5;

            // dark background
            g2.setColor(new Color(15, 15, 25, 210));
            g2.fillRoundRect(tx, ty, tableW, tableH, 6, 6);
            g2.setColor(new Color(60, 60, 75));
            g2.drawRoundRect(tx, ty, tableW, tableH, 6, 6);

            // headers
            int headerY = ty + 4 + fm.getAscent();
            g2.setColor(settings.bidLineColor);
            g2.drawString("BID", tx + colW / 2 - fm.stringWidth("BID") / 2, headerY);
            g2.setColor(settings.askLineColor);
            g2.drawString("ASK", tx + colW + 10 + colW / 2 - fm.stringWidth("ASK") / 2, headerY);

            // separator line
            g2.setColor(new Color(60, 60, 75));
            int sepY = ty + lineH + 4;
            g2.drawLine(tx + 4, sepY, tx + tableW - 4, sepY);

            // vertical divider
            int divX = tx + colW + 5;
            g2.drawLine(divX, ty + 4, divX, ty + tableH - 4);

            // bid side rows
            for (int i = 0; i < bidLevels.size(); i++) {
                int rowY = sepY + 2 + (i + 1) * lineH;
                double price = bidLevels.get(i)[0] * pips;
                int vol = bidLevels.get(i)[1];
                String priceStr = String.format(priceFormat, price);
                String volStr = formatVolumeDouble(vol);
                String row = priceStr + " " + volStr;
                g2.setColor(new Color(0, 180, 255));
                g2.drawString(row, tx + colW / 2 - fm.stringWidth(row) / 2, rowY);
            }

            // ask side rows
            for (int i = 0; i < askLevels.size(); i++) {
                int rowY = sepY + 2 + (i + 1) * lineH;
                double price = askLevels.get(i)[0] * pips;
                int vol = askLevels.get(i)[1];
                String priceStr = String.format(priceFormat, price);
                String volStr = formatVolumeDouble(vol);
                String row = priceStr + " " + volStr;
                g2.setColor(new Color(255, 100, 100));
                g2.drawString(row, tx + colW + 10 + colW / 2 - fm.stringWidth(row) / 2, rowY);
            }
        }

        private String formatVolumeDouble(double size) {
            if (size >= 1_000_000) return String.format("%.1fM", size / 1_000_000.0);
            if (size >= 1_000) return String.format("%.1fK", size / 1_000.0);
            return String.format("%.0f", size);
        }

        private int toY(double price, double minPrice, double priceRange, int chartH) {
            return PAD_TOP + chartH - (int) ((price - minPrice) / priceRange * chartH);
        }

        private int toX(long time, long startTime, long timeRange, int chartW) {
            return PAD_LEFT + (int) ((double) (time - startTime) / timeRange * chartW);
        }

        // binary search: first index where timestamps.get(i) >= target
        private int lowerBound(List<Long> times, long target) {
            int lo = 0, hi = times.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (times.get(mid) < target) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        private void drawGrid(Graphics2D g2, int chartW, int chartH,
                              long startTime, long endTime,
                              double minPrice, double maxPrice, double priceRange) {
            g2.setStroke(new BasicStroke(1f));

            // price axis labels (larger font)
            g2.setFont(new Font("Monospaced", Font.BOLD, 12));
            int numH = 6;  // horizontal price lines — keep as-is
            for (int i = 0; i <= numH; i++) {
                double price = minPrice + priceRange * i / numH;
                int y = toY(price, minPrice, priceRange, chartH);
                g2.setColor(settings.gridColor);
                g2.drawLine(PAD_LEFT, y, PAD_LEFT + chartW, y);
                g2.setColor(new Color(140, 140, 150));
                g2.drawString(String.format(priceFormat, price), 2, y + 5);
            }

            // time axis labels — real local time like Bookmap
            g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
            long timeRange = endTime - startTime;
            // use HH:mm:ss for short windows (< 10 min), HH:mm for longer
            SimpleDateFormat timeFmt = timeRange < 600_000
                    ? new SimpleDateFormat("HH:mm:ss")
                    : new SimpleDateFormat("HH:mm");
            int numV = 7;
            for (int i = 0; i <= numV; i++) {
                int x = PAD_LEFT + chartW * i / numV;
                g2.setColor(settings.gridColor);
                g2.drawLine(x, PAD_TOP, x, PAD_TOP + chartH);
                long t = startTime + timeRange * i / numV;
                String timeLabel = timeFmt.format(new Date(t));
                FontMetrics tfm = g2.getFontMetrics();
                int labelW = tfm.stringWidth(timeLabel);
                g2.setColor(new Color(140, 140, 150));
                g2.drawString(timeLabel, x - labelW / 2, PAD_TOP + chartH + 14);
            }

            g2.setColor(new Color(60, 60, 70));
            g2.drawRect(PAD_LEFT, PAD_TOP, chartW, chartH);
        }

        private void drawPriceLine(Graphics2D g2, int chartW, int chartH,
                                   long startTime, long endTime,
                                   double minPrice, double priceRange,
                                   List<Double> prices, Color color) {
            long timeRange = endTime - startTime;
            if (timeRange <= 0) return;
            g2.setColor(color);
            g2.setStroke(new BasicStroke(2f));

            int idxStart = lowerBound(timestamps, startTime);
            int idxEnd = Math.min(lowerBound(timestamps, endTime + 1), timestamps.size());
            int visibleCount = idxEnd - idxStart;
            if (visibleCount < 1) return;

            Path2D path = new Path2D.Double();
            boolean started = false;
            int lastY = 0;

            if (visibleCount <= chartW * 2) {
                // few enough points: draw every point directly
                for (int i = idxStart; i < idxEnd; i++) {
                    int x = toX(timestamps.get(i), startTime, timeRange, chartW);
                    int y = toY(prices.get(i), minPrice, priceRange, chartH);
                    lastY = y;
                    if (!started) { path.moveTo(x, y); started = true; }
                    else { path.lineTo(x, y); }
                }
            } else {
                // min/max per pixel column — stable across frames
                int prevX = -1;
                double colMin = Double.MAX_VALUE, colMax = -Double.MAX_VALUE;
                double colFirst = 0, colLast = 0;
                boolean colHasData = false;

                for (int i = idxStart; i < idxEnd; i++) {
                    int x = toX(timestamps.get(i), startTime, timeRange, chartW);
                    double p = prices.get(i);

                    if (x != prevX && colHasData) {
                        // emit previous column: first -> min -> max -> last
                        int yFirst = toY(colFirst, minPrice, priceRange, chartH);
                        int yMin = toY(colMin, minPrice, priceRange, chartH);
                        int yMax = toY(colMax, minPrice, priceRange, chartH);
                        int yLast = toY(colLast, minPrice, priceRange, chartH);

                        if (!started) { path.moveTo(prevX, yFirst); started = true; }
                        else { path.lineTo(prevX, yFirst); }
                        path.lineTo(prevX, yMin);
                        path.lineTo(prevX, yMax);
                        path.lineTo(prevX, yLast);
                        lastY = yLast;

                        // reset column
                        colMin = Double.MAX_VALUE;
                        colMax = -Double.MAX_VALUE;
                        colHasData = false;
                    }

                    if (!colHasData) { colFirst = p; }
                    colLast = p;
                    colMin = Math.min(colMin, p);
                    colMax = Math.max(colMax, p);
                    colHasData = true;
                    prevX = x;
                }
                // emit final column
                if (colHasData) {
                    int yFirst = toY(colFirst, minPrice, priceRange, chartH);
                    int yMin = toY(colMin, minPrice, priceRange, chartH);
                    int yMax = toY(colMax, minPrice, priceRange, chartH);
                    int yLast = toY(colLast, minPrice, priceRange, chartH);

                    if (!started) { path.moveTo(prevX, yFirst); started = true; }
                    else { path.lineTo(prevX, yFirst); }
                    path.lineTo(prevX, yMin);
                    path.lineTo(prevX, yMax);
                    path.lineTo(prevX, yLast);
                    lastY = yLast;
                }
            }

            // extend last known value to the right edge
            if (started) {
                path.lineTo(PAD_LEFT + chartW, lastY);
                g2.draw(path);
            }
        }

        private void drawSpreadFill(Graphics2D g2, int chartW, int chartH,
                                    long startTime, long endTime,
                                    double minPrice, double priceRange) {
            long timeRange = endTime - startTime;
            if (timeRange <= 0) return;

            int idxStart = lowerBound(timestamps, startTime);
            int idxEnd = Math.min(lowerBound(timestamps, endTime + 1), timestamps.size());
            int visibleCount = idxEnd - idxStart;
            if (visibleCount < 2) return;

            // collect one (askY, bidY) per pixel column using last value per column
            // this is stable and produces at most chartW points
            List<int[]> cols = new ArrayList<>(); // {x, askY, bidY}
            int prevX = -1;
            int curAskY = 0, curBidY = 0;

            for (int i = idxStart; i < idxEnd; i++) {
                int x = toX(timestamps.get(i), startTime, timeRange, chartW);
                int yAsk = toY(askPrices.get(i), minPrice, priceRange, chartH);
                int yBid = toY(bidPrices.get(i), minPrice, priceRange, chartH);

                if (x != prevX && prevX >= 0) {
                    cols.add(new int[]{prevX, curAskY, curBidY});
                }
                curAskY = yAsk;
                curBidY = yBid;
                prevX = x;
            }
            // emit final column
            if (prevX >= 0) {
                cols.add(new int[]{prevX, curAskY, curBidY});
            }

            if (cols.size() < 2) return;

            // extend to right edge
            int[] last = cols.get(cols.size() - 1);
            int rightEdge = PAD_LEFT + chartW;
            if (last[0] < rightEdge) {
                cols.add(new int[]{rightEdge, last[1], last[2]});
            }

            Path2D fill = new Path2D.Double();
            fill.moveTo(cols.get(0)[0], cols.get(0)[1]); // ask forward
            for (int i = 1; i < cols.size(); i++) {
                fill.lineTo(cols.get(i)[0], cols.get(i)[1]);
            }
            for (int i = cols.size() - 1; i >= 0; i--) { // bid backward
                fill.lineTo(cols.get(i)[0], cols.get(i)[2]);
            }
            fill.closePath();

            g2.setColor(settings.spreadFillColor);
            g2.fill(fill);
        }
    }
}
