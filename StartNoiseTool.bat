SET "scriptPath=%~dp0"
set JavaHome="C:\JAVA\jdk-23\bin\java"

cd "%~dp0build\libs"

::%JavaHome% --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -jar BiomeTool-0.4.9-win.jar
%JavaHome% -jar NoiseTool-1.2.2-all.jar