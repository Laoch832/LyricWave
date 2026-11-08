# 云端构建与发布

开发仓库：[Laoch832/LyricWave](https://github.com/Laoch832/LyricWave)。

- [工作流与构建记录](https://github.com/Laoch832/LyricWave/actions/workflows/build.yml)
- [已发布安装包](https://github.com/Laoch832/LyricWave/releases)
- [签名与发布配置](https://github.com/Laoch832/LyricWave/settings/secrets/actions)

## 每次提交会做什么

`main`、`codex/**` 分支的提交、指向 `main` 的 PR，以及手动运行都会触发 CI。

1. 检查渲染源码、架构边界、精确宿主版本配置、Python 测试与工作流语法。
2. 使用 JDK 21、Gradle Wrapper、Android SDK 37.0 和 Build Tools 37.0.0，构建独立插件 SDK 与测试样例。
3. 执行所有 Gradle 单元测试、应用与库的 Lint，构建 Debug 与 unsigned Release APK。CI 限制最多 2 个 Gradle worker、3 GiB JVM 堆，避免并行打包耗尽默认堆内存。
4. 对本仓库由 Laoch832 发起的 `main` 或版本标签构建，使用固定密钥签名，核对源码提交、应用 ID、版本号、APK 校验和及证书指纹。
5. 上传 APK、SDK、插件样例和测试报告。即使测试失败，也尝试保留诊断报告。

PR 和普通开发分支没有签名步骤，也不会读取发布凭据。Debug APK 使用 `.debug` 应用 ID，主要用于开发验证；日常安装应使用 `LyricWave-signed` 里的 Release APK。

进入某次成功运行页面，在 **Artifacts** 中下载：

| 附件 | 内容 | 保留时间 |
| --- | --- | --- |
| LyricWave-signed | 可安装 APK、SHA256SUMS.txt、SIGNATURE.txt、源码与版本元数据、发布说明 | 30 天 |
| LyricWave-debug | Debug APK | 14 天 |
| LyricWave-unsigned | 未签名构建与来源元数据，供同次运行的签名步骤使用 | 14 天 |
| LyricWave-plugin-sdk-v1 | SDK JAR 与独立插件样例 | 30 天 |
| test-and-lint-reports | 单元测试与 Lint 报告 | 14 天 |

Release 附件不按这些 Artifact 时限自动清理。

## 发布新版本

先修改 `app/build.gradle.kts` 的 `versionName` 与递增的 `versionCode`，提交到新仓库，再选择一种方式：

- 在 Actions → **LyricWave CI** → **Run workflow** 中选择 `main`，勾选 `publish`；或
- 为该提交推送与应用版本完全一致的标签，例如应用版本 `1.6.8-custom.2` 对应 `v1.6.8-custom.2`。

普通 `main` 提交自动生成签名 Artifact，不会创建 Release。只有手动勾选发布或推送版本标签才会发布；包含 `-custom.*` 等后缀的版本标记为预发布，无后缀版本为正式发布。

所有检查通过后，发布步骤使用个人账号凭据验证 GitHub 用户名、用户 ID、账号类型和触发者，再创建草稿、上传附件、核对 Release 作者，最后公开发布。默认的 `GITHUB_TOKEN` 仅有读权限，不用于发布。系统拒绝覆盖已存在的版本；手动发布所产生的标签事件会识别已有 Release 并跳过重复构建；失败后遗留的草稿需要在确认原因后处理，不能通过重跑悄悄替换附件。

版本标签必须与 `versionName` 一致，构建与签名必须来自同一次工作流和同一完整提交 SHA。Release 附带的 `build-metadata.json` 可追溯应用 ID、版本号、源码提交、APK 哈希和签名指纹。

## 固定签名与个人身份

以下配置已保存在本仓库的 GitHub Actions Secrets / Variables 中：

| 配置 | 用途 |
| --- | --- |
| AMPP_RELEASE_KEYSTORE_BASE64 | 本分支固定签名密钥，Base64 编码；Base64 本身不是加密，由 GitHub Secret 保护 |
| AMPP_RELEASE_STORE_PASSWORD | 密钥库密码 |
| AMPP_RELEASE_KEY_ALIAS | 签名别名 |
| AMPP_RELEASE_KEY_PASSWORD | 签名私钥密码 |
| AMPP_PUBLISH_TOKEN | Laoch832 授权的个人 GitHub 凭据，用于创建与发布 Release |
| AMPP_SIGNING_CERT_SHA256（Variable） | 预期的公开证书指纹 |

预期证书 SHA-256：

```text
4ec2f441139585083e476b596a503db7e6c459ec1ca1d9bcf59d815b6981da53
```

私钥只在签名步骤的临时目录中展开，结束后删除，不进入仓库或构建附件。密钥已由 GitHub Secret 持久保存；另行保管自己的离线备份，避免删除 Secret 后失去升级签名。

原版签名与此分支不同。第一次迁移时先备份设置和自定义歌词，再卸载原版后安装；以后同签名版本可覆盖升级。更换签名密钥会破坏这一升级关系，不能为了修复构建随意重新生成密钥。

`host-netease` 与 Apple Music 适配分开编译，参与完整单元测试和 Lint；链接协议只允许 HTTPS 的 `music.apple.com` 地址。架构检查拒绝重新加入微信作用域或微信私有页面桥接。

`AMPP_PUBLISH_TOKEN` 是个人凭据。撤销登录授权、更改账号权限或令牌失效后，构建和签名仍可运行，发布会失败并停止；重新以 Laoch832 授权后更新该 Secret。不要把个人凭据放进 PR、代码、构建产物或日志。不会自动向上游创建 PR。

## 维护和故障定位

- 工作流步骤使用完整提交 SHA 固定 Action 版本；升级时人工核对上游版本和 SHA，避免机器人自动提交 PR。
- Gradle 下载校验和已固定，工作流检查器也校验固定版本与哈希。
- Gradle 仅由 `setup-gradle` 管理缓存；独立插件使用精确输入指纹缓存，没有宽泛的回退键。PR 只读 Gradle 缓存。
- 同一分支的新构建取消旧构建；版本标签与手动发布不会被后续提交自动取消。
- 失败时先查看具体任务日志及 `test-and-lint-reports`，修复后用新提交重跑。源码检查失败不会启动 Android 构建，测试失败不会签名或发布。
- `release` 环境限制从 `main` 或 `v*` 版本标签发布；发布脚本进一步核对精确版本和个人账号。
- 构建通过不能替代 LSPosed、Apple Music 及实体设备上的安装和播放验证。

## 改名后的兼容约定

对外名称为澜音（LyricWave），仓库与新发布附件使用 LyricWave。安装身份 `dev.amenhancer.module`、插件 API 的 Java 命名空间、既有存储路径和 `AMPP_*` 签名配置继续沿用，以便保留设置、翻译缓存、插件兼容性及覆盖升级关系。证书指纹保持不变。历史版本附件和上游来源记录保留当时名称。
