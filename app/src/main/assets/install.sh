#!/bin/bash
# 在 Ubuntu rootfs 内执行的 deepseek-harness 安装脚本
# 由 proot 调用：proot ... /bin/bash install.sh
set -e

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8

echo "=========================================="
echo "  DeepSeek Harness 安装开始"
echo "  $(date)"
echo "=========================================="

# 1. 更换 apt 源为国内镜像（清华 TUNA），加速并提高成功率
echo "==> [1/6] 配置 apt 国内镜像源"
# 备份原源（如未备份过）
if [ ! -f /etc/apt/sources.list.bak ]; then
  cp /etc/apt/sources.list /etc/apt/sources.list.bak 2>/dev/null || true
fi
# Ubuntu 24.04 使用 deb822 格式 (/etc/apt/sources.list.d/ubuntu.sources)
if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
  sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|https://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|https://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
elif [ -f /etc/apt/sources.list ]; then
  sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|https://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|https://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' /etc/apt/sources.list 2>/dev/null || true
fi
# 配置完成后自动接受 https
apt-get install -y ca-certificates >/dev/null 2>&1 || true

# 2. 更新 apt（带重试）
echo "==> [2/6] 更新 apt 源"
for i in 1 2 3; do
  if apt-get update -y; then
    break
  fi
  echo "  apt-get update 第 $i 次失败，重试..."
  sleep 3
done

# 3. 安装基础工具
echo "==> [3/6] 安装基础工具 (git/curl/python3/make/gcc)"
apt-get install -y --no-install-recommends curl git python3 make gcc g++ xz-utils ca-certificates >/dev/null 2>&1 || {
  echo "  apt 安装失败，重试..."
  apt-get install -y curl git python3 make gcc g++ xz-utils ca-certificates
}

# 4. 安装 Node.js（多镜像重试）
echo "==> [4/6] 安装 Node.js"
NODE_VER="24.19.0"
cd /tmp

NODE_MIRRORS=(
  "https://npmmirror.com/mirrors/node/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.xz"
  "https://mirrors.huaweicloud.com/nodejs/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.xz"
  "https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.xz"
)

NODE_OK=0
for url in "${NODE_MIRRORS[@]}"; do
  echo "  尝试: $url"
  if curl -fL --connect-timeout 15 --retry 2 "$url" -o node.tar.xz; then
    echo "  下载成功"
    NODE_OK=1
    break
  fi
done

if [ "$NODE_OK" != "1" ]; then
  echo "!! Node.js 下载失败（所有镜像都不可用）"
  exit 1
fi

tar -xJf node.tar.xz -C /usr/local --strip-components=1
rm -f node.tar.xz
node -v
npm -v

# 5. 配置 npm 国内镜像
echo "==> [5/6] 配置 npm 国内镜像"
npm config set registry https://registry.npmmirror.com
npm config set fetch-retries 3
npm config set fetch-retry-mintimeout 20000
npm config set fetch-retry-maxtimeout 120000

# 6. 安装 @deepseek-ai/dsh（带重试）
echo "==> [6/6] 安装 @deepseek-ai/dsh"
for i in 1 2 3; do
  if npm install -g @deepseek-ai/dsh; then
    break
  fi
  echo "  npm install 第 $i 次失败，重试..."
  sleep 3
done

echo ""
echo "=========================================="
echo "  安装完成！"
echo "=========================================="
echo "  Node.js: $(node --version)"
echo "  npm:     $(npm --version)"
echo "  dsh:     $(dsh --version 2>/dev/null || echo '已安装')"
echo "  启动:    dsh web"
echo "=========================================="