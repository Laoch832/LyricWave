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
- 所有下载响应 `Cache-Control: no-store, no-transform`，包括 Range；流量经过 Vercel，可能产生带宽/函数费用。EdgeOne 不能消除回源成本，也不能保证 GitHub 源站可用。

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

EdgeOne 对 `/api/v1/*` 设置绕过缓存，不做 HTML/JS 挑战或响应转换（原生客户端无法完成浏览器挑战）。保留 Range/If-Range、查询参数、状态码、流式响应及 CORS 头；不要缓存 206、错误或重定向签名地址。首次上线保持源站缓存头，不添加全站“缓存所有内容”。结合套餐设置下载路径速率限制，避免误伤断点续传；先观察流量费用再决定独立对象存储方案。

部署后验收：真实版本 JSON、频道空状态、APK HEAD、前 1 KiB Range、一次完整文件下载并与 GitHub 原件 SHA-256 比对、无效 ID、跨域请求及 GitHub 失败回退。单元测试的模拟上游不替代线上完整 APK 比对。原有下载页面仍使用 GitHub 直链，技术部可依据本文单独接入代理。
