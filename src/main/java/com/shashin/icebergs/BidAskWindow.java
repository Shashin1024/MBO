package com.shashin.icebergs;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class BidAskWindow extends JFrame {

    private final double pips;
    private final String priceFormat;
    private final IcebergSettings settings;
    private final BidAskPanel chartPanel;
    private final JLabel bidLabel;
    private final JLabel askLabel;
    private final JLabel spreadLabel;
    private SettingsWindow settingsWindow;

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

    // enough for a full trading session (~8+ hours at high frequency)
    private static final int MAX_POINTS = 1_000_000;
    private static final int TRIM_BATCH = 50_000; // trim in bulk for efficiency

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

            if (timestamps.size() > MAX_POINTS) {
                int excess = TRIM_BATCH;
                timestamps.subList(0, excess).clear();
                bidPrices.subList(0, excess).clear();
                askPrices.subList(0, excess).clear();
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

    public void addIceberg(double price, double totalFilled, double executionRatio, boolean isBid, long timestamp) {
        synchronized (icebergTimes) {
            icebergTimes.add(timestamp);
            icebergPrices.add(price);
            icebergVolumes.add(totalFilled);
            icebergExecRatios.add(executionRatio);
            icebergIsBid.add(isBid);
        }
    }

    public void addIcebergV3(double price, double totalFilled, double executionRatio, boolean isBid, long timestamp) {
        synchronized (v3Times) {
            v3Times.add(timestamp);
            v3Prices.add(price);
            v3Volumes.add(totalFilled);
            v3ExecRatios.add(executionRatio);
            v3IsBid.add(isBid);
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
        private static final int PAD_BOTTOM = 25;

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
                for (int i = 0; i < timestamps.size(); i++) {
                    long t = timestamps.get(i);
                    if (t >= startTime && t <= endTime) {
                        double bid = bidPrices.get(i);
                        double ask = askPrices.get(i);
                        minP = Math.min(minP, Math.min(bid, ask));
                        maxP = Math.max(maxP, Math.max(bid, ask));
                    }
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
                long endTime = now - timeOffset;
                long startTime = endTime - visibleMs;

                // find auto-fit price range over visible window
                double minPrice = Double.MAX_VALUE;
                double maxPrice = -Double.MAX_VALUE;

                for (int i = 0; i < timestamps.size(); i++) {
                    long t = timestamps.get(i);
                    if (t >= startTime && t <= endTime) {
                        double bid = bidPrices.get(i);
                        double ask = askPrices.get(i);
                        minPrice = Math.min(minPrice, Math.min(bid, ask));
                        maxPrice = Math.max(maxPrice, Math.max(bid, ask));
                    }
                }

                if (minPrice >= maxPrice) {
                    double mid = (minPrice + maxPrice) / 2.0;
                    minPrice = mid - pips * 5;
                    maxPrice = mid + pips * 5;
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

                // draw completed iceberg diamonds on top (V2 then V3)
                drawIcebergBubbles(g2, chartW, chartH, startTime, endTime, minPrice, priceRange);
                drawV3IcebergBubbles(g2, chartW, chartH, startTime, endTime, minPrice, priceRange);

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

                // show "HISTORY" indicator when panned away from live
                if (timeOffset > 0) {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                    g2.setColor(new Color(255, 200, 50, 200));
                    g2.drawString("HISTORY (double-click to return to live)",
                            PAD_LEFT + 5, PAD_TOP + 14);
                }
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

        private void drawIcebergBubbles(Graphics2D g2, int chartW, int chartH,
                                         long startTime, long endTime,
                                         double minPrice, double priceRange) {
            long timeRange = endTime - startTime;

            synchronized (icebergTimes) {
                if (icebergTimes.isEmpty()) return;

                for (int i = 0; i < icebergTimes.size(); i++) {
                    long t = icebergTimes.get(i);
                    if (t < startTime || t > endTime) continue;

                    double price = icebergPrices.get(i);
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

                    // label: volume + exec ratio
                    g2.setFont(new Font("SansSerif", Font.BOLD, settings.diamondLabelFontSize));
                    FontMetrics fm = g2.getFontMetrics();
                    String volStr = formatVolumeDouble(volume);
                    String ratioStr = String.format("%.1fx", execRatio);
                    String label = volStr + " | " + ratioStr;
                    int labelW = fm.stringWidth(label);

                    // label above the diamond
                    g2.setColor(Color.WHITE);
                    g2.drawString(label, cx - labelW / 2, cy - r - 4);
                }
            }
        }

        private void drawV3IcebergBubbles(Graphics2D g2, int chartW, int chartH,
                                            long startTime, long endTime,
                                            double minPrice, double priceRange) {
            long timeRange = endTime - startTime;

            synchronized (v3Times) {
                if (v3Times.isEmpty()) return;

                for (int i = 0; i < v3Times.size(); i++) {
                    long t = v3Times.get(i);
                    if (t < startTime || t > endTime) continue;

                    double price = v3Prices.get(i);
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

                    // label: "V3" prefix + volume + exec ratio
                    g2.setFont(new Font("SansSerif", Font.BOLD, settings.diamondLabelFontSize));
                    FontMetrics fm = g2.getFontMetrics();
                    String volStr = formatVolumeDouble(volume);
                    String ratioStr = String.format("%.1fx", execRatio);
                    String label = "V3 " + volStr + " | " + ratioStr;
                    int labelW = fm.stringWidth(label);

                    g2.setColor(Color.WHITE);
                    g2.drawString(label, cx - labelW / 2, cy - r - 4);
                }
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

        private void drawGrid(Graphics2D g2, int chartW, int chartH,
                              long startTime, long endTime,
                              double minPrice, double maxPrice, double priceRange) {
            g2.setStroke(new BasicStroke(1f));

            // price axis labels (larger font)
            g2.setFont(new Font("Monospaced", Font.BOLD, 12));
            int numH = 6;
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
            int numV = 6;
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
            g2.setColor(color);
            g2.setStroke(new BasicStroke(2f));

            Path2D path = new Path2D.Double();
            boolean started = false;
            int lastY = 0;

            for (int i = 0; i < timestamps.size(); i++) {
                long t = timestamps.get(i);
                if (t < startTime) continue;
                if (t > endTime) break;

                int x = toX(t, startTime, timeRange, chartW);
                int y = toY(prices.get(i), minPrice, priceRange, chartH);
                lastY = y;

                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
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
            List<int[]> pts = new ArrayList<>();

            for (int i = 0; i < timestamps.size(); i++) {
                long t = timestamps.get(i);
                if (t < startTime) continue;
                if (t > endTime) break;

                int x = toX(t, startTime, timeRange, chartW);
                int yAsk = toY(askPrices.get(i), minPrice, priceRange, chartH);
                int yBid = toY(bidPrices.get(i), minPrice, priceRange, chartH);
                pts.add(new int[]{x, yAsk, yBid});
            }

            if (pts.size() < 2) return;

            // extend last known values to the right edge
            int[] last = pts.get(pts.size() - 1);
            int rightEdge = PAD_LEFT + chartW;
            if (last[0] < rightEdge) {
                pts.add(new int[]{rightEdge, last[1], last[2]});
            }

            Path2D fill = new Path2D.Double();
            fill.moveTo(pts.get(0)[0], pts.get(0)[1]);
            for (int i = 1; i < pts.size(); i++) {
                fill.lineTo(pts.get(i)[0], pts.get(i)[1]);
            }
            for (int i = pts.size() - 1; i >= 0; i--) {
                fill.lineTo(pts.get(i)[0], pts.get(i)[2]);
            }
            fill.closePath();

            g2.setColor(settings.spreadFillColor);
            g2.fill(fill);
        }
    }
}
