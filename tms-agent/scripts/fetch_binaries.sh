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
  mkdir -p "$BIN/$plat"
  cp "$src" "$BIN/$plat/ios"
  chmod +x "$BIN/$plat/ios"
}

fetch_adb() {
  local plat="$1"              # platform-tools 的 os 名与我们目录名一致：darwin|linux
  echo "==> adb ($plat)"
  curl -fsSL "https://dl.google.com/android/repository/platform-tools-latest-${plat}.zip" -o "$TMP/pt-$plat.zip"
  unzip -o "$TMP/pt-$plat.zip" -d "$TMP/pt-$plat" >/dev/null
  mkdir -p "$BIN/$plat"
  cp "$TMP/pt-$plat/platform-tools/adb" "$BIN/$plat/adb"
  chmod +x "$BIN/$plat/adb"
}

fetch_go_ios darwin mac
fetch_go_ios linux linux
fetch_adb darwin
fetch_adb linux

echo ""
echo "完成，二进制已就绪："
find "$BIN" -type f \( -name adb -o -name ios \) -exec ls -lh {} \;
