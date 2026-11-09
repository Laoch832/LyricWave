# LyricWave 版本 API 与附件反代接入 v1

基地址：`https://lyricwave.canz-monkey.cn`。接口部署本次代码后生效；本文不表示线上已经更新。无需鉴权，支持跨域公开读取，不发送 Cookie 或 API Key。所有时间为 ISO 8601 UTC，文本为 UTF-8。

## 1. 获取版本

`GET /api/v1/version?channel=latest`

- `latest`（默认）：最近发布的公开版本，包括预发布。
- `stable`：非预发布；`prerelease`：预发布。依据 GitHub 的 prerelease 标记，不能根据名称推断稳定性。
- 当前数据源：本项目最近 20 条 Releases 中，经验证后保留最新 6 个公开发布；选取发生在该窗口内。窗口内没有所需频道返回 404，不承诺返回任意历史稳定版本。
- GitHub 数据缓存约 60 秒；API 浏览器缓存 60 秒、共享缓存 300 秒。更新并非实时，缓存层叠加还可能延迟。

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

- 响应为原始二进制流，不是 JSON/base64；Content-Disposition 同时含普通 ASCII filename 与 UTF-8 filename*；APK 返回 application/vnd.android.package-archive。
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

保留 Vercel Root Directory `website`、包含根目录外文件、Node 24、`npm ci`、`npm run build`。可选服务端 GITHUB_TOKEN 用于公开 Releases API 读取；不得用 NEXT_PUBLIC 前缀暴露。版本与下载无需额外必填环境变量；反馈服务另需下述私有存储与管理员凭据。

EdgeOne 缓存规则调整（覆盖此前全 API 绕过规则）：

1. 高优先级规则匹配 `/api/v1/download/*`：遵循源站 Cache-Control，不强制缓存错误或所有状态码；缓存键保留完整路径和查询参数，不忽略附件 ID。确认规则优先级足以覆盖原有 `/api/v1/*` 绕过规则。
2. 开启套餐支持的 Range 回源/分片能力（若控制台提供），不改写 Range/If-Range。206/416 响应仍遵循 no-store，禁止作为完整文件缓存。保留 Vary、Content-Length、Content-Range、状态码和 CORS。
3. 其余 `/api/v1/*` 继续绕过 EdgeOne 缓存；下载路径不做 HTML/JS 挑战、响应压缩或内容转换。不要修改全站缓存规则。
4. 新版本用真实附件 ID 做一次完整 GET 预热，完整成功后再次 GET，检查 EdgeOne 控制台/响应中的命中状态。不同边缘节点可能分别冷启动。下载完整文件比较 SHA-256；若总回源不能在函数限制内完成，缓存无法解决首次回源失败。
5. 免费套餐的功能和流量额度以账号当前控制台为准，不能保证零费用或固定下载速度。此仓库更新不代表已修改 EdgeOne 控制台规则。


部署后验收：真实版本 JSON、频道空状态、APK HEAD、前 1 KiB Range、一次完整文件下载并与 GitHub 原件 SHA-256 比对、无效 ID、跨域请求及 GitHub 失败回退。单元测试的模拟上游不替代线上完整 APK 比对。下载页面的附件主入口使用本站反代，每个附件保留对应的 GitHub 原始下载回退链接，并提供版本 JSON 与本文入口。

## 5. Android 客户端接入

正式客户端优先 GET `/api/v1/client-update`，只提供正式版本；旧协议回退明确指定 `channel=stable`。响应含 `clientSchemaVersion: 1`、`channel: stable` 和 `release`：`build` 是该发布的完整构建元数据，`summary`、`changes[{type,title,description}]`、`notices` 是本版本专有说明，`assets` 仅含匹配的 APK。云端比对签名构建的 `client-update.json` 和 `build-metadata.json`，验证包名、证书、提交、版本和 APK 哈希，拒绝不一致配置。源文件为仓库 `release-notes/x.y.z.json`，签名工作流将实际构建字段写入附件，不手工编造 versionCode。

客户端使用模块 BuildConfig，按整数 versionCode 比较。当前版本等于或高于云端均显示“已是最新版本”，不展示旧更新说明或降级按钮；仅真正更新的版本展示摘要、改动、下载。请求失败显示失败，不能当作最新。默认自动检测，在宿主回前台后查询，成功间隔 6 小时、失败 15 分钟，手动查询可跳过间隔；不随切歌轮询。新版本只提示，不自动下载或安装。

