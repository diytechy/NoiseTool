@echo off
echo Clearing local Maven cache for Terra artifacts...
echo This will force Gradle to fall back to the Repsy remote repository.
echo.

set "M2_TERRA=%USERPROFILE%\.m2\repository\com\dfsek\terra"

if exist "%M2_TERRA%" (
    rmdir /s /q "%M2_TERRA%"
    echo Removed: %M2_TERRA%
) else (
    echo No local Terra artifacts found at %M2_TERRA%
)

echo.
echo Done. Next build will resolve Terra dependencies from Repsy.
echo To republish locally, run publish_to_maven_local.bat in the Terra project.
pause
