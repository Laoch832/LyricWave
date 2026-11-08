<a id="top"></a>

# 澜音 · LyricWave

由 [Laoch832](https://github.com/Laoch832) 维护的 Apple Music Android 增强项目，重点完善 AI 歌词翻译、缓存复用与原歌词更新校验，并保留歌词定制、平板播放器和液态玻璃等功能。

[下载安装包](https://github.com/Laoch832/LyricWave/releases) · [云端构建](https://github.com/Laoch832/LyricWave/actions/workflows/build.yml) · [反馈问题](https://github.com/Laoch832/LyricWave/issues) · [文档目录](docs/README.md) · [English](README.en.md)

[![CI](https://github.com/Laoch832/LyricWave/actions/workflows/build.yml/badge.svg)](https://github.com/Laoch832/LyricWave/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/Laoch832/LyricWave)](LICENSE)

## 项目简介

本项目在 Apple Music 内提供增强设置和歌词功能，由 Laoch832 维护开发、问题反馈和版本发布。安装后，在 Apple Music → 设置 → **澜音模块设置** 中管理功能。

当前重点：

- AI 译文通过 TTML 校验后自动保存，保存不依赖播放器指针继续存活；再次播放或重启后优先复用。
- 对比当前选中的原生、在线或导入歌词；文字、时间轴等变化后使旧译文失效，并在符合条件时重新翻译。
- 对过期请求、切歌、翻译失败和任务提交失败进行处理。
- 使用云端测试、固定签名和个人账号发布流程交付安装包。

## 致谢与来源

感谢 **[Zennmn（Zen）](https://github.com/Zennmn)** 创建 [AM++](https://github.com/Zennmn/AM-plus-plus)，也感谢上游贡献者为歌词、宿主适配、播放器和设置功能建立的基础。本项目在此基础上继续开发，保留原有贡献记录与版权声明。

原作者的感谢说明、赞赏入口，以及其他开源项目的贡献，统一放在 **[致谢与项目来源](docs/ACKNOWLEDGMENTS.md)** 中。第三方许可与具体改编来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 功能

| 功能 | 说明 |
| --- | --- |
| 播放中断恢复 | 确认网络播放源错误后尝试一次同曲重准备，保留队列和进度；不对正常缓冲或账号错误循环重试。 |
| HDR 歌词 | 100%–800% 亮度比例与震爆弹模式；真正 HDR 需 Android 14+ 和 HDR 屏幕，峰值由系统控制。 |
| 朋友圈链接分享 | 通过网易云原生分享面板分享可点击的 Apple Music URL；只勾选 Apple Music 与网易云，不 Hook 微信。 |
| AI 歌词翻译 | 使用自己的 OpenAI 兼容接口，将符合条件的当前选中的外语歌词翻译为简体中文。 |
| 自动翻译缓存 | 译文通过 TTML 校验后自动保存，重播和重启后优先复用。 |
| 原歌词更新校验 | 当前来源完整原歌词 TTML 的 SHA-256 变化后，使旧译文失效并重新翻译。 |
| 自定义歌词 | 导入、编辑、搜索、删除 TTML，支持在线获取和 ZIP 备份恢复。 |
| 在线歌词补全 | 为缺词、非逐字或缺翻译的歌曲查找在线歌词。 |
| 歌词字体与效果 | 自定义 TTF/OTF 字体、双向歌词模糊和 CJK 长尾歌词动画。 |
| 平板双栏播放器 | 横屏并排显示播放控件和实时歌词。 |
| 液态玻璃 | 导航栏、迷你播放器和手机底栏收缩动画。 |
| 歌曲名显示修正 | 按歌曲原地区、中国大陆或日本显示歌曲信息。 |
| ZIP 插件 | 导入独立插件扩展功能，见 [插件开发手册](docs/plugin-development.md)。 |

### AI 翻译与缓存规则

需要启用自定义歌词和 AI 歌词翻译，并配置自己的接口和模型。在线歌词补全可独立选择。已有翻译、中文歌词、没有逐行或逐字时间轴的歌词会保留原样。

- 缓存保留最近使用的 100 首，与手动导入的歌词分开保存。
- 更新检测以 Apple Music 本次提供的原歌词为准，不单独轮询远端服务器。
- 接口、模型或有效思考配置变化后重新翻译；只更换 API Key 不影响缓存。
- 缓存不保存 API Key；失败或取消时保留原歌词，下次播放可重试。

## 下载与安装

需要 Android 8.0+、受支持版本的 Apple Music，以及支持 libxposed API 102 的 LSPosed 或兼容框架。具体宿主版本以 [版本配置目录](host-applemusic/src/main/resources/host-profiles/) 为准。

1. 从 [本项目 Releases](https://github.com/Laoch832/LyricWave/releases) 下载 APK。
2. 在 LSPosed 中启用模块，作用域选择 Apple Music（`com.apple.android.music`）；若使用朋友圈链接分享，额外勾选网易云音乐（`com.netease.cloudmusic`），不勾选微信。
3. 强制停止并重新打开 Apple Music。
4. 打开 Apple Music → 设置 → **澜音模块设置**。

本项目使用独立固定签名。从上游原版迁移时，请先备份设置和自定义歌词，再卸载原版后安装。本分支同签名版本可覆盖升级。

## 使用

HDR 与朋友圈链接分享的入口、作用域及兼容边界见 [HDR 与链接分享说明](docs/hdr-and-moments.md)。

### AI 歌词翻译

开启自定义歌词和 AI 翻译，填写接口地址、模型和需要的 API Key。当前选中的原生、在线或导入歌词都可补充翻译并自动缓存。

在 **AI 歌词翻译与缓存 → API 调用与翻译缓存** 中，可直接翻译当前或已配置歌曲、查看缓存译文预览、真实 API 请求记录、成功/失败与 Token 统计。操作和统计口径见 [AI 翻译与缓存说明](docs/ai-lyrics.md)。

### 自定义歌词

打开自定义歌词并启用替换，读取当前歌曲 ID，粘贴或导入 TTML，也可从 AMLL、AM-Lyrics 或 Lunabeat 获取。保存并启用后，按设置提示重启 Apple Music。

歌词支持搜索、编辑、删除和 ZIP 备份恢复。手动编写歌词可参考 [TTML 格式说明](docs/apple-music-ttml-format.md)。

### 字体、播放器与玻璃效果

在对应设置中导入字体、启用平板双栏、调节歌词模糊或开启液态玻璃。部分改动需要强制停止并重开 Apple Music 后生效；底栏动画等实时设置按界面提示使用。

## 云端构建与发布

普通提交和 PR 自动运行源码检查、单元测试、Lint 和 APK 构建。本仓库可信的主分支构建还会生成固定签名安装包。

发布新版本时递增 `versionName` 和 `versionCode`，再推送对应版本标签，或手动运行 CI 并勾选 `publish`。发布流程核实个人账号 **Laoch832**，检查版本、来源提交、APK 哈希和证书，拒绝覆盖已有版本。

构建产物、测试报告和操作步骤见 **[云端构建与发布说明](docs/ci-and-releases.md)**。

## 从源码构建

环境：JDK 21、Android SDK Platform 37.0、Build Tools 37.0.0，以及项目自带的 Gradle Wrapper。

```bash
chmod +x gradlew
python3 scripts/build-plugin-example.py
./gradlew test :app:lintDebug :app:lintVitalRelease :app:assembleDebug :app:assembleRelease -PpluginFixture="$PWD/build/plugin-example/basic-plugin.zip"
```

Windows 使用 `gradlew.bat`，并按本机路径传入插件样例。未配置签名时，Release 产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`。本地签名可参考 `keystore.properties.example`；正式发布使用仓库中已配置的固定签名流程。

## 项目结构与文档

| 目录 | 内容 |
| --- | --- |
| app | 模块入口、设置界面与功能装配 |
| core | 配置、歌词、翻译、缓存与共享策略 |
| host-api / hook-runtime | 宿主能力接口与 Xposed 运行封装 |
| host-applemusic | Apple Music 精确版本适配 |
| glass / backdrop | 玻璃组件与渲染，来源声明见致谢文档 |
| plugin-api / plugin-runtime | 插件 SDK 与运行管理 |
| docs / scripts | 使用、维护、构建和验证工具 |

更多内容见 [文档目录](docs/README.md)。

## 反馈与贡献

请在 [本项目 Issues](https://github.com/Laoch832/LyricWave/issues) 反馈问题，并附设备、Android 与 Apple Music 版本、复现步骤和相关截图。涉及 AI 翻译时提供接口类型和错误现象，不要提交 API Key。

代码改动需通过相应测试与构建。本分支先积累、验证改动，再考虑向上游提交 PR；发布和未来 PR 使用维护者本人授权的账号。

## 隐私与许可证

声明 `INTERNET` 权限用于歌词获取、AI 翻译和歌曲信息查询，并声明朋友圈链接桥接的本地签名权限。文件通过系统选择器导入，设置、歌词和字体保存在 Apple Music 私有目录；没有独立启动图标或分析服务。

使用 [GNU GPL v3.0](LICENSE) 许可证。原作者与第三方版权、许可和来源声明见 [致谢](docs/ACKNOWLEDGMENTS.md) 与 [第三方声明](THIRD_PARTY_NOTICES.md)。
