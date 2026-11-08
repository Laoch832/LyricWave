# LyricWave — second visual review

This pass changes presentation and interaction behavior. It preserves the Fumadocs loader, MDX pipeline, full-text search, generated technical content, original article URLs, host profile data, and Android sources.

## Issues found and corrected

- **Tablet proportions:** 768px inherited a compressed desktop hero and download sidebar. The hero now has a dedicated stacked tablet composition; download assets use the full reading width with compact setup notes above them.
- **Reading hierarchy:** several captions were 8–10px and too faint on the paper palette. Secondary text is larger, and the light theme's secondary color is darker. Both themes are checked independently.
- **Material presentation:** the rotated, cropped device frame and overlaid play button obscured the real capture. The full capture is now upright inside a quiet media surface, with playback controls below it. Redundant decorative material copy and repeated diagram boxes were removed.
- **Document entry:** `/docs/zh` and `/docs/en` now offer an ordered setup path and topic-based guide links. The full original introduction remains at its existing URL. No technical article was replaced.
- **Publication details:** wide article text narrowed from 836px to 736px at the reviewed 1440px viewport. Tablet article text grew from 452px to 496px by adjusting the sidebar width. Titles, spacing, code, tables, quotes and the small-screen layout were tuned. Table field columns retain enough width for technical identifiers rather than splitting them into several fragments. Repeated boilerplate descriptions were removed from the reading view; source links remain.
- **Navigation continuity:** document navigation no longer remounts and animates the entire sidebar. Folder state survives article navigation; article-only transitions are brief. Reading progress updates through one animation-frame callback without triggering React renders on every scroll event.
- **Download priority:** authentic APK assets now appear before long release notes, with APKs first and visually emphasized. GitHub fetching, validation, caching and fallback behavior are unchanged.
- **Footer:** a flat link row became two concise navigation groups alongside the brand and maintainer attribution.
- **Motion:** section reveals happen once using opacity and translation; content stays visible without JavaScript. Reduced-motion preferences bypass reveals. Lyric playback and GIF playback stop when they leave the viewport or the document becomes hidden. No animation runs continuously by default.
- **Small-screen keyboard access:** the overflowing capability strip now wraps into rows instead of leaving a non-focusable scrolling region.

## Browser evidence

Production build inspected in Chromium at **375px, 768px and 1440px**, in **dark and light** themes. Review includes Chinese and English home/document entry pages, technical articles, installation callouts, tables, download pages and footer layouts. Mobile document navigation was also opened and used to navigate to installation.

Captures are in the ignored local directory `artifacts/refinement/verified/`; baseline captures are in `artifacts/refinement/before/`.

Automated checks cover:

- all six width/theme combinations, horizontal overflow and browser errors;
- axe WCAG 2 A/AA and WCAG 2.1 A/AA checks on the home and documentation entry pages in each combination;
- playback stopping offscreen, tablet sidebar collapse, and sidebar folder state across navigation;
- existing search, theme, language, menu, code copy, progress, pagination, official download and original article-route checks.

Run `npm run build` followed by `UPDATE_SCREENSHOTS=1 npm run test:e2e` to regenerate the review captures. Standard tests do not require screenshot generation.

## Still worth confirming on physical devices

Chromium emulation cannot establish the exact appearance of platform fonts, Safari text rasterization, touch feel, or scroll/animation timing on lower-powered phones. Review the final typography and pacing on real iOS Safari and Android Chrome before publishing. Existing app captures retain their original quality and upstream identity; they were not repainted or replaced with fabricated screens.

## Verification results

- Production build and strict TypeScript checks passed.
- All 58 synchronized article routes, image links and anchors passed content validation; the four release-data tests passed.
- The expanded 15-case browser suite passed. After the final table adjustment, all six width/theme matrix cases passed again; the sidebar continuity case was checked three consecutive times after correcting its navigation wait.
- No root horizontal overflow or browser page errors were observed in the reviewed matrix. Home and document-entry pages passed the specified automated WCAG checks in both palettes.
- The mobile TTML field `itunes:timing` was confirmed to render on one line in both themes. Code and wide tables retain their own keyboard-focusable scroll regions.
- Original repository docs, Android sources, host profiles and the existing Android build workflow were unchanged.
