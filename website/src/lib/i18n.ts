export const locales = ["zh", "en"] as const;
export type Locale = (typeof locales)[number];
export function isLocale(s: string): s is Locale {
  return locales.some((l) => l === s);
}
export const repo = "https://github.com/Laoch832/LyricWave";
export const dictionary = {
  zh: {
    home: "首页",
    features: "功能",
    docs: "文档",
    download: "下载",
    tag: "为 Apple Music Android 而作",
    headline: "让每一句，",
    accent: "都有回响。",
    intro:
      "在熟悉的旋律里，读懂更多。AI 歌词翻译、精细的逐字体验，以及恰到好处的播放器增强。",
    start: "下载澜音",
    read: "阅读文档",
    explore: "向下探索",
    featureTitle: "音乐未变，感受更深。",
    featureIntro: "从理解一句歌词，到沉浸一整首歌。每一处增强，都为聆听本身。",
    ai: "跨过语言，抵达共鸣。",
    aiText:
      "通过你自己的 OpenAI 兼容接口，为符合条件的外语歌词补充简体中文译文。校验后保存，重播时复用。",
    custom: "每个字，都在它的位置。",
    customText:
      "导入与编辑 TTML，让时间轴、声部与译文准确相遇。支持在线获取和 ZIP 备份恢复。",
    glass: "轻盈，且有分量。",
    glassText: "液态玻璃融入导航与迷你播放器。清晰的内容层级，克制的材质变化。",
    tablet: "给音乐，更多空间。",
    tabletText: "横屏双栏，让播放控件与实时歌词并排展开。",
    plugins: "你的体验，继续延伸。",
    pluginText:
      "使用独立 ZIP 插件扩展功能。稳定 SDK、生命周期管理与冲突提示，为创作者保留空间。",
    ready: "从下一首歌开始。",
    requirements: "运行前，请确认",
    android: "Android 8.0 或以上",
    framework: "支持 libxposed API 102 的框架",
    host: "精确宿主版本适配",
    credit: "源于开源，持续共创。",
    creditText:
      "由 Laoch832 维护，基于 Zennmn（Zen）创建的 AM++。感谢上游贡献者与开源社区，让更好的聆听体验成为可能。",
    concept: "原创交互概念 · 非应用截图",
    actual: "仓库实机素材 · 上游界面保留原署名",
    guide: "查看指南",
    footer: "随旋律起伏，让文字澄明。",
    source: "源代码",
    license: "许可与致谢",
    install: "安装与启用",
    release: "版本与下载",
    releaseIntro:
      "只从本项目的官方发布获取安装包。安装之前，请核对系统、宿主版本与签名迁移说明。",
  },
  en: {
    home: "Home",
    features: "Features",
    docs: "Docs",
    download: "Download",
    tag: "Made for Apple Music on Android",
    headline: "Every lyric.",
    accent: "A deeper resonance.",
    intro:
      "Understand more of the music you love. AI lyric translation, precise word timing, and thoughtful player enhancements.",
    start: "Get LyricWave",
    read: "Read the docs",
    explore: "Explore the experience",
    featureTitle: "Same music. More meaning.",
    featureIntro:
      "From understanding a line to finding yourself in a song. Thoughtfully built for listening.",
    ai: "Beyond language. Into feeling.",
    aiText:
      "Add Simplified Chinese translations to eligible lyrics with your own OpenAI-compatible endpoint. Validated, saved, and reused on your next listen.",
    custom: "Every word, in its place.",
    customText:
      "Import and edit TTML to bring timing, voices, and translations together. Fetch online lyrics and back up with ZIP.",
    glass: "Lightness with depth.",
    glassText:
      "Liquid Glass brings subtle material and clear layers to navigation and the mini-player.",
    tablet: "More room for music.",
    tabletText: "Playback controls and live lyrics, side by side in landscape.",
    plugins: "Make room for your ideas.",
    pluginText:
      "Extend the experience with independent ZIP plugins, a stable SDK, lifecycle management, and conflict reporting.",
    ready: "Begin with your next song.",
    requirements: "Before you start",
    android: "Android 8.0 or later",
    framework: "Framework with libxposed API 102",
    host: "Exact host build profiles",
    credit: "Built on shared work.",
    creditText:
      "Maintained by Laoch832, derived from AM++ by Zennmn (Zen). With thanks to upstream contributors and the open-source community.",
    concept: "Original interactive concept · not an app screenshot",
    actual: "Repository device capture · original upstream identity retained",
    guide: "View guide",
    footer: "Words in clarity. Music in motion.",
    source: "Source",
    license: "Licenses & credits",
    install: "Installation",
    release: "Releases & downloads",
    releaseIntro:
      "Get packages from this project’s official releases. Check system requirements, host profiles, and signing migration before installing.",
  },
};
