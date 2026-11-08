# 搜索与 AI 检索维护

公开站点固定为 `https://lyricwave.canz-monkey.cn`。生产 canonical、OG、robots 和 sitemap 使用 `src/lib/site.ts` 的 `publicSiteOrigin`，不使用回源地址或请求 Host。修改公开域名时同步维护此常量、public/llms.txt、indexnow.json；IndexNow key 文件也需部署到新域名。开发环境仍使用 NEXT_PUBLIC_SITE_URL。

## 自动可提交部分

indexnow.json 和 public 下同名 txt 文件是公开的域名验证材料，不是账号密码。上线后在 website/ 运行：

```bash
NODE_USE_ENV_PROXY=1 node scripts/submit-indexnow.mjs           # 检查及列出，不提交
NODE_USE_ENV_PROXY=1 node scripts/submit-indexnow.mjs --submit  # 通知 sitemap 中的同域网页
```

先验证线上 key 文件与 sitemap 域名。只在真实内容更新后提交，不反复轰炸。结果保存在忽略目录 artifacts/seo/indexnow-submission.json。200 表示请求处理成功，202 表示收到但 key 验证尚在进行；均不等于已收录、排名提升或 Google 提交成功。参与引擎自行决定抓取与收录。

## 需要账号验证的部分

Google Search Console 中添加 URL 前缀资源 `https://lyricwave.canz-monkey.cn/`（或 DNS 验证域资源），完成所有权验证，再提交 `https://lyricwave.canz-monkey.cn/sitemap.xml`，通过 URL 检查申请主要页面抓取。旧版 sitemap ping 接口已停用，不调用它冒充提交成功。Bing Webmaster Tools 可验证同一站点查看收录情况。其他站长平台按实际账号和验证要求另行提交。

不要提交 originlw.canz-monkey.cn 回源地址或 /api/、签名附件链接。若 EdgeOne 缓存旧 sitemap、robots 或 canonical，应仅刷新对应 URL 后验证。不修改 DNS 或回源域名来修复 canonical。

## GEO

首页包含 WebSite、SoftwareSourceCode JSON-LD，项目维护者、上游与许可证可核实；不生成虚假评分、用户量或兼容承诺。/llms.txt 提供简洁的项目与证据链接索引，属于补充资料，不是统一标准、收录接口或排名保证。公开 HTML、来源清晰的技术文档和可访问的 robots/sitemap 是基础。

robots 允许网页抓取、禁止 /api/。若 EdgeOne 给爬虫返回验证码、403、429，应核实真实爬虫与安全日志后处理，不凭可伪造 User-Agent 全站豁免。持续观察站长平台的抓取与索引状态，不把爬虫访问等同于已收录。
