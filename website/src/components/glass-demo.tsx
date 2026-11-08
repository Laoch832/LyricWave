"use client";
import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import type { Locale } from "@/lib/i18n";
import { Button } from "./ui/button";
export function GlassDemo({ locale }: { locale: Locale }) {
  const [play, setPlay] = useState(false);
  const figureRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (!play || !figureRef.current) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting) setPlay(false);
    });
    observer.observe(figureRef.current);
    const pause = () => {
      if (document.hidden) setPlay(false);
    };
    document.addEventListener("visibilitychange", pause);
    return () => {
      observer.disconnect();
      document.removeEventListener("visibilitychange", pause);
    };
  }, [play]);
  return (
    <figure className="glass-image" ref={figureRef}>
      <div className="glass-device">
        <Image
          src={
            play
              ? "/media/liquid-glass-demo.gif"
              : "/media/liquid-glass-poster.webp"
          }
          alt={
            locale === "zh"
              ? "仓库液态玻璃实机演示，保留上游界面标识"
              : "Repository Liquid Glass device capture with original upstream identity"
          }
          width={420}
          height={924}
          unoptimized={play}
          sizes="(max-width: 760px) 70vw, 280px"
        />
      </div>
      <figcaption>
        <span>{locale === "zh" ? "实机录屏" : "Device capture"}</span>
        <Button
          variant="outline"
          aria-pressed={play}
          onClick={() => setPlay(!play)}
        >
          {play
            ? locale === "zh"
              ? "停止动图"
              : "Stop animation"
            : locale === "zh"
              ? "播放实机演示"
              : "Play device demo"}
        </Button>
      </figcaption>
    </figure>
  );
}
