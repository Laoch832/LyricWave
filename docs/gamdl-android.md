# LyricWave · Gamdl Android 内嵌下载

> 开发状态：实验性分支。**在实际 Android ARM64 APK 构建、账户授权及真机下载验收前，不得合并或发布稳定版。**

## 代码来源与版本

- Gamdl：[\`glomatico/gamdl\`](https://github.com/glomatico/gamdl)，v4.1.1，源码提交 \`3da517e32a1a576a4c774f8c2a0ac7db4c42fc7b\`，MIT。
- Python 运行时：Chaquopy 17.2.0（Python 3.13，Android ARM64），MIT。
- DRM 依赖：\`pywidevine==1.9.0\`。此项目 **没有** 从 Apple Music 宿主进程提取账号、DRM key、离线缓存或私有播放器 URL，也没有把 Apple Music APK 装入下载模块。
- Wrapper v2 不属于当前 Android APK 实现；这条路线依赖内嵌 Gamdl 的 Widevine 后端。

## 使用入口

Apple Music → 澜音模块设置 → 高级设置 → **下载当前歌曲**。

点击后打开澜音进程的下载页，可选择一次音质；上次选择的音质会保留。也可粘贴当前 Apple Music 歌曲/MV 链接或纯数字目录 ID。MV 使用对应 **music-video** ID，不能将任意歌曲 ID 当作 MV ID。

歌曲输出 \`.m4a\`，预设包括 AAC、HE-AAC、AAC Web、ALAC、ALAC Lossless、ALAC Hi-Res、Dolby Atmos 和 AC-3。MV 输出 \`.mp4\`，预设包括 H.264 720p/1080p、H.265 1080p/2160p。预设是“请求的目标品质”，不能用它推定该资源实际有对应码流或许可证。

首次使用需要使用者提供本人有权限使用的 Apple Music 网页端 \`media-user-token\`；Apple Music 原生 App 已登录不等于当前模块已获得 Gamdl 网页端令牌。令牌仅在澜音自身 UID 中经 Android Keystore AES-GCM 加密保存，绝不通过 Xposed、Intent 或日志传给 Apple Music 宿主进程。

成功文件由模块进程发布至 Android MediaStore：

- 音频：\`Music/LyricWave/\`
- MV：\`Movies/LyricWave/\`

下载期间可离开选择页，前台服务提供任务通知。取消和失败不得保留未完成的公开文件。

## 本地构建

需要 JDK 21、Android SDK 37、Android NDK 28.2、Rust、Python 3.13。目标 ABI 目前限定 Android ARM64。

\`\`\`bash
bash scripts/build-gamdl-android.sh
./gradlew test :app:lintDebug :app:assembleDebug
\`\`\`

\`build-gamdl-android.sh\` 使用固定 GitHub SHA 获取 Gamdl 4.1.1，Maturin 将 PyO3 扩展交叉编译成 Android ARM64 wheel，随后 Gradle/Chaquopy 将其和 Python 依赖打包进 APK。普通 Linux wheel 不可直接用于 Android。构建脚本未运行成功前，Gradle 应报错而不是静默生成“可下载”的假 APK。

CI 的 Android job 也按同样顺序运行完整源检查、测试、Lint 和 APK 构建。

## 安全与行为约束

1. 严格使用当前歌曲的 Apple Music catalog ID；对 MV、非当前歌曲链接需独立验证对应 ID。
2. 不复制、不上传、不记录用户的 token/cookies/DRM challenge/license/签名媒体 URL。
3. 不改变 Apple Music 原本的播放队列、离线文件、账号状态和 DRM 逻辑。
4. 受保护媒体能否访问由账户、地区、DRM 安全等级和服务端许可证决定；Android Widevine L3 的存在不能保证无损、Atmos 或 4K MV。
5. 下载器按用户明确选择的 codec 请求，不得静默以低音质替代、再错误标为 Hi-Res。
6. 不应把成功编译、音质预设显示或 SDK 返回流信息当作真实成功下载的证据。

## 发布验收清单

- [ ] CI 成功构建 Android ARM64 Gamdl wheel，验证 ABI/架构/依赖；无未声明二进制依赖。
- [ ] \`./gradlew test :app:lintDebug :app:assembleDebug\` 和源检查全部通过。
- [ ] 真机 Android ARM64：模块独立进程成功初始化 Chaquopy，无 Apple Music 宿主崩溃。
- [ ] 当前歌曲 ID 精确；切歌、重新打开设置、旋转/后台保持身份正确。
- [ ] 账号有效/无效/过期时状态准确，不泄露令牌。
- [ ] AAC 成功保存与播放的完整 M4A 文件，经独立工具确认音频编码及时长。
- [ ] ALAC/Atmos 在实际授权满足时验证编码；不满足时明确失败且不虚构成功。
- [ ] MV 实际 MP4 的音画同步、编码、分辨率及最终播放验证。
- [ ] 下载取消、网络断开、空间不足、进程杀死和文件重名时无残缺公开文件。
- [ ] 账号凭据清除、无日志泄漏、稳定签名/原设置兼容。
- [ ] 使用者确认实际版权授权及适用服务条款，才对相关内容进行文件导出。

未满足这些项目时 PR 维持 Draft，不合并、不生成稳定 Release。
