#!/bin/bash
# 在 Ubuntu rootfs 内执行的 deepseek-harness 安装脚本
# 由 proot 调用：proot ... /bin/bash install.sh
set -e

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export DEBIAN_FRONTEND=noninteractive

echo "==> [1/5] 更新 apt 源"
apt-get update -y >/dev/null 2>&1

echo "==> [2/5] 安装基础工具 (git/curl/python3/make/gcc)"
apt-get install -y curl git python3 make gcc g++ xz-utils ca-certificates >/dev/null 2>&1

echo "==> [3/5] 安装 Node.js"
NODE_VER="24.19.0"
cd /tmp
curl -fsSL "https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.xz" -o node.tar.xz
tar -xJf node.tar.xz -C /usr/local --strip-components=1
node -v
npm -v

echo "==> [4/5] 配置 npm 国内镜像"
npm config set registry https://registry.npmmirror.com

echo "==> [5/5] 安装 @deepseek-ai/dsh"
npm install -g @deepseek-ai/dsh
dsh --version 2>/dev/null || echo "(dsh 命令可能需要重启后生效)"

echo ""
echo "=========================================="
echo "  安装完成！"
echo "=========================================="
echo "  Node.js: $(node --version)"
echo "  dsh:     $(dsh --version 2>/dev/null || echo '已安装')"
echo "  启动:    dsh web"
echo "=========================================="