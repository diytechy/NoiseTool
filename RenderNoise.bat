@echo off
setlocal enabledelayedexpansion
REM ==========================================================================
REM RenderNoise.bat - headless (no-GUI) batch renderer for NoiseTool.
REM
REM Compiles a sampler definition and writes a PNG, with no window and no pause,
REM so it can be driven by scripts / CI / an automated agent. All arguments are
REM forwarded to the NoiseTool headless renderer.
REM
REM Example:
REM   RenderNoise.bat --common C:\Projects\CHIMERA\.artifacts\resolved_samplers.yml --in temperature.yml --out shots\temperature.png --size 512x512 --multiplier 24 --color-scale grayscale
REM
REM Exit codes: 0 = ok, 1 = compile/render error, 2 = usage error.
REM ==========================================================================

set "SCRIPT_DIR=%~dp0"
set "JAR_PATTERN=NoiseTool-*-all.jar"

REM Find the JAR (root first, then build\libs)
set "JAR_FILE="
for %%F in ("%SCRIPT_DIR%%JAR_PATTERN%") do set "JAR_FILE=%%F"
if not defined JAR_FILE (
    for %%F in ("%SCRIPT_DIR%build\libs\%JAR_PATTERN%") do set "JAR_FILE=%%F"
)
if not defined JAR_FILE (
    echo ERROR: No NoiseTool JAR found. Build first: gradlew.bat build 1>&2
    exit /b 2
)

REM Locate Java (JAVA_HOME, then PATH)
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
    where java >nul 2>&1 && set "JAVA_EXE=java"
)
if not defined JAVA_EXE (
    echo ERROR: Java 25+ not found. Set JAVA_HOME or add java to PATH. 1>&2
    exit /b 2
)

REM Run from the JAR's directory so addons\ resolves correctly
for %%F in ("%JAR_FILE%") do set "WORK_DIR=%%~dpF"
cd /d "%WORK_DIR%"

"%JAVA_EXE%" -jar "%JAR_FILE%" --headless %*
exit /b %errorlevel%
