"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Captions, Clock3, Copy, ExternalLink, Heart, Maximize, Play, Radio, Search, Tv2, WifiOff, X, CalendarDays } from "lucide-react";

type Channel = { id: string; name: string; logo: string; group: string; category: string; url: string; sources: string[] };
type Program = { startMs: number; stopMs: number; title: string; desc?: string };
const categories = ["Việt Nam", "Thể thao", "Phim quốc tế", "Giải trí", "Tin tức", "Thiếu nhi"];

declare global {
  interface Document {
    modelContext?: { registerTool: (tool: unknown, options?: { signal?: AbortSignal }) => void | Promise<void> };
  }
}

const starter: Channel[] = [
  { id: "loading-1", name: "Đang tải kênh Việt Nam…", logo: "", group: "Truyền hình", category: "Việt Nam", url: "", sources: [] },
];

function parseM3u(text: string): Channel[] {
  const lines = text.split(/\r?\n/);
  const result: Channel[] = [];
  const attr = (line: string, name: string) => line.match(new RegExp(`${name}="([^"]*)"`, "i"))?.[1]?.trim() || "";
  for (let i = 0; i < lines.length; i++) {
    const info = lines[i].trim();
    if (!info.startsWith("#EXTINF")) continue;
    const url = lines.slice(i + 1).find((line) => line.trim() && !line.trim().startsWith("#"))?.trim() || "";
    if (!/^https?:\/\//i.test(url)) continue;
    const name = info.slice(info.indexOf(",") + 1).trim();
    if (!name) continue;
    result.push({ id: attr(info, "tvg-id") || `${name}-${result.length}`, name, logo: attr(info, "tvg-logo"), group: attr(info, "group-title") || "Việt Nam", category: "Việt Nam", url, sources: [url] });
  }
  return result.filter((channel, index, all) => all.findIndex((item) => item.url === channel.url) === index);
}

export default function Home() {
  const [channels, setChannels] = useState<Channel[]>(starter);
  const [selected, setSelected] = useState<Channel | null>(null);
  const [query, setQuery] = useState("");
  const [tab, setTab] = useState<"all" | "favorites" | "recent">("all");
  const [category, setCategory] = useState("Việt Nam");
  const [sourceIndex, setSourceIndex] = useState(0);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const [scheduleError, setScheduleError] = useState("");
  const [favorites, setFavorites] = useState<string[]>([]);
  const [recent, setRecent] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const [hasCaptions, setHasCaptions] = useState(false);
  const [captionsOn, setCaptionsOn] = useState(false);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !selected?.url) return;
    let cancelled = false;
    let player: import("hls.js").default | undefined;
    setHasCaptions(false);
    setCaptionsOn(false);
    // Một track khai báo trong manifest có thể rỗng; chỉ xác nhận CC khi có cue thật.
    const verified = () => setHasCaptions(Array.from(video.textTracks).some(track => Array.from(track.cues || []).some(cue => Boolean((cue as VTTCue).text?.trim()))));
    const observeTracks = () => { for (const track of Array.from(video.textTracks)) { if (track.mode === "disabled") track.mode = "hidden"; track.addEventListener("cuechange", verified); } verified(); };
    video.textTracks.addEventListener("addtrack", observeTracks);
    const url = selected.sources?.[sourceIndex] || selected.url;
    if (/\.m3u8(?:\?|$)/i.test(url) && !video.canPlayType("application/vnd.apple.mpegurl")) {
      import("hls.js").then(({ default: Hls }) => {
        if (cancelled) return;
        if (!Hls.isSupported()) { setError("Trình duyệt này không hỗ trợ luồng phát. Hãy mở bằng VLC."); return; }
        player = new Hls({ enableWorker: true });
        player.loadSource(url);
        player.attachMedia(video);
        player.on(Hls.Events.MANIFEST_PARSED, () => { if (!cancelled) void video.play().catch(() => {}); });
        player.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, observeTracks);
        player.on(Hls.Events.ERROR, (_, data) => { if (data.fatal && !cancelled) setError("Không phát được nguồn này. Hãy đổi sang nguồn dự phòng hoặc mở bằng VLC."); });
      }).catch(() => setError("Không tải được trình phát. Vui lòng thử lại."));
    } else {
      video.src = url;
      void video.play().catch(() => {});
    }
    observeTracks();
    return () => { cancelled = true; video.textTracks.removeEventListener("addtrack", observeTracks); for (const track of Array.from(video.textTracks)) track.removeEventListener("cuechange", verified); player?.destroy(); video.removeAttribute("src"); video.load(); };
  }, [selected?.url, selected?.sources, sourceIndex]);

  useEffect(() => {
    if (!selected?.name || !scheduleOpen) return;
    setScheduleError(""); setPrograms([]);
    fetch(`/api/schedule?name=${encodeURIComponent(selected.name)}`).then(async response => {
      if (!response.ok) throw new Error();
      return response.json();
    }).then(data => setPrograms(data.items || [])).catch(() => setScheduleError("Không tải được lịch phát sóng."));
  }, [selected?.name, scheduleOpen]);

  const remind = (program: Program) => {
    if (!selected) return;
    const stamp = (time: number) => new Date(time).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
    const escape = (value: string) => value.replace(/\\/g, "\\\\").replace(/,/g, "\\,").replace(/;/g, "\\;").replace(/\n/g, "\\n");
    const content = [`BEGIN:VCALENDAR`, `VERSION:2.0`, `PRODID:-//theVu TV//Reminder//VI`, `BEGIN:VEVENT`, `UID:${program.startMs}-${encodeURIComponent(selected.id)}@thevu-tv`, `DTSTAMP:${stamp(Date.now())}`, `DTSTART:${stamp(program.startMs)}`, `DTEND:${stamp(program.stopMs)}`, `SUMMARY:${escape(`${selected.name}: ${program.title}`)}`, `DESCRIPTION:${escape("Nhắc xem trên theVũ TV")}`, `BEGIN:VALARM`, `ACTION:DISPLAY`, `DESCRIPTION:Sắp đến giờ xem`, `TRIGGER:-PT5M`, `END:VALARM`, `END:VEVENT`, `END:VCALENDAR`].join("\r\n");
    const href = URL.createObjectURL(new Blob([content], { type: "text/calendar;charset=utf-8" }));
    const anchor = document.createElement("a"); anchor.href = href; anchor.download = "nhac-xem-thevu-tv.ics"; anchor.click(); setTimeout(() => URL.revokeObjectURL(href), 1000);
  };

  const toggleCaptions = () => {
    const video = videoRef.current;
    if (!video) return;
    const next = !captionsOn;
    Array.from(video.textTracks).forEach(track => { track.mode = next ? "showing" : "disabled"; });
    setCaptionsOn(next);
  };

  useEffect(() => {
    try {
      setFavorites(JSON.parse(localStorage.getItem("thevu-tv:favorites") || "[]"));
      setRecent(JSON.parse(localStorage.getItem("thevu-tv:recent") || "[]"));
    } catch {}
    if ("serviceWorker" in navigator) void navigator.serviceWorker.register("/sw.js");
  }, []);

  useEffect(() => {
    setChannels(starter); setSelected(null);
    fetch(`/api/channels?category=${encodeURIComponent(category)}`)
      .then(async (r) => {
        if (r.ok) return r.json();
        const fallback = await fetch("https://iptv-org.github.io/iptv/countries/vn.m3u");
        if (!fallback.ok) throw new Error();
        return parseM3u(await fallback.text());
      })
      .then((data) => { const list = Array.isArray(data) ? data as Channel[] : []; setChannels(list); if (list.length) { setSelected(list[0]); setSourceIndex(0); } })
      .catch(() => { setChannels([]); setError("Chưa tải được danh sách kênh. Vui lòng thử lại sau."); });
  }, [category]);

  useEffect(() => {
    const context = document.modelContext;
    if (!context?.registerTool) return;
    const lifecycle = new AbortController();
    const register = async () => {
      await context.registerTool({
        name: "search_tv_channels",
        title: "Tìm kênh truyền hình",
        description: "Tìm các kênh đang có trong theVũ TV và cập nhật danh sách hiển thị.",
        inputSchema: { type: "object", properties: { query: { type: "string" } }, required: ["query"], additionalProperties: false },
        annotations: { readOnlyHint: true, untrustedContentHint: true },
        execute(input: unknown) {
          const value = typeof input === "object" && input && "query" in input ? String((input as { query: unknown }).query) : "";
          setTab("all");
          setQuery(value);
          const matches = channels.filter((c) => `${c.name} ${c.group}`.toLocaleLowerCase("vi").includes(value.toLocaleLowerCase("vi"))).slice(0, 20);
          return { count: matches.length, channels: matches.map(({ id, name, group }) => ({ id, name, group })) };
        },
      }, { signal: lifecycle.signal });
      await context.registerTool({
        name: "play_tv_channel",
        title: "Phát kênh truyền hình",
        description: "Chọn và phát một kênh trong theVũ TV theo mã kênh.",
        inputSchema: { type: "object", properties: { channelId: { type: "string" } }, required: ["channelId"], additionalProperties: false },
        annotations: { readOnlyHint: false, untrustedContentHint: true },
        execute(input: unknown) {
          const id = typeof input === "object" && input && "channelId" in input ? String((input as { channelId: unknown }).channelId) : "";
          const channel = channels.find((c) => c.id === id);
          if (!channel?.url) throw new Error("Không tìm thấy kênh");
          choose(channel);
          return { selected: channel.name, id: channel.id };
        },
      }, { signal: lifecycle.signal });
    };
    void register().catch(() => {});
    return () => lifecycle.abort();
  }, [channels]);

  const choose = (channel: Channel) => {
    if (!channel.url) return;
    setSelected(channel);
    setSourceIndex(0);
    setScheduleOpen(false);
    setError("");
    const next = [channel.id, ...recent.filter((id) => id !== channel.id)].slice(0, 12);
    setRecent(next);
    localStorage.setItem("thevu-tv:recent", JSON.stringify(next));
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const toggleFavorite = (id: string) => {
    const next = favorites.includes(id) ? favorites.filter((item) => item !== id) : [id, ...favorites];
    setFavorites(next);
    localStorage.setItem("thevu-tv:favorites", JSON.stringify(next));
  };

  const visible = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase("vi");
    return channels.filter((channel) => {
      const matches = !normalized || `${channel.name} ${channel.group}`.toLocaleLowerCase("vi").includes(normalized);
      const inTab = tab === "all" || (tab === "favorites" ? favorites.includes(channel.id) : recent.includes(channel.id));
      return matches && inTab;
    }).sort((a, b) => tab === "recent" ? recent.indexOf(a.id) - recent.indexOf(b.id) : a.name.localeCompare(b.name, "vi"));
  }, [channels, favorites, query, recent, tab]);

  const openVlc = () => {
    if (!selected?.url) return;
    window.location.href = `vlc://${(selected.sources?.[sourceIndex] || selected.url).replace(/^https?:\/\//, "")}`;
  };

  const copyUrl = async () => {
    if (!selected?.url) return;
    await navigator.clipboard.writeText(selected.sources?.[sourceIndex] || selected.url);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  };

  return (
    <main className="min-h-screen bg-[#050a12] text-white">
      <header className="sticky top-0 z-30 border-b border-white/10 bg-[#07101d]/90 backdrop-blur-xl">
        <div className="mx-auto flex max-w-[1500px] items-center gap-3 px-4 py-3 md:px-8">
          <div className="brand-mark"><span>Vũ</span></div>
          <div className="mr-auto">
            <h1 className="text-lg font-bold tracking-tight"><span className="text-orange-400">theVũ</span><span className="text-cyan-300">TV</span></h1>
            <p className="text-xs text-slate-400">Kênh công khai • Việt Nam mặc định</p>
          </div>
        </div>
      </header>

      <div className="mx-auto grid max-w-[1500px] gap-6 px-4 py-5 md:px-8 lg:grid-cols-[minmax(0,1.7fr)_minmax(330px,.75fr)]">
        <section className="min-w-0">
          <div className="player-shell">
            {selected?.url ? (
              <video ref={videoRef} key={selected.url} className="aspect-video w-full bg-black object-contain" controls playsInline
                poster={selected.logo || undefined} onError={() => setError("Trình duyệt không phát được luồng này. Hãy chọn “Mở bằng VLC”.")} />
            ) : (
              <div className="grid aspect-video place-items-center bg-black/60">
                <div className="text-center text-slate-400"><Radio className="mx-auto mb-3" size={42} /><p>Chọn một kênh để bắt đầu</p></div>
              </div>
            )}
          </div>

          <div className="mt-4 flex flex-wrap items-start gap-3">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[.14em] text-cyan-400"><span className="live-dot" /> Đang xem</div>
              <h2 className="mt-1 truncate text-2xl font-bold">{selected?.name || "theVũ TV"}</h2>
              <p className="mt-1 text-sm text-slate-400">{selected?.group || "Danh sách truyền hình công khai"}</p>
            </div>
            <div className="flex gap-2">
              {hasCaptions && <button className={`icon-button ${captionsOn ? "is-active" : ""}`} onClick={toggleCaptions} aria-label={captionsOn ? "Tắt phụ đề" : "Bật phụ đề"} title="Phụ đề CC"><Captions size={20} /></button>}
              {selected && <button className={`icon-button ${favorites.includes(selected.id) ? "is-active" : ""}`} onClick={() => toggleFavorite(selected.id)} aria-label="Yêu thích"><Heart size={19} fill={favorites.includes(selected.id) ? "currentColor" : "none"} /></button>}
              <button className="icon-button" onClick={() => videoRef.current?.requestFullscreen()} aria-label="Toàn màn hình"><Maximize size={19} /></button>
            </div>
          </div>

          {selected?.url && <div className="mt-3 flex flex-wrap items-center gap-2">
            <button className="secondary-action px-4" onClick={() => setScheduleOpen(!scheduleOpen)}><CalendarDays size={17} /> Lịch phát sóng</button>
            {(selected.sources?.length || 0) > 1 && <label className="text-sm text-slate-300">Nguồn phát <select className="source-select" value={sourceIndex} onChange={e => { setSourceIndex(Number(e.target.value)); setError(""); }} aria-label="Chọn nguồn phát">{selected.sources.map((_, index) => <option key={index} value={index}>Nguồn {index + 1}</option>)}</select></label>}
          </div>}

          {scheduleOpen && <div className="schedule-panel"><h3>Lịch phát sóng · {selected?.name}</h3>{scheduleError ? <p>{scheduleError}</p> : programs.length ? <div className="schedule-list">{programs.filter(p => p.stopMs > Date.now() - 86400000 && p.startMs < Date.now() + 2 * 86400000).map((program, i) => <div className="schedule-row" key={`${program.startMs}-${i}`}><time>{new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }).format(program.startMs)}</time><strong>{program.title}</strong>{program.startMs > Date.now() && <button onClick={() => remind(program)}>Nhắc xem</button>}</div>)}</div> : <p>Chưa có lịch phát sóng cho kênh này.</p>}</div>}

          {error && <div className="notice"><WifiOff size={18} /><span>{error}</span><button onClick={() => setError("")} aria-label="Đóng"><X size={17} /></button></div>}

          <div className="mt-5 grid gap-3 sm:grid-cols-2">
            <button className="primary-action" onClick={openVlc} disabled={!selected?.url}><ExternalLink size={19} /> Mở bằng VLC</button>
            <button className="secondary-action" onClick={copyUrl} disabled={!selected?.url}><Copy size={18} /> {copied ? "Đã sao chép liên kết" : "Sao chép liên kết"}</button>
          </div>
          <p className="mt-3 text-xs leading-5 text-slate-500">Một số kênh có thể giới hạn theo khu vực hoặc chỉ phát được trong VLC. theVũ TV không lưu trữ nội dung truyền hình.</p>
        </section>

        <aside className="channel-panel">
          <div className="p-4 pb-3">
            <div className="category-list" role="group" aria-label="Nhóm kênh">{categories.map(value => <button key={value} className={value === category ? "category active" : "category"} onClick={() => setCategory(value)}>{value}</button>)}</div>
            <div className="search-box"><Search size={18} /><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Tìm VTV, HTV, THVL…" aria-label="Tìm kênh" />{query && <button onClick={() => setQuery("")} aria-label="Xóa tìm kiếm"><X size={17} /></button>}</div>
            <div className="mt-3 grid grid-cols-3 gap-1 rounded-xl bg-black/30 p-1">
              <button className={tab === "all" ? "tab active" : "tab"} onClick={() => setTab("all")}><Tv2 size={15} /> Tất cả</button>
              <button className={tab === "favorites" ? "tab active" : "tab"} onClick={() => setTab("favorites")}><Heart size={15} /> Yêu thích</button>
              <button className={tab === "recent" ? "tab active" : "tab"} onClick={() => setTab("recent")}><Clock3 size={15} /> Gần đây</button>
            </div>
          </div>

          <div className="channel-list">
            {visible.length ? visible.map((channel) => (
              <button key={channel.id} className={`channel-row ${selected?.id === channel.id ? "selected" : ""}`} onClick={() => choose(channel)} disabled={!channel.url}>
                <span className="channel-logo">{channel.logo ? <img src={channel.logo} alt="" loading="lazy" /> : <Tv2 size={21} />}</span>
                <span className="min-w-0 flex-1 text-left"><strong>{channel.name}</strong><small>{channel.group}</small></span>
                {channel.url && <Play size={17} fill="currentColor" />}
              </button>
            )) : (
              <div className="empty-state"><Heart size={28} /><p>{tab === "favorites" ? "Chưa có kênh yêu thích" : tab === "recent" ? "Chưa xem kênh nào" : "Không tìm thấy kênh"}</p><span>{tab !== "all" ? "Các kênh bạn chọn sẽ xuất hiện tại đây." : "Thử một từ khóa khác."}</span></div>
            )}
          </div>
          <div className="border-t border-white/10 px-4 py-3 text-xs text-slate-500">{visible.filter(c => c.url).length} kênh • Nguồn IPTV-org</div>
        </aside>
      </div>
    </main>
  );
}
