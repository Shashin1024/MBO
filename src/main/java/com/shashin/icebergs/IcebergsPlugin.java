package com.shashin.icebergs;

import velox.api.layer1.Layer1ApiDataAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentAdapter;
import velox.api.layer1.Layer1ApiMboDataAdapter;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.Layer1CustomPanelsGetter;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.gui.StrategyPanel;

import javax.swing.*;
import java.awt.FlowLayout;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Layer1Attachable
@Layer1StrategyName("Bid/Ask Lines")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class IcebergsPlugin implements
        Layer1ApiFinishable,
        Layer1ApiInstrumentAdapter,
        Layer1ApiDataAdapter,
        Layer1ApiMboDataAdapter,
        Layer1CustomPanelsGetter {

    private final Layer1ApiProvider provider;
    private final IcebergSettings settings = new IcebergSettings();
    private final Map<String, InstrumentInfo> instruments = new ConcurrentHashMap<>();
    private final Map<String, BidAskWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Integer, Integer>> bidBooks = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Integer, Integer>> askBooks = new ConcurrentHashMap<>();
    private final Map<String, IcebergDetector> detectors = new ConcurrentHashMap<>();
    private final Map<String, IcebergV3Detector> v3Detectors = new ConcurrentHashMap<>();

    // Per-instrument enable/disable state — false by default until user checks the box
    private final ConcurrentHashMap<String, Boolean> activeInstruments = new ConcurrentHashMap<>();

    // Tracks which price ticks currently have an active heatmap bar (volume crossed above threshold).
    private final Map<String, Set<Integer>> heatmapTracked = new ConcurrentHashMap<>();

    // Periodic cleanup: evicts stale orders that were never explicitly cancelled.
    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "iceberg-cleanup");
                t.setDaemon(true);
                return t;
            });

    public IcebergsPlugin(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
        cleanupScheduler.scheduleAtFixedRate(this::cleanupAllDetectors, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanupAllDetectors() {
        for (IcebergDetector d : detectors.values()) d.cleanupOldOrders();
        for (IcebergV3Detector d : v3Detectors.values()) d.cleanupOldOrders();
    }

    // ---- Per-instrument lifecycle ----

    private void startForInstrument(String alias) {
        InstrumentInfo info = instruments.get(alias);
        if (info == null) return;

        // Use putIfAbsent so any books already filled by onDepth are preserved
        bidBooks.putIfAbsent(alias, new TreeMap<>());
        askBooks.putIfAbsent(alias, new TreeMap<>());
        heatmapTracked.put(alias, ConcurrentHashMap.newKeySet());

        TreeMap<Integer, Integer> bidBook = bidBooks.get(alias);
        TreeMap<Integer, Integer> askBook = askBooks.get(alias);

        SwingUtilities.invokeLater(() -> {
            BidAskWindow window = new BidAskWindow(alias, info.pips, settings);
            window.setDepthBooks(bidBook, askBook);
            windows.put(alias, window);
            window.setVisible(true);
        });

        IcebergDetector detector = new IcebergDetector(
                info.sizeMultiplier, info.pips, settings,
                completed -> {
                    BidAskWindow w = windows.get(alias);
                    if (w != null) {
                        w.addIceberg(
                                completed.price,
                                completed.totalFilled,
                                completed.executionRatio,
                                completed.isBid,
                                completed.timestamp
                        );
                    }
                }
        );
        detectors.put(alias, detector);

        IcebergV3Detector v3Detector = new IcebergV3Detector(
                info.sizeMultiplier, info.pips, settings,
                completed -> {
                    BidAskWindow w = windows.get(alias);
                    if (w != null) {
                        w.addIcebergV3(
                                completed.price,
                                completed.totalFilled,
                                completed.executionRatio,
                                completed.isBid,
                                completed.timestamp
                        );
                    }
                }
        );
        v3Detectors.put(alias, v3Detector);
    }

    private void stopForInstrument(String alias) {
        // Keep books so they stay populated across enable/disable cycles
        heatmapTracked.remove(alias);
        detectors.remove(alias);
        v3Detectors.remove(alias);

        BidAskWindow window = windows.remove(alias);
        if (window != null) {
            SwingUtilities.invokeLater(window::dispose);
        }
    }

    // ---- Layer1CustomPanelsGetter ----

    @Override
    public StrategyPanel[] getCustomGuiFor(String alias, String title) {
        StrategyPanel enablePanel = new StrategyPanel("Enable");
        enablePanel.setLayout(new FlowLayout(FlowLayout.LEFT));

        JCheckBox enableCb = new JCheckBox(
                "Enable for " + alias,
                activeInstruments.getOrDefault(alias, false)
        );
        enableCb.addActionListener(e -> {
            boolean selected = enableCb.isSelected();
            activeInstruments.put(alias, selected);
            if (selected) {
                startForInstrument(alias);
            } else {
                stopForInstrument(alias);
            }
        });
        enablePanel.add(enableCb);

        return new StrategyPanel[]{ enablePanel };
    }

    // ---- Instrument lifecycle ----

    @Override
    public void finish() {
        cleanupScheduler.shutdownNow();
        ListenableHelper.removeListeners(provider, this);
        for (BidAskWindow window : windows.values()) {
            SwingUtilities.invokeLater(window::dispose);
        }
        windows.clear();
        instruments.clear();
        activeInstruments.clear();
        bidBooks.clear();
        askBooks.clear();
        detectors.clear();
        v3Detectors.clear();
        heatmapTracked.clear();
    }

    @Override
    public void onInstrumentAdded(String alias, InstrumentInfo info) {
        instruments.put(alias, info);
        activeInstruments.putIfAbsent(alias, false);
        // Initialize books immediately so onDepth can populate them before enable
        bidBooks.putIfAbsent(alias, new TreeMap<>());
        askBooks.putIfAbsent(alias, new TreeMap<>());
    }

    @Override
    public void onInstrumentRemoved(String alias) {
        activeInstruments.remove(alias);
        instruments.remove(alias);
        bidBooks.remove(alias);
        askBooks.remove(alias);
        stopForInstrument(alias);
    }

    // ---- Data callbacks (all gated on enabled state) ----

    @Override
    public void onDepth(String alias, boolean isBid, int price, int size) {
        // Always update the order book — even when the instrument is disabled,
        // so the full snapshot is ready the moment the user enables it.
        TreeMap<Integer, Integer> book = isBid ? bidBooks.get(alias) : askBooks.get(alias);
        if (book == null) return;

        Integer oldSize;
        synchronized (book) {
            oldSize = book.get(price);
            if (size == 0) {
                book.remove(price);
            } else {
                book.put(price, size);
            }
        }

        // Everything below is only relevant when the instrument is active
        if (!activeInstruments.getOrDefault(alias, false)) return;

        TreeMap<Integer, Integer> bidBook = bidBooks.get(alias);
        TreeMap<Integer, Integer> askBook = askBooks.get(alias);
        if (bidBook == null || askBook == null) return;

        Integer bestBidTick;
        Integer bestAskTick;

        synchronized (bidBook) {
            bestBidTick = bidBook.isEmpty() ? null : bidBook.lastKey();
        }
        synchronized (askBook) {
            bestAskTick = askBook.isEmpty() ? null : askBook.firstKey();
        }

        InstrumentInfo info = instruments.get(alias);
        BidAskWindow window = windows.get(alias);
        if (info == null || window == null) return;

        // Depth heatmap:
        // - Bar STARTS when a level first crosses above the threshold (and is not already tracked).
        // - Bar ENDS when volume drops back below the threshold (meaning the significant volume was
        //   pulled). This handles both full removal (size=0) AND partial removal leaving only small
        //   residual orders below the threshold — both indicate the order of interest was pulled.
        int oldSizeVal = (oldSize != null) ? oldSize : 0;
        double threshold = settings.heatmapVolumeThreshold;
        Set<Integer> tracked = heatmapTracked.get(alias);
        if (tracked != null) {
            boolean isTracked = tracked.contains(price);
            boolean nowAbove = size >= threshold;   // also false when size == 0

            if (!isTracked && nowAbove) {
                tracked.add(price);
                window.addDepthEvent(System.currentTimeMillis(), price * info.pips, size, isBid, true);
            } else if (isTracked && !nowAbove) {
                tracked.remove(price);
                window.addDepthEvent(System.currentTimeMillis(), price * info.pips, oldSizeVal, isBid, false);
            }
        }

        IcebergDetector detector = detectors.get(alias);
        if (detector != null) {
            detector.setBestBidAsk(bestBidTick, bestAskTick);
        }
        IcebergV3Detector v3Detector = v3Detectors.get(alias);
        if (v3Detector != null) {
            v3Detector.setBestBidAsk(bestBidTick, bestAskTick);
        }

        Double bestBid = bestBidTick != null ? bestBidTick * info.pips : null;
        Double bestAsk = bestAskTick != null ? bestAskTick * info.pips : null;

        if (bestBid != null && bestAsk != null) {
            window.updatePrices(bestBid, bestAsk);
        }
    }

    @Override
    public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
        if (!activeInstruments.getOrDefault(alias, false)) return;

        InstrumentInfo info = instruments.get(alias);
        if (info == null) return;

        int priceTicks = (int) price;
        double actualSize = info.sizeMultiplier > 0 ? size / info.sizeMultiplier : size;

        IcebergDetector detector = detectors.get(alias);
        if (detector != null) {
            detector.addTrade(priceTicks, actualSize, tradeInfo.isBidAggressor);
        }
        IcebergV3Detector v3Detector = v3Detectors.get(alias);
        if (v3Detector != null) {
            v3Detector.addTrade(priceTicks, actualSize, tradeInfo.isBidAggressor);
        }

        BidAskWindow window = windows.get(alias);
        if (window != null) {
            long now = System.currentTimeMillis();
            window.addTrade(now, actualSize, tradeInfo.isBidAggressor);

            if (actualSize >= settings.bigVolumeThreshold) {
                double realPrice = price * info.pips;
                window.addBigTrade(now, realPrice, actualSize, tradeInfo.isBidAggressor);
            }
        }
    }

    // --- MBO callbacks ---

    @Override
    public void onMboSend(String alias, String orderId, boolean isBid, int price, int size) {
        if (!activeInstruments.getOrDefault(alias, false)) return;

        IcebergDetector detector = detectors.get(alias);
        if (detector != null) {
            detector.processNewOrder(orderId, isBid, price, size);
        }
        IcebergV3Detector v3Detector = v3Detectors.get(alias);
        if (v3Detector != null) {
            v3Detector.processNewOrder(orderId, isBid, price, size);
        }
    }

    @Override
    public void onMboReplace(String alias, String orderId, int price, int size) {
        if (!activeInstruments.getOrDefault(alias, false)) return;

        IcebergDetector detector = detectors.get(alias);
        if (detector != null) {
            detector.processReplaceOrder(orderId, price, size);
        }
        IcebergV3Detector v3Detector = v3Detectors.get(alias);
        if (v3Detector != null) {
            v3Detector.processReplaceOrder(orderId, price, size);
        }
    }

    @Override
    public void onMboCancel(String alias, String orderId) {
        if (!activeInstruments.getOrDefault(alias, false)) return;

        IcebergDetector detector = detectors.get(alias);
        if (detector != null) {
            detector.processCancelOrder(orderId);
        }
        IcebergV3Detector v3Detector = v3Detectors.get(alias);
        if (v3Detector != null) {
            v3Detector.processCancelOrder(orderId);
        }
    }
}
