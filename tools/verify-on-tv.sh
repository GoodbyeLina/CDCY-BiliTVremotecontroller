#!/usr/bin/env bash
# 电视端验收 / 交接自检脚本（P4-1）。
#
# 用法：
#   ./tools/verify-on-tv.sh --dry-run            无电视自检（默认输出人类可读行）
#   ./tools/verify-on-tv.sh --dry-run --json     无电视自检，输出 JSON（给 CI / jq 用）
#   ./tools/verify-on-tv.sh                      真机：需要 adb 已连电视
#   ./tools/verify-on-tv.sh --config PATH
#
# 退出码：0 全通过（允许 SKIP）；1 有 FAIL；2 环境缺失
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRY_RUN=0
JSON=0
CONFIG="${HOME}/.tvrc/config.json"
while [ $# -gt 0 ]; do
  case "${1}" in
    --dry-run) DRY_RUN=1; shift ;;
    --json) JSON=1; shift ;;
    --config) CONFIG="${2}"; shift 2 ;;
    *) echo "未知参数：${1}"; exit 1 ;;
  esac
done

JAVA_HOME_17=/opt/homebrew/opt/openjdk@17
GRADLE="${ROOT}/tools/gradle-8.10.2/bin/gradle"
TMP=$(mktemp -t tvrc-verify)
trap 'rm -f "${TMP}"' EXIT

PORT=$(python3 - "${CONFIG}" <<'PY' 2>/dev/null || echo 8100
import json,sys
try:
    print(json.load(open(sys.argv[1])).get('port', 8100))
except Exception:
    print(8100)
PY
)

PASS=0; FAIL=0; SKIP=0
record(){ # id status detail
  printf '%s\t%s\t%s\n' "${1}" "${2}" "${3}" >> "${TMP}"
  [ "${JSON}" = 1 ] || echo "${2} ${1}${3:+ -> ${3}}"
  case "${2}" in PASS) PASS=$((PASS+1));; FAIL) FAIL=$((FAIL+1));; *) SKIP=$((SKIP+1));; esac
}
ok(){ record "${1}" PASS "${2:-}"; }
bad(){ record "${1}" FAIL "${2:-}"; }
skip(){ record "${1}" SKIP "${2:-}"; }
section(){ [ "${JSON}" = 1 ] || echo "== ${1} =="; }

section "0) 环境"
[ -x "${JAVA_HOME_17}/bin/java" ] && ok "jdk17" "" || bad "jdk17" "找不到 ${JAVA_HOME_17}"
[ -x "${GRADLE}" ] && ok "gradle" "" || bad "gradle" "找不到 ${GRADLE}"

section "1) 配置校验（走 App 自己的 ConfigValidator）"
if [ -f "${CONFIG}" ]; then
  OUT=$(cd "${ROOT}" && JAVA_HOME="${JAVA_HOME_17}" "${GRADLE}" -q :core:runConfigCheck --args="${CONFIG}" --no-daemon --console=plain 2>&1)
  echo "${OUT}" | grep -q "CONFIG_OK" && ok "config_valid" "" || bad "config_valid" "$(echo "${OUT}" | head -3 | tr '\n' ' ')"
else
  LEGACY="${ROOT}/相关文件/termux-port/config.example.json"
  if [ "${DRY_RUN}" = 1 ] && [ -f "${LEGACY}" ]; then
    OUT=$(cd "${ROOT}" && JAVA_HOME="${JAVA_HOME_17}" "${GRADLE}" -q :core:runConfigCheck --args="--migrate ${LEGACY}" --no-daemon --console=plain 2>&1)
    if echo "${OUT}" | grep -q "MIGRATE_OK"; then ok "config_migrate_from_v2" "$(echo "${OUT}" | head -1)"; else bad "config_migrate_from_v2" "$(echo "${OUT}" | tail -3 | tr '\n' ' ')"; fi
  elif [ "${DRY_RUN}" = 1 ]; then
    skip "config_valid" "没有 ${CONFIG} 也没有二代样例"
  else
    bad "config_valid" "配置不存在：${CONFIG}"
  fi
fi

