#!/data/data/com.termux/files/usr/bin/bash
# =============================================
# DeepSeek Harness - Termux 一键安装脚本
# 在 Termux 中运行：bash setup_dsh.sh
# =============================================

set -e

echo "========================================="
echo "  DeepSeek Harness for Android 安装脚本"
echo "========================================="

# 1. 更新包管理器
echo "[1/5] 更新包管理器..."
pkg update -y && pkg upgrade -y

# 2. 安装 Node.js
echo "[2/5] 安装 Node.js..."
pkg install -y nodejs

# 检查 Node.js 版本
NODE_VER=$(node --version)
echo "  Node.js 版本: $NODE_VER"

# 3. 配置 npm 镜像（国内加速）
echo "[3/5] 配置 npm 镜像..."
npm config set registry https://registry.npmmirror.com
npm config set fetch-retries 3
npm config set fetch-retry-mintimeout 20000
npm config set fetch-retry-maxtimeout 120000

# 4. 安装 @deepseek-ai/dsh
echo "[4/5] 安装 @deepseek-ai/dsh..."
npm install -g @deepseek-ai/dsh

# 验证安装
echo ""
echo "  验证安装..."
dsh --version 2>/dev/null || echo "  (dsh 命令可能需要重启 Termux 后生效)"

# 5. 创建启动脚本
echo "[5/5] 创建启动脚本..."
SCRIPT_DIR="$HOME/.dsh-mobile"
mkdir -p "$SCRIPT_DIR"

cat > "$SCRIPT_DIR/start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# DeepSeek Harness 启动脚本
echo "========================================="
echo "  启动 DeepSeek Harness..."
echo "  端口: 3080"
echo "  UI:  http://127.0.0.1:3080"
echo "========================================="
echo ""
# 启动 dsh web
cd "$HOME"
dsh web
SCRIPT

chmod +x "$SCRIPT_DIR/start.sh"

echo ""
echo "========================================="
echo "  安装完成！"
echo "========================================="
echo ""
echo "启动方式:"
echo "  1. 在 Termux 中运行: bash ~/.dsh-mobile/start.sh"
echo "  2. 或在手机应用中点击 \"启动 DSH\""
echo ""
echo "启动后，在 DSH 应用中即可连接到 Web UI"
echo ""