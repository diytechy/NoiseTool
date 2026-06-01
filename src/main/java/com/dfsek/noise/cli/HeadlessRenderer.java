package com.dfsek.noise.cli;

import com.dfsek.noise.config.HighAliasYamlConfiguration;
import com.dfsek.noise.platform.DummyPack;
import com.dfsek.noise.platform.PlatformImpl;
import com.dfsek.noise.swing.ColorScale;
import com.dfsek.noise.swing.NoisePanel;
import com.dfsek.seismic.type.sampler.Sampler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Headless (no-GUI) batch renderer for the Noise Tool.
 *
 * <p>Compiles one or more Terra sampler definition files into a Terra {@link Sampler}, renders a
 * 2D top-down image of the sampler output, and writes it to a PNG. Intended for generating the
 * support documentation screenshots and for letting an automated agent validate sampler YAML and
 * read back errors without opening the Swing UI.
 *
 * <p>Invoked from {@link com.dfsek.noise.NoiseTool#main(String[])} when the first argument is
 * {@code --headless} (or {@code --cli}). Returns a process exit code: {@code 0} on success,
 * {@code 1} on a render/compile error, {@code 2} on a usage/argument error.
 *
 * <p>The working directory must contain the {@code addons/} folder (same requirement as the GUI),
 * because Terra noise addons are loaded relative to it. {@code StartNoiseTool.bat} and
 * {@code RenderNoise.bat} both {@code cd} to the JAR directory before launching.
 */
public final class HeadlessRenderer {

    private HeadlessRenderer() {
    }

    /** Exit code: success. */
    public static final int EXIT_OK = 0;
    /** Exit code: sampler failed to compile or render. */
    public static final int EXIT_RENDER_ERROR = 1;
    /** Exit code: bad/missing command-line arguments. */
    public static final int EXIT_USAGE_ERROR = 2;

    /**
     * Entry point for headless rendering. Parses {@code args} (excluding the leading
     * {@code --headless}/{@code --cli} flag), renders, and returns a process exit code.
     */
    public static int run(String[] args) {
        // Ensure no display is required, even on machines without one.
        System.setProperty("java.awt.headless", "true");

        Args a;
        try {
            a = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
            System.err.println();
            printUsage(System.err);
            return EXIT_USAGE_ERROR;
        }

        if (a.help) {
            printUsage(System.out);
            return EXIT_OK;
        }

        // Collect log lines so we can optionally persist them next to the image.
        StringBuilder logBuffer = new StringBuilder();
        Consumer<String> log = line -> {
            System.out.println(line);
            logBuffer.append(line).append(System.lineSeparator());
        };

        try {
            int code = render(a, log);
            writeLog(a, logBuffer, null);
            return code;
        } catch (Throwable t) {
            // Any compile/render failure: report clearly, dump stack to stderr + log, exit non-zero.
            String message = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.accept("ERROR: " + message);
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            System.err.println(sw);
            writeLog(a, logBuffer, sw.toString());
            return EXIT_RENDER_ERROR;
        }
    }

    private static int render(Args a, Consumer<String> log) throws IOException {
        log.accept("[NoiseTool headless] elevation=" + a.elevation
                + " common=" + a.common + " color=" + a.color);
        log.accept("seed=" + a.seed + " size=" + a.width + "x" + a.height
                + " origin=(" + a.originX + "," + a.originZ + ") multiplier=" + a.multiplier
                + " colorScale=" + a.colorScale.getName() + " let=" + a.useLet);

        String commonText = a.common != null ? readFile(a.common) : "";
        String elevationText = readFile(a.elevation);

        long t0 = System.nanoTime();

        PlatformImpl platform = new PlatformImpl();

        // Compile the main (elevation) sampler.
        HighAliasYamlConfiguration elevationConfig =
                NoisePanel.mergeConfigs(commonText, elevationText, "Headless Elevation", log, a.multiplier);
        Sampler sampler = new DummyPack(platform, elevationConfig, a.useLet).getSampler();
        if (sampler == null) {
            throw new IllegalStateException("Elevation sampler compiled to null (check the 'type' / 'expression' keys).");
        }

        // Optionally compile a separate color sampler.
        Sampler colorSampler = null;
        if (a.color != null) {
            String colorText = readFile(a.color);
            HighAliasYamlConfiguration colorConfig =
                    NoisePanel.mergeConfigs(commonText, colorText, "Headless Color", log, a.multiplier);
            colorSampler = new DummyPack(platform, colorConfig, a.useLet).getSampler();
            if (colorSampler == null) {
                throw new IllegalStateException("Color sampler compiled to null.");
            }
        }

        long t1 = System.nanoTime();
        log.accept(String.format(Locale.ROOT, "Compiled samplers in %.1f ms", (t1 - t0) / 1_000_000.0));

        // --- Sample the elevation grid (parallel over x columns) ---
        double[][] noiseVals = sampleGrid(sampler, a);
        double[] noiseRange = minMax(noiseVals);

        // Color source: the dedicated color sampler if present, otherwise the elevation values.
        double[][] colorVals = noiseVals;
        double[] colorRange = noiseRange;
        if (colorSampler != null) {
            colorVals = sampleGrid(colorSampler, a);
            colorRange = minMax(colorVals);
        }

        // --- Paint the image ---
        BufferedImage image = new BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_RGB);
        final double cMin = colorRange[0], cMax = colorRange[1];
        final ColorScale scale = a.colorScale;
        final double[][] cv = colorVals;
        IntStream.range(0, a.width).parallel().forEach(x -> {
            for (int z = 0; z < a.height; z++) {
                image.setRGB(x, z, scale.valueToIRgb(cv[x][z], cMin, cMax));
            }
        });

        long t2 = System.nanoTime();
        log.accept(String.format(Locale.ROOT,
                "Sampled %d points in %.1f ms (min=%.4f max=%.4f)",
                a.width * a.height, (t2 - t1) / 1_000_000.0, noiseRange[0], noiseRange[1]));

        // --- Write the PNG ---
        File out = a.out.toFile();
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory: " + parent);
        }
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG writer available (ImageIO.write returned false).");
        }
        log.accept("Wrote image: " + out.getAbsolutePath());
        return EXIT_OK;
    }

    /** Samples a {@code width x height} grid using the GUI's world-to-pixel coordinate convention. */
    private static double[][] sampleGrid(Sampler sampler, Args a) {
        double[][] vals = new double[a.width][a.height];
        IntStream.range(0, a.width).parallel().forEach(px -> {
            double wx = px * a.multiplier + a.originX;
            for (int pz = 0; pz < a.height; pz++) {
                double wz = pz * a.multiplier + a.originZ;
                vals[px][pz] = sampler.getSample(a.seed, wx, wz);
            }
        });
        return vals;
    }

    private static double[] minMax(double[][] vals) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] row : vals) {
            for (double v : row) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        return new double[]{min, max};
    }

    private static String readFile(Path p) throws IOException {
        if (!Files.exists(p)) {
            throw new IOException("File not found: " + p.toAbsolutePath());
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static void writeLog(Args a, StringBuilder buffer, String extra) {
        if (a == null || a.log == null) return;
        try {
            String content = buffer.toString() + (extra != null ? extra : "");
            Files.writeString(a.log, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: could not write log file " + a.log + ": " + e.getMessage());
        }
    }

    private static void printUsage(java.io.PrintStream o) {
        o.println("NoiseTool headless renderer");
        o.println();
        o.println("Usage:");
        o.println("  java -jar NoiseTool-*-all.jar --headless --in <elevation.yml> --out <image.png> [options]");
        o.println();
        o.println("Required:");
        o.println("  --in, --elevation <file>   Main sampler definition YAML (the rendered sampler).");
        o.println("  --out <file.png>           Output image path (parent dirs are created).");
        o.println();
        o.println("Optional:");
        o.println("  --common <file>            Shared samplers YAML (e.g. resolved_samplers.yml). Named");
        o.println("                             samplers here are callable from the elevation/color YAML.");
        o.println("  --color <file>            Separate color sampler YAML; its output drives the color scale.");
        o.println("  --seed <long>             World seed (default 0).");
        o.println("  --size <WxH>              Image size in pixels (default 512x512).");
        o.println("  --origin <x,z>            World-coordinate origin of the top-left pixel (default 0,0).");
        o.println("  --multiplier <int>        World units per pixel (\"perspective multiplier\", default 1).");
        o.println("  --color-scale <name|file> Color scale: a preset name, a short alias, or a file of");
        o.println("                             \"value,r,g,b\" stops (default \"Grayscale normalized\").");
        o.println("                             Presets: Solid | Grayscale normalized | Grayscale 0 - 1 |");
        o.println("                             Grayscale 0 - 256 | Grayscale (-64) - 320 | Colored 0 - 320.");
        o.println("                             Aliases: gray/grayscale, terrain/colored, solid.");
        o.println("  --normalize <true|false>  Override color-scale normalization.");
        o.println("  --let                     Enable Paralithic 'let' expressions (default off).");
        o.println("  --log <file>              Also write the console log (and any error) to this file.");
        o.println("  --help                    Print this help and exit.");
        o.println();
        o.println("Exit codes: 0 = ok, 1 = compile/render error, 2 = usage error.");
    }

    /** Parsed command-line arguments with defaults matching the GUI. */
    private static final class Args {
        Path elevation;
        Path common;
        Path color;
        Path out;
        Path log;
        long seed = 0;
        int width = 512;
        int height = 512;
        double originX = 0;
        double originZ = 0;
        int multiplier = 1;
        ColorScale colorScale = ColorScale.GRAYSCALE_NORMALIZED;
        Boolean normalizeOverride = null;
        boolean useLet = false;
        boolean help = false;

        static Args parse(String[] args) {
            Args a = new Args();
            String colorScaleSpec = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> a.help = true;
                    case "--in", "--elevation" -> a.elevation = Path.of(next(args, ++i, arg));
                    case "--common" -> a.common = Path.of(next(args, ++i, arg));
                    case "--color" -> a.color = Path.of(next(args, ++i, arg));
                    case "--out" -> a.out = Path.of(next(args, ++i, arg));
                    case "--log" -> a.log = Path.of(next(args, ++i, arg));
                    case "--seed" -> a.seed = Long.parseLong(next(args, ++i, arg));
                    case "--size" -> {
                        String[] wh = next(args, ++i, arg).toLowerCase(Locale.ROOT).split("x");
                        if (wh.length != 2) throw new IllegalArgumentException("--size expects WxH, got " + args[i]);
                        a.width = Integer.parseInt(wh[0].trim());
                        a.height = Integer.parseInt(wh[1].trim());
                    }
                    case "--origin" -> {
                        String[] xz = next(args, ++i, arg).split(",");
                        if (xz.length != 2) throw new IllegalArgumentException("--origin expects x,z, got " + args[i]);
                        a.originX = Double.parseDouble(xz[0].trim());
                        a.originZ = Double.parseDouble(xz[1].trim());
                    }
                    case "--multiplier" -> a.multiplier = Integer.parseInt(next(args, ++i, arg));
                    case "--color-scale" -> colorScaleSpec = next(args, ++i, arg);
                    case "--normalize" -> a.normalizeOverride = Boolean.parseBoolean(next(args, ++i, arg));
                    case "--let" -> a.useLet = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (a.help) return a;

            if (a.elevation == null) throw new IllegalArgumentException("--in/--elevation is required.");
            if (a.out == null) throw new IllegalArgumentException("--out is required.");
            if (a.width <= 0 || a.height <= 0) throw new IllegalArgumentException("--size must be positive.");
            if (a.multiplier <= 0) throw new IllegalArgumentException("--multiplier must be positive.");

            if (colorScaleSpec != null) {
                a.colorScale = resolveColorScale(colorScaleSpec, a.normalizeOverride);
            } else if (a.normalizeOverride != null) {
                // Re-wrap the default with the requested normalization.
                a.colorScale = new ColorScale(a.colorScale.getName(), a.normalizeOverride, a.colorScale.getScale());
            }
            return a;
        }

        private static String next(String[] args, int i, String flag) {
            if (i >= args.length) throw new IllegalArgumentException(flag + " requires a value.");
            return args[i];
        }
    }

    /**
     * Resolves a {@code --color-scale} value into a {@link ColorScale}. Accepts (a) an exact preset
     * name, (b) a short alias, or (c) a path to a file containing {@code value,r,g,b} stops.
     */
    private static ColorScale resolveColorScale(String spec, Boolean normalizeOverride) {
        ColorScale base = switch (spec.toLowerCase(Locale.ROOT)) {
            case "solid" -> ColorScale.SOLID;
            case "gray", "grayscale", "grayscale normalized" -> ColorScale.GRAYSCALE_NORMALIZED;
            case "grayscale 0 - 1", "grayscale01" -> ColorScale.GRAYSCALE_0_1;
            case "grayscale 0 - 256" -> ColorScale.GRAYSCALE_0_256;
            case "grayscale (-64) - 320" -> ColorScale.GRAYSCALE_N64_320;
            case "terrain", "colored", "colored 0 - 320" -> ColorScale.COLORED_0_320;
            default -> null;
        };
        if (base != null) {
            if (normalizeOverride != null && normalizeOverride != base.getNormalized()) {
                return new ColorScale(base.getName(), normalizeOverride, base.getScale());
            }
            return base;
        }

        // Treat as a file containing scale stops ("value, r, g, b" per line).
        Path p = Path.of(spec);
        if (Files.exists(p)) {
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                return new ColorScale("Custom", normalizeOverride != null && normalizeOverride, text);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read color-scale file " + p + ": " + e.getMessage());
            }
        }

        // Last resort: treat the literal string as inline scale text.
        List<String> known = new ArrayList<>(List.of(
                "Solid", "Grayscale normalized", "Grayscale 0 - 1", "Grayscale 0 - 256",
                "Grayscale (-64) - 320", "Colored 0 - 320"));
        throw new IllegalArgumentException("Unknown --color-scale '" + spec
                + "'. Use a preset name (" + String.join(" | ", known) + "), an alias, or a file path.");
    }
}
