import Link from "next/link";
import {
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  Download,
  FileCode2,
  Layers3,
  Sparkles,
  Puzzle,
} from "lucide-react";
import { DocsPage, DocsTitle } from "fumadocs-ui/page";
import type { Locale } from "@/lib/i18n";
export function DocumentationOverview({ locale }: { locale: Locale }) {
  const zh = locale === "zh";
  const route = (slug: string) => `/docs/${locale}/${slug}`;
  const starts = [
    {
      n: "01",
      name: zh ? "核对环境" : "Check your setup",
      desc: zh
        ? "Android、框架与精确宿主版本。"
        : "Android, framework, and exact host profiles.",
      url: "start/requirements",
    },
    {
      n: "02",
      name: zh ? "安装与启用" : "Install and enable",
      desc: zh
        ? "从官方发布开始，配置模块作用域。"
        : "Start with an official release and set module scope.",
      url: "start/installation",
    },
    {
      n: "03",
      name: zh ? "从上游迁移" : "Migrate from upstream",
      desc: zh
        ? "先备份，再处理独立签名与升级。"
        : "Back up before switching signing identities.",
      url: "start/migration",
    },
  ];
  const guides = [
    {
      icon: Sparkles,
      name: zh ? "理解与翻译" : "Understand & translate",
      desc: zh
        ? "AI 翻译、缓存复用、原歌词更新检测。"
        : "AI translation, cache reuse, and source updates.",
      url: "features/ai-lyrics",
    },
    {
      icon: FileCode2,
      name: zh ? "定制歌词" : "Shape your lyrics",
      desc: zh
        ? "导入 TTML、整理时间轴与声部。"
        : "Import TTML and work with timing and voices.",
      url: "features/custom-lyrics",
    },
    {
      icon: Layers3,
      name: zh ? "播放器与视觉" : "Player & appearance",
      desc: zh
        ? "液态玻璃、双栏布局与显示边界。"
        : "Glass, dual-pane layouts, and display boundaries.",
      url: "features/player",
    },
    {
      icon: Puzzle,
      name: zh ? "开发与扩展" : "Build & extend",
      desc: zh
        ? "插件 SDK、宿主适配与项目架构。"
        : "Plugin SDK, host adaptation, and architecture.",
      url: "advanced/plugin-author-guide",
    },
  ];
  return (
    <DocsPage
      full
      className="docs-overview"
      toc={[]}
      breadcrumb={{ enabled: false }}
      footer={{ enabled: false }}
    >
      <header className="overview-header">
        <span className="eyebrow">
          <BookOpen size={13} />
          LYRICWAVE / DOCUMENTATION
        </span>
        <DocsTitle>{zh ? "项目文档" : "Documentation"}</DocsTitle>
        <p>
          {zh
            ? "从第一次启用，到深入理解每一处增强。"
            : "From your first setup to the details behind every enhancement."}
        </p>
        <Link href={route("start/introduction")} className="inline-link">
          {zh ? "了解澜音与项目来源" : "About LyricWave and its origins"}
          <ArrowRight size={14} />
        </Link>
      </header>
      <section
        className="overview-start"
        aria-labelledby="overview-start-title"
      >
        <div className="overview-section-title">
          <h2 id="overview-start-title">{zh ? "开始使用" : "Get started"}</h2>
          <span>
            {zh ? "按顺序，完成第一次设置" : "A clear path to your first setup"}
          </span>
        </div>
        {starts.map((item) => (
          <Link className="overview-step" key={item.n} href={route(item.url)}>
            <span className="overview-step-number">{item.n}</span>
            <div>
              <h3>{item.name}</h3>
              <p>{item.desc}</p>
            </div>
            <ArrowRight size={16} />
          </Link>
        ))}
      </section>
      <section
        className="overview-guides"
        aria-labelledby="overview-guide-title"
      >
        <div className="overview-section-title">
          <h2 id="overview-guide-title">
            {zh ? "找到你的阅读方向" : "Find your next chapter"}
          </h2>
        </div>
        <div className="overview-guide-grid">
          {guides.map(({ icon: Icon, ...item }) => (
            <Link
              href={route(item.url)}
              className="overview-guide"
              key={item.url}
            >
              <Icon size={20} />
              <div>
                <h3>{item.name}</h3>
                <p>{item.desc}</p>
              </div>
              <ArrowUpRight size={14} />
            </Link>
          ))}
        </div>
      </section>
      <div className="overview-help">
        <span>{zh ? "还没安装？" : "Ready to install?"}</span>
        <Link href={`/${locale}/download`}>
          <Download size={14} />
          {zh ? "查看官方发布" : "View official releases"}
          <ArrowRight size={14} />
        </Link>
      </div>
      <p className="overview-source">
        {zh
          ? "技术内容随仓库同步；精确版本和能力边界以原始档案为准。"
          : "Technical content follows repository sources. Exact eligibility remains defined by host profiles."}
      </p>
    </DocsPage>
  );
}
