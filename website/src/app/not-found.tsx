import Link from "next/link";
export default function NotFound() {
  return (
    <main className="error-page">
      <p className="eyebrow">404 / LOST IN THE RHYTHM</p>
      <h1>这一页，暂时无声。</h1>
      <p>This page could not be found.</p>
      <Link className="button button-primary" href="/zh">
        返回首页 / Home
      </Link>
    </main>
  );
}
