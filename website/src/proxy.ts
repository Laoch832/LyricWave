import { NextResponse, type NextRequest } from "next/server";
/** Route-derived language for correct HTML on the first server response. */
export function proxy(request: NextRequest) {
  const language =
    request.nextUrl.pathname.match(/^\/(?:docs\/)?(zh|en)(?:\/|$)/)?.[1] ||
    "zh";
  const headers = new Headers(request.headers);
  headers.set("x-lyricwave-locale", language);
  return NextResponse.next({ request: { headers } });
}
export const config = { matcher: ["/zh/:path*", "/en/:path*", "/docs/:path*"] };
