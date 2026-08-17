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

# 0. 确保 DNS 可用
# proot 环境下 rootfs 继承的 /etc/resolv.conf 通常是无效的（Android 用 netd 管理 DNS），
# 必须显式写入公共 DNS，否则 apt/curl 无法解析域名。
if ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
  echo "==> [0/7] 配置 DNS"
  printf 'nameserver 114.114.114.114\nnameserver 223.5.5.5\nnameserver 8.8.8.8\n' > /etc/resolv.conf
fi

# 1. 更换 apt 源为国内镜像（清华 TUNA）
# 注意用 http 而非 https：rootfs 里可能还没有 ca-certificates，https 会因证书校验失败
echo "==> [1/7] 配置 apt 国内镜像源"
# 备份原源（如未备份过）
if [ ! -f /etc/apt/sources.list.bak ]; then
  cp /etc/apt/sources.list /etc/apt/sources.list.bak 2>/dev/null || true
fi
# Ubuntu 24.04 使用 deb822 格式 (/etc/apt/sources.list.d/ubuntu.sources)
if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
  sed -i 's|https://archive.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://archive.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|https://security.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
elif [ -f /etc/apt/sources.list ]; then
  sed -i 's|https://archive.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://archive.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|https://security.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' /etc/apt/sources.list 2>/dev/null || true
fi

# 2. 更新 apt（严格失败检测：3 次失败直接退出，避免后续装包报"找不到包"）
echo "==> [2/7] 更新 apt 源"
APT_OK=0
for i in 1 2 3; do
  if apt-get update -y; then
    APT_OK=1
    break
  fi
  echo "  apt-get update 第 $i 次失败，重试..."
  sleep 3
done
if [ "$APT_OK" != "1" ]; then
  echo "!! apt-get update 连续 3 次失败，无法继续安装"
  echo "  可能原因: 网络不通 / DNS 解析失败 / 镜像源不可达"
  echo "  --- /etc/resolv.conf ---"
  cat /etc/resolv.conf 2>/dev/null || true
  echo "  --- DNS 解析测试 ---"
  getent hosts mirrors.tuna.tsinghua.edu.cn 2>&1 | head -2 || true
  echo "  -----------------------"
  exit 1
fi

# 3. 安装基础工具（输出直接透出，便于诊断）
echo "==> [3/7] 安装基础工具 (git/curl/python3/make/gcc)"
apt-get install -y --no-install-recommends curl git python3 make gcc g++ xz-utils ca-certificates

# 4. 安装 Node.js（多镜像重试）
echo "==> [4/7] 安装 Node.js"
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
echo "==> [5/7] 配置 npm 国内镜像"
npm config set registry https://registry.npmmirror.com
npm config set fetch-retries 3
npm config set fetch-retry-mintimeout 20000
npm config set fetch-retry-maxtimeout 120000

# 6. 安装 @deepseek-ai/dsh（带重试）
echo "==> [6/7] 安装 @deepseek-ai/dsh"
DSH_OK=0
for i in 1 2 3; do
  if npm install -g @deepseek-ai/dsh; then
    DSH_OK=1
    break
  fi
  echo "  npm install 第 $i 次失败，重试..."
  sleep 3
done
if [ "$DSH_OK" != "1" ]; then
  echo "!! @deepseek-ai/dsh 安装失败"
  exit 1
fi

echo ""
echo "=========================================="
echo "  安装完成！"
echo "=========================================="
echo "  Node.js: $(node --version)"
echo "  npm:     $(npm --version)"
echo "  dsh:     $(dsh --version 2>/dev/null || echo '已安装')"
echo "  启动:    dsh web"
echo "=========================================="
