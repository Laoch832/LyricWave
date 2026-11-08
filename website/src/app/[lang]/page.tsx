import { pageMetadata, siteOrigin } from "@/lib/site";
import Link from "next/link";
import Image from "next/image";
import {
  ArrowRight,
  ArrowDown,
  ArrowUpRight,
  AudioLines,
  Check,
  Code2,
  FileCode2,
  Fingerprint,
  Layers3,
  Monitor,
  Puzzle,
  Smartphone,
} from "lucide-react";
import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { LyricScene } from "@/components/lyric-scene";
import { TranslationDemo } from "@/components/translation-demo";
import { GlassDemo } from "@/components/glass-demo";
import { Button } from "@/components/ui/button";
import { dictionary, isLocale } from "@/lib/i18n";
import { notFound } from "next/navigation";
import type { Metadata } from "next";
type Props = { params: Promise<{ lang: string }> };
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang } = await params;
  const en = lang === "en";
  return pageMetadata({
    locale: en ? "en" : "zh",
    title: en
      ? "LyricWave — Every lyric. A deeper resonance."
      : "澜音 LyricWave — 让每一句，都有回响",
    description: dictionary[en ? "en" : "zh"].intro,
    path: `/${lang}`,
    alternates: { "zh-CN": "/zh", en: "/en" },
  });
}
export default async function Home({ params }: Props) {
  const { lang } = await params;
  if (!isLocale(lang)) notFound();
  const t = dictionary[lang],
    zh = lang === "zh";
  const doc = (p: string) => `/docs/${lang}/${p}`;
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify({
            "@context": "https://schema.org",
            "@graph": [
              {
                "@type": "WebSite",
                "@id": `${siteOrigin}/#website`,
                name: "澜音 LyricWave",
                url: siteOrigin,
                inLanguage: ["zh-CN", "en"],
              },
              {
                "@type": "SoftwareSourceCode",
                "@id": `${siteOrigin}/#project`,
                name: "LyricWave",
                alternateName: "澜音",
                url: `${siteOrigin}/${lang}`,
                description: zh
                  ? "基于 AM++ 的 Apple Music Android 增强项目，由 Laoch832 维护。"
                  : "An Apple Music Android enhancement project based on AM++, maintained by Laoch832.",
                codeRepository: "https://github.com/Laoch832/LyricWave",
                license: "https://www.gnu.org/licenses/gpl-3.0.html",
                author: {
                  "@type": "Person",
                  name: "Laoch832",
                  url: "https://github.com/Laoch832",
                },
                isBasedOn: "https://github.com/Zennmn/AM-plus-plus",
              },
            ],
          }).replace(/</g, "\\u003c"),
        }}
      />
      <SiteHeader locale={lang} />
      <main id="main">
        <section className="hero page-width">
          <div className="hero-copy">
            <div className="eyebrow hero-eyebrow">
              <span className="tiny-wave">
                <AudioLines size={14} />
              </span>
              {t.tag}
            </div>
            <h1>
              {t.headline}
              <br />
              <span>{t.accent}</span>
            </h1>
            <p className="hero-intro">{t.intro}</p>
            <div className="hero-actions">
              <Button asChild>
                <Link href={`/${lang}/download`}>
                  {t.start}
                  <ArrowRight size={16} />
                </Link>
              </Button>
              <Button asChild variant="outline">
                <Link href={`/docs/${lang}`}>
                  {t.read}
                  <ArrowUpRight size={15} />
                </Link>
              </Button>
            </div>
            <div className="hero-note">
              <span className="status-dot" />
              {zh
                ? "开源 · 独立维护 · 为聆听而来"
                : "Open source · Independently maintained"}
            </div>
          </div>
          <div className="hero-visual">
            <LyricScene locale={lang} />
            <p className="concept-caption">{t.concept}</p>
          </div>
          <a className="explore-link" href="#features">
            <ArrowDown size={13} />
            {t.explore}
            <span>01 — 05</span>
          </a>
        </section>
        <div className="capability-strip">
          <div className="page-width">
            <span>
              <LanguagesMark />
              AI TRANSLATION
            </span>
            <span>
              <FileCode2 size={15} />
              WORD BY WORD
            </span>
            <span>
              <Layers3 size={15} />
              LIQUID GLASS
            </span>
            <span>
              <Monitor size={15} />
              DUAL PANE
            </span>
            <span>
              <Puzzle size={15} />
              OPEN TO EXTEND
            </span>
          </div>
        </div>
        <section className="features page-width" id="features">
          <div className="section-intro">
            <p className="eyebrow">THE DETAILS MAKE THE DIFFERENCE</p>
            <h2>{t.featureTitle}</h2>
            <p>{t.featureIntro}</p>
          </div>
          <article className="feature-row">
            <div className="feature-copy">
              <span className="feature-index">01 / UNDERSTAND</span>
              <h3>{t.ai}</h3>
              <p>{t.aiText}</p>
              <ul className="quiet-list">
                <li>
                  <Check size={14} />
                  {zh
                    ? "当前选中原文 · SHA-256 校验"
                    : "Selected source · SHA-256 checks"}
                </li>
                <li>
                  <Check size={14} />
                  {zh
                    ? "最近使用的 100 首 · 独立缓存"
                    : "100 most recent songs · Separate cache"}
                </li>
                <li>
                  <Check size={14} />
                  {zh
                    ? "已有译文与中文歌词保持原样"
                    : "Existing translations and Chinese preserved"}
                </li>
              </ul>
              <Link className="inline-link" href={doc("features/ai-lyrics")}>
                {t.guide}
                <ArrowRight size={14} />
              </Link>
            </div>
            <TranslationDemo locale={lang} />
          </article>
          <article className="feature-row custom-feature">
            <div className="ttml-study">
              <div className="demo-label">
                <FileCode2 size={14} />
                LYRIC STUDY / TTML
              </div>
              <div className="ttml-words">
                <span>Let</span>
                <span>the</span>
                <span className="word-active">words</span>
                <span>find</span>
                <span>their</span>
                <span>way.</span>
              </div>
              <div className="word-timeline" aria-hidden="true">
                <span />
                <span />
                <span />
                <span />
                <span />
                <span />
              </div>
              <div className="time-labels">
                <span>00:28.000</span>
                <span>00:32.600</span>
              </div>
              <pre
                tabIndex={0}
                role="region"
                aria-label={zh ? "TTML 歌词示例" : "TTML lyric example"}
              >
                <code>
                  <span className="code-muted">&lt;p</span> begin=
                  <span className="code-accent">
                    &quot;00:28.000&quot;
                  </span>{" "}
                  end=&quot;00:32.600&quot;
                  <br /> itunes:key=&quot;L3&quot; ttm:agent=&quot;v1&quot;
                  <span className="code-muted">&gt;</span>
                  <br /> &lt;span begin=&quot;00:29.120&quot;
                  end=&quot;00:29.680&quot;&gt;
                  <span className="code-accent">words</span>&lt;/span&gt;
                  <br />
                  <span className="code-muted">&lt;/p&gt;</span>
                </code>
              </pre>
              <span className="study-caption">{t.concept}</span>
            </div>
            <div className="feature-copy">
              <span className="feature-index">02 / MAKE IT YOURS</span>
              <h3>{t.custom}</h3>
              <p>{t.customText}</p>
              <div className="format-tags">
                <span>.ttml</span>
                <span>.ttf / .otf</span>
                <span>.zip</span>
              </div>
              <Link
                className="inline-link"
                href={doc("features/custom-lyrics")}
              >
                {t.guide}
                <ArrowRight size={14} />
              </Link>
            </div>
          </article>
          <article className="material-section">
            <div className="material-text">
              <span className="feature-index">03 / FEEL THE MATERIAL</span>
              <h3>{t.glass}</h3>
              <p>{t.glassText}</p>
              <Link className="inline-link" href={doc("features/player")}>
                {t.guide}
                <ArrowRight size={14} />
              </Link>
              <p className="material-caption">{t.actual}</p>
            </div>
            <GlassDemo locale={lang} />
          </article>
          <article className="tablet-section">
            <div className="tablet-heading">
              <div>
                <span className="feature-index">04 / A WIDER PERSPECTIVE</span>
                <h3>{t.tablet}</h3>
              </div>
              <p>{t.tabletText}</p>
            </div>
            <figure className="tablet-image">
              <Image
                src="/media/tablet-dual-pane-player-open-source-blur.jpg"
                alt={
                  zh
                    ? "仓库平板双栏播放器：左侧控件，右侧实时歌词"
                    : "Repository tablet player: controls on the left and live lyrics on the right"
                }
                width={1526}
                height={1079}
                sizes="(max-width: 1200px) 90vw, 1100px"
              />
              <figcaption>{t.actual}</figcaption>
            </figure>
          </article>
          <article className="plugin-section">
            <div className="feature-copy">
              <span className="feature-index">05 / BUILT TO EXTEND</span>
              <h3>{t.plugins}</h3>
              <p>{t.pluginText}</p>
              <Link
                className="inline-link"
                href={doc("advanced/plugin-author-guide")}
              >
                {zh ? "开始开发插件" : "Build a plugin"}
                <ArrowRight size={14} />
              </Link>
            </div>
            <div className="plugin-diagram">
              <div className="plugin-host">
                <AudioLines size={20} />
                LyricWave <span>HOST</span>
              </div>
              <div className="plugin-connectors" aria-hidden="true" />
              <div className="plugin-nodes">
                <span>
                  <Code2 size={20} />
                  SDK v1
                </span>
                <span>
                  <Puzzle size={20} />
                  .zip
                </span>
                <span>
                  <Fingerprint size={20} />
                  {zh ? "冲突检测" : "Conflicts"}
                </span>
              </div>
              <p>
                {zh
                  ? "架构概念 · 插件与宿主同进程，不是隔离沙箱"
                  : "Architecture concept · Plugins run in the host process, without sandboxing"}
              </p>
            </div>
          </article>
        </section>
        <section className="requirements-section">
          <div className="page-width">
            <p className="eyebrow">A FEW THINGS TO KNOW</p>
            <h2>{t.requirements}</h2>
            <div className="requirement-grid">
              <div>
                <Smartphone size={21} />
                <h3>{t.android}</h3>
                <p>
                  {zh
                    ? "真正 HDR 需 Android 14+ 与 HDR 屏幕。"
                    : "Genuine HDR needs Android 14+ and an HDR display."}
                </p>
              </div>
              <div>
                <Layers3 size={21} />
                <h3>{t.framework}</h3>
                <p>
                  {zh
                    ? "在 LSPosed 或兼容框架中启用模块与作用域。"
                    : "Enable the module and scope in LSPosed or a compatible framework."}
                </p>
              </div>
              <div>
                <Check size={21} />
                <h3>{t.host}</h3>
                <p>
                  {zh
                    ? "按 versionName 与 versionCode 核对，不推定未知版本兼容。"
                    : "Match versionName and versionCode. Unknown builds are not assumed compatible."}
                </p>
              </div>
            </div>
            <Link className="inline-link" href={doc("start/requirements")}>
              {zh ? "查看完整兼容性说明" : "Read compatibility details"}
              <ArrowRight size={14} />
            </Link>
          </div>
        </section>
        <section className="open-source page-width">
          <div>
            <span className="eyebrow">OPEN SOURCE, OPEN GRATITUDE</span>
            <h2>{t.credit}</h2>
          </div>
          <div>
            <p>{t.creditText}</p>
            <Link className="inline-link" href={doc("project/ACKNOWLEDGMENTS")}>
              {zh ? "致谢与项目来源" : "Acknowledgments & origins"}
              <ArrowUpRight size={14} />
            </Link>
          </div>
        </section>
        <section className="closing page-width">
          <AudioLines size={32} />
          <h2>{t.ready}</h2>
          <p>
            {zh ? "让文字与旋律，在这里相遇。" : "Where words and music meet."}
          </p>
          <Button asChild>
            <Link href={`/${lang}/download`}>
              {t.start}
              <ArrowRight size={16} />
            </Link>
          </Button>
        </section>
      </main>
      <SiteFooter locale={lang} />
    </>
  );
}
function LanguagesMark() {
  return <span className="strip-glyph">译</span>;
}
