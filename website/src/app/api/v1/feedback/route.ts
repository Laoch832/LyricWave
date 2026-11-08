import { receiveFeedback, readFeedback } from "@/lib/feedback-api";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) { return receiveFeedback(request); }
export async function GET(request: Request) { return readFeedback(request); }
