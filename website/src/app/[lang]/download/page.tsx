import { pageMetadata } from "@/lib/site";
import Link from "next/link";
import { notFound } from "next/navigation";
import {
  ArrowRight,
  ArrowUpRight,
  Download,
  ExternalLink,
  ShieldCheck,
  PackageOpen,
  AlertCircle,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { getReleases, releasesUrl, formatAssetSize } from "@/lib/releases";
import { isLocale, dictionary } from "@/lib/i18n";
import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { Button } from "@/components/ui/button";
import type { Metadata } from "next";
type Props = { params: Promise<{ lang: string }> };
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang } = await params;
  const locale = lang === "en" ? "en" : "zh";
  return pageMetadata({
    locale,
    title: locale === "zh" ? "下载与安装" : "Downloads & installation",
    description: dictionary[locale].releaseIntro,
    path: `/${lang}/download`,
    alternates: { "zh-CN": "/zh/download", en: "/en/download" },
  });
}
export default async function DownloadPage({ params }: Props) {
  const { lang } = await params;
  if (!isLocale(lang)) notFound();
  const zh = lang === "zh",
    t = dictionary[lang],
    result = await getReleases();
  return (
    <>
      <SiteHeader locale={lang} />
      <main id="main" className="download-main page-width">
        <div className="download-intro">
          <p className="eyebrow">OFFICIAL RELEASES / LAOCH832</p>
          <h1>{t.release}</h1>
          <p>{t.releaseIntro}</p>
        </div>
        <div className="download-layout">
          <div className="releases">
            {result.ok ? (
              result.releases.map((r, i) => (
                <article className="release" key={r.id}>
                  <div className="release-heading">
                    <div>
                      <h2>{r.name || r.tag_name}</h2>
                      <time dateTime={r.published_at!}>
                        {new Intl.DateTimeFormat(zh ? "zh-CN" : "en-US", {
                          year: "numeric",
                          month: "long",
                          day: "numeric",
                          timeZone: "Asia/Shanghai",
                        }).format(new Date(r.published_at!))}{" "}
                        · {r.tag_name}
                      </time>
                    </div>
                    <span className="release-badge">
                      {i === 0 ? (zh ? "最近发布 · " : "RECENT · ") : ""}
                      {r.prerelease
                        ? zh
                          ? "预发布"
                          : "PRERELEASE"
                        : zh
                          ? "正式版"
                          : "STABLE"}
                    </span>
                  </div>
                  <div className="assets">
                    {r.assets.some((a) => /\.apk$/i.test(a.name)) ? (
                      r.assets.filter((a) => /\.apk$/i.test(a.name))
                        .toSorted(
                          (a, b) =>
                            Number(/\.apk$/i.test(b.name)) -
                            Number(/\.apk$/i.test(a.name)),
                        )
                        .map((a) => (
                          <div key={a.id}>
                            <a
                              className={`asset-link ${/\.apk$/i.test(a.name) ? "asset-primary" : ""}`}
                              href={`/api/v1/download/${a.id}/${encodeURIComponent(a.name)}`}
                              download={a.name}
                            >
                              <Download size={15} />
                              <span>{a.name}</span>
                              <small>{formatAssetSize(a.size)}</small>
                              <ArrowRight size={12} />
                            </a>
                            <a
                              className="inline-link"
                              href={a.browser_download_url}
                              target="_blank"
                              rel="noopener noreferrer"
                              aria-label={`${zh ? "GitHub 原始下载" : "Download from GitHub"}: ${a.name}`}
                            >
                              {zh ? "GitHub 原始下载" : "Download from GitHub"}
                              <ArrowUpRight size={12} />
                            </a>
                          </div>
                        ))
                    ) : (
                      <p className="release-origin">
                        {zh
                          ? "此发布没有附件，请查看 GitHub 原始记录。"
                          : "This release has no attached assets. View the original record."}
                      </p>
                    )}
                  </div>
                  {r.assets.some((a) => !/\.apk$/i.test(a.name)) && (
                    <details className="release-notes">
                      <summary>{zh ? "校验与配置文件（非安装包）" : "Verification and configuration files (not installers)"}</summary>
                      {r.assets.filter((a) => !/\.apk$/i.test(a.name)).map((a) => (
                        <p key={a.id}><a className="inline-link" href={`/api/v1/download/${a.id}/${encodeURIComponent(a.name)}`} download={a.name}>{a.name}</a></p>
                      ))}
                    </details>
                  )}
                  <details className="release-notes" open={i === 0}>
                    <summary>{zh ? "发布说明" : "Release notes"}</summary>
                    <div className="prose">
                      <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        components={{
                          h1: ({ children }) => <h3>{children}</h3>,
                          h2: ({ children }) => <h4>{children}</h4>,
                          h3: ({ children }) => <h5>{children}</h5>,
                          h4: ({ children }) => <h6>{children}</h6>,
                          h5: ({ children }) => <h6>{children}</h6>,
                          h6: ({ children }) => <h6>{children}</h6>,
                          a: ({ href, children }) => (
                            <a
                              href={
                                href?.startsWith("https://") ? href : r.html_url
                              }
                              target="_blank"
                              rel="noopener noreferrer"
                            >
                              {children}
                            </a>
                          ),
                          img: ({ alt }) => <span>{alt}</span>,
                        }}
                      >
                        {r.body ||
                          (zh
                            ? "此版本未提供发布说明。"
                            : "No release notes provided.")}
                      </ReactMarkdown>
                    </div>
                  </details>

                  <a
                    className="inline-link"
                    href={r.html_url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {zh ? "查看原始发布" : "View on GitHub"}
                    <ExternalLink size={12} />
                  </a>
                </article>
              ))
            ) : (
              <div className="release-fallback">
                <PackageOpen size={30} />
                <h2>
                  {zh
                    ? "前往官方发布获取安装包"
                    : "Get packages from official releases"}
                </h2>
                <p>
                  {result.reason === "empty"
                    ? zh
                      ? "当前没有可展示的公开发布。"
                      : "No public releases are available to display."
                    : zh
                      ? "暂时无法读取 GitHub 发布信息，可能是网络或 API 限流。你仍可直接访问官方 Releases。"
                      : "GitHub release information is temporarily unavailable, possibly due to network or API limits. Visit the official releases directly."}
                </p>
                <Button asChild>
                  <a
                    href={releasesUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    GitHub Releases
                    <ArrowUpRight size={15} />
                  </a>
                </Button>
              </div>
            )}
            <p className="release-origin">
              {zh
                ? "来源：GitHub Releases。最多展示最近 6 个公开发布，按发布时间排序；缓存约 30 分钟。日期采用中国标准时间。"
                : "Source: GitHub Releases. Up to 6 public releases by publication date; cached for approximately 30 minutes. Dates use China Standard Time."}
              <br />
              <a
                className="inline-link"
                href={releasesUrl}
                target="_blank"
                rel="noopener noreferrer"
              >
                {zh
                  ? "查看全部版本与原始附件"
                  : "All releases & original assets"}
                <ArrowUpRight size={12} />
              </a>
            </p>
          </div>
          <aside className="download-aside">
            <div className="install-note">
              <h2>
                <Download size={17} />
                {zh ? "下载方式" : "Download options"}
              </h2>
              <p>
                {zh
                  ? "点击附件通过本站下载，支持断点续传（取决于上游）。如下载失败或中断，请使用对应的 GitHub 原始下载链接重试。"
                  : "Download attachments through this site, with range requests when supported upstream. If a download fails or stops, retry using its original GitHub link."}
              </p>
            </div>
            <div className="install-note">
              <h2>
                <PackageOpen size={17} />
                {zh ? "版本 API 与集成" : "Version API & integration"}
              </h2>
              <p>
                {zh
                  ? "查询最新版本与附件，或为客户端接入本站下载接口。"
                  : "Query releases and assets, or integrate the download endpoint into your client."}
              </p>
              <a
                className="inline-link"
                href="/api/v1/version"
                target="_blank"
                rel="noopener noreferrer"
              >
                {zh ? "查看版本 JSON" : "View version JSON"}
                <ArrowUpRight size={12} />
              </a>
              <br />
              <a
                className="inline-link"
                href="https://github.com/Laoch832/LyricWave/blob/main/website/VERSION_API.md"
                target="_blank"
                rel="noopener noreferrer"
              >
                {zh ? "技术接入文档" : "Integration documentation"}
                <ArrowUpRight size={12} />
              </a>
            </div>
            <div className="install-note">
              <h2>
                <ShieldCheck size={17} />
                {zh ? "签名迁移须知" : "Signing migration"}
              </h2>
              <p>
                {zh
                  ? "本分支使用独立固定签名。首次从上游迁移：备份设置与自定义歌词，卸载原版，再安装本分支。同签名版本可覆盖升级。"
                  : "This fork uses its own fixed signing key. Back up settings and custom lyrics, uninstall upstream, then install this fork. Same-signature updates can install in place."}
              </p>
              <Link
                className="inline-link"
                href={`/docs/${lang}/start/migration`}
              >
                {zh ? "迁移指南" : "Migration guide"}
                <ArrowRight size={12} />
              </Link>
            </div>
            <div className="install-note">
              <h2>
                <Download size={17} />
                {t.install}
              </h2>
              <ol>
                <li>
                  {zh
                    ? "下载本项目 Release APK。"
                    : "Download this project’s release APK."}
                </li>
                <li>
                  {zh
                    ? "在 LSPosed 启用模块并选择 Apple Music。"
                    : "Enable the module and scope Apple Music in LSPosed."}
                </li>
                <li>
                  {zh
                    ? "强制停止并重新打开 Apple Music。"
                    : "Force stop and reopen Apple Music."}
                </li>
                <li>
                  {zh
                    ? "进入设置 → 澜音模块设置。"
                    : "Open Settings → LyricWave module settings."}
                </li>
              </ol>
              <Link
                className="inline-link"
                href={`/docs/${lang}/start/installation`}
              >
                {zh ? "完整安装步骤" : "Full installation guide"}
                <ArrowRight size={12} />
              </Link>
            </div>
            <div className="install-note">
              <h2>
                <AlertCircle size={17} />
                {zh ? "核对兼容性" : "Check compatibility"}
              </h2>
              <p>
                {zh
                  ? "Android 8.0+，libxposed API 102，受支持的精确 Apple Music 构建。未知版本不推定兼容。"
                  : "Android 8.0+, libxposed API 102, and an exact supported Apple Music build. Unknown versions are not assumed compatible."}
              </p>
              <Link
                className="inline-link"
                href={`/docs/${lang}/start/requirements`}
              >
                {zh
                  ? "版本档案与功能边界"
                  : "Host profiles & feature boundaries"}
                <ArrowRight size={12} />
              </Link>
            </div>
          </aside>
        </div>
      </main>
      <SiteFooter locale={lang} />
    </>
  );
}
