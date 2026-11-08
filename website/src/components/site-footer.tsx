import Link from "next/link";
import { Brand } from "./brand";
import { dictionary, type Locale, repo } from "@/lib/i18n";
import { ArrowUpRight } from "lucide-react";
export function SiteFooter({ locale }: { locale: Locale }) {
  const t = dictionary[locale],
    zh = locale === "zh";
  return (
    <footer className="site-footer">
      <div className="footer-top">
        <div className="footer-brand">
          <Link href={`/${locale}`}>
            <Brand />
          </Link>
          <p>{t.footer}</p>
          <span className="footer-maintainer">
            {zh
              ? "由 Laoch832 独立维护"
              : "Independently maintained by Laoch832"}
          </span>
        </div>
        <nav className="footer-group" aria-label={zh ? "探索" : "Explore"}>
          <h2>{zh ? "探索" : "Explore"}</h2>
          <Link href={`/docs/${locale}`}>{t.docs}</Link>
          <Link href={`/${locale}/download`}>{t.download}</Link>
          <Link href={`/docs/${locale}/start/faq`}>
            {zh ? "常见问题" : "FAQ"}
          </Link>
        </nav>
        <nav className="footer-group" aria-label={zh ? "项目" : "Project"}>
          <h2>{zh ? "项目" : "Project"}</h2>
          <a href={repo} target="_blank" rel="noopener noreferrer">
            GitHub
            <ArrowUpRight size={12} />
          </a>
          <Link href={`/docs/${locale}/project/ACKNOWLEDGMENTS`}>
            {zh ? "致谢与来源" : "Acknowledgments"}
          </Link>
          <Link href={`/docs/${locale}/project/third-party`}>
            {zh ? "第三方许可" : "Third-party notices"}
          </Link>
        </nav>
      </div>
      <div className="footer-bottom">
        <span>
          © {new Date().getFullYear()} LyricWave contributors · GPL-3.0
        </span>
        <span>
          {zh
            ? "独立开源项目，与 Apple Inc. 无隶属关系。"
            : "Independent open-source project. Not affiliated with Apple Inc."}
        </span>
      </div>
    </footer>
  );
}
