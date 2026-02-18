package com.shashin.icebergs;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.function.Consumer;

public class SettingsWindow extends JFrame {

    private static final Color PANEL_BG = new Color(35, 38, 42);
    private static final Color SECTION_BG = new Color(42, 45, 50);
    private static final Color LABEL_FG = new Color(180, 185, 190);
    private static final Color VALUE_FG = new Color(220, 225, 230);
    private static final Color ACCENT = new Color(88, 166, 255);
    private static final Color BORDER_COLOR = new Color(60, 64, 70);

    private final IcebergSettings settings;

    public SettingsWindow(IcebergSettings settings) {
        super("Iceberg Settings");
        this.settings = settings;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(440, 900);
        setLocationRelativeTo(null);
        setResizable(true);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new GridBagLayout());
        mainPanel.setBackground(PANEL_BG);
        mainPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(3, 0, 3, 0);

        gbc.gridy = 0;
        mainPanel.add(createDetectionSection(), gbc);
        gbc.gridy = 1;
        mainPanel.add(createAlertSection(), gbc);
        gbc.gridy = 2;
        mainPanel.add(createPatternSection(), gbc);
        gbc.gridy = 3;
        mainPanel.add(createTimingSection(), gbc);
        gbc.gridy = 4;
        mainPanel.add(createChartColorsSection(), gbc);
        gbc.gridy = 5;
        mainPanel.add(createDiamondSection(), gbc);
        gbc.gridy = 6;
        mainPanel.add(createV3ThresholdsSection(), gbc);
        gbc.gridy = 7;
        mainPanel.add(createV3DiamondSection(), gbc);
        gbc.gridy = 8;
        mainPanel.add(createDiamondSizingSection(), gbc);
        gbc.gridy = 9;
        mainPanel.add(createDeltaTableSection(), gbc);
        gbc.gridy = 10;
        mainPanel.add(createDepthTableSection(), gbc);

        // push everything up
        gbc.gridy = 11;
        gbc.weighty = 1.0;
        mainPanel.add(new JPanel() {{
            setOpaque(false);
        }}, gbc);

