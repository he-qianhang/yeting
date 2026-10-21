# 第三方版权与许可

夜听原创代码以 GPL-3.0-only 发布。以下第三方组件、工具和素材保留其自身版权和许可；项目整体许可不替代这些原始声明。

| 组件 | 固定版本 / 来源 | 许可及资料 |
| --- | --- | --- |
| youtubedl-android library / ffmpeg / common | 0.18.1，[上游](https://github.com/yausername/youtubedl-android/tree/0.18.1) | GPLv3；许可全文在 `android/app/src/main/assets/licenses/youtubedl-android-GPL-3.0.txt`，封装源码与清单在 `third_party/sources/` |
| yt-dlp | 2026.08.19，[上游](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19) | Unlicense；原分发 Python zipapp 保存在 `android/app/src/main/res/raw/ytdlp`，可用 ZIP 工具查看其中源码；版本/哈希在 `feasibility/pinned-extractor.json`，原许可及上游第三方声明在 `assets/licenses/yt-dlp-2026.08.19-*` |
| bgutils-js | 4.0.3，[LuanRT/BgUtils](https://github.com/LuanRT/BgUtils) | MIT；原 npm 分发、许可、来源提交与哈希在 `third_party/bgutils/`，分发含源码映射 |
| esbuild | 0.25.12，[上游](https://github.com/evanw/esbuild/tree/v0.25.12) | MIT，仅可选网页脚本构建；锁文件在 `tools/youtube-proof/`，许可文本在 `third_party/licenses/esbuild-MIT.txt` |
| AndroidX / Media3 | 见 `android/app/build.gradle.kts`，[上游](https://android.googlesource.com/platform/frameworks/support/) | Apache-2.0；全文在 `android/app/src/main/assets/licenses/Apache-2.0.txt` |
| OkHttp | 4.12.0，[上游](https://github.com/square/okhttp/tree/parent-4.12.0) | Apache-2.0；保留上游及传递依赖的许可 |
| Gradle Wrapper | 8.14.3，[上游](https://github.com/gradle/gradle/tree/v8.14.3) | Apache-2.0；标准脚本与 Wrapper JAR 位于 `android/` |
| JUnit / Android 测试库 | 见 Gradle 测试依赖，[JUnit](https://github.com/junit-team/junit4) | JUnit4 为 EPL-1.0；仅测试使用，不进入主应用；AndroidX 测试库为 Apache-2.0 |
| Simple Icons | [来源清单](design/platform-icons/sources.json) | 图标轮廓为 CC0-1.0，全文在 `assets/licenses/simple-icons-CC0.txt`；平台商标权仍属各权利人 |

这里的 `assets/licenses/` 均指 `android/app/src/main/assets/licenses/`。Maven 传递依赖随构建解析，原生组件的特殊分发状态见 [BINARY_RELEASE.md](docs/BINARY_RELEASE.md)；不能把附带三个封装源码 JAR 解释为已提供全部原生对应源码。

原生 APK 文件清单在 `third_party/native-inventory.json`，仅保存从既有内部 APK 得到的哈希和构建配置，不包含原生二进制本身。原生源码与构建资料未核实完整前，不提供官方 APK 或 CI APK 下载。

夜听品牌图形由项目进行构图探索后整理为几何矢量，生产矢量在 `design/brand/` 和应用资源中；本仓库的原创矢量按项目许可发布。平台图标仅用于识别来源，不表示官方合作。合成测试信号的生成命令在 `tools/make-test-audio.sh`，不含平台媒体。