section "2) 回归闸门"
OUT=$(cd "${ROOT}" && JAVA_HOME="${JAVA_HOME_17}" "${GRADLE}" -q :core:test --no-daemon --console=plain 2>&1)
COUNT=$(python3 - "${ROOT}" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
t = 0
for p in glob.glob(sys.argv[1] + '/core/build/test-results/test/*.xml'):
    try:
        t += int(ET.parse(p).getroot().get('tests'))
    except Exception:
        pass
print(t)
PY
)
if [ "${OUT}" = "" ] || ! echo "${OUT}" | grep -qi "FAILED"; then
  if [ "${COUNT}" -ge 170 ]; then ok "regression_170" "${COUNT} tests"; else bad "regression_170" "只跑了 ${COUNT} 条（基线 170）"; fi
else
  bad "regression_170" "$(echo "${OUT}" | grep -i "FAILED" | head -2 | tr '\n' ' ')"
fi

section "3) 代理端口 ${PORT}"
FREE=$(python3 - "${PORT}" <<'PY'
import socket, sys
s = socket.socket()
try:
    s.bind(('0.0.0.0', int(sys.argv[1])))
    print('free')
except OSError as e:
    print('busy', e)
finally:
    s.close()
PY
)
case "${FREE}" in
  free*) ok "port_free" "${PORT}" ;;
  *) bad "port_free" "${PORT} 被占用：${FREE}" ;;
esac

if [ "${DRY_RUN}" = 1 ]; then
  section "4) 真机项（dry-run 跳过）"
  for item in adb_device adb_authorized mpv_installed tv_wake proxy_live_ffprobe apk_install; do
    skip "${item}" "需要电视"
  done
else
  section "4) adb 设备"
  ADB_BIN=$(command -v adb || true)
  DEV=""
  if [ -z "${ADB_BIN}" ]; then
    bad "adb_device" "本机没有 adb"
  else
    DEV=$("${ADB_BIN}" devices | awk 'NR>1 && ${2}=="device"{print ${1}; exit}')
    if [ -n "${DEV}" ]; then ok "adb_device" "${DEV}"; else bad "adb_device" "没有已授权设备（先在电视上点允许调试）"; fi
  fi

  section "5) 电视侧"
  if [ -n "${ADB_BIN}" ] && [ -n "${DEV}" ]; then
    "${ADB_BIN}" -s "${DEV}" shell pm list packages 2>/dev/null | grep -q "is.xyz.mpv" && ok "mpv_installed" "" || bad "mpv_installed" "电视没有 is.xyz.mpv"
    "${ADB_BIN}" -s "${DEV}" shell input keyevent 224 >/dev/null 2>&1 && ok "tv_wake" "KEYCODE_WAKEUP" || bad "tv_wake" ""
  else
    skip "mpv_installed" "无设备"; skip "tv_wake" "无设备"
  fi

  section "6) 代理真流"
  if [ -x "${ROOT}/tools/verify-proxy.sh" ]; then
    "${ROOT}/tools/verify-proxy.sh" > /tmp/verify-proxy.log 2>&1 && ok "proxy_live_ffprobe" "见 /tmp/verify-proxy.log" || bad "proxy_live_ffprobe" "见 /tmp/verify-proxy.log"
  else
    skip "proxy_live_ffprobe" "缺 verify-proxy.sh"
  fi

  section "7) APK"
  if ls "${ROOT}"/app/build/outputs/apk/debug/*.apk >/dev/null 2>&1; then ok "apk_install" "有 debug APK，可 adb install"; else skip "apk_install" "S2 之后才有 Android 壳"; fi
fi

if [ "${JSON}" = 1 ]; then
  python3 - "${TMP}" "${FAIL}" "${PASS}" "${SKIP}" "${DRY_RUN}" <<'PY'
import json, sys
tsv, fail, pas, skip, dry = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5] == '1'
checks = []
with open(tsv, encoding='utf-8') as f:
    for line in f:
        parts = line.rstrip('\n').split('\t')
        if len(parts) >= 2:
            checks.append({'id': parts[0], 'status': parts[1], 'detail': parts[2] if len(parts) > 2 else ''})
print(json.dumps({'ok': fail == 0, 'dryRun': dry, 'summary': {'pass': pas, 'fail': fail, 'skip': skip}, 'checks': checks}, ensure_ascii=False, indent=2))
PY
else
  echo "== 结束：${PASS} PASS / ${FAIL} FAIL / ${SKIP} SKIP =="
  if [ "${DRY_RUN}" = 1 ]; then
    echo "真机前请准备：电视开发者选项打开网络调试、与手机同一 WiFi、装好 mpv。"
  fi
fi

[ "${FAIL}" -eq 0 ] || exit 1
exit 0
