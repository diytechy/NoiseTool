@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "JAR_PATTERN=NoiseTool-*-all.jar"
set "MIN_JAVA_VERSION=25"
set "JAVA_EXE="

:: ── 1. Find the JAR ──────────────────────────────────────────────────────────
:: Check alongside this script first (distributed layout), then build/libs (local dev)
set "JAR_FILE="
for %%F in ("%SCRIPT_DIR%%JAR_PATTERN%") do set "JAR_FILE=%%F"
if not defined JAR_FILE (
    for %%F in ("%SCRIPT_DIR%build\libs\%JAR_PATTERN%") do set "JAR_FILE=%%F"
)

if not defined JAR_FILE (
    echo.
    echo  ERROR: No NoiseTool JAR found. Looked in:
    echo         %SCRIPT_DIR%
    echo         %SCRIPT_DIR%build\libs\
    echo.
    echo  Build the project first:
    echo    gradlew.bat build
    echo.
    pause
    exit /b 1
)

:: ── 2. Locate Java ───────────────────────────────────────────────────────────

:: Priority 1: JAVA_HOME environment variable
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
        goto :check_version
    ) else (
        echo  WARNING: JAVA_HOME is set but "%JAVA_HOME%\bin\java.exe" does not exist.
    )
)

:: Priority 2: java on the system PATH
where java >nul 2>&1
if %errorlevel% == 0 (
    set "JAVA_EXE=java"
    goto :check_version
)

:: Priority 3: scan common Windows installation paths
echo  Java not found on PATH or via JAVA_HOME. Scanning common install locations...

set "SEARCH_ROOTS=C:\Program Files\Java C:\Program Files\Eclipse Adoptium C:\Program Files\Microsoft C:\Program Files\Amazon Corretto C:\Program Files\BellSoft C:\Program Files\Azul Systems\Zulu C:\JAVA"

for %%R in (%SEARCH_ROOTS%) do (
    if exist "%%R" (
        for /d %%D in ("%%R\jdk*" "%%R\jre*") do (
            if exist "%%D\bin\java.exe" (
                set "JAVA_EXE=%%D\bin\java.exe"
                echo  Found Java at: %%D
                goto :check_version
            )
        )
    )
)

:: Nothing found
echo.
echo  ERROR: Java %MIN_JAVA_VERSION%+ could not be found on this system.
echo.
echo  Please install a Java %MIN_JAVA_VERSION%+ JRE or JDK from one of:
echo    - https://adoptium.net             (Eclipse Temurin, recommended)
echo    - https://www.microsoft.com/openjdk
echo    - https://aws.amazon.com/corretto/
echo    - https://azul.com/downloads/
echo.
echo  After installing, either:
echo    a) Set the JAVA_HOME environment variable to the JDK/JRE folder, or
echo    b) Add the JDK/JRE bin folder to your PATH.
echo.
pause
exit /b 1

:: ── 3. Validate Java version ─────────────────────────────────────────────────
:check_version
for /f "tokens=3" %%V in ('""%JAVA_EXE%" -version 2>&1 | findstr /i "version""') do (
    set "RAW_VERSION=%%~V"
)

:: Extract the major version number (handles both "1.8.0_xxx" and "21.0.x" formats)
for /f "tokens=1 delims=." %%M in ("!RAW_VERSION!") do set "MAJOR=%%M"
if "!MAJOR!" == "1" (
    for /f "tokens=2 delims=." %%M in ("!RAW_VERSION!") do set "MAJOR=%%M"
)

if not defined MAJOR (
    echo  WARNING: Could not determine Java version from: !RAW_VERSION!
    echo  Attempting to launch anyway...
    goto :launch
)

if !MAJOR! LSS %MIN_JAVA_VERSION% (
    echo.
    echo  ERROR: Java %MIN_JAVA_VERSION%+ is required. Found Java !MAJOR! at:
    echo         !JAVA_EXE!
    echo.
    echo  Please install Java %MIN_JAVA_VERSION%+ from https://adoptium.net
    echo  and update your JAVA_HOME or PATH.
    echo.
    pause
    exit /b 1
)

echo  Using Java !MAJOR! from: !JAVA_EXE!

:: ── 4. Copy local DendryTerra build if available ─────────────────────────────
if exist "%SCRIPT_DIR%..\DendryTerra\build\libs\DendryTerra-1.0.0-BETA-*.jar" (
    del /Q "%LIBS_DIR%\addons\DendryTerra*.jar" 2>nul
    copy /Y "%SCRIPT_DIR%..\DendryTerra\build\libs\DendryTerra-1.0.0-BETA-*.jar" "%LIBS_DIR%\addons\" >nul
    echo  Loaded local DendryTerra build.
)

:: ── 5. Launch ────────────────────────────────────────────────────────────────
:launch
echo  Launching: %JAR_FILE%
echo.
cd /d "%LIBS_DIR%"
"%JAVA_EXE%" -jar "%JAR_FILE%"

if %errorlevel% neq 0 (
    echo.
    echo  NoiseTool exited with error code %errorlevel%.
    pause
)
endlocal
