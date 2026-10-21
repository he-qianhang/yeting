# 第三方源码与许可入口

`sources/` 保存 Maven Central 的 youtubedl-android 0.18.1 library、ffmpeg、common 封装层源码 JAR，来源、大小和 SHA-256 在 `sources/manifest.json`。它们不包含所有 FFmpeg、Python、QuickJS 原生对应源码；分发准备的缺口见 [APK 发布状态](../docs/BINARY_RELEASE.md)。

`bgutils/` 保存 bgutils-js4.0.3 的原 npm 分发、MIT 许可、来源提交与哈希。分发包含 JavaScript、类型与源码映射，原 TypeScript 对应上游提交 `f39a04173c6af1056c39a9a72c8f04bc86b5cfd2`。Android 构建使用已提交的网页脚本，不需要 Node.js。

`native-inventory.json` 是既有内部0.2.9 APK 的六项 arm64 原生文件哈希和提取出的 FFmpeg 配置，不包含原生二进制。它证明检查对象，不证明完整构建来源。

固定 yt-dlp Python zipapp 在 `android/app/src/main/res/raw/ytdlp`，来源及哈希在 `feasibility/pinned-extractor.json`。应用未启用提取器自动更新。

完整版权与许可入口见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
