package com.dfsek.noise.swing;

import com.dfsek.noise.platform.DummyPack;
import com.dfsek.noise.config.HighAliasYamlConfiguration;
import com.dfsek.terra.api.Platform;
import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.terra.api.util.mutable.MutableBoolean;
import net.worldsynth.glpreview.heightmap.Heightmap3DGLPreviewBufferedGL;
import net.worldsynth.glpreview.voxel.Blockspace3DGLPreviewBufferedGL;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutionException;

public class NoisePanel extends JPanel {
    private final RSyntaxTextArea elevationTextArea;
    private final RSyntaxTextArea commonTextArea;
    private final RSyntaxTextArea colorTextArea;

    private final JLabel image;
    private JPanel imagePanel;

    private final Heightmap3DGLPreviewBufferedGL noise3d;
    private final Blockspace3DGLPreviewBufferedGL noise3dVox;

    private final JTextArea statisticsPanel;

    private final NoiseDistributionPanel distributionPanel;

    private final MutableBoolean chunk = new MutableBoolean();

    private final NoiseSettingsPanel settingsPanel;
    private final AdvancedSettingsPanel advancedPanel;
    private final StatusBar statusBar;
    private final MutableBoolean error;
    private final MutableBoolean moved;
    private final MutableBoolean freshRender;
    private BufferedImage render;

    private Sampler noiseSeeded;
    private Sampler colorSamplerSeeded;
    private final Platform platform;

    // Console logging - writes directly to sysout JTextArea, bypassing the filter
    private JTextArea consoleOutput;

    // Background rendering support
    private volatile RenderWorker currentWorker = null;
    private RenderCallback renderCallback;

    public interface RenderCallback {
        void onRenderComplete(boolean success, double renderTimeMs);
    }

    // Data class to hold render results computed off the EDT
    private static class RenderResult {
        final Sampler sampler;
        final Sampler colorSampler;
        final BufferedImage image;
        final double[][] noiseVals;
        final double[][] colorNoiseVals;
        final boolean[][][] voxelVals;
        final double min;
        final double max;
        final int[] buckets;
        final String statisticsText;
        final double sampleTimeMs;
        final long seed;
        final ColorScale colorScale;
        final float yScale;

        RenderResult(Sampler sampler, Sampler colorSampler, BufferedImage image, double[][] noiseVals,
                     double[][] colorNoiseVals, boolean[][][] voxelVals,
                     double min, double max, int[] buckets, String statisticsText, double sampleTimeMs,
                     long seed, ColorScale colorScale, float yScale) {
            this.sampler = sampler;
            this.colorSampler = colorSampler;
            this.image = image;
            this.noiseVals = noiseVals;
            this.colorNoiseVals = colorNoiseVals;
            this.voxelVals = voxelVals;
            this.min = min;
            this.max = max;
            this.buckets = buckets;
            this.statisticsText = statisticsText;
            this.sampleTimeMs = sampleTimeMs;
            this.seed = seed;
            this.colorScale = colorScale;
            this.yScale = yScale;
        }
    }

    // SwingWorker that performs rendering on a background thread
    private class RenderWorker extends SwingWorker<RenderResult, Void> {
        private final long startTime;
        private volatile boolean cancelled = false;

        // Snapshotted EDT values
        private final long seed;
        private final double originX;
        private final double originZ;
        private final int multiplier;
        private final int sizeX;
        private final int sizeZ;
        private final int voxelRes;
        private final int voxelBottomY;
        private final int voxelTopY;
        private final boolean useLetExpressions;
        private final ColorScale colorScale;
        private final boolean showChunks;
        private final String elevationYamlText;
        private final String commonYamlText;
        private final String colorYamlText;
        private final float yScale;

