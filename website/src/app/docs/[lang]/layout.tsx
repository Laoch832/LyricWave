import { notFound } from "next/navigation";
import { DocsLayout } from "fumadocs-ui/layouts/docs";
import { source } from "@/lib/source";
import { Brand } from "@/components/brand";
import { Providers } from "@/components/providers";
import { ReadingProgress } from "@/components/reading-progress";
import { isLocale } from "@/lib/i18n";
export default async function Layout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params;
  if (!isLocale(lang)) notFound();
  return (
    <Providers locale={lang}>
      <a href="#nd-page" className="skip-link">
        {lang === "zh" ? "跳至正文" : "Skip to content"}
      </a>
      <ReadingProgress />
      <DocsLayout
        tree={source.getPageTree(lang)}
        i18n={{ languages: ["zh", "en"], defaultLanguage: "zh" }}
        nav={{ title: <Brand />, url: `/${lang}` }}
        githubUrl="https://github.com/Laoch832/LyricWave"
        links={[
          {
            text: lang === "zh" ? "文档导览" : "Documentation",
            url: `/docs/${lang}`,
          },
          { text: lang === "zh" ? "首页" : "Home", url: `/${lang}` },
          {
            text: lang === "zh" ? "下载" : "Download",
            url: `/${lang}/download`,
          },
        ]}
      >
        {children}
      </DocsLayout>
    </Providers>
  );
}
