package com.dfsek.noise.swing;

import org.fife.rsta.ui.SizeGripIcon;

import javax.swing.*;
import java.awt.*;

/**
 * The status bar for this application.
 */
public class StatusBar extends JPanel {

    private final JLabel label;
    private final JLabel coordinatesLabel;
    private final JLabel renderTimeLabel;
    private final JLabel autoRenderLabel;
    private final JProgressBar progressBar;
    private final JLabel progressLabel;

    public StatusBar() {
        setLayout(new BorderLayout());

        // Create grouping for components on the left side
        JPanel leftGroup = new JPanel(new FlowLayout());
        add(leftGroup, BorderLayout.LINE_START);

        label = new JLabel("Ready");
        leftGroup.add(label, BorderLayout.LINE_START);

        autoRenderLabel = new JLabel("");
        leftGroup.add(autoRenderLabel);

        // Center group for progress bar
        JPanel centerGroup = new JPanel(new FlowLayout(FlowLayout.LEFT));
        add(centerGroup, BorderLayout.CENTER);

        progressLabel = new JLabel("");
        centerGroup.add(progressLabel);

        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(200, 16));
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        centerGroup.add(progressBar);

        // Create grouping for components on the right side
        JPanel rightGroup = new JPanel(new FlowLayout());
        add(rightGroup, BorderLayout.LINE_END);

        renderTimeLabel = new JLabel("");
        rightGroup.add(renderTimeLabel);

        rightGroup.add(Box.createHorizontalStrut(20));

        coordinatesLabel = new JLabel("Coordinates");
        rightGroup.add(coordinatesLabel);

        rightGroup.add(new JLabel(new SizeGripIcon()));
    }

    public void setLabel(String label) {
        this.label.setText(label);
    }

    public void setCoordinates(int x, int z) {
        coordinatesLabel.setText("X=" + x + "   Z=" + z);
    }

    public void clearCoordinates() {
        coordinatesLabel.setText(" ");
    }

    public void setRenderTime(double timeMs) {
        renderTimeLabel.setText(String.format("Render: %.2fms", timeMs));
    }

    public void setAutoRenderStatus(boolean enabled) {
        if (enabled) {
            autoRenderLabel.setText("  [Auto-render: ON]  ");
            autoRenderLabel.setForeground(new Color(0, 180, 0));
        } else {
            autoRenderLabel.setText("");
        }
    }

    public void showProgress(boolean visible) {
        progressBar.setVisible(visible);
        if (!visible) {
            progressLabel.setText("");
        }
    }

    public void setProgress(int percent, String phase) {
        progressBar.setVisible(true);
        if (percent < 0) {
            progressBar.setIndeterminate(true);
            progressBar.setString(phase);
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setValue(percent);
            progressBar.setString(phase + " " + percent + "%");
        }
        progressLabel.setText(phase);
    }
}
