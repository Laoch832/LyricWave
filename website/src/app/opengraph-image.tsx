import { ImageResponse } from "next/og";
export const alt = "LyricWave — Every lyric. A deeper resonance.";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";
export default function Image() {
  return new ImageResponse(
    <div
      style={{
        background: "#101315",
        color: "#f1f4f5",
        display: "flex",
        width: "100%",
        height: "100%",
        padding: 80,
        flexDirection: "column",
        justifyContent: "space-between",
      }}
    >
      <div style={{ fontSize: 32, color: "#a9cbd8" }}>LyricWave</div>
      <div
        style={{
          fontSize: 76,
          letterSpacing: -4,
          display: "flex",
          flexDirection: "column",
        }}
      >
        <span>Every lyric.</span>
        <span style={{ color: "#a9cbd8" }}>A deeper resonance.</span>
      </div>
      <div style={{ fontSize: 22, color: "#a5aaaf" }}>
        Apple Music for Android · Open source
      </div>
    </div>,
    size,
  );
}
