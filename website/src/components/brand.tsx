export function WaveMark({ className = "" }: { className?: string }) {
  return (
    <svg
      className={className}
      width="29"
      height="29"
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M4 19V13M10 25V7M16 21V11M22 28V4M28 19V13"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  );
}
export function Brand() {
  return (
    <span className="brand">
      <WaveMark />
      <span>
        LyricWave<span className="brand-cn">澜音</span>
      </span>
    </span>
  );
}