        JScrollPane scrollPane = new JScrollPane(mainPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getViewport().setBackground(PANEL_BG);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scrollPane, BorderLayout.CENTER);
    }

    private JPanel createSection(String title) {
        JPanel section = new JPanel(new GridBagLayout());
        section.setBackground(SECTION_BG);
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                title);
        tb.setTitleColor(ACCENT);
        tb.setTitleFont(new Font("SansSerif", Font.BOLD, 12));
        section.setBorder(tb);
        return section;
    }

    private void addRow(JPanel section, int row, String label, JComponent control) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(3, 8, 3, 10);
        lc.weightx = 1.0;

        JLabel lbl = new JLabel(label);
        lbl.setForeground(LABEL_FG);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        section.add(lbl, lc);

        GridBagConstraints rc = new GridBagConstraints();
        rc.gridx = 1;
        rc.gridy = row;
        rc.anchor = GridBagConstraints.EAST;
        rc.insets = new Insets(3, 0, 3, 8);
        section.add(control, rc);
    }

    // ---- Sections ----

    private JPanel createDetectionSection() {
        JPanel s = createSection("Detection Parameters");
        addRow(s, 0, "Trigger Size %", makeSpinner(settings.triggerSizePercentage, 1, 200, 1,
                v -> settings.triggerSizePercentage = v));
        addRow(s, 1, "Max Visible %", makeSpinner(settings.maxVisiblePercentage, 10, 500, 5,
                v -> settings.maxVisiblePercentage = v));
        addRow(s, 2, "Min Visible %", makeSpinner(settings.minVisiblePercentage, 1, 100, 1,
                v -> settings.minVisiblePercentage = v));
        addRow(s, 3, "Volume Threshold %", makeSpinner(settings.volumeThresholdPercentage, 1, 200, 5,
                v -> settings.volumeThresholdPercentage = v));
        addRow(s, 4, "Max Distance (pips)", makeSpinner(settings.maxDistancePips, 1, 500, 5,
                v -> settings.maxDistancePips = v));
        return s;
    }

    private JPanel createAlertSection() {
        JPanel s = createSection("Alert Thresholds");
        addRow(s, 0, "Exec Ratio Threshold", makeSpinner(settings.alertExecutionRatioThreshold, 0.5, 50, 0.5,
                v -> settings.alertExecutionRatioThreshold = v));
        addRow(s, 1, "Total Filled Threshold", makeSpinner(settings.alertTotalFilledThreshold, 10, 5000, 10,
                v -> settings.alertTotalFilledThreshold = v));
        return s;
    }

    private JPanel createPatternSection() {
        JPanel s = createSection("Pattern Settings");
        addRow(s, 0, "Min Refill Count", makeIntSpinner(settings.minRefillCount, 0, 20, 1,
                v -> settings.minRefillCount = v));
        addRow(s, 1, "Min Score Threshold", makeSpinner(settings.minScoreThreshold, 0.0, 1.0, 0.05,
                v -> settings.minScoreThreshold = v));
        addRow(s, 2, "Min Size Decrease (Vol)", makeIntSpinner(settings.minSizeDecreaseForVolume, 1, 20, 1,
                v -> settings.minSizeDecreaseForVolume = v));
        addRow(s, 3, "Min Size Decrease (Partial)", makeIntSpinner(settings.minSizeDecreaseForPartial, 1, 20, 1,
                v -> settings.minSizeDecreaseForPartial = v));
        addRow(s, 4, "Execution Threshold", makeSpinner(settings.executionThreshold, 0.1, 5.0, 0.1,
                v -> settings.executionThreshold = v));
        addRow(s, 5, "Hidden Liq. Size Ratio", makeSpinner(settings.hiddenLiquiditySizeRatio, 0.1, 1.0, 0.05,
                v -> settings.hiddenLiquiditySizeRatio = v));
        return s;
    }

    private JPanel createTimingSection() {
        JPanel s = createSection("Timing");
        addRow(s, 0, "Time Window (sec)", makeIntSpinner((int) settings.timeWindowSeconds, 60, 60000, 60,
                v -> settings.timeWindowSeconds = v));
        addRow(s, 1, "Visible Seconds", makeIntSpinner(settings.visibleSeconds, 10, 600, 5,
                v -> settings.visibleSeconds = v));
        return s;
    }

    private JPanel createChartColorsSection() {
        JPanel s = createSection("Chart Colors");
        addRow(s, 0, "Bid Line", makeColorButton(settings.bidLineColor, c -> settings.bidLineColor = c));
        addRow(s, 1, "Ask Line", makeColorButton(settings.askLineColor, c -> settings.askLineColor = c));
        addRow(s, 2, "Spread Fill", makeColorButton(settings.spreadFillColor, c -> settings.spreadFillColor = c));
        addRow(s, 3, "Background", makeColorButton(settings.chartBackground, c -> settings.chartBackground = c));
        addRow(s, 4, "Grid", makeColorButton(settings.gridColor, c -> settings.gridColor = c));
        return s;
    }

    private JPanel createDiamondSection() {
        JPanel s = createSection("V2 Diamond Colors");
        addRow(s, 0, "Bid Fill", makeColorButton(settings.iceBidFill, c -> settings.iceBidFill = c));
        addRow(s, 1, "Bid Edge", makeColorButton(settings.iceBidEdge, c -> settings.iceBidEdge = c));
        addRow(s, 2, "Ask Fill", makeColorButton(settings.iceAskFill, c -> settings.iceAskFill = c));
        addRow(s, 3, "Ask Edge", makeColorButton(settings.iceAskEdge, c -> settings.iceAskEdge = c));
        return s;
    }

    private JPanel createV3ThresholdsSection() {
        JPanel s = createSection("V3 Detection Thresholds");
        addRow(s, 0, "Exec Ratio Threshold", makeSpinner(settings.v3ExecRatioThreshold, 1, 200, 1,
                v -> settings.v3ExecRatioThreshold = v));
        addRow(s, 1, "Total Filled Threshold", makeSpinner(settings.v3TotalFilledThreshold, 10, 5000, 10,
                v -> settings.v3TotalFilledThreshold = v));
        return s;
    }

    private JPanel createV3DiamondSection() {
        JPanel s = createSection("V3 Diamond Colors");
        addRow(s, 0, "Bid Fill", makeColorButton(settings.v3BidFill, c -> settings.v3BidFill = c));
        addRow(s, 1, "Bid Edge", makeColorButton(settings.v3BidEdge, c -> settings.v3BidEdge = c));
        addRow(s, 2, "Ask Fill", makeColorButton(settings.v3AskFill, c -> settings.v3AskFill = c));
        addRow(s, 3, "Ask Edge", makeColorButton(settings.v3AskEdge, c -> settings.v3AskEdge = c));
        return s;
    }

    private JPanel createDiamondSizingSection() {
        JPanel s = createSection("Diamond Sizing (V2 & V3)");
        addRow(s, 0, "Min Radius", makeSpinner(settings.diamondMinRadius, 2, 40, 1,
                v -> settings.diamondMinRadius = v));
        addRow(s, 1, "Max Radius", makeSpinner(settings.diamondMaxRadius, 10, 120, 5,
                v -> settings.diamondMaxRadius = v));
        addRow(s, 2, "Min Volume", makeSpinner(settings.diamondMinVolume, 1, 500, 5,
                v -> settings.diamondMinVolume = v));
        addRow(s, 3, "Max Volume", makeSpinner(settings.diamondMaxVolume, 100, 10000, 100,
                v -> settings.diamondMaxVolume = v));
        addRow(s, 4, "Label Font Size", makeIntSpinner(settings.diamondLabelFontSize, 8, 24, 1,
                v -> settings.diamondLabelFontSize = v));
        return s;
    }

    private JPanel createDeltaTableSection() {
        JPanel s = createSection("Delta Table");
        addRow(s, 0, "Font Size", makeIntSpinner(settings.deltaTableFontSize, 6, 20, 1,
                v -> settings.deltaTableFontSize = v));
        return s;
    }

    private JPanel createDepthTableSection() {
        JPanel s = createSection("Depth Table");
        addRow(s, 0, "Volume Threshold", makeSpinner(settings.depthVolumeThreshold, 1, 10000, 10,
                v -> settings.depthVolumeThreshold = v));
        addRow(s, 1, "Font Size", makeIntSpinner(settings.depthTableFontSize, 6, 20, 1,
                v -> settings.depthTableFontSize = v));
        return s;
    }

    // ---- Control Builders ----

    private JSpinner makeSpinner(double initial, double min, double max, double step,
                                  Consumer<Double> onChange) {
        SpinnerNumberModel model = new SpinnerNumberModel(initial, min, max, step);
        JSpinner spinner = new JSpinner(model);
        spinner.setPreferredSize(new Dimension(100, 24));
        styleSpinner(spinner);
        spinner.addChangeListener(e -> onChange.accept(((Number) spinner.getValue()).doubleValue()));
        return spinner;
    }

    private JSpinner makeIntSpinner(int initial, int min, int max, int step,
                                     Consumer<Integer> onChange) {
        SpinnerNumberModel model = new SpinnerNumberModel(initial, min, max, step);
        JSpinner spinner = new JSpinner(model);
        spinner.setPreferredSize(new Dimension(100, 24));
        styleSpinner(spinner);
        spinner.addChangeListener(e -> onChange.accept(((Number) spinner.getValue()).intValue()));
        return spinner;
    }

    private void styleSpinner(JSpinner spinner) {
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
            tf.setBackground(new Color(55, 59, 65));
            tf.setForeground(VALUE_FG);
            tf.setCaretColor(VALUE_FG);
            tf.setFont(new Font("SansSerif", Font.PLAIN, 12));
        }
    }

    private JButton makeColorButton(Color initial, Consumer<Color> onChange) {
        JButton btn = new JButton() {
            private Color currentColor = initial;

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(currentColor);
                g.fillRoundRect(4, 4, getWidth() - 8, getHeight() - 8, 4, 4);
            }

            // expose getter/setter via client property
        };
        btn.putClientProperty("swatchColor", initial);
        btn.setPreferredSize(new Dimension(60, 24));
        btn.setBackground(SECTION_BG);
        btn.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btn.addActionListener(e -> {
            Color current = (Color) btn.getClientProperty("swatchColor");
            Color chosen = JColorChooser.showDialog(this, "Choose Color", current);
            if (chosen != null) {
                Color withAlpha = new Color(chosen.getRed(), chosen.getGreen(),
                        chosen.getBlue(), initial.getAlpha());
                btn.putClientProperty("swatchColor", withAlpha);
                onChange.accept(withAlpha);
                btn.repaint();
            }
        });
        return btn;
    }
}
