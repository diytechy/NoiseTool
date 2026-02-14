package com.dfsek.noise.swing;

import com.dfsek.noise.utils.SwingUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;


public class NoiseSettingsPanel extends JPanel {
    private final JSpinner seedSpinner;
    private final JSpinner xOrigin;
    private final JSpinner zOrigin;
    private final JSpinner perspectiveMultiplier;

    // Color scale presets
    private final ColorScale customColorScalePreset = new ColorScale("Custom", false, (float[][]) null);
    private final ColorScale[] presets = {
        ColorScale.SOLID,
        ColorScale.GRAYSCALE_NORMALIZED,
        ColorScale.GRAYSCALE_0_1,
        ColorScale.GRAYSCALE_0_256,
        ColorScale.GRAYSCALE_N64_320,
        ColorScale.COLORED_0_320,
        customColorScalePreset
    };
    private ColorScale customColorScale;

    // Color scale settings
    private final JComboBox<ColorScale> colorScalePresets;
    private final JCheckBox colorScaleNormalized;
    private final JTextArea colorScaleEditor;

    public NoiseSettingsPanel(Properties settings) {
        super(new SpringLayout());

        int seed = Integer.parseInt(settings.getProperty("seed", "2403"));
        double origX = Double.parseDouble(settings.getProperty("originX", "0"));
        double origZ = Double.parseDouble(settings.getProperty("originZ", "0"));
        int perspMult = Integer.parseInt(settings.getProperty("perspectiveMultiplier", "20"));

        seedSpinner = new JSpinner(new SpinnerNumberModel(seed, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
        xOrigin = new JSpinner(new SpinnerNumberModel(origX, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
        zOrigin = new JSpinner(new SpinnerNumberModel(origZ, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
        perspectiveMultiplier = new JSpinner(new SpinnerNumberModel(perspMult, 1, 100, 1));

        colorScalePresets = new JComboBox<>(presets);
        colorScaleNormalized = new JCheckBox();
        colorScaleEditor = new JTextArea();

        add(new JLabel("Seed: "));
        add(seedSpinner);
        add(new JLabel("X Origin: "));
        add(xOrigin);
        add(new JLabel("Z Origin: "));
        add(zOrigin);

        add(new JLabel("Perspective Multiplier: "));
        add(perspectiveMultiplier);

        add(new JLabel("Color scale preset: "));

        // Restore color scale preset from settings
        String presetName = settings.getProperty("colorScalePreset", "Grayscale normalized");
        ColorScale selectedPreset = ColorScale.GRAYSCALE_NORMALIZED;
        for (ColorScale p : presets) {
            if (p != customColorScalePreset && p.getName().equals(presetName)) {
                selectedPreset = p;
                break;
            }
        }
        // Check if custom was saved
        if ("Custom".equals(presetName)) {
            String customText = settings.getProperty("colorScaleText", "");
            boolean customNorm = Boolean.parseBoolean(settings.getProperty("colorScaleNormalized", "false"));
            if (!customText.isEmpty()) {
                try {
                    customColorScale = new ColorScale("Custom", customNorm, customText);
                    selectedPreset = customColorScalePreset;
                } catch (Exception e) {
                    // fallback to default
                }
            }
        }

        colorScalePresets.setSelectedItem(selectedPreset);
        add(colorScalePresets);

        boolean norm = selectedPreset == customColorScalePreset && customColorScale != null
                ? customColorScale.getNormalized()
                : selectedPreset.getNormalized();
        colorScaleNormalized.setSelected(norm);
        add(new JLabel("Color scale normalization: "));
        add(colorScaleNormalized);

        colorScaleEditor.setRows(10);
        String scaleText = selectedPreset == customColorScalePreset && customColorScale != null
                ? customColorScale.getScaleAsText()
                : selectedPreset.getScaleAsText();
        colorScaleEditor.setText(scaleText);
        add(new JLabel("Color scale: "));
        add(new JScrollPane(colorScaleEditor));

        AtomicBoolean ignoreColorScaleChange = new AtomicBoolean(false);
        colorScalePresets.addActionListener(e -> {
            ColorScale selection = (ColorScale) colorScalePresets.getSelectedItem();
            if (selection == customColorScalePreset) return;

            ignoreColorScaleChange.set(true);
            colorScaleEditor.setText(selection.getScaleAsText());
            colorScaleNormalized.setSelected(selection.getNormalized());
            ignoreColorScaleChange.set(false);
        });

        colorScaleEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (ignoreColorScaleChange.get()) return;
                colorScalePresets.setSelectedItem(customColorScalePreset);
                customColorScale = new ColorScale("Custom", colorScaleNormalized.isSelected(), colorScaleEditor.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (ignoreColorScaleChange.get()) return;
                colorScalePresets.setSelectedItem(customColorScalePreset);
                customColorScale = new ColorScale("Custom", colorScaleNormalized.isSelected(), colorScaleEditor.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                if (ignoreColorScaleChange.get()) return;
                colorScalePresets.setSelectedItem(customColorScalePreset);
                customColorScale = new ColorScale("Custom", colorScaleNormalized.isSelected(), colorScaleEditor.getText());
            }
        });

        colorScaleNormalized.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (ignoreColorScaleChange.get()) return;
                colorScalePresets.setSelectedItem(customColorScalePreset);
                customColorScale = new ColorScale("Custom", colorScaleNormalized.isSelected(), colorScaleEditor.getText());
            }
        });

        SwingUtils.makeCompactGrid(this, 7, 2, 10, 10, 10, 10);
    }

    public NoiseSettingsPanel() {
        this(new Properties());
    }

    public int getSeed() {
        return ((Number) seedSpinner.getValue()).intValue();
    }

    public double getOriginX() {
        return ((Number) xOrigin.getValue()).doubleValue();
    }

    public void setOriginX(double x) {
        xOrigin.setValue(x);
    }

    public double getOriginZ() {
        return ((Number) zOrigin.getValue()).doubleValue();
    }

    public void setOriginZ(double z) {
        zOrigin.setValue(z);
    }

    public int getPerspectiveMultiplier() {
        return ((Number) perspectiveMultiplier.getValue()).intValue();
    }

    public ColorScale getColorScale() {
        ColorScale selection = (ColorScale) colorScalePresets.getSelectedItem();
        if (selection == customColorScalePreset) {
            return customColorScale;
        }
        else {
            return selection;
        }
    }

    public String getColorScalePresetName() {
        ColorScale selection = (ColorScale) colorScalePresets.getSelectedItem();
        return selection.getName();
    }

    public boolean isColorScaleNormalized() {
        return colorScaleNormalized.isSelected();
    }

    public String getColorScaleText() {
        return colorScaleEditor.getText();
    }
}
