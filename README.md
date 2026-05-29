![](https://i.imgur.com/0CtCgvX.png)

# Noise Tool

This tool allows you to visualise and edit Terra noise configurations. Features include:

* 2D, 3D and Voxel rendering of the noise function itself
* Minimum/Maximum values of the function
* Plots of the distribution of noise values
* Time taken to generate the noise
* YAML editor with syntax highlighting and basic auto-completion.
* Ability to load/save configurations.
* Ability to save renders to images.
* Ability to pan noise renders by dragging.

# Using the tool

## Setup

To use the tool, simply download the latest release and run it. 
```

Once you have verified your file structure is correct, rerun the application.
A window will open up containing an editor on the left, and a render of the noise function on the right.

## Reloading the config

This tool allows you to reload the config live. To do so, simply select `Noise > Render`, or press `F5`.

## Setting the seed

To set the noise seed, go to the `Settings` panel on the right side of the application, and set the
"Seed" spinner.

## Plotting distribution

To view the noise distribution, see the "Distribution" panel on the right.

## Loading/Saving configurations

To load/Save configurations, use the `File` menu. Standard keyboard shortcuts (`Ctrl+O`, `Ctrl+S`, `Ctrl+Shift+S`)
are available as well. To save noise renders to an image, select `File > Save Render As` or use `Ctrl+Alt+S`.

# Headless / CLI rendering

The tool can also render a sampler to a PNG **without opening the GUI**. This is used to
generate documentation screenshots and to let scripts (or an automated agent) validate sampler
YAML and read back compile errors. It is triggered by passing `--headless` (or `--cli`) as the
first argument.

```
java -jar NoiseTool-*-all.jar --headless --in <elevation.yml> --out <image.png> [options]
```

Or use the no-window, no-pause launcher (forwards all arguments, `cd`s to the JAR directory so
`addons/` resolves correctly):

```
RenderNoise.bat --in elevation.yml --out shots\elevation.png --size 512x512
```

| Flag | Meaning | Default |
|---|---|---|
| `--in`, `--elevation <file>` | Main sampler definition YAML (the rendered sampler). **Required.** | — |
| `--out <file.png>` | Output image path (parent dirs are created). **Required.** | — |
| `--common <file>` | Shared samplers YAML (e.g. `resolved_samplers.yml`). Named samplers here become callable from the `--in` / `--color` YAML. Unused samplers/functions are filtered out automatically. | none |
| `--color <file>` | Separate color sampler YAML; its output drives the color scale instead of the elevation values. | none |
| `--seed <long>` | World seed. | `0` |
| `--size <WxH>` | Image size in pixels. | `512x512` |
| `--origin <x,z>` | World-coordinate origin of the top-left pixel. | `0,0` |
| `--multiplier <int>` | World units per pixel ("perspective multiplier"). | `1` |
| `--color-scale <name\|file>` | Preset name, short alias, or path to a file of `value,r,g,b` stops. | `Grayscale normalized` |
| `--normalize <true\|false>` | Override color-scale normalization. | preset default |
| `--let` | Enable Paralithic `let` expressions. | off |
| `--log <file>` | Also write the console log (and any error) to this file. | none |
| `--help` | Print usage and exit. | — |

Color-scale presets: `Solid`, `Grayscale normalized`, `Grayscale 0 - 1`, `Grayscale 0 - 256`,
`Grayscale (-64) - 320`, `Colored 0 - 320`. Aliases: `gray`/`grayscale`, `terrain`/`colored`, `solid`.

**Exit codes:** `0` = success, `1` = sampler failed to compile/render (stack trace on stderr and in
`--log`), `2` = bad/missing arguments. The working directory must contain the `addons/` folder — both
`StartNoiseTool.bat` and `RenderNoise.bat` handle this by `cd`-ing to the JAR directory.

Example — render CHIMERA's `temperature` climate field through its resolved samplers:

```
RenderNoise.bat --common C:\Projects\CHIMERA\.artifacts\resolved_samplers.yml ^
  --in temperature.yml --out shots\temperature.png ^
  --size 512x512 --multiplier 24 --color-scale grayscale
```

where `temperature.yml` is simply:

```yaml
type: EXPRESSION
expression: temperature(x, z)
```