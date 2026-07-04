#!/usr/bin/env bash
#
# 下载并准备项目自带二进制（adb / go-ios），按平台放入 bin/<platform>/。
# 运行时 agent 直接使用这些二进制，不依赖宿主机环境，同时保证 adb 版本统一。
#
# 用法: bash scripts/fetch_binaries.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/bin"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

GO_IOS_VERSION="v1.2.0"   # 固定版本，保证可复现

# 原子安装二进制到目标路径。
# 关键：用「同目录临时文件 + mv」原子替换，而非就地 cp 覆盖——
# 就地覆盖会 truncate 同一 inode，若目标正被运行中的进程占用（如 ios tunnel start），
# 内核缓存的代码签名会与新内容冲突，触发 macOS AMFI 把后续启动 SIGKILL 或卡在 dyld。
# mv 产生新 inode，正在运行的旧实例继续持有旧 inode，互不影响。
# macOS 上安装前 ad-hoc 重签，修复下载包/拷贝可能带来的签名失效。
install_binary() {
  local src="$1" dst="$2" plat="$3"
  mkdir -p "$(dirname "$dst")"

  # 目标被占用时给出提示（原子替换本身安全，仅提醒旧实例仍在跑，建议先停 agent）
  if pgrep -f "$dst" >/dev/null 2>&1; then
    echo "⚠️  检测到有进程正在使用 $dst；将以原子替换安装（新 inode，不影响运行中的旧实例）。"
    echo "    建议随后重启 tms-agent 以启用新二进制。"
  fi

  local tmp="$dst.new.$$"
  cp "$src" "$tmp"
  chmod +x "$tmp"
  # 仅对 darwin 目标且当前在 macOS 上时重签（codesign 只处理 Mach-O）
  if [ "$plat" = "darwin" ] && [ "$(uname -s)" = "Darwin" ]; then
    codesign --force --sign - "$tmp" >/dev/null 2>&1 || echo "⚠️  codesign 重签失败（继续安装）: $tmp"
  fi
  mv -f "$tmp" "$dst"   # 同一文件系统内 rename，原子且换新 inode
}

fetch_go_ios() {
  local plat="$1" asset="$2"   # plat=darwin|linux ; asset=mac|linux
  echo "==> go-ios ($plat)"
  curl -fsSL "https://github.com/danielpaulus/go-ios/releases/download/${GO_IOS_VERSION}/go-ios-${asset}.zip" -o "$TMP/goios-$plat.zip"
  unzip -o "$TMP/goios-$plat.zip" -d "$TMP/goios-$plat" >/dev/null
  # mac 包内为 ios；linux 包内为 ios-amd64/ios-arm64，统一取 amd64（设备主机通常为 x86_64）
  local src=""
  for cand in ios ios-amd64; do
    [ -f "$TMP/goios-$plat/$cand" ] && { src="$TMP/goios-$plat/$cand"; break; }
  done
  [ -n "$src" ] || { echo "未在 go-ios-${asset}.zip 中找到 ios 二进制"; exit 1; }
  install_binary "$src" "$BIN/$plat/ios" "$plat"
}

fetch_adb() {
  local plat="$1"              # platform-tools 的 os 名与我们目录名一致：darwin|linux
  echo "==> adb ($plat)"
  curl -fsSL "https://dl.google.com/android/repository/platform-tools-latest-${plat}.zip" -o "$TMP/pt-$plat.zip"
  unzip -o "$TMP/pt-$plat.zip" -d "$TMP/pt-$plat" >/dev/null
  install_binary "$TMP/pt-$plat/platform-tools/adb" "$BIN/$plat/adb" "$plat"
}

fetch_go_ios darwin mac
fetch_go_ios linux linux
fetch_adb darwin
fetch_adb linux

echo ""
echo "完成，二进制已就绪："
find "$BIN" -type f \( -name adb -o -name ios \) -exec ls -lh {} \;
