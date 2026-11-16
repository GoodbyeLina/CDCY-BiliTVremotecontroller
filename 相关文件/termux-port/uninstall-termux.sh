#!/data/data/com.termux/files/usr/bin/bash
# 卸载 Termux 上的 tvstation（不会动电视上的 mpv）。
set -u
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export SVDIR="$PREFIX/var/service"
export PATH="$PREFIX/bin:$PATH"

sv down tvstation 2>/dev/null || true
sv down tvcontrol 2>/dev/null || true
sv-disable tvstation 2>/dev/null || true
sv-disable tvcontrol 2>/dev/null || true
rm -rf "$SVDIR/tvstation" "$SVDIR/tvcontrol"
rm -f "$HOME/.termux/boot/00-tvstation"
echo "已停止并移除 tvstation / tvcontrol 服务与开机脚本。"

read -r -p "是否删除 ~/tvstation 整个目录（含日志）？[y/N] " ANS
case "$ANS" in
  y|Y) rm -rf "$HOME/tvstation"; echo "已删除 ~/tvstation" ;;
  *) echo "保留 ~/tvstation" ;;
esac
echo "如需彻底移除：pkg uninstall termux-services（可选），并卸载 Termux:Boot。"
