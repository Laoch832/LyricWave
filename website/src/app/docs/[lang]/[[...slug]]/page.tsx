import Link from "next/link";
import contentMap from "@/lib/generated/content-map.json";
import { pageMetadata } from "@/lib/site";
import { DocumentationOverview } from "@/components/documentation-overview";
import { isLocale } from "@/lib/i18n";
import { source } from "@/lib/source";
import { notFound } from "next/navigation";
import { DocsPage, DocsBody, DocsTitle } from "fumadocs-ui/page";
import { GlassDemo } from "@/components/glass-demo";
import { mdxComponents } from "@/components/mdx";
import type { Metadata } from "next";
type Props = { params: Promise<{ lang: string; slug?: string[] }> };
export default async function Page({ params }: Props) {
  const { lang, slug } = await params;
  if (!isLocale(lang)) notFound();
  if (!slug?.length) return <DocumentationOverview locale={lang} />;
  const page = source.getPage(slug, lang);
  if (!page) notFound();
  const MDX = page.data.body;
  const contentLanguage =
    contentMap.find((entry) => entry.route === page.url)?.contentLanguage ||
    lang;
  return (
    <DocsPage
      key={page.url}
      className="reading-article"
      toc={page.data.toc}
      full={page.data.full}
    >
      <DocsTitle
        lang={/[\u3400-\u9fff]/.test(page.data.title) ? "zh-CN" : lang}
      >
        {page.data.title}
      </DocsTitle>
      {lang === "en" && contentLanguage === "zh" && (
        <p className="source-language-notice" lang="en">
          This article follows the authoritative Chinese source. English
          translation is not yet available.{" "}
          <Link href={`/docs/zh/${slug.join("/")}`}>
            Read the Chinese version
          </Link>
          .
        </p>
      )}
      <DocsBody lang={contentLanguage === "zh" ? "zh-CN" : "en"}>
        <MDX
          components={{
            ...mdxComponents,
            GlassDemo: () => <GlassDemo locale={lang} />,
          }}
        />
      </DocsBody>
    </DocsPage>
  );
}
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang, slug } = await params;
  if (!isLocale(lang)) notFound();
  if (!slug?.length)
    return pageMetadata({
      locale: lang,
      title: lang === "zh" ? "项目文档" : "Documentation",
      description:
        lang === "zh"
          ? "澜音项目文档：安装、歌词翻译、播放器与插件开发。"
          : "LyricWave documentation: installation, lyric translation, player enhancements, and plugin development.",
      path: `/docs/${lang}`,
      alternates: { "zh-CN": "/docs/zh", en: "/docs/en" },
    });
  const page = source.getPage(slug, lang);
  if (!page) notFound();
  return pageMetadata({
    locale: lang,
    title: page.data.title,
    description: page.data.description || page.data.title,
    path: page.url,
    alternates: {
      "zh-CN": `/docs/zh/${slug.join("/")}`,
      en: `/docs/en/${slug.join("/")}`,
    },
  });
}