        public RenderWorker() {
            this.startTime = System.nanoTime();
            // Snapshot all Swing component values on the EDT
            this.seed = settingsPanel.getSeed();
            this.originX = settingsPanel.getOriginX();
            this.originZ = settingsPanel.getOriginZ();
            this.multiplier = settingsPanel.getPerspectiveMultiplier();
            this.sizeX = NoisePanel.this.getWidth();
            this.sizeZ = NoisePanel.this.getHeight();
            this.voxelRes = advancedPanel.getVoxelResolution();
            this.voxelBottomY = advancedPanel.getVoxelBottomY();
            this.voxelTopY = advancedPanel.getVoxelTopY();
            this.useLetExpressions = advancedPanel.isUseLetExpressions();
            this.colorScale = settingsPanel.getColorScale();
            this.showChunks = chunk.get();
            this.elevationYamlText = elevationTextArea.getText();
            this.commonYamlText = commonTextArea.getText();
            this.colorYamlText = colorTextArea.getText().trim();
            this.yScale = advancedPanel.getYScale() / (float) this.multiplier;
        }

        public void cancelRender() {
            this.cancelled = true;
            cancel(false);
        }

        @Override
        protected RenderResult doInBackground() throws Exception {
            // Step 1: Compile elevation YAML config -> Sampler (off EDT)
            consoleLog("Compiling elevation config...");
            DummyPack pack = new DummyPack(platform, new HighAliasYamlConfiguration(prependCommon(commonYamlText, elevationYamlText), "Noise Config"), useLetExpressions);
            Sampler sampler = pack.getSampler();
            if (cancelled) return null;

            // Step 1b: Compile color sampler (if defined)
            Sampler colorSampler = null;
            if (!colorYamlText.isEmpty()) {
                try {
                    consoleLog("Compiling color config...");
                    DummyPack colorPack = new DummyPack(platform, new HighAliasYamlConfiguration(prependCommon(commonYamlText, colorYamlText), "Color Config"), useLetExpressions);
                    colorSampler = colorPack.getSampler();
                    consoleLog("Color sampler compiled successfully.");
                } catch (Exception e) {
                    consoleLog("Warning: Color sampler failed to compile: " + e.getMessage());
                    colorSampler = null;
                }
            }
            if (cancelled) return null;

            // Step 2: Generate 2D image (pixel loops with cancellation checks)
            consoleLog("Rendering noise with seed " + seed);
            long imgStartTime = System.nanoTime();

            BufferedImage img = new BufferedImage(sizeX, sizeZ, BufferedImage.TYPE_INT_ARGB);
            double[][] noiseVals = new double[sizeX][sizeZ];

            for (int x = 0; x < sizeX; x++) {
                if (cancelled) return null;
                for (int z = 0; z < sizeZ; z++) {
                    double n = sampler.getSample(seed, x * multiplier + originX, z * multiplier + originZ);
                    noiseVals[x][z] = n;
                }
            }

            // Sample color noise if color sampler exists
            double[][] colorNoiseVals = null;
            if (colorSampler != null) {
                colorNoiseVals = new double[sizeX][sizeZ];
                for (int x = 0; x < sizeX; x++) {
                    if (cancelled) return null;
                    for (int z = 0; z < sizeZ; z++) {
                        colorNoiseVals[x][z] = colorSampler.getSample(seed, x * multiplier + originX, z * multiplier + originZ);
                    }
                }
            }

            long imgEndTime = System.nanoTime();
            double sampleTimeMs = (imgEndTime - imgStartTime) / 1_000_000.0;

            if (cancelled) return null;

            // Calculate elevation min/max
            double max = Double.MIN_VALUE;
            double min = Double.MAX_VALUE;
            for (double[] noiseVal : noiseVals) {
                for (double v : noiseVal) {
                    max = Math.max(v, max);
                    min = Math.min(v, min);
                }
            }

            // Determine color source and its min/max
            double[][] colorSource = (colorNoiseVals != null) ? colorNoiseVals : noiseVals;
            double colorMax = Double.MIN_VALUE;
            double colorMin = Double.MAX_VALUE;
            if (colorNoiseVals != null) {
                for (double[] row : colorNoiseVals) {
                    for (double v : row) {
                        colorMax = Math.max(v, colorMax);
                        colorMin = Math.min(v, colorMin);
                    }
                }
            } else {
                colorMax = max;
                colorMin = min;
            }

            // Apply colors using color source, statistics use elevation
            int[] buckets = new int[sizeX];
            for (int x = 0; x < noiseVals.length; x++) {
                if (cancelled) return null;
                for (int z = 0; z < noiseVals[x].length; z++) {
                    img.setRGB(x, z, colorScale.valueToIRgb(colorSource[x][z], colorMin, colorMax));
                    buckets[normal(noiseVals[x][z], (sizeX - 1), min, max)] =
                            buckets[normal(noiseVals[x][z], (sizeX - 1), min, max)] + 1;
                }
            }

            // Chunk borders
            if (showChunks) {
                for (int x = 0; x < Math.floorDiv(img.getWidth(), 16); x++) {
                    for (int y = 0; y < img.getHeight(); y++) {
                        img.setRGB(x * 16, y, buildRGBA(0));
                    }
                }
                for (int y = 0; y < Math.floorDiv(img.getHeight(), 16); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        img.setRGB(x, y * 16, buildRGBA(0));
                    }
                }
            }

            String statsText = "min: " + min + "\nmax: " + max + "\nseed: " + seed + "\ntime: " + sampleTimeMs + "ms";
            consoleLog("Rendered " + (sizeX * sizeZ) + " points in " + sampleTimeMs + "ms.");

            if (cancelled) return null;

            // Step 3: Generate noise vals for 3D heightmap
            double[][] heightmapVals = new double[sizeX][sizeZ];
            for (int x = 0; x < sizeX; x++) {
                if (cancelled) return null;
                for (int z = 0; z < sizeZ; z++) {
                    heightmapVals[x][z] = sampler.getSample(seed, x * multiplier + originX, z * multiplier + originZ);
                }
            }

            if (cancelled) return null;

            // Step 4: Generate voxel data (if enabled)
            boolean[][][] voxelVals = null;
            if (voxelRes > 0) {
                voxelVals = new boolean[voxelRes][voxelTopY - voxelBottomY][voxelRes];
                for (int x = 0; x < voxelVals.length; x++) {
                    if (cancelled) return null;
                    for (int y = 0; y < voxelVals[x].length; y++) {
                        for (int z = 0; z < voxelVals[x][y].length; z++) {
                            double n = sampler.getSample(seed, x * multiplier + originX, y + voxelBottomY, z * multiplier + originZ);
                            voxelVals[x][y][z] = n > 0;
                        }
                    }
                }
            }

            return new RenderResult(sampler, colorSampler, img, heightmapVals, colorNoiseVals, voxelVals,
                    min, max, buckets, statsText, sampleTimeMs, seed, colorScale, yScale);
        }

        @Override
        protected void done() {
            // Runs on EDT
            if (cancelled || isCancelled()) {
                return;
            }
            try {
                RenderResult result = get();
                if (result == null) return;

                // Apply results to UI
                noiseSeeded = result.sampler;
                colorSamplerSeeded = result.colorSampler;
                render = result.image;
                image.setIcon(new ImageIcon(render));
                image.setText(null);

                noise3d.setYScale(result.yScale);
                if (result.colorNoiseVals != null) {
                    float[][][] colormap = computeColormap(result.colorNoiseVals, result.colorScale);
                    noise3d.setHeightmapWithColormap(result.noiseVals, colormap);
                } else {
                    noise3d.setColorScale(result.colorScale);
                    noise3d.setHeightmap(result.noiseVals);
                }

                if (result.voxelVals != null) {
                    noise3dVox.setBlockspace(result.voxelVals);
                } else {
                    noise3dVox.clearBlockspace();
                }

                statisticsPanel.setText(result.statisticsText);
                distributionPanel.update(result.buckets);
                error.set(false);

                double totalTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;
                statusBar.setRenderTime(totalTimeMs);

                if (renderCallback != null) {
                    renderCallback.onRenderComplete(true, totalTimeMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause != null) {
                    cause.printStackTrace();
                } else {
                    e.printStackTrace();
                }
                image.setIcon(new TextIcon(NoisePanel.this, "An error occurred. "));
                image.setText(null);
                noise3d.clearHeightmap();
                noise3dVox.clearBlockspace();
                statisticsPanel.setText("An error occurred.");
                distributionPanel.error();
                error.set(true);

                if (renderCallback != null) {
                    renderCallback.onRenderComplete(false, 0);
                }
            } finally {
                currentWorker = null;
                freshRender.set(false);
            }
        }
    }

    public NoisePanel(RSyntaxTextArea elevationTextArea, RSyntaxTextArea commonTextArea, RSyntaxTextArea colorTextArea, Heightmap3DGLPreviewBufferedGL noise3d, Blockspace3DGLPreviewBufferedGL noise3dVox, NoiseDistributionPanel distributionPanel, final NoiseSettingsPanel settingsPanel, AdvancedSettingsPanel advancedPanel, Platform platform, StatusBar statusBar) {
        setLayout(new java.awt.BorderLayout());
        this.elevationTextArea = elevationTextArea;
        this.commonTextArea = commonTextArea;
        this.colorTextArea = colorTextArea;
        this.noise3d = noise3d;
        this.noise3dVox = noise3dVox;
        this.statisticsPanel = advancedPanel.getStatisticsPanel();
        this.distributionPanel = distributionPanel;
        this.settingsPanel = settingsPanel;
        this.advancedPanel = advancedPanel;
        this.statusBar = statusBar;
        this.platform = platform;
        this.image = new JLabel();
        this.image.setVerticalAlignment(JLabel.TOP);
        this.image.setHorizontalAlignment(JLabel.LEFT);
        this.error = new MutableBoolean();
        this.moved = new MutableBoolean();
        this.freshRender = new MutableBoolean();
        this.moved.set(false);
        this.freshRender.set(false);
        error.set(false);
        this.imagePanel = new JPanel() {
            private volatile int screenX;

            private volatile int screenY;

            private volatile int myX;

            private volatile int myY;

            {
                setLayout(new java.awt.BorderLayout());
                add(image, java.awt.BorderLayout.CENTER);
                addMouseListener(new MouseListener() {

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        System.nanoTime();
                    }

                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (!error.get()) {
                            screenX = e.getXOnScreen();
                            screenY = e.getYOnScreen();

                            myX = getX();
                            myY = getY();
                        }
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        if (!error.get() && moved.get()) {
                            moved.set(false);
                            int deltaX = e.getXOnScreen() - screenX;
                            int deltaY = e.getYOnScreen() - screenY;
                            int multiplier = settingsPanel.getPerspectiveMultiplier();

                            settingsPanel.setOriginX(settingsPanel.getOriginX() - deltaX * multiplier);
                            settingsPanel.setOriginZ(settingsPanel.getOriginZ() - deltaY * multiplier);
                            // Re-render at the new origin, then reset panel position
                            // Both changes paint in the same repaint cycle, avoiding a visual jump
                            NoisePanel.this.update();
                            imagePanel.setLocation(0, 0);
                        }
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        statusBar.clearCoordinates();
                    }

                });

                addMouseMotionListener(new MouseMotionListener() {

                    @Override
                    public void mouseDragged(MouseEvent e) {
                        if (!error.get()) {
                            moved.set(true);
                            int deltaX = e.getXOnScreen() - screenX;
                            int deltaY = e.getYOnScreen() - screenY;

                            setLocation(myX + deltaX, myY + deltaY);
                        }
                    }

                    @Override
                    public void mouseMoved(MouseEvent e) {
                        int multiplier = settingsPanel.getPerspectiveMultiplier();
                        statusBar.setCoordinates((int) (e.getX() * multiplier + settingsPanel.getOriginX()), (int) (e.getY() * multiplier + settingsPanel.getOriginZ()));
                    }
                });
            }
        };
        add(imagePanel, java.awt.BorderLayout.CENTER);

        // Bind Escape key to cancel rendering
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "cancelRender");
        getActionMap().put("cancelRender", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancelRender();
            }
        });
    }

    public void setConsoleOutput(JTextArea consoleOutput) {
        this.consoleOutput = consoleOutput;
    }

    private void consoleLog(String message) {
        if (consoleOutput != null) {
            SwingUtilities.invokeLater(() -> {
                consoleOutput.append(message + "\n");
                consoleOutput.setCaretPosition(consoleOutput.getDocument().getLength());
            });
        }
    }

    private static String prependCommon(String commonYaml, String editorYaml) {
        if (commonYaml == null || commonYaml.trim().isEmpty()) {
            return editorYaml;
        }
        return commonYaml + "\n" + editorYaml;
    }

    private static int normal(double in, double out, double min, double max) {
        double range = max - min;
        return (int) ((in - min) * out / range);
    }

    private static int buildRGBA(int in) {
        return -16777216 + (in << 16) + (in << 8) + in;
    }

    private static float[][][] computeColormap(double[][] colorNoiseVals, ColorScale colorScale) {
        int width = colorNoiseVals.length;
        int length = colorNoiseVals[0].length;
        float[][][] colormap = new float[width][length][3];

        double max = Double.MIN_VALUE;
        double min = Double.MAX_VALUE;
        for (double[] row : colorNoiseVals) {
            for (double v : row) {
                max = Math.max(v, max);
                min = Math.min(v, min);
            }
        }

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                colormap[x][z] = colorScale.valueToFRgb((float) colorNoiseVals[x][z], (float) min, (float) max);
            }
        }
        return colormap;
    }

    public void update() {
        if (this.error.get()) return;
        this.error.set(true);
        try {
            this.render = getImage(this.settingsPanel.getSeed());
            this.image.setIcon(new ImageIcon(this.render));

            double[][] noiseVals = getNoiseVals(this.settingsPanel.getSeed());

            this.noise3d.setYScale(advancedPanel.getYScale() / (float) settingsPanel.getPerspectiveMultiplier());
            if (colorSamplerSeeded != null) {
                double[][] colorVals = getColorNoiseVals(this.settingsPanel.getSeed());
                float[][][] colormap = computeColormap(colorVals, settingsPanel.getColorScale());
                this.noise3d.setHeightmapWithColormap(noiseVals, colormap);
            } else {
                this.noise3d.setColorScale(settingsPanel.getColorScale());
                this.noise3d.setHeightmap(noiseVals);
            }

            if (this.advancedPanel.getVoxelResolution() > 0) {
                boolean[][][] noiseValsVox = getNoiseVals3d(this.settingsPanel.getSeed());
                this.noise3dVox.setBlockspace(noiseValsVox);
            } else {
                this.noise3dVox.clearBlockspace();
            }

            this.error.set(false);
        } catch (Exception e) {
            e.printStackTrace();
            this.image.setIcon(new TextIcon(this, "An error occurred. "));
            this.noise3d.clearHeightmap();
            this.noise3dVox.clearBlockspace();
            this.statisticsPanel.setText("An error occurred.");
            this.distributionPanel.error();
        }
    }

    public void reload() {
        this.error.set(true);
        try {
            String commonText = this.commonTextArea.getText();
            DummyPack pack = new DummyPack(platform, new HighAliasYamlConfiguration(prependCommon(commonText, this.elevationTextArea.getText()), "Noise Config"), this.advancedPanel.isUseLetExpressions());
            this.noiseSeeded = pack.getSampler();

            // Compile color sampler if defined
            String colorText = this.colorTextArea.getText().trim();
            if (!colorText.isEmpty()) {
                try {
                    DummyPack colorPack = new DummyPack(platform, new HighAliasYamlConfiguration(prependCommon(commonText, colorText), "Color Config"), this.advancedPanel.isUseLetExpressions());
                    this.colorSamplerSeeded = colorPack.getSampler();
                } catch (Exception e) {
                    consoleLog("Warning: Color sampler failed to compile: " + e.getMessage());
                    this.colorSamplerSeeded = null;
                }
            } else {
                this.colorSamplerSeeded = null;
            }

            this.error.set(false);
        } catch (Exception e) {
            e.printStackTrace();
            this.image.setIcon(new TextIcon(this, "An error occurred. "));
            this.noise3d.clearHeightmap();
            this.noise3dVox.clearBlockspace();
            this.statisticsPanel.setText("An error occurred.");
            this.distributionPanel.error();
        }
    }

    public void renderAsync() {
        // Cancel any in-progress render
        if (currentWorker != null) {
            currentWorker.cancelRender();
            currentWorker = null;
        }

        // Reset imagePanel position to origin (it may have been moved by dragging)
        this.imagePanel.setLocation(0, 0);

        // Set backgrounds to black and make panels opaque to clear artifacts
        this.setBackground(java.awt.Color.BLACK);
        this.setOpaque(true);
        this.imagePanel.setBackground(java.awt.Color.BLACK);
        this.imagePanel.setOpaque(true);
        this.image.setBackground(java.awt.Color.BLACK);
        this.image.setOpaque(true);

        // Clear the icon and show rendering text
        this.image.setIcon(null);
        this.image.setText("Rendering... (Escape to cancel)");
        this.image.setForeground(java.awt.Color.WHITE);
        this.freshRender.set(true);
        this.error.set(true);

        // Start background render
        currentWorker = new RenderWorker();
        currentWorker.execute();
    }

    public void cancelRender() {
        if (currentWorker != null) {
            currentWorker.cancelRender();
            currentWorker = null;
            this.image.setText("Cancelled");
            this.freshRender.set(false);
            this.error.set(false);
        }
    }

    public boolean isRendering() {
        return currentWorker != null;
    }

    public void setRenderCallback(RenderCallback callback) {
        this.renderCallback = callback;
    }

    public BufferedImage getRender() {
        return this.render;
    }

    public MutableBoolean getChunk() {
        return this.chunk;
    }

    private double[][] getNoiseVals(long seed) {
        int sizeX = getWidth();
        int sizeZ = getHeight();
        double originX = this.settingsPanel.getOriginX();
        double originZ = this.settingsPanel.getOriginZ();
        int multiplier = this.settingsPanel.getPerspectiveMultiplier();

        double[][] noiseVals = new double[sizeX][sizeZ];
        for (int x = 0; x < noiseVals.length; x++) {
            for (int z = 0; z < (noiseVals[x]).length; z++) {
                double n = noiseSeeded.getSample(seed, x * multiplier + originX, z * multiplier + originZ);
                noiseVals[x][z] = n;
            }
        }
        return noiseVals;
    }

    private boolean[][][] getNoiseVals3d(long seed) {
        double originX = this.settingsPanel.getOriginX();
        double originZ = this.settingsPanel.getOriginZ();
        int multiplier = this.settingsPanel.getPerspectiveMultiplier();

        int sampleRes = this.advancedPanel.getVoxelResolution();
        int sampleYMin = this.advancedPanel.getVoxelBottomY();
        int sampleYMax = this.advancedPanel.getVoxelTopY();

        boolean[][][] noiseVals = new boolean[sampleRes][sampleYMax - sampleYMin][sampleRes];
        for (int x = 0; x < noiseVals.length; x++) {
            for (int y = 0; y < noiseVals[x].length; y++) {
                for(int z = 0; z < (noiseVals[x][y]).length; z++) {
                    double n = noiseSeeded.getSample(seed, x * multiplier + originX, y + sampleYMin, z * multiplier + originZ);
                    noiseVals[x][y][z] = n > 0;
                }
            }
        }
        return noiseVals;
    }

    private double[][] getColorNoiseVals(long seed) {
        int sizeX = getWidth();
        int sizeZ = getHeight();
        double originX = this.settingsPanel.getOriginX();
        double originZ = this.settingsPanel.getOriginZ();
        int multiplier = this.settingsPanel.getPerspectiveMultiplier();

        double[][] colorVals = new double[sizeX][sizeZ];
        for (int x = 0; x < colorVals.length; x++) {
            for (int z = 0; z < colorVals[x].length; z++) {
                colorVals[x][z] = colorSamplerSeeded.getSample(seed, x * multiplier + originX, z * multiplier + originZ);
            }
        }
        return colorVals;
    }

    private BufferedImage getImage(long seed) {
        consoleLog("Rendering noise with seed " + seed);

        int sizeX = getWidth();
        int sizeY = getHeight();
        double originX = this.settingsPanel.getOriginX();
        double originZ = this.settingsPanel.getOriginZ();
        int multiplier = this.settingsPanel.getPerspectiveMultiplier();
        BufferedImage image = new BufferedImage(sizeX, sizeY, BufferedImage.TYPE_INT_ARGB);
        double[][] noiseVals = new double[sizeX][sizeY];

        long startTime = System.nanoTime();
        for (int x = 0; x < noiseVals.length; x++) {
            for (int z = 0; z < (noiseVals[x]).length; z++) {
                double n = noiseSeeded.getSample(seed, x * multiplier + originX, z * multiplier + originZ);
                noiseVals[x][z] = n;
            }
        }
        long endTime = System.nanoTime();
        double timeMs = (endTime - startTime) / 1000000.0D;

        // Sample color noise if color sampler exists
        double[][] colorSource = noiseVals;
        double colorMin, colorMax;

        double max = Double.MIN_VALUE;
        double min = Double.MAX_VALUE;
        for (double[] noiseVal : noiseVals) {
            for (double v : noiseVal) {
                max = Math.max(v, max);
                min = Math.min(v, min);
            }
        }

        if (colorSamplerSeeded != null) {
            colorSource = new double[sizeX][sizeY];
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeY; z++) {
                    colorSource[x][z] = colorSamplerSeeded.getSample(seed, x * multiplier + originX, z * multiplier + originZ);
                }
            }
            colorMin = Double.MAX_VALUE;
            colorMax = Double.MIN_VALUE;
            for (double[] row : colorSource) {
                for (double v : row) {
                    colorMax = Math.max(v, colorMax);
                    colorMin = Math.min(v, colorMin);
                }
            }
        } else {
            colorMin = min;
            colorMax = max;
        }

        int[] buckets = new int[sizeX];
        ColorScale colorScale = this.settingsPanel.getColorScale();
        for (int x = 0; x < noiseVals.length; x++) {
            for (int z = 0; z < (noiseVals[x]).length; z++) {
                image.setRGB(x, z, colorScale.valueToIRgb(colorSource[x][z], colorMin, colorMax));
                buckets[normal(noiseVals[x][z], (sizeX - 1), min, max)] = buckets[normal(noiseVals[x][z], (sizeX - 1), min, max)] + 1;
            }
        }

        if (this.chunk.get()) {
            for (int x = 0; x < Math.floorDiv(image.getWidth(), 16); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    image.setRGB(x * 16, y, buildRGBA(0));
                }
            }
            for (int y = 0; y < Math.floorDiv(image.getHeight(), 16); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y * 16, buildRGBA(0));
                }
            }
        }
        this.statisticsPanel.setText("min: " + min + "\nmax: " + max + "\nseed: " + seed + "\ntime: " + timeMs + "ms");
        this.distributionPanel.update(buckets);
        consoleLog("Rendered " + (sizeX * sizeY) + " points in " + timeMs + "ms.");

        return image;
    }
}