分享和下载面板留在 Apple Music 内，避免为启动功能跳转模块 Activity。经验证的宿主通过受限 Provider 的一次性票据启动模块私有前台服务；只提交附件 ID 和连接数，不接受外部下载地址、哈希或文件路径。下载线程可选 1、4、8、16、32、64、128，默认 8 并记住选择；实际并行数受文件大小和服务器 Range 支持限制，更多线程不保证更快。

下载先探测 Range，严格验证分段范围、总长、字节数及 ETag；分段失败后等待线程退出、清理文件，再单流降级一次。关闭面板继续后台下载，通知可返回 Apple Music；支持取消与用户主动选择 GitHub 原始来源。Android 16 使用标准 ProgressStyle 并请求实时／焦点展示，受系统权限和厂商支持限制；其他系统显示持续进度通知。不会伪造已获得焦点展示权限。

下载完成验证完整 SHA-256、APK 实际包名、版本和固定证书；安装前再次验证。用户点击安装才将私有缓存文件临时授予系统安装器。安装权限、系统安装确认仍由 Android 负责。

1.7.3 起，模块控制 Provider 捕获实际来电 UID 并校验包名/签名，模块业务使用清除后的 Binder 身份，最终恢复来电身份；不得把宿主身份用于模块 PendingIntent 或通知查询，也不得用清除后的 UID 代替实际 caller 做授权。状态增加 module_version_code/module_version_name，用于区分安装版本与宿主中仍加载的旧模块代码。客户端优先按实际安装 code 比较更新，并重新比较缓存，不把已安装的同版再次提示为更新；提示重开宿主不等于已自动重载 Hook。

下载面板显示近期吞吐与剩余秒数，停滞、重试后旧估计作废。服务异常有独立提示，不被普通状态轮询覆盖；备用内建下载入口只在用户点击后进入模块 Activity，并保留线程选择，仍需要用户主动开始下载和安装，不启动浏览器下载器。普通内嵌和后台通知流程保持可用，不保证厂商通知权限或跨应用启动提示可被消除。

## 6. 浏览器下载文件名

推荐路径 `/api/v1/download/{assetId}/{fileName}`，例如尾段 `LyricWave-1.7.0.apk`；文件名必须精确匹配真实附件，旧 ID 路径保留兼容。响应同时提供 APK MIME、普通 filename 和 RFC5987 filename*；网站链接设置 download 文件名，兼容不识别 filename* 的浏览器。JSON 校验清单仍是 JSON，下载页把它们收进“校验与构建信息”，不作为安装包主按钮。外层 CDN 应保留这些响应头；旧缓存须清理。

## 7. 私有错误反馈

`POST /api/v1/feedback` 接收 JSON：schemaVersion=1、UUID batchId、最多 20 条 records，整体最多 64 KiB。每条只允许固定类别、模块/宿主版本、SDK、整数错误码、异常类名和栈类名/方法/行号；拒绝任意 message、URL、歌词、API Key 或请求正文。成功返回 202 和同一 batchId，客户端收到后才标记发送成功；失败保留队列并退避。默认开启，可在高级设置关闭、手动发送、查看或清空本地记录。私有本地历史最多 200 条／512 KiB。

服务器按自身时间只接受过去30天至未来一天的记录；1.7.2 起客户端按设备时间预筛选，时间超限的日志暂缓上传并保留在本地，不让旧记录阻塞新批次。系统时钟偏差仍可能导致服务端拒绝，失败不算已上报。匹配确认后持久保存最近一次回执，可在反馈设置主动复制：schemaVersion=1、batchId、acknowledgedAt（设备收到确认的毫秒时间）、records（本批数量）、appVersion（发送端模块版本）。回执无诊断正文或凭据，仅用于关联已接收批次；不是服务端故障已处理的证明。旧客户端已有成功时间但没有回执时，须下次成功发送才出现复制按钮。

服务器使用私有 Vercel Blob，必须配置服务端 BLOB_READ_WRITE_TOKEN；管理读取须 FEEDBACK_ADMIN_TOKEN，均禁止 NEXT_PUBLIC 前缀。GET 同路径仅接受 Bearer 管理凭据，支持 date=YYYY-MM-DD（UTC）与 cursor 分页；未授权返回 401。公开浏览器不可读取日志。每日维护查 UTC 当天与前一天，按记录 ID 去重，只提交匿名汇总。服务含批次幂等与每来源分钟限流；当前不承诺服务端自动保留期限，需维护存储配额。反馈接口与鉴权读取禁止 CDN 缓存。
