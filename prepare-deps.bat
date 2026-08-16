@echo off
chcp 65001 >nul
echo ========================================
echo  DeepSeek Harness Android - 依赖准备脚本
echo ========================================
echo.

REM 检查 Node.js
echo [1/3] 检查 Node.js...
where node >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo   ✗ Node.js 未安装，请先安装 Node.js 22+:
    echo     https://nodejs.org/
    pause
    exit /b 1
)
echo   ✓ Node.js version: 
node --version

REM 检查 npm
echo.
echo [2/3] 检查 npm...
where npm >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo   ✗ npm 未安装
    pause
    exit /b 1
)
echo   ✓ npm version:
npm --version

REM 安装 DSH
echo.
echo [3/3] 安装 @deepseek-ai/dsh（全局）...
npm install -g @deepseek-ai/dsh
if %ERRORLEVEL% neq 0 (
    echo   ✗ 安装失败，请检查网络连接
    pause
    exit /b 1
)
echo   ✓ 安装成功

echo.
echo ========================================
echo  依赖准备完成！
echo.
echo  构建 APK 的步骤:
echo   1. 用 Android Studio 打开本项目目录
echo   2. 等待 Gradle 同步完成
echo   3. 点击 Build - Build Bundle(s) / APK
echo   4. 选择 Build APK
echo.
echo  在手机上使用:
echo   1. 安装 APK
echo   2. 首次使用需安装 Termux（F-Droid）
echo   3. 在 Termux 中运行安装命令
echo   4. 返回应用点击"启动 DSH"
echo ========================================
echo.
pause