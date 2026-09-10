#!/usr/bin/env bash
# ============================================================
# JavaTool MCP backend launcher (used by mask and manually)
# Backend launch mode is controlled by env BACKEND_LAUNCH_MODE:
#   source - mvn quarkus:dev (dev mode, hot reload, debug disabled)
#   jar    - java -jar thin target/quarkus-app/quarkus-run.jar, fallback uber-jar target/javatool-mcp-runner.jar (default)
# Related env: CONSOLE_PORT, CONSOLE_TOKEN, DATA_DIR,
#              JAVA_HOME (JDK 17), MAVEN_HOME (optional)
# ============================================================
cd "$(dirname "$0")" || exit 1

MODE="${BACKEND_LAUNCH_MODE:-jar}"

find_mvn() {
  if [ -n "$MAVEN_HOME" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
    echo "$MAVEN_HOME/bin/mvn"
  elif command -v mvn >/dev/null 2>&1; then
    echo "mvn"
  else
    echo ""
  fi
}

find_java() {
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then
    echo "java"
  else
    echo ""
  fi
}

case "$(echo "$MODE" | tr '[:upper:]' '[:lower:]')" in
  source)
    echo "[start.sh] Launching backend in SOURCE mode (mvn quarkus:dev)..."
    MVN="$(find_mvn)"
    if [ -z "$MVN" ]; then
      echo "[start.sh] mvn not found (set MAVEN_HOME or add to PATH)"
      exit 1
    fi
    exec "$MVN" -q quarkus:dev -Ddebug=false -Dquarkus.dev.no-interactive
    ;;
  jar)
    JAVA_EXE="$(find_java)"
    if [ -z "$JAVA_EXE" ]; then
      echo "[start.sh] java not found (set JAVA_HOME or add to PATH)"
      exit 1
    fi
    # 优先瘦 jar（quarkus-app 目录），不存在则回退 uber-jar（target/javatool-mcp-runner.jar）
    RUN_JAR="target/quarkus-app/quarkus-run.jar"
    [ -f "$RUN_JAR" ] || RUN_JAR="target/javatool-mcp-runner.jar"
    if [ ! -f "$RUN_JAR" ]; then
      echo "[start.sh] Backend jar not found: target/quarkus-app/quarkus-run.jar (thin) or target/javatool-mcp-runner.jar (uber)"
      echo '[start.sh] Run "mvn package" first, or set BACKEND_LAUNCH_MODE=source'
      exit 1
    fi
    echo "[start.sh] Launching backend in JAR mode: $RUN_JAR"
    exec "$JAVA_EXE" -Dfile.encoding=UTF-8 -jar "$RUN_JAR"
    ;;
  *)
    echo "[start.sh] Unknown BACKEND_LAUNCH_MODE \"$MODE\", fallback to jar"
    exec bash "$0"
    ;;
esac