#!/usr/bin/env bash
# 审计驱动：固化 classpath，对 v1/v2 两个构建提取/比对
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
ROOT=/Users/shizhihui/ccfb/runs/17820/workspace/given-audit
cd "$ROOT"

sbt --no-colors \
  "export inspector/Compile/fullClasspath" \
  "export appV1/Compile/fullClasspath" \
  "export appV2/Compile/fullClasspath" 2>/dev/null \
  | grep -v '^\[' | grep -v '^$' > /tmp/given-audit-cps.txt

INSP_CP=$(sed -n '1p' /tmp/given-audit-cps.txt)
V1_CP=$(sed -n '2p' /tmp/given-audit-cps.txt)
V2_CP=$(sed -n '3p' /tmp/given-audit-cps.txt)

mode="${1:-dump-v1}"
# 关键：TastyInspector 靠 JVM 类加载器解析依赖符号（Scala2 类只有 .class/pickle，
# 必须真正在 classpath 上），因此把应用 classpath 并入 JVM -cp，而非仅作字符串参数。
run() {
  local app_cp="$1"; shift
  java -cp "$INSP_CP:$app_cp" givenaudit.Main "$@"
}
case "$mode" in
  dump-v1)
    run "$V1_CP" dump "$V1_CP" demo ;;
  dump-v2)
    run "$V2_CP" dump "$V2_CP" demo ;;
  audit)
    run "$V1_CP" audit "$V1_CP" /tmp/audit-v1.json demo
    run "$V2_CP" audit "$V2_CP" /tmp/audit-v2.json demo
    ;;
  diff)
    run "$V1_CP" audit "$V1_CP" /tmp/audit-v1.json demo
    run "$V2_CP" audit "$V2_CP" /tmp/audit-v2.json demo
    run "$INSP_CP:$V2_CP" diff /tmp/audit-v1.json /tmp/audit-v2.json /tmp/audit-report.json
    rc=$?
    echo "DIFF_EXIT_CODE=$rc  (非0表示检测到外部 given 切换，应阻断构建)"
    exit $rc
    ;;
esac
