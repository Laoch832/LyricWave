"use client";
import { useState } from "react";
import {
  Check,
  Database,
  Fingerprint,
  Languages,
  RefreshCw,
} from "lucide-react";
import type { Locale } from "@/lib/i18n";
export function TranslationDemo({ locale }: { locale: Locale }) {
  const [changed, setChanged] = useState(false);
  const zh = locale === "zh";
  return (
    <div className="translation-demo">
      <div className="demo-label">
        <Languages size={14} />
        {zh ? "缓存逻辑 · 概念演示" : "CACHE LOGIC · CONCEPT"}
      </div>
      <div className="source-line">
        <small>SOURCE / TTML</small>
        <p>
          {changed
            ? "Let the new words find their way."
            : "Let the words find their way."}
        </p>
        <code>
          <Fingerprint size={12} />
          {changed ? "sha256: b8e4…c02f" : "sha256: 91a7…d03b"}
        </code>
      </div>
      <div className="flow-line">
        <span />
        <Fingerprint size={17} />
        <span />
      </div>
      <div className={`cache-result ${changed ? "changed" : ""}`}>
        <div>
          {changed ? <RefreshCw size={19} /> : <Database size={19} />}
          <strong>
            {changed
              ? zh
                ? "原文已变化"
                : "Source changed"
              : zh
                ? "缓存可复用"
                : "Cache reusable"}
          </strong>
          <span className="result-chip">
            {changed ? "REVALIDATE" : "CACHE HIT"}
          </span>
        </div>
        <p>
          {changed
            ? zh
              ? "旧译文失效，需重新翻译与校验。"
              : "Invalidate the old translation and validate a new one."
            : "让文字，找到回响。"}
        </p>
      </div>
      <button className="text-action" onClick={() => setChanged(!changed)}>
        {changed ? <Check size={14} /> : <RefreshCw size={14} />}
        {changed
          ? zh
            ? "重置演示"
            : "Reset demo"
          : zh
            ? "模拟原歌词更新"
            : "Simulate a source update"}
      </button>
      <p className="demo-disclaimer">
        {zh
          ? "仅演示本地状态；不会调用 AI 接口。"
          : "Local state demonstration. No AI requests are sent."}
      </p>
    </div>
  );
}
