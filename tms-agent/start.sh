#!/usr/bin/env bash
# 重启 tms-agent：清理旧进程后以 nohup 后台启动
# 用法：./start.sh   （tunnel 多为 sudo 起，清理时可能需要输入 sudo 密码）

# 切到脚本所在目录（tms-agent 根），保证 start.py 路径正确
cd "$(dirname "$0")" || exit 1

echo "[tms-agent] 清理旧的 start.py 进程..."
pkill -f "start.py" 2>/dev/null

echo "[tms-agent] 清理 go-ios tunnel 进程..."
# tunnel 多由 sudo 启动（root 所属），仅在确有该进程时才动用 sudo，避免无谓的密码提示
if pgrep -f "tunnel start" >/dev/null 2>&1; then
    pkill -f "tunnel start" 2>/dev/null
    sudo pkill -f "tunnel start" 2>/dev/null
fi

echo "[tms-agent] 清理主机上所有 adb server 进程..."
# adb server 命令行形如 "adb -L tcp:PORT ... fork-server server"，按 fork-server 匹配可清掉任意端口/版本的 server
pkill -f "adb.*fork-server" 2>/dev/null

# 等待端口 / 进程释放
sleep 2

echo "[tms-agent] 启动新的 agent..."
nohup python3 start.py > running.log 2>&1 &

echo "[tms-agent] 已启动 (PID $!)，日志见 running.log"
