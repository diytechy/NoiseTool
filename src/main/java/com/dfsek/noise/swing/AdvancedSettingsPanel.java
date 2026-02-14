package com.dfsek.noise.swing;

import javax.swing.*;
import java.awt.*;
import java.util.Properties;

public class AdvancedSettingsPanel extends JPanel {
    private final JCheckBox useLetExpressions;
    private final JSpinner voxelResolution;
    private final JSpinner voxelBottomY;
    private final JSpinner voxelTopY;
    private final JCheckBox editorVerboseConsole;
    private final JTextArea statisticsPanel;

    private Runnable onVerboseConsoleChanged;

    public AdvancedSettingsPanel(Properties settings) {
        setLayout(new BorderLayout());

        boolean useLet = Boolean.parseBoolean(settings.getProperty("useLetExpressions", "false"));
        int voxRes = Integer.parseInt(settings.getProperty("voxelResolution", "128"));
        int voxBottom = Integer.parseInt(settings.getProperty("voxelBottomY", "-64"));
        int voxTop = Integer.parseInt(settings.getProperty("voxelTopY", "319"));
        boolean verbose = Boolean.parseBoolean(settings.getProperty("editorVerboseConsole", "false"));

        useLetExpressions = new JCheckBox();
        useLetExpressions.setSelected(useLet);

        voxelResolution = new JSpinner(new SpinnerNumberModel(voxRes, 0, Integer.MAX_VALUE, 1));
        voxelBottomY = new JSpinner(new SpinnerNumberModel(voxBottom, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
        voxelTopY = new JSpinner(new SpinnerNumberModel(voxTop, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));

        editorVerboseConsole = new JCheckBox();
        editorVerboseConsole.setSelected(verbose);
        editorVerboseConsole.addActionListener(e -> {
            if (onVerboseConsoleChanged != null) {
                onVerboseConsoleChanged.run();
            }
        });

        statisticsPanel = new JTextArea();
        statisticsPanel.setEditable(false);

        // Build the top section with settings
        JPanel settingsSection = new JPanel();
        settingsSection.setLayout(new BoxLayout(settingsSection, BoxLayout.Y_AXIS));

        // Options section
        JPanel optionsPanel = new JPanel(new GridBagLayout());
        optionsPanel.setBorder(BorderFactory.createTitledBorder("Options"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        optionsPanel.add(new JLabel("Use Let Expressions: "), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        optionsPanel.add(useLetExpressions, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        optionsPanel.add(new JLabel("Editor Verbose Console: "), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        optionsPanel.add(editorVerboseConsole, gbc);

        settingsSection.add(optionsPanel);

        // Voxel section
        JPanel voxelPanel = new JPanel(new GridBagLayout());
        voxelPanel.setBorder(BorderFactory.createTitledBorder("Voxel Preview"));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        voxelPanel.add(new JLabel("Resolution: "), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        voxelPanel.add(voxelResolution, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        voxelPanel.add(new JLabel("Bottom Y: "), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        voxelPanel.add(voxelBottomY, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        voxelPanel.add(new JLabel("Top Y: "), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        voxelPanel.add(voxelTopY, gbc);

        settingsSection.add(voxelPanel);

        add(settingsSection, BorderLayout.NORTH);

        // Statistics section at the bottom
        JPanel statsWrapper = new JPanel(new BorderLayout());
        statsWrapper.setBorder(BorderFactory.createTitledBorder("Statistics"));
        statsWrapper.add(new JScrollPane(statisticsPanel), BorderLayout.CENTER);

        add(statsWrapper, BorderLayout.CENTER);
    }

    public AdvancedSettingsPanel() {
        this(new Properties());
    }

    public boolean isUseLetExpressions() {
        return useLetExpressions.isSelected();
    }

    public int getVoxelResolution() {
        return ((Number) voxelResolution.getValue()).intValue();
    }

    public int getVoxelBottomY() {
        return ((Number) voxelBottomY.getValue()).intValue();
    }

    public int getVoxelTopY() {
        return ((Number) voxelTopY.getValue()).intValue();
    }

    public boolean isEditorVerboseConsole() {
        return editorVerboseConsole.isSelected();
    }

    public JTextArea getStatisticsPanel() {
        return statisticsPanel;
    }

    public void setOnVerboseConsoleChanged(Runnable callback) {
        this.onVerboseConsoleChanged = callback;
    }
}
