"use client";
import { useEffect, useRef, useState } from "react";
import { LazyMotion, m, useReducedMotion } from "motion/react";
import {
  AudioLines,
  Check,
  Pause,
  Play,
  SkipBack,
  SkipForward,
} from "lucide-react";
import type { Locale } from "@/lib/i18n";
const lines = [
  ["A quiet light upon the sea", "微光落在海面"],
  ["Every wave remembers you", "每一道澜，记得你"],
  ["Let the words find their way", "让文字，找到回响"],
  ["And the music carry through", "让旋律，继续流淌"],
];
const loadFeatures = () =>
  import("./motion-features").then((module) => module.default);
export function LyricScene({ locale }: { locale: Locale }) {
  const reduce = useReducedMotion();
  const scene = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(1),
    [playing, setPlaying] = useState(false),
    [translated, setTranslated] = useState(true);
  useEffect(() => {
    if (!playing) return;
    const id = setInterval(
      () => setActive((v) => (v + 1) % lines.length),
      3600,
    );
    return () => clearInterval(id);
  }, [playing]);
  useEffect(() => {
    if (!playing || !scene.current) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting) setPlaying(false);
    });
    observer.observe(scene.current);
    const pause = () => {
      if (document.hidden) setPlaying(false);
    };
    document.addEventListener("visibilitychange", pause);
    return () => {
      observer.disconnect();
      document.removeEventListener("visibilitychange", pause);
    };
  }, [playing]);
  const change = (d: number) =>
    setActive((v) => (v + d + lines.length) % lines.length);
  return (
    <LazyMotion features={loadFeatures} strict>
      <m.div
        ref={scene}
        className="lyric-scene"
        initial={false}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.8, delay: 0.15 }}
      >
        <div className="scene-grid" aria-hidden="true" />
        <div className="scene-top">
          <span>
            <span className="status-dot" /> LYRIC ENGINE
          </span>
          <button
            className={`translation-toggle ${translated ? "on" : ""}`}
            onClick={() => setTranslated(!translated)}
            aria-pressed={translated}
          >
            <Check size={12} />
            {locale === "zh" ? "译文" : "Translation"}
          </button>
        </div>
        <div className="lyric-lines" aria-live="polite">
          {lines.map(([en, zh], i) => (
            <m.button
              initial={false}
              animate={{ x: !reduce && i === active ? 3 : 0 }}
              transition={{ duration: reduce ? 0 : 0.3 }}
              key={en}
              className={`lyric-line ${i === active ? "active" : ""}`}
              onClick={() => setActive(i)}
              aria-current={i === active ? "true" : undefined}
            >
              <span className="line-time">
                00:{String(12 + i * 8).padStart(2, "0")}
              </span>
              <span className="lyric-copy">
                <span>{en}</span>
                {translated && <small>{zh}</small>}
              </span>
              {i === active && (
                <AudioLines className="active-line-icon" size={18} />
              )}
            </m.button>
          ))}
        </div>
        <div className="scene-bottom">
          <div
            className={`waveform ${playing && !reduce ? "is-playing" : ""}`}
            aria-hidden="true"
          >
            {Array.from({ length: 45 }, (_, i) => (
              <i
                key={i}
                style={{
                  height:
                    5 +
                    Math.round(
                      (Math.sin(i * 1.7) + 1) * 12 +
                        (Math.sin(i * 0.2) + 1) * 8,
                    ),
                  animationDelay: `${i * -0.08}s`,
                }}
              />
            ))}
          </div>
          <div className="scene-controls">
            <span className="scene-track">
              LYRICWAVE <small>Original lyric study / 01</small>
            </span>
            <div>
              <button
                onClick={() => change(-1)}
                aria-label={locale === "zh" ? "上一句" : "Previous line"}
              >
                <SkipBack size={15} />
              </button>
              <button
                className="play-button"
                aria-label={
                  playing
                    ? locale === "zh"
                      ? "暂停演示"
                      : "Pause demo"
                    : locale === "zh"
                      ? "播放歌词演示（无音频）"
                      : "Play lyric demo (no audio)"
                }
                aria-pressed={playing}
                onClick={() => setPlaying(!playing)}
              >
                {playing ? <Pause size={17} /> : <Play size={17} />}
              </button>
              <button
                onClick={() => change(1)}
                aria-label={locale === "zh" ? "下一句" : "Next line"}
              >
                <SkipForward size={15} />
              </button>
            </div>
          </div>
          <div className="scene-footnote">
            <span>TTML → TRANSLATE → VALIDATE → CACHE</span>
            <span>
              SHA-256 <Check size={10} />
            </span>
          </div>
        </div>
      </m.div>
    </LazyMotion>
  );
}
