import { NextResponse } from "next/server";

const PLAYLISTS: Record<string, string> = {
  "Việt Nam": "https://iptv-org.github.io/iptv/countries/vn.m3u",
  "Thể thao": "https://iptv-org.github.io/iptv/categories/sports.m3u",
  "Phim quốc tế": "https://iptv-org.github.io/iptv/categories/movies.m3u",
  "Giải trí": "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
  "Tin tức": "https://iptv-org.github.io/iptv/categories/news.m3u",
  "Thiếu nhi": "https://iptv-org.github.io/iptv/categories/kids.m3u",
  "Tiếng Việt": "https://iptv-org.github.io/iptv/languages/vie.m3u",
};
type Catchup = { url: string; template: string; days: number };
type Channel = { id: string; name: string; logo: string; group: string; category: string; url: string; sources: string[]; catchup: Catchup[] };
const attr = (info: string, name: string) => info.match(new RegExp(`(?:^|\\s)${name}="([^"]*)"`, "i"))?.[1]?.trim() || "";
const norm = (name: string) => name.split(/[([]/)[0].normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/gi, "d").toLowerCase().replace(/[^a-z0-9]/g, "").replace(/(?:hd|sd)$/, "");
function parse(text: string, category: string): Channel[] {
  const out: Channel[] = [];
  let info = "";
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith("#EXTINF")) { info = line; continue; }
    if (!info || !/^https?:\/\//i.test(line)) continue;
    const quoted = info.match(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/);
    const name = (quoted ? info.slice((quoted.index || 0) + 1) : attr(info, "tvg-name")).trim();
    if (name) {
      const template = attr(info, "catchup-source");
      const days = Number(attr(info, "catchup-days") || attr(info, "timeshift")) || 0;
      out.push({ id: attr(info, "tvg-id") || name, name: name.replace(/\s*[([][^)\]]*[)\]]/g, "").trim(), logo: attr(info, "tvg-logo"), group: attr(info, "group-title") || "Việt Nam", category, url: line, sources: [line], catchup: template ? [{ url: line, template, days }] : [] });
    }
    info = "";
  }
  return out;
}
export async function GET(request: Request) {
  const category = new URL(request.url).searchParams.get("category") || "Việt Nam";
  if (!(category in PLAYLISTS)) return NextResponse.json({ error: "unknown_category" }, { status: 400 });
  try {
    const urls = category === "Việt Nam" ? [PLAYLISTS[category], PLAYLISTS["Tiếng Việt"]] : [PLAYLISTS[category]];
    const results = await Promise.allSettled(urls.map(async url => {
      const response = await fetch(url, { next: { revalidate: 3600 } });
      if (!response.ok) throw new Error(String(response.status));
      return parse(await response.text(), category);
    }));
    const merged = new Map<string, Channel>();
    for (const result of results) if (result.status === "fulfilled") for (const item of result.value) {
      const key = norm(item.name) || item.name.toLowerCase();
      const old = merged.get(key);
      if (!old) merged.set(key, item);
      else { if (!old.sources.includes(item.url)) old.sources.push(item.url); old.catchup.push(...item.catchup); }
    }
    if (!merged.size) throw new Error("empty_playlist");
    return NextResponse.json([...merged.values()].slice(0, 550), { headers: { "Cache-Control": "public, s-maxage=3600, stale-while-revalidate=86400" } });
  } catch { return NextResponse.json({ error: "playlist_unavailable" }, { status: 503 }); }
}
