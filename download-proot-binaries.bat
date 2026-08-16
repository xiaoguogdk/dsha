@echo off
chcp 65001 >nul
echo ===============================================
echo  下载 proot 二进制文件
echo  来源: DSHA (github.com/qiannianhuanxiang/DSHA)
echo ===============================================
echo.

set "JNIDIR=app\src\main\jniLibs\arm64-v8a"
if not exist "%JNIDIR%" mkdir "%JNIDIR%"

set "BASE=https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/main/app/src/main/jniLibs/arm64-v8a"

echo [1/5] 下载 libproot.so...
curl -fSL "%BASE%/libproot.so" -o "%JNIDIR%\libproot.so"
if %ERRORLEVEL% neq 0 (
    echo   ✗ 下载失败，检查网络连接
    pause
    exit /b 1
)
echo   ✓ libproot.so (268KB)

echo [2/5] 下载 libprootloader.so...
curl -fSL "%BASE%/libprootloader.so" -o "%JNIDIR%\libprootloader.so"
echo   ✓ libprootloader.so (67KB)

echo [3/5] 下载 libprootloader32.so...
curl -fSL "%BASE%/libprootloader32.so" -o "%JNIDIR%\libprootloader32.so"
echo   ✓ libprootloader32.so (67KB)

echo [4/5] 下载 libtalloc.so...
curl -fSL "%BASE%/libtalloc.so" -o "%JNIDIR%\libtalloc.so"
echo   ✓ libtalloc.so (31KB)

echo [5/5] 下载 libandroidshmem.so...
curl -fSL "%BASE%/libandroidshmem.so" -o "%JNIDIR%\libandroidshmem.so"
echo   ✓ libandroidshmem.so (14KB)

echo.
echo ===============================================
echo  全部下载完成！
echo  文件位置: %JNIDIR%
echo.
echo  现在可以用 Android Studio 打开项目构建 APK 了
echo ===============================================
pause