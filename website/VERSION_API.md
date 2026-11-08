# LyricWave 版本 API 与附件反代接入 v1

基地址：`https://lyricwave.canz-monkey.cn`。接口部署本次代码后生效；本文不表示线上已经更新。无需鉴权，支持跨域公开读取，不发送 Cookie 或 API Key。所有时间为 ISO 8601 UTC，文本为 UTF-8。

## 1. 获取版本

`GET /api/v1/version?channel=latest`

- `latest`（默认）：最近发布的公开版本，包括预发布。
- `stable`：非预发布；`prerelease`：预发布。依据 GitHub 的 prerelease 标记，不能根据名称推断稳定性。
- 当前数据源：本项目最近 20 条 Releases 中，经验证后保留最新 6 个公开发布；选取发生在该窗口内。窗口内没有所需频道返回 404，不承诺返回任意历史稳定版本。
- GitHub 数据缓存约 30 分钟；API 浏览器缓存 60 秒、共享缓存 300 秒。更新并非实时，缓存层叠加还可能延迟。

成功响应结构（字段类型；以下不是实际版本声明）：

```ts
interface VersionResponse {
  schemaVersion: 1;
  repository: 'Laoch832/LyricWave';
  channel: 'latest' | 'stable' | 'prerelease';
  release: {
    id: number;
    tag: string;
    name: string;
    publishedAt: string;
    prerelease: boolean;
    notes: string; // 原始 Markdown，不应未经消毒作为 HTML 插入
    htmlUrl: string; // 官方 GitHub Release
    versionCode: null; // 没有发布级权威元数据，禁止从 tag 猜测 Android versionCode
    assets: Array<{
      id: number;
      name: string;
      size: number; // bytes
      kind: 'apk' | 'other'; // 只按扩展名分类，不代表签名或兼容性验证
      downloadUrl: string; // 相对路径 /api/v1/download/{id}
      upstreamUrl: string; // 官方 GitHub 下载地址，用作失败回退
    }>;
  };
}
```

技术部应按平台/文件名明确选择附件，不能无条件取 assets[0]。tag 作为不透明标识，不做字典序大小比较。当前不能用此 API 实现可靠的 Android 自动升级判定：应先补充每个 Release 的可信 versionCode、包名、证书和文件 SHA-256 清单；本接口不伪造这些字段。禁止自动安装，不绕过系统签名校验。

```js
const origin = 'https://lyricwave.canz-monkey.cn';
const response = await fetch(`${origin}/api/v1/version?channel=latest`);
if (!response.ok) throw new Error(`版本查询失败: ${response.status}`);
const data = await response.json();
if (data.schemaVersion !== 1) throw new Error('不支持的协议版本');
const apks = data.release.assets.filter(asset => asset.kind === 'apk');
// UI 展示附件名称、体积、预发布标记，让用户选择；不默认假设存在 APK。
const downloads = apks.map(asset => ({ ...asset, url: new URL(asset.downloadUrl, origin).href }));
```

## 2. 流式下载反代

`GET /api/v1/download/{assetId}`，也支持 `HEAD`、`OPTIONS`。

只接受 API 当前发布窗口内真实存在的附件 ID。不是开放代理：无 `url` 参数，不接受额外查询参数。历史版本或移出窗口的附件使用保存的 upstreamUrl 或官方 Releases。上游删除/替换文件时也可能失效。

- 响应为原始二进制流，不是 JSON/base64；Content-Disposition 含 UTF-8 文件名。
- 支持单区间 `Range: bytes=0-1048575`、`bytes=1048576-` 或 `bytes=-1024`。转发 If-Range；200/206/416 取决于上游，不保证上游一定支持断点。
- 只转发必要的下载响应头；不转发客户端 Cookie/Authorization，也不向附件源发送 GITHUB_TOKEN。
- GitHub 重定向只允许本项目 github.com 发布下载路径及精确的 release-assets.githubusercontent.com 域名，最多 5 次请求。源站策略改变时会安全失败。
- 代理总超时 55 秒；Vercel 函数 maxDuration 配置为 60 秒，实际能力以账号套餐和平台限制为准。大文件/慢速连接可能在返回 200 后中断，客户端必须检测完成度并提供直链重试。
- 仅无 Range 的完整 GET 200、Content-Length 与附件大小一致且无压缩/Content-Range 时，返回 `Cache-Control: public, max-age=0, s-maxage=86400, no-transform`，允许外层 CDN 缓存 24 小时。HEAD、206、416、错误及其他响应禁止缓存。Vercel 自身 CDN 设置 no-store；EdgeOne 需配置遵循源站 Cache-Control，首次回源仍经过 Vercel，不能保证免费额度或 GitHub 源站可用。删除附件后需同步清除 EdgeOne 缓存，避免继续提供缓存的已删除附件。

```bash
curl -fsS 'https://lyricwave.canz-monkey.cn/api/v1/version?channel=latest'
# 将 ASSET_ID 替换成上面 JSON 中的真实 id：
curl -I 'https://lyricwave.canz-monkey.cn/api/v1/download/ASSET_ID'
curl -f -H 'Range: bytes=0-1023' -o first.bin \
  'https://lyricwave.canz-monkey.cn/api/v1/download/ASSET_ID'
```

客户端反代失败时，展示“使用 GitHub 原始下载”并打开 upstreamUrl，不无期限自动重试。不要对下载超时启用多个并行自动重试。

## 3. 错误协议

错误体：`{ "schemaVersion": 1, "error": { "code": "…" }, "releasesUrl": "https://github.com/Laoch832/LyricWave/releases" }`。错误禁止缓存。HEAD 错误无可读响应体，以状态码处理。

| HTTP | code | 建议处理 |
| --- | --- | --- |
| 400 | INVALID_QUERY / INVALID_ASSET_ID / INVALID_RANGE | 修正请求，不重试 |
| 404 | NO_RELEASE / NO_RELEASE_IN_CHANNEL / ASSET_NOT_FOUND | 显示空状态或官方 Releases，不当成最新版本 |
| 503 | UPSTREAM_UNAVAILABLE | 保留上次成功信息并明确其时间；退回官方来源 |
| 502 | UNSAFE_UPSTREAM / INVALID_REDIRECT / UNSAFE_REDIRECT / TOO_MANY_REDIRECTS / DOWNLOAD_UNAVAILABLE | 下载失败，提供官方直链 |
| 416 | 上游 Range 响应（二进制/空响应） | 重新检查文件大小，必要时全量下载 |

其他方法返回框架的 405。CORS 允许公开 GET/HEAD/OPTIONS；不支持凭据请求。未知字段应忽略，不兼容升级使用新路径版本。

## 4. Vercel / EdgeOne 运维

保留 Vercel Root Directory `website`、包含根目录外文件、Node 24、`npm ci`、`npm run build`。可选服务端 GITHUB_TOKEN 用于公开 Releases API 读取；不得用 NEXT_PUBLIC 前缀暴露。无需额外必填环境变量。

EdgeOne 缓存规则调整（覆盖此前全 API 绕过规则）：

1. 高优先级规则匹配 `/api/v1/download/*`：遵循源站 Cache-Control，不强制缓存错误或所有状态码；缓存键保留完整路径和查询参数，不忽略附件 ID。确认规则优先级足以覆盖原有 `/api/v1/*` 绕过规则。
2. 开启套餐支持的 Range 回源/分片能力（若控制台提供），不改写 Range/If-Range。206/416 响应仍遵循 no-store，禁止作为完整文件缓存。保留 Vary、Content-Length、Content-Range、状态码和 CORS。
3. 其余 `/api/v1/*` 继续绕过 EdgeOne 缓存；下载路径不做 HTML/JS 挑战、响应压缩或内容转换。不要修改全站缓存规则。
4. 新版本用真实附件 ID 做一次完整 GET 预热，完整成功后再次 GET，检查 EdgeOne 控制台/响应中的命中状态。不同边缘节点可能分别冷启动。下载完整文件比较 SHA-256；若总回源不能在函数限制内完成，缓存无法解决首次回源失败。
5. 免费套餐的功能和流量额度以账号当前控制台为准，不能保证零费用或固定下载速度。此仓库更新不代表已修改 EdgeOne 控制台规则。


部署后验收：真实版本 JSON、频道空状态、APK HEAD、前 1 KiB Range、一次完整文件下载并与 GitHub 原件 SHA-256 比对、无效 ID、跨域请求及 GitHub 失败回退。单元测试的模拟上游不替代线上完整 APK 比对。下载页面的附件主入口使用本站反代，每个附件保留对应的 GitHub 原始下载回退链接，并提供版本 JSON 与本文入口。

## 5. Android 客户端接入

澜音设置首页与高级设置提供“检测更新”。客户端使用模块自己的 BuildConfig 版本及包名，不读取被注入的 Apple Music 版本。

v1 响应仍保留 `release.versionCode: null`。Android 客户端额外选择该 Release 的 `build-metadata.json` 附件，先读取本站反代，失败后仅回退一次到官方附件。仅当元数据包名、固定证书指纹、发布 tag 与 versionName 的关联、APK 文件名、SHA-256 格式和源码提交标识均有效时，使用其中的整数 versionCode 比较新旧。不能验证时只展示公开发布信息，不把不同 tag 自动认定为升级。

自动检测默认开启，可在设置中关闭。在已识别的 Apple Music 界面进入前台时触发后台查询；成功后 6 小时内不重复，失败后等待 15 分钟，手动检测可跳过间隔。一个进程最多一个检测任务，不随播放或切歌轮询。保留上次成功的公开 API/清单结果和查询时间，重启后仍重新校验缓存；失败不会覆盖旧结果。新版本只提示，不自动下载或安装。

用户选择已匹配构建清单的 APK 后，打开澜音自身的内建下载页面，再点击开始。页面仅接收附件 ID 并重新查询验证，不能由外部 Intent 注入 URL、哈希或安装文件。本站反代和 GitHub 原始来源都由内建下载器处理，发布页仍可在浏览器打开。

下载首先发送 `Range: bytes=0-0` 探测。文件不少于 4 MiB 时最多使用 4 个不重叠区间；严格校验每个 206 的 Content-Range、总大小、字节数和 ETag（若提供）。服务器直接返回 200 时消费该响应，不重复下载。分段失败时关闭并等待所有分段任务结束，再清空临时文件，以单流完整下载一次；不并行发起自动重试。反代最终失败后由用户选择原始来源，不无限切换或重试。

页面展示进度、当前连接数，支持取消和重试。屏幕旋转保留任务，关闭页面会取消下载并删除未完成文件。完成后校验完整文件大小和清单 SHA-256，再核对 APK 实际包名、版本号、版本名及固定签名证书；任何不符均禁止安装。文件保存在澜音私有缓存，只有经用户点击安装才通过限定 updates 目录的 FileProvider 授予系统安装器临时读取权限。首次需要用户授予澜音安装权限，返回后再次点安装，最终确认与签名校验仍由 Android 负责。
