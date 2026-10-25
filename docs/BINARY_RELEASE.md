# APK 发布状态

2026-10-26：公开源码更新到0.3.2，继续仅发布源码，暂不提供官方APK，也不在GitHub Actions上传APK构件。依赖版本未变，本次重新核对0.3.2构建的六项原生文件，哈希仍与下方原生清单一致；未借此次源码更新认定原有对应源码缺口已经解决。

## 已知事实

本项目直接使用 youtubedl-android `library`、`ffmpeg` 0.18.1，间接使用 `common` 同版本。固定的 Maven 构件哈希在 [引擎清单](../feasibility/android-engine-audit.json)，Android 封装源码在 [third_party/sources/](../third_party/sources/)。

对应 APK 包含 `libffmpeg.so`、`libffmpeg.zip.so`、`libffprobe.so`、`libpython.so`、`libpython.zip.so` 和 `libqjs.so` 六项 arm64 文件。三个 sources.jar 是 Android 封装层源码，不能证明原生负载的对应源码和构建资料已经完整。

本轮直接检查原0.2.9 APK，确认 FFmpeg 配置包含 `--enable-gpl`、`--enable-version3`、`--enable-shared`，并核对六个原生文件哈希；结果在 [原生清单](../third_party/native-inventory.json)。这里的二进制哈希和配置来自本项目实际文件，不把候选构建配方当作已证实的生产记录。

上游已有相关讨论：[问题358](https://github.com/yausername/youtubedl-android/issues/358)、[问题363](https://github.com/yausername/youtubedl-android/issues/363)。检查时，后者的回复明确表示新的运行时重建工作不能确立现有0.18.1的来源关系。上游通用 [FFmpeg](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) / [Python](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md) 构建文档没有解决这一对应关系。

## 发布 APK 前的待办

- 核对实际原生组件及相关库的版本、适用许可、版权声明和对应源码。
- 补齐相关源码、适用补丁、构建与归档说明，以及稳定可获取的下载入口。
- 把这些资料与计划分发的实际 APK 绑定，并核对必要的法律声明入口。

这是本项目尚未完成的分发准备记录，不对上游作违规结论。当前没有为解锁 APK 下载而擅自升级依赖、替换原生组件或修改播放功能；后续若需要此类变更，应另行验证和编号。

源码本身以 GPL-3.0-only 公开，第三方文件保持其原许可。开发者可以自行构建研究；准备再分发编译后的安装包时，也需要核对所含组件的适用条件。
