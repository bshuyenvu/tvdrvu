import { NextResponse } from "next/server";
const BASE = "https://lichphatsong.io.vn/api";
const normalize = (value: string) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/gi, "d").toLowerCase().replace(/[^a-z0-9]/g, "").replace(/(?:hd|sd)$/, "");
export async function GET(request: Request) {
  const name = new URL(request.url).searchParams.get("name")?.slice(0, 100) || "";
  if (!name) return NextResponse.json({ error: "missing_name" }, { status: 400 });
  try {
    const response = await fetch(`${BASE}/channels`, { next: { revalidate: 86400 } });
    if (!response.ok) throw new Error("channels");
    const data = await response.json() as { channels: { id: string; name: string; hasEpg?: boolean }[] };
    const key = normalize(name);
    const channel = data.channels.find(item => item.hasEpg !== false && (normalize(item.name) === key || normalize(item.name).replace(/tv/g, "") === key.replace(/tv/g, "")));
    if (!channel) return NextResponse.json({ items: [] });
    const schedule = await fetch(`${BASE}/schedule/${encodeURIComponent(channel.id)}`, { next: { revalidate: 900 } });
    if (!schedule.ok) throw new Error("schedule");
    const programs = await schedule.json() as { items: { startMs: number; stopMs: number; title: string; desc?: string }[] };
    return NextResponse.json({ items: programs.items.filter(item => item.stopMs > item.startMs && item.title).slice(0, 200) });
  } catch { return NextResponse.json({ error: "schedule_unavailable" }, { status: 503 }); }
}
