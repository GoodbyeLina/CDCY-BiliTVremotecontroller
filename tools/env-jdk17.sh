#!/usr/bin/env bash
# 三代原生 App —— JDK 17 环境固定（不改全局默认，避免影响你的 JDK 24 工作流）
# 用法： source tools/env-jdk17.sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
java -version 2>&1 | head -1
