"use client";
import { useEffect, useRef } from "react";
import { usePathname } from "next/navigation";
export function ReadingProgress() {
  const bar = useRef<HTMLDivElement>(null);
  const path = usePathname();
  useEffect(() => {
    let frame = 0;
    const paint = () => {
      frame = 0;
      const range = document.documentElement.scrollHeight - innerHeight;
      if (bar.current)
        bar.current.style.transform = `scaleX(${range > 0 ? Math.min(1, Math.max(0, scrollY / range)) : 0})`;
    };
    const update = () => {
      if (!frame) frame = requestAnimationFrame(paint);
    };
    update();
    addEventListener("scroll", update, { passive: true });
    addEventListener("resize", update);
    return () => {
      cancelAnimationFrame(frame);
      removeEventListener("scroll", update);
      removeEventListener("resize", update);
    };
  }, [path]);
  return (
    <div
      ref={bar}
      className="reading-progress"
      aria-hidden="true"
      style={{ transform: "scaleX(0)" }}
    />
  );
}
