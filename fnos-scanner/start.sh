#!/usr/bin/env bash
# ============================================================
#  fnOS 端口扫描器 - Linux 一键启动
#  用法:  ./start.sh [监听地址:端口]     默认 127.0.0.1:8787
# ============================================================
set -euo pipefail

cd "$(dirname "$0")"

BIN="fnos-scanner-linux-amd64"
HOST="${1:-127.0.0.1:8787}"

# 1. 后端二进制
if [ ! -x "$BIN" ]; then
    echo "[!] 缺少 $BIN，尝试用 go 编译..."
    if command -v go >/dev/null 2>&1; then
        GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$BIN" .
    else
        echo "[x] 未找到 $BIN 且本机无 Go 工具链，无法启动" >&2
        exit 1
    fi
fi

# 2. trim-cli 可执行文件
if [ ! -x "./trim-cli" ]; then
    echo "[!] 缺少 ./trim-cli，请将 fnOS trim-cli (linux amd64) 放到本目录" >&2
    exit 1
fi
chmod +x ./trim-cli

echo "[*] 启动中 -> http://$HOST"
echo "[*] 按 Ctrl+C 停止"
exec ./"$BIN" --listen "$HOST"
