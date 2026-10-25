# 构建与测试

## 工具链

保持已固定的工具链：JDK21、Gradle8.14.3、AGP8.13.0、Kotlin2.2.20、compileSdk36、targetSdk35、minSdk26、arm64-v8a。Gradle Wrapper 已固定版本及官方分发 SHA-256，不需要全局 Gradle。

安装 Android SDK 平台和构建工具：

```sh
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

将 `JAVA_HOME` 指向自己的 JDK21，将 `ANDROID_HOME` 指向自己的 SDK。也可在未跟踪的 `android/local.properties` 写入 `sdk.dir=/absolute/path/to/Android/sdk`，或让 Android Studio 自动生成。

从项目根目录执行：

```sh
./tools/build.sh -Pkotlin.compiler.execution.strategy=in-process \
  :app:testReleaseUnitTest :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

中文路径下若遇到 Kotlin daemon 路径编码问题，使用 UTF-8 locale 并保留上面的 in-process 参数。Windows 在 `android/` 中使用 `gradlew.bat` 执行同样任务。

从ZIP源码包恢复时，若解压工具没有保留执行权限，先运行 `chmod +x android/gradlew tools/build.sh`，再执行上述命令。无需下载维护者的签名文件。

debug 输出位于 `android/app/build/outputs/apk/debug/`，可安装到自己的隔离测试设备。没有本地签名配置时，release 输出 `android/app/build/outputs/apk/release/app-release-unsigned.apk`。

## 可选的本地 release 签名

创建自己的密钥，将 `android/release.properties.example` 复制为 `android/.signing/release.properties`，填写自己的四项属性。`storeFile` 相对于 `android/app/`；示例 `../.signing/release.jks` 指向 `android/.signing/release.jks`。

`.signing/`、密钥和密码都被 Git 忽略，不随源码发布。debug 始终使用 Android 默认调试签名。没有维护者密钥也能进行项目配置、单元测试、Lint 和构建。自行签名不能作为覆盖原签名安装的官方更新。

目前不发布预编译 APK；自行构建不等于已经完成再次分发的全部许可准备，见 [BINARY_RELEASE.md](BINARY_RELEASE.md)。

## 设备验收

以下38个公开设备选择器最初在0.2.9整理时验证；本次0.3.2同步仅编译该设备测试包，没有重跑这些方法，也未将内部65项设备检查导入公开套件。它们不覆盖新增平稳音量的完整验收。

只在全新的专用模拟器 `YeTingOpenSourceQA` 上运行公开设备测试。推荐 API36 / arm64 / 1080×2400 / 440dpi，保留系统动画默认值。测试会建立和删除合成条目、操作播放器和系统界面，不能用于日常手机或有收藏数据的模拟器。

```sh
export YETING_QA_DEVICE=emulator-5554 # 填写专用模拟器实际序列号
python3 tools/check-device.py
```

脚本先检查 Android 模拟器标记和 AVD 名称，再安装本次 debug 与测试包，运行明确选定的方法；不会搜索其他设备，不会清空应用数据，不会运行真实来源网络采集、旧版本升级或外部强停配对流程。需先完成上一节构建，并让 `adb` 位于 PATH，或设置 `ANDROID_HOME`。

## 合成测试音频

`android/app/src/androidTest/assets/six-seconds.m4a` 为公开整理时新生成的约6秒（6.02秒）、440Hz、单声道 AAC 测试信号，前后淡化；不含人声、音乐作品或平台音频。它让既有测试的6000ms定位点处于AAC时间线内部，避免精确6秒容器在Media3中向下取整为5999ms；原内部素材也略长于6秒。来源为 FFmpeg `lavfi sine`，生成命令完整保留：

```sh
sh tools/make-test-audio.sh
```

重新生成需要 FFmpeg，不是正常 Android 构建的前置条件。不同 FFmpeg/AAC 编码版本可能生成不同字节，测试验证播放行为，不要求跨版本编码字节一致。

## 可选的网页验证脚本构建

普通 Android 构建使用已提交的脚本，无需 Node.js。要重建：

```sh
cd tools/youtube-proof
npm ci --ignore-scripts
npm run build
```

依赖锁定为 bgutils-js4.0.3、esbuild0.25.12；生成文件的 SHA-256 应与 `third_party/bgutils/manifest.json` 一致。这只验证本地构建，不能证明 YouTube 实际线路可用。
