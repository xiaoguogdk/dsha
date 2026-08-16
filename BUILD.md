# 构建 DeepSeek Harness Android APK

本指南介绍如何构建 `DeepSeekHarnessAndroid` 的 APK，支持两种方式：**本地（Android Studio）** 和 **GitHub Actions（CI）**。

## 项目简介

这是一个将 DeepSeek Harness 打包进 Android 应用的工程，通过 proot + Ubuntu rootfs 在 Android 上运行。关键信息：

- **Min SDK 26 / Target SDK 34**，仅支持 **arm64-v8a**
- **Gradle 8.2**（见 `gradle/wrapper/gradle-wrapper.properties`）
- **AGP 8.1.0**，**Java 17**
- 原生库位于 `app/src/main/jniLibs/arm64-v8a/`（`libproot.so`、`libprootloader.so` 等）
- `assets/install.sh` 用于 Ubuntu rootfs 安装

---

## 方式一：本地构建（Android Studio）

### 前置条件

| 依赖 | 版本 |
| --- | --- |
| JDK | 17（Oracle / Temurin 均可） |
| Android Studio | Hedgehog 或更新版本 |
| Android SDK | Platform 34、Build-Tools 34.0.0、NDK 26.1.10909125 |
| Gradle | 8.2（由 wrapper 自动下载） |

### 步骤

1. **下载 proot 二进制文件**

   原生库来自 DSHA 仓库（`github.com/qiannianhuanxiang/DSHA`）。在项目根目录双击运行：

   ```
   download-proot-binaries.bat
   ```

   或手动执行（Windows PowerShell / CMD）：

   ```bat
   set BASE=https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/main/app/src/main/jniLibs/arm64-v8a
   set JNIDIR=app\src\main\jniLibs\arm64-v8a
   mkdir %JNIDIR%
   curl -fSL %BASE%/libproot.so -o %JNIDIR%\libproot.so
   curl -fSL %BASE%/libprootloader.so -o %JNIDIR%\libprootloader.so
   curl -fSL %BASE%/libprootloader32.so -o %JNIDIR%\libprootloader32.so
   curl -fSL %BASE%/libtalloc.so -o %JNIDIR%\libtalloc.so
   curl -fSL %BASE%/libandroidshmem.so -o %JNIDIR%\libandroidshmem.so
   ```

   确认 `app/src/main/jniLibs/arm64-v8a/` 下已存在上述 5 个 `.so` 文件。

2. **用 Android Studio 打开项目**

   - 启动 Android Studio → **Open** → 选择 `DeepSeekHarnessAndroid` 目录。
   - 首次打开会自动下载 Gradle 8.2 与依赖，请保持网络畅通。

3. **配置 SDK / NDK**

   - **File → Project Structure → SDK Location**，确认 SDK 路径正确。
   - 通过 **SDK Manager** 安装：
     - `Android SDK Platform 34`
     - `Android SDK Build-Tools 34.0.0`
     - `NDK (Side by side) 26.1.10909125`
     - `Android SDK Command-line Tools`

4. **构建 Debug APK**

   - 菜单栏 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
   - 或使用右侧 **Gradle 面板 → app → Tasks → build → assembleDebug**。

5. **产物位置**

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

   构建完成后 Android Studio 会弹出提示，点击 **locate** 即可定位 APK。

### 命令行构建（可选）

在项目根目录执行：

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

---

## 方式二：GitHub Actions（CI）

项目已内置工作流文件 `.github/workflows/build.yml`，推送到 `main` 分支或发起 Pull Request 时自动触发构建。

### 触发条件

- `push` 到 `main` 分支
- 任何 `pull_request`

### 工作流内容

1. 检出代码（`actions/checkout@v4`）
2. 安装 **Oracle JDK 17**（`actions/setup-java@v4`）
3. 安装 **Android SDK 34** 组件：
   - `platforms;android-34`
   - `build-tools;34.0.0`
   - `ndk;26.1.10909125`
   - `cmdline-tools`
4. 从 DSHA 仓库下载 proot 二进制到 `app/src/main/jniLibs/arm64-v8a/`
5. 执行 `./gradlew assembleDebug` 构建 Debug APK
6. 上传 APK 为构建产物（`actions/upload-artifact@v4`）

### 使用步骤

1. 将项目推送到 GitHub 仓库（确保 `gradlew`、`gradlew.bat`、`gradle/wrapper/` 均已提交）。
2. 打开仓库 **Actions** 页面，选择 **Build Android APK** 工作流。
3. 等待构建完成（首次构建需下载依赖，约 5–10 分钟）。
4. 构建成功后，在对应运行记录页面底部 **Artifacts** 区域下载 `app-debug-apk`，解压即得 `app-debug.apk`。

---

## 常见问题

| 问题 | 解决方法 |
| --- | --- |
| 找不到 `libproot.so` 等文件 | 先运行 `download-proot-binaries.bat` 下载原生库 |
| SDK 版本不匹配 | 确认安装了 Platform 34 与 Build-Tools 34.0.0 |
| NDK 报错 | 安装 NDK 26.1.10909125（Side by side） |
| Gradle 下载慢 | 使用代理或镜像，或确认网络可访问 `services.gradle.org` |
| 构建产物为空 | 检查 `app/build/outputs/apk/debug/app-debug.apk` 路径是否正确 |

---

## 产物说明

- **Debug APK**：`app/build/outputs/apk/debug/app-debug.apk`
- 该 APK 仅支持 **arm64-v8a** 架构设备。