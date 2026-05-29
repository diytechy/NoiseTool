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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

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

    private static final int BLOCK_SIZE = 256; // world coordinate block size for cache-friendly rendering

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
            cancel(true); // interrupt the worker thread for faster cancellation
        }

        private void checkCancelled() throws InterruptedException {
            if (cancelled || Thread.interrupted()) {
                throw new InterruptedException("Render cancelled");
            }
        }

        private void reportProgress(int percent, String phase) {
            SwingUtilities.invokeLater(() -> statusBar.setProgress(percent, phase));
        }

        /**
         * Build the list of (blockX, blockZ) pairs covering the view window,
         * aligned to BLOCK_SIZE world-coordinate boundaries.
         */
        private List<int[]> computeBlocks(double oX, double oZ, int pixelsX, int pixelsZ, int mult) {
            double worldMinX = oX;
            double worldMaxX = oX + (double) pixelsX * mult;
            double worldMinZ = oZ;
            double worldMaxZ = oZ + (double) pixelsZ * mult;

            int blockStartX = (int) Math.floor(worldMinX / BLOCK_SIZE);
            int blockEndX = (int) Math.floor(worldMaxX / BLOCK_SIZE);
            int blockStartZ = (int) Math.floor(worldMinZ / BLOCK_SIZE);
            int blockEndZ = (int) Math.floor(worldMaxZ / BLOCK_SIZE);

            List<int[]> blocks = new ArrayList<>();
            for (int bx = blockStartX; bx <= blockEndX; bx++) {
                for (int bz = blockStartZ; bz <= blockEndZ; bz++) {
                    blocks.add(new int[]{bx, bz});
                }
            }
            return blocks;
        }

        @Override
        protected RenderResult doInBackground() throws Exception {
            try {
                // Step 1: Compile elevation YAML config -> Sampler (off EDT)
                reportProgress(-1, "Compiling elevation...");
                consoleLog("Compiling elevation config...");
                DummyPack pack = new DummyPack(platform, mergeConfigs(commonYamlText, elevationYamlText, "Noise Config", NoisePanel.this::consoleLog, multiplier), useLetExpressions);
                Sampler sampler = pack.getSampler();
                checkCancelled();

                // Step 1b: Compile color sampler (if defined)
                Sampler colorSampler = null;
                if (!colorYamlText.isEmpty()) {
                    try {
                        reportProgress(-1, "Compiling color...");
                        consoleLog("Compiling color config...");
                        DummyPack colorPack = new DummyPack(platform, mergeConfigs(commonYamlText, colorYamlText, "Color Config", NoisePanel.this::consoleLog, multiplier), useLetExpressions);
                        colorSampler = colorPack.getSampler();
                        consoleLog("Color sampler compiled successfully.");
                    } catch (RuntimeException e) {
                        if (e.getCause() instanceof InterruptedException) {
                            throw new InterruptedException("Render cancelled");
                        }
                        consoleLog("Warning: Color sampler failed to compile: " + e.getMessage());
                        colorSampler = null;
                    } catch (Exception e) {
                        consoleLog("Warning: Color sampler failed to compile: " + e.getMessage());
                        colorSampler = null;
                    }
                }
                checkCancelled();

                // Step 2: Generate 2D image using block-based sampling
                reportProgress(10, "Sampling elevation...");
                consoleLog("Rendering noise with seed " + seed);
                long imgStartTime = System.nanoTime();

                BufferedImage img = new BufferedImage(sizeX, sizeZ, BufferedImage.TYPE_INT_ARGB);
                double[][] noiseVals = new double[sizeX][sizeZ];

                AtomicBoolean cancelFlag = new AtomicBoolean(false);
                List<int[]> blocks = computeBlocks(originX, originZ, sizeX, sizeZ, multiplier);
                int totalBlocks = blocks.size();
                AtomicInteger completedBlocks = new AtomicInteger(0);

                // Elevation sampling — block-based parallel
                blocks.parallelStream().forEach(block -> {
                    if (cancelled || cancelFlag.get()) { cancelFlag.set(true); return; }
                    int blockMinWX = block[0] * BLOCK_SIZE;
                    int blockMaxWX = blockMinWX + BLOCK_SIZE;
                    int blockMinWZ = block[1] * BLOCK_SIZE;
                    int blockMaxWZ = blockMinWZ + BLOCK_SIZE;

                    for (int wx = blockMinWX; wx < blockMaxWX; wx += multiplier) {
                        int px = (int) Math.round((wx - originX) / multiplier);
                        if (px < 0 || px >= sizeX) continue;
                        for (int wz = blockMinWZ; wz < blockMaxWZ; wz += multiplier) {
                            int pz = (int) Math.round((wz - originZ) / multiplier);
                            if (pz < 0 || pz >= sizeZ) continue;
                            noiseVals[px][pz] = sampler.getSample(seed, px * multiplier + originX, pz * multiplier + originZ);
                        }
                    }
                    int done = completedBlocks.incrementAndGet();
                    if (done % Math.max(1, totalBlocks / 20) == 0) {
                        reportProgress(10 + (int)(done * 35.0 / totalBlocks), "Sampling elevation...");
                    }
                });

                // Sample color noise if color sampler exists
                double[][] colorNoiseVals = null;
                final Sampler fColorSampler = colorSampler;
                if (fColorSampler != null) {
                    checkCancelled();
                    reportProgress(45, "Sampling color...");
                    colorNoiseVals = new double[sizeX][sizeZ];
                    final double[][] colorVals = colorNoiseVals;
                    cancelFlag.set(false);
                    completedBlocks.set(0);
                    blocks.parallelStream().forEach(block -> {
                        if (cancelled || cancelFlag.get()) { cancelFlag.set(true); return; }
                        int blockMinWX = block[0] * BLOCK_SIZE;
                        int blockMaxWX = blockMinWX + BLOCK_SIZE;
                        int blockMinWZ = block[1] * BLOCK_SIZE;
                        int blockMaxWZ = blockMinWZ + BLOCK_SIZE;

                        for (int wx = blockMinWX; wx < blockMaxWX; wx += multiplier) {
                            int px = (int) Math.round((wx - originX) / multiplier);
                            if (px < 0 || px >= sizeX) continue;
                            for (int wz = blockMinWZ; wz < blockMaxWZ; wz += multiplier) {
                                int pz = (int) Math.round((wz - originZ) / multiplier);
                                if (pz < 0 || pz >= sizeZ) continue;
                                colorVals[px][pz] = fColorSampler.getSample(seed, px * multiplier + originX, pz * multiplier + originZ);
                            }
                        }
                        int done = completedBlocks.incrementAndGet();
                        if (done % Math.max(1, totalBlocks / 20) == 0) {
                            reportProgress(45 + (int)(done * 15.0 / totalBlocks), "Sampling color...");
                        }
                    });
                }

                long imgEndTime = System.nanoTime();
                double sampleTimeMs = (imgEndTime - imgStartTime) / 1_000_000.0;

                checkCancelled();

                // Calculate elevation min/max
                reportProgress(62, "Applying colors...");
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
                final double fMin = min, fMax = max;
                final double fColorMin = colorMin, fColorMax = colorMax;
                final double[][] fColorSource = colorSource;
                IntStream.range(0, noiseVals.length).parallel().forEach(x -> {
                    int[] localBuckets = new int[sizeX];
                    for (int z = 0; z < noiseVals[x].length; z++) {
                        img.setRGB(x, z, colorScale.valueToIRgb(fColorSource[x][z], fColorMin, fColorMax));
                        localBuckets[normal(noiseVals[x][z], (sizeX - 1), fMin, fMax)]++;
                    }
                    synchronized (buckets) {
                        for (int i = 0; i < sizeX; i++) buckets[i] += localBuckets[i];
                    }
                });

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

                checkCancelled();

                // Step 3: Generate noise vals for 3D heightmap (block-based)
                reportProgress(75, "3D heightmap...");
                double[][] heightmapVals = new double[sizeX][sizeZ];
                cancelFlag.set(false);
                completedBlocks.set(0);
                blocks.parallelStream().forEach(block -> {
                    if (cancelled || cancelFlag.get()) { cancelFlag.set(true); return; }
                    int blockMinWX = block[0] * BLOCK_SIZE;
                    int blockMaxWX = blockMinWX + BLOCK_SIZE;
                    int blockMinWZ = block[1] * BLOCK_SIZE;
                    int blockMaxWZ = blockMinWZ + BLOCK_SIZE;

                    for (int wx = blockMinWX; wx < blockMaxWX; wx += multiplier) {
                        int px = (int) Math.round((wx - originX) / multiplier);
                        if (px < 0 || px >= sizeX) continue;
                        for (int wz = blockMinWZ; wz < blockMaxWZ; wz += multiplier) {
                            int pz = (int) Math.round((wz - originZ) / multiplier);
                            if (pz < 0 || pz >= sizeZ) continue;
                            heightmapVals[px][pz] = sampler.getSample(seed, px * multiplier + originX, pz * multiplier + originZ);
                        }
                    }
                    int done = completedBlocks.incrementAndGet();
                    if (done % Math.max(1, totalBlocks / 10) == 0) {
                        reportProgress(75 + (int)(done * 15.0 / totalBlocks), "3D heightmap...");
                    }
                });

                checkCancelled();

                // Step 4: Generate voxel data (if enabled)
                boolean[][][] voxelVals = null;
                if (voxelRes > 0) {
                    reportProgress(92, "Voxel data...");
                    voxelVals = new boolean[voxelRes][voxelTopY - voxelBottomY][voxelRes];
                    final boolean[][][] fVoxelVals = voxelVals;
                    cancelFlag.set(false);
                    IntStream.range(0, voxelRes).parallel().forEach(x -> {
                        if (cancelled || cancelFlag.get()) { cancelFlag.set(true); return; }
                        for (int y = 0; y < fVoxelVals[x].length; y++) {
                            for (int z = 0; z < fVoxelVals[x][y].length; z++) {
                                fVoxelVals[x][y][z] = sampler.getSample(seed, x * multiplier + originX, y + voxelBottomY, z * multiplier + originZ) > 0;
                            }
                        }
                    });
                }

                reportProgress(100, "Complete");
                return new RenderResult(sampler, colorSampler, img, heightmapVals, colorNoiseVals, voxelVals,
                        min, max, buckets, statsText, sampleTimeMs, seed, colorScale, yScale);
            } catch (InterruptedException e) {
                // Render was cancelled via thread interrupt
                return null;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof InterruptedException) {
                    // Compilation was interrupted via cancel
                    return null;
                }
                throw e;
            }
        }

        @Override
        protected void done() {
            // Runs on EDT
            statusBar.showProgress(false);
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
                if (cause instanceof InterruptedException) {
                    // Cancelled during compilation — not an error
                    return;
                }
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

    /**
     * Scan text for references to known names using function-call syntax: name( or name (
     * False positives are harmless (just loads an extra sampler/function).
     */
    private static Set<String> findReferencedNames(String text, Set<String> knownNames) {
        Set<String> found = new HashSet<>();
        for (String name : knownNames) {
            if (text.contains(name + "(") || text.contains(name + " (")) {
                found.add(name);
            }
        }
        return found;
    }

    /**
     * Starting from seed sampler names, transitively discover all samplers they depend on.
     * Walks each sampler's config (serialized to string) looking for references to other
     * known sampler names.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> resolveTransitiveDeps(Set<String> seeds, Map<String, Object> allSamplers) {
        Set<String> resolved = new HashSet<>(seeds);
        Queue<String> queue = new LinkedList<>(seeds);
        Set<String> knownNames = allSamplers.keySet();
        Yaml yaml = createHighAliasYaml();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            Object config = allSamplers.get(name);
            if (config == null) continue;
            String configText = yaml.dump(config);
            for (String candidate : knownNames) {
                if (!resolved.contains(candidate) &&
                    (configText.contains(candidate + "(") || configText.contains(candidate + " ("))) {
                    resolved.add(candidate);
                    queue.add(candidate);
                }
            }
        }
        return resolved;
    }

    /**
     * Filter a samplers/functions map to only include entries whose names are in keepNames.
     */
    private static Map<String, Object> filterMapEntries(Map<String, Object> map, Set<String> keepNames) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String name : keepNames) {
            if (map.containsKey(name)) {
                filtered.put(name, map.get(name));
            }
        }
        return filtered;
    }

    /**
     * Filter the merged config map to only include samplers and functions that are
     * transitively referenced by the editor YAML. Logs filtering stats to the console.
     */
    @SuppressWarnings("unchecked")
    private static void filterUnusedEntries(Map<String, Object> merged, String editorYaml,
                                            java.util.function.Consumer<String> logger) {
        Object samplersObj = merged.get("samplers");
        if (!(samplersObj instanceof Map)) return;
        Map<String, Object> allSamplers = (Map<String, Object>) samplersObj;
        if (allSamplers.isEmpty()) return;

        // Find sampler names directly referenced in editor text
        Set<String> directRefs = findReferencedNames(editorYaml, allSamplers.keySet());

        // Also keep any sampler names that appear as keys in the editor's own samplers section
        // (these are local expression samplers the editor defines)
        try {
            Yaml yaml = createHighAliasYaml();
            Map<String, Object> editorMap = (Map<String, Object>) yaml.load(editorYaml);
            if (editorMap != null) {
                Object editorSamplers = editorMap.get("samplers");
                if (editorSamplers instanceof Map) {
                    directRefs.addAll(((Map<String, Object>) editorSamplers).keySet());
                }
            }
        } catch (Exception ignored) {
            // Editor YAML might not parse standalone — that's fine
        }

        // Transitively resolve dependencies
        Set<String> allRefs = resolveTransitiveDeps(directRefs, allSamplers);

        int totalSamplers = allSamplers.size();
        int keptSamplers = allRefs.size();
        if (keptSamplers < totalSamplers) {
            merged.put("samplers", filterMapEntries(allSamplers, allRefs));
            if (logger != null) {
                logger.accept("Filtered samplers: " + keptSamplers + " of " + totalSamplers
                    + " referenced (" + (totalSamplers - keptSamplers) + " skipped)");
            }
        }

        // Also filter functions
        Object functionsObj = merged.get("functions");
        if (functionsObj instanceof Map) {
            Map<String, Object> allFunctions = (Map<String, Object>) functionsObj;
            if (!allFunctions.isEmpty()) {
                // Scan editor text + kept sampler configs for function references
                StringBuilder allText = new StringBuilder(editorYaml);
                Yaml yaml = createHighAliasYaml();
                for (String name : allRefs) {
                    Object config = allSamplers.get(name);
                    if (config != null) {
                        allText.append('\n').append(yaml.dump(config));
                    }
                }
                Set<String> funcRefs = findReferencedNames(allText.toString(), allFunctions.keySet());

                // Functions can reference other functions — resolve transitively
                Set<String> allFuncRefs = new HashSet<>(funcRefs);
                Queue<String> funcQueue = new LinkedList<>(funcRefs);
                while (!funcQueue.isEmpty()) {
                    String fname = funcQueue.poll();
                    Object fconfig = allFunctions.get(fname);
                    if (fconfig == null) continue;
                    String ftext = yaml.dump(fconfig);
                    for (String candidate : allFunctions.keySet()) {
                        if (!allFuncRefs.contains(candidate) &&
                            (ftext.contains(candidate + "(") || ftext.contains(candidate + " ("))) {
                            allFuncRefs.add(candidate);
                            funcQueue.add(candidate);
                        }
                    }
                }

                int totalFuncs = allFunctions.size();
                int keptFuncs = allFuncRefs.size();
                if (keptFuncs < totalFuncs) {
                    merged.put("functions", filterMapEntries(allFunctions, allFuncRefs));
                    if (logger != null) {
                        logger.accept("Filtered functions: " + keptFuncs + " of " + totalFuncs
                            + " referenced (" + (totalFuncs - keptFuncs) + " skipped)");
                    }
                }
            }
        }
    }

    /**
     * Combines Common and Editor YAML into a single Configuration.
     * First tries text prepending (preserves YAML anchors/aliases across tabs).
     * Falls back to YAML-aware map merging if text prepend fails (e.g. duplicate keys),
     * which unions map-valued keys like samplers/functions/variables.
     * In both paths, filters out unused samplers/functions for faster compilation.
     * Prepends PerspectiveMultiplier as both a YAML anchor and a variables entry.
     */
    @SuppressWarnings("unchecked")
    public static HighAliasYamlConfiguration mergeConfigs(String commonYaml, String editorYaml,
                                                            String configName, java.util.function.Consumer<String> logger,
                                                            int perspectiveMultiplier) {
        // Prepend PerspectiveMultiplier anchor so samplers can reference it
        String pmPrefix = "PerspectiveMultiplier: &PerspectiveMultiplier " + perspectiveMultiplier + "\n";

        boolean hasCommon = commonYaml != null && !commonYaml.trim().isEmpty();

        String combined = pmPrefix + (hasCommon ? commonYaml + "\n" + editorYaml : editorYaml);

        Map<String, Object> merged = null;

        // Try text prepend first — preserves cross-tab anchors/aliases
        try {
            Yaml yaml = createHighAliasYaml();
            merged = (Map<String, Object>) yaml.load(combined);
        } catch (Exception ignored) {
            // Fall through to map merge (handles duplicate keys, etc.)
        }

        // Map-merge fallback: parse separately, union map-valued keys
        if (merged == null) {
            Yaml yaml = createHighAliasYaml();

            Map<String, Object> commonMap = hasCommon
                ? (Map<String, Object>) yaml.load(commonYaml)
                : new LinkedHashMap<>();
            Map<String, Object> editorMap = (editorYaml != null && !editorYaml.trim().isEmpty())
                ? (Map<String, Object>) yaml.load(editorYaml)
                : new LinkedHashMap<>();

            if (commonMap == null) commonMap = new LinkedHashMap<>();
            if (editorMap == null) editorMap = new LinkedHashMap<>();

            merged = new LinkedHashMap<>(commonMap);

            for (Map.Entry<String, Object> entry : editorMap.entrySet()) {
                String key = entry.getKey();
                Object editorVal = entry.getValue();
                Object commonVal = merged.get(key);

                if (commonVal instanceof Map && editorVal instanceof Map) {
                    Map<String, Object> mergedSub = new LinkedHashMap<>((Map<String, Object>) commonVal);
                    mergedSub.putAll((Map<String, Object>) editorVal);
                    merged.put(key, mergedSub);
                } else {
                    merged.put(key, editorVal);
                }
            }
        }

        // Ensure PerspectiveMultiplier is in the variables map (for EXPRESSION samplers)
        Object varsObj = merged.get("variables");
        if (varsObj instanceof Map) {
            ((Map<String, Object>) varsObj).putIfAbsent("PerspectiveMultiplier", perspectiveMultiplier);
        } else {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("PerspectiveMultiplier", perspectiveMultiplier);
            merged.put("variables", vars);
        }
        // Also ensure top-level key exists (for YAML anchor reference)
        merged.putIfAbsent("PerspectiveMultiplier", perspectiveMultiplier);

        // Filter unused samplers/functions for faster compilation
        if (hasCommon) {
            filterUnusedEntries(merged, editorYaml, logger);
        }

        return new HighAliasYamlConfiguration(merged, configName);
    }

    private static Yaml createHighAliasYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(500);
        options.setCodePointLimit(16 * 1024 * 1024); // 16 MB (default is 3 MB)
        return new Yaml(options);
    }

    /**
     * Compute the list of (blockX, blockZ) pairs covering the view window,
     * aligned to BLOCK_SIZE world-coordinate boundaries.
     */
    private static List<int[]> computeViewBlocks(double originX, double originZ, int pixelsX, int pixelsZ, int multiplier) {
        double worldMaxX = originX + (double) pixelsX * multiplier;
        double worldMaxZ = originZ + (double) pixelsZ * multiplier;

        int blockStartX = (int) Math.floor(originX / BLOCK_SIZE);
        int blockEndX = (int) Math.floor(worldMaxX / BLOCK_SIZE);
        int blockStartZ = (int) Math.floor(originZ / BLOCK_SIZE);
        int blockEndZ = (int) Math.floor(worldMaxZ / BLOCK_SIZE);

        List<int[]> blocks = new ArrayList<>();
        for (int bx = blockStartX; bx <= blockEndX; bx++) {
            for (int bz = blockStartZ; bz <= blockEndZ; bz++) {
                blocks.add(new int[]{bx, bz});
            }
        }
        return blocks;
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
            int pm = this.settingsPanel.getPerspectiveMultiplier();
            DummyPack pack = new DummyPack(platform, mergeConfigs(commonText, this.elevationTextArea.getText(), "Noise Config", this::consoleLog, pm), this.advancedPanel.isUseLetExpressions());
            this.noiseSeeded = pack.getSampler();

            // Compile color sampler if defined
            String colorText = this.colorTextArea.getText().trim();
            if (!colorText.isEmpty()) {
                try {
                    DummyPack colorPack = new DummyPack(platform, mergeConfigs(commonText, colorText, "Color Config", this::consoleLog, pm), this.advancedPanel.isUseLetExpressions());
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

        // Show progress bar
        statusBar.setProgress(0, "Starting...");

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
            statusBar.showProgress(false);
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
        List<int[]> blocks = computeViewBlocks(originX, originZ, sizeX, sizeZ, multiplier);
        blocks.parallelStream().forEach(block -> {
            int blockMinWX = block[0] * BLOCK_SIZE;
            int blockMaxWX = blockMinWX + BLOCK_SIZE;
            int blockMinWZ = block[1] * BLOCK_SIZE;
            int blockMaxWZ = blockMinWZ + BLOCK_SIZE;
            for (int wx = blockMinWX; wx < blockMaxWX; wx += multiplier) {
                int px = (int) Math.round((wx - originX) / multiplier);
                if (px < 0 || px >= sizeX) continue;
                for (int wz = blockMinWZ; wz < blockMaxWZ; wz += multiplier) {
                    int pz = (int) Math.round((wz - originZ) / multiplier);
                    if (pz < 0 || pz >= sizeZ) continue;
                    noiseVals[px][pz] = noiseSeeded.getSample(seed, px * multiplier + originX, pz * multiplier + originZ);
                }
            }
        });
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
        IntStream.range(0, sampleRes).parallel().forEach(x -> {
            for (int y = 0; y < noiseVals[x].length; y++) {
                for (int z = 0; z < noiseVals[x][y].length; z++) {
                    noiseVals[x][y][z] = noiseSeeded.getSample(seed, x * multiplier + originX, y + sampleYMin, z * multiplier + originZ) > 0;
                }
            }
        });
        return noiseVals;
    }

    private double[][] getColorNoiseVals(long seed) {
        int sizeX = getWidth();
        int sizeZ = getHeight();
        double originX = this.settingsPanel.getOriginX();
        double originZ = this.settingsPanel.getOriginZ();
        int multiplier = this.settingsPanel.getPerspectiveMultiplier();

        double[][] colorVals = new double[sizeX][sizeZ];
        List<int[]> blocks = computeViewBlocks(originX, originZ, sizeX, sizeZ, multiplier);
        blocks.parallelStream().forEach(block -> {
            int blockMinWX = block[0] * BLOCK_SIZE;
            int blockMaxWX = blockMinWX + BLOCK_SIZE;
            int blockMinWZ = block[1] * BLOCK_SIZE;
            int blockMaxWZ = blockMinWZ + BLOCK_SIZE;
            for (int wx = blockMinWX; wx < blockMaxWX; wx += multiplier) {
                int px = (int) Math.round((wx - originX) / multiplier);
                if (px < 0 || px >= sizeX) continue;
                for (int wz = blockMinWZ; wz < blockMaxWZ; wz += multiplier) {
                    int pz = (int) Math.round((wz - originZ) / multiplier);
                    if (pz < 0 || pz >= sizeZ) continue;
                    colorVals[px][pz] = colorSamplerSeeded.getSample(seed, px * multiplier + originX, pz * multiplier + originZ);
                }
            }
        });
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

        List<int[]> blocks = computeViewBlocks(originX, originZ, sizeX, sizeY, multiplier);

        long startTime = System.nanoTime();
        blocks.parallelStream().forEach(block -> {
            int blockMinWX = block[0] * BLOCK_SIZE;
            int blockMaxWX = blockMinWX + BLOCK_SIZE;
            int blockMinWZ = block[1] * BLOCK_SIZE;
            int blockMaxWZ = blockMinWZ + BLOCK_SIZE;
            for (int wx = blockMinWX; wx < blockMaxWX; wx += multiplier) {
                int px = (int) Math.round((wx - originX) / multiplier);
                if (px < 0 || px >= sizeX) continue;
                for (int wz = blockMinWZ; wz < blockMaxWZ; wz += multiplier) {
                    int pz = (int) Math.round((wz - originZ) / multiplier);
                    if (pz < 0 || pz >= sizeY) continue;
                    noiseVals[px][pz] = noiseSeeded.getSample(seed, px * multiplier + originX, pz * multiplier + originZ);
                }
            }
        });
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
            final double[][] fColorSource = colorSource;
            blocks.parallelStream().forEach(block -> {
                int blockMinWX = block[0] * BLOCK_SIZE;
                int blockMaxWX = blockMinWX + BLOCK_SIZE;
                int blockMinWZ = block[1] * BLOCK_SIZE;
                int blockMaxWZ = blockMinWZ + BLOCK_SIZE;
                for (int wx = blockMinWX; wx < blockMaxWX; wx += multiplier) {
                    int px = (int) Math.round((wx - originX) / multiplier);
                    if (px < 0 || px >= sizeX) continue;
                    for (int wz = blockMinWZ; wz < blockMaxWZ; wz += multiplier) {
                        int pz = (int) Math.round((wz - originZ) / multiplier);
                        if (pz < 0 || pz >= sizeY) continue;
                        fColorSource[px][pz] = colorSamplerSeeded.getSample(seed, px * multiplier + originX, pz * multiplier + originZ);
                    }
                }
            });
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
        final double fMin = min, fMax = max;
        final double fColorMin = colorMin, fColorMax = colorMax;
        final double[][] fColorSrc = colorSource;
        IntStream.range(0, noiseVals.length).parallel().forEach(x -> {
            int[] localBuckets = new int[sizeX];
            for (int z = 0; z < noiseVals[x].length; z++) {
                image.setRGB(x, z, colorScale.valueToIRgb(fColorSrc[x][z], fColorMin, fColorMax));
                localBuckets[normal(noiseVals[x][z], (sizeX - 1), fMin, fMax)]++;
            }
            synchronized (buckets) {
                for (int i = 0; i < sizeX; i++) buckets[i] += localBuckets[i];
            }
        });

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
