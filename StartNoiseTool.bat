SET "scriptPath=%~dp0"
set JavaHome="C:\JAVA\jdk-23\bin\java"

if exist "%~dp0..\DendryTerra\build\libs\DendryTerra-1.0.0-BETA-2.jar" (
    del /Q "%~dp0build\libs\addons\DendryTerra*.jar" 2>nul
    copy /Y "%~dp0..\DendryTerra\build\libs\DendryTerra-1.0.0-BETA-2.jar" "%~dp0build\libs\addons\"
)

cd "%~dp0build\libs"

::%JavaHome% --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -jar BiomeTool-0.4.9-win.jar
%JavaHome% -jar NoiseTool-1.2.2-all.jar