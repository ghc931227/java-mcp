@echo off
rem ============================================================
rem JavaTool MCP backend launcher (used by mask and manually)
rem Backend launch mode is controlled by env BACKEND_LAUNCH_MODE:
rem   source - mvn quarkus:dev (dev mode, hot reload, debug disabled)
rem   jar    - java -jar thin target\quarkus-app\quarkus-run.jar, fallback uber-jar target\javatool-mcp-runner.jar (default)
rem Related env: CONSOLE_PORT, CONSOLE_TOKEN, DATA_DIR,
rem              JAVA_HOME (JDK 17), MAVEN_HOME (defaults to D:\Soft\Maven3)
rem ============================================================
setlocal
cd /d "%~dp0"

if "%BACKEND_LAUNCH_MODE%"=="" set "BACKEND_LAUNCH_MODE=jar"

if /i "%BACKEND_LAUNCH_MODE%"=="source" goto source
if /i "%BACKEND_LAUNCH_MODE%"=="jar" goto jar
echo [start.bat] Unknown BACKEND_LAUNCH_MODE "%BACKEND_LAUNCH_MODE%", fallback to jar
goto jar

:source
echo [start.bat] Launching backend in SOURCE mode (mvn quarkus:dev)...
set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not exist "%MVN%" set "MVN=mvn"
call "%MVN%" -q quarkus:dev -Ddebug=false -Dquarkus.dev.no-interactive
goto :eof

:jar
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"
rem 优先瘦 jar（quarkus-app 目录），不存在则回退 uber-jar（target\javatool-mcp-runner.jar）
set "RUN_JAR=target\quarkus-app\quarkus-run.jar"
if exist "%RUN_JAR%" goto jar_run
set "RUN_JAR=target\javatool-mcp-runner.jar"
if exist "%RUN_JAR%" goto jar_run
echo [start.bat] Backend jar not found: target\quarkus-app\quarkus-run.jar (thin) or target\javatool-mcp-runner.jar (uber)
echo [start.bat] Run "mvn package" first, or set BACKEND_LAUNCH_MODE=source
exit /b 1
:jar_run
echo [start.bat] Launching backend in JAR mode: %RUN_JAR%
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%RUN_JAR%"
goto :eof