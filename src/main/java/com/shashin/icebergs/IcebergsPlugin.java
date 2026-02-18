package com.shashin.icebergs;

import velox.api.layer1.Layer1ApiDataAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentAdapter;
import velox.api.layer1.Layer1ApiMboDataAdapter;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;

import javax.swing.*;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Layer1Attachable
@Layer1StrategyName("Bid/Ask Lines")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class IcebergsPlugin implements
        Layer1ApiFinishable,
        Layer1ApiInstrumentAdapter,
        Layer1ApiDataAdapter,
        Layer1ApiMboDataAdapter {

    private final Layer1ApiProvider provider;
    private final IcebergSettings settings = new IcebergSettings();
    private final Map<String, InstrumentInfo> instruments = new ConcurrentHashMap<>();
    private final Map<String, BidAskWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Integer, Integer>> bidBooks = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Integer, Integer>> askBooks = new ConcurrentHashMap<>();
    private final Map<String, IcebergDetector> detectors = new ConcurrentHashMap<>();
    private final Map<String, IcebergV3Detector> v3Detectors = new ConcurrentHashMap<>();

    public IcebergsPlugin(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
    }

    @Override
    public void finish() {
        ListenableHelper.removeListeners(provider, this);
        for (BidAskWindow window : windows.values()) {
            SwingUtilities.invokeLater(window::dispose);
        }
        windows.clear();
        instruments.clear();
        bidBooks.clear();
        askBooks.clear();
        detectors.clear();
        v3Detectors.clear();
    }

    @Override
    public void onInstrumentAdded(String alias, InstrumentInfo info) {
        instruments.put(alias, info);
        bidBooks.put(alias, new TreeMap<>());
        askBooks.put(alias, new TreeMap<>());

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

        // V3 detector (independent algorithm, different thresholds)
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

    @Override
    public void onInstrumentRemoved(String alias) {
        instruments.remove(alias);
        bidBooks.remove(alias);
        askBooks.remove(alias);
        detectors.remove(alias);
        v3Detectors.remove(alias);

        BidAskWindow window = windows.remove(alias);
        if (window != null) {
            SwingUtilities.invokeLater(window::dispose);
        }
    }

    @Override
    public void onDepth(String alias, boolean isBid, int price, int size) {
        TreeMap<Integer, Integer> book = isBid ? bidBooks.get(alias) : askBooks.get(alias);
        if (book == null) return;

        synchronized (book) {
            if (size == 0) {
                book.remove(price);
            } else {
                book.put(price, size);
            }
        }

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

        // Feed raw tick prices to both detectors
        IcebergDetector detector = detectors.get(alias);
        if (detector != null) {
            detector.setBestBidAsk(bestBidTick, bestAskTick);
        }
        IcebergV3Detector v3Detector = v3Detectors.get(alias);
        if (v3Detector != null) {
            v3Detector.setBestBidAsk(bestBidTick, bestAskTick);
        }

        // Convert to real prices only for the chart window
        Double bestBid = bestBidTick != null ? bestBidTick * info.pips : null;
        Double bestAsk = bestAskTick != null ? bestAskTick * info.pips : null;

        if (bestBid != null && bestAsk != null) {
            window.updatePrices(bestBid, bestAsk);
        }
    }

    @Override
    public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
        InstrumentInfo info = instruments.get(alias);
        if (info == null) return;

        // Pass raw tick price to detector (matches Python behavior)
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
            window.addTrade(System.currentTimeMillis(), actualSize, tradeInfo.isBidAggressor);
        }
    }

    // --- MBO callbacks ---

    @Override
    public void onMboSend(String alias, String orderId, boolean isBid, int price, int size) {
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
