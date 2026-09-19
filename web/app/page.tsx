"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Captions, Clock3, Heart, Maximize, Play, Radio, Search, Tv2, X, CalendarDays, Circle, Square, PictureInPicture2, Timer, RotateCcw } from "lucide-react";

type Catchup = { url: string; template: string; days: number };
type Channel = { id: string; name: string; logo: string; group: string; category: string; url: string; sources: string[]; catchup?: Catchup[] };
type Program = { startMs: number; stopMs: number; title: string; desc?: string };
const categories = ["Việt Nam", "Thể thao", "Phim quốc tế", "Giải trí", "Tin tức", "Thiếu nhi"];
const dayStart = (offset: number) => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + offset); return d.getTime(); };
function replayUrl(channel: Channel, program: Program): string | null {
  if (program.stopMs >= Date.now()) return null;
  for (const source of channel.catchup || []) {
    if (source.days > 0 && Date.now() - program.startMs > source.days * 86400000) continue;
    const start = Math.floor(program.startMs / 1000);
    const duration = Math.max(1, Math.floor((program.stopMs - program.startMs) / 1000));
    const expanded = source.template.replace(/\$?\{(utc|lutc|start|timestamp)\}/g, String(start)).replace(/\$?\{duration\}/g, String(duration));
    const url = expanded.startsWith("?") ? source.url.split("?")[0] + expanded : expanded.startsWith("&") ? source.url + expanded : expanded;
    if (/^https?:\/\//i.test(url)) return url;
  }
  return null;
}

declare global {
  interface Document {
    modelContext?: { registerTool: (tool: unknown, options?: { signal?: AbortSignal }) => void | Promise<void> };
  }
}

const starter: Channel[] = [
  { id: "loading-1", name: "Đang tải kênh Việt Nam…", logo: "", group: "Truyền hình", category: "Việt Nam", url: "", sources: [] },
];

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
  const [scheduleLoading, setScheduleLoading] = useState(false);
  const [replay, setReplay] = useState<string | null>(null);
  const [recording, setRecording] = useState(false);
  const [recordMessage, setRecordMessage] = useState("");
  const [sleepMinutes, setSleepMinutes] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [favorites, setFavorites] = useState<string[]>([]);
  const [recent, setRecent] = useState<string[]>([]);
  const [error, setError] = useState("");
  const videoRef = useRef<HTMLVideoElement>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const recordChunks = useRef<Blob[]>([]);
  const stopTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [hasCaptions, setHasCaptions] = useState(false);
  const [captionsOn, setCaptionsOn] = useState(false);

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (!sleepMinutes) return;
    const timer = setTimeout(() => {
      videoRef.current?.pause();
      setSleepMinutes(0);
      setError("Đã dừng phát theo hẹn giờ tắt.");
    }, sleepMinutes * 60000);
    return () => clearTimeout(timer);
  }, [sleepMinutes]);

  const stopRecording = () => {
    if (stopTimer.current) { clearTimeout(stopTimer.current); stopTimer.current = null; }
    if (recorderRef.current?.state === "recording") {
      recorderRef.current.requestData();
      recorderRef.current.stop();
      setRecordMessage("Đang chuẩn bị tệp ghi hình…");
    }
  };

  const enterPictureInPicture = () => {
    const video = videoRef.current;
    if (!video?.requestPictureInPicture) { setError("Trình duyệt này không hỗ trợ cửa sổ nhỏ."); return; }
    void video.requestPictureInPicture().catch(() => setError("Hãy phát kênh trước khi mở cửa sổ nhỏ."));
  };

  const startRecording = async () => {
    const video = videoRef.current as (HTMLVideoElement & { captureStream?: () => MediaStream }) | null;
    if (!video || video.readyState < 2 || video.paused) {
      setRecordMessage("Kênh chưa phát được trên trình duyệt. Hãy phát kênh hoặc thử nguồn khác trước khi ghi hình.");
      return;
    }
    if (!window.MediaRecorder) {
      setRecordMessage("Trình duyệt này không hỗ trợ ghi hình. Hãy thử Chrome hoặc Edge trên máy tính.");
      return;
    }
    let stream: MediaStream | undefined;
    try {
      // Prefer the media element; a tab capture works when captureStream is absent
      // or the source prohibits direct capture. The chooser requires a user gesture.
      try { stream = video.captureStream?.(); } catch { /* try tab capture */ }
      if (!stream?.getVideoTracks().some(track => track.readyState === "live")) {
        stream?.getTracks().forEach(track => track.stop());
        if (!navigator.mediaDevices?.getDisplayMedia) {
          setRecordMessage("Trình duyệt này không hỗ trợ ghi tab. Hãy thử Chrome hoặc Edge trên máy tính.");
          return;
        }
        setRecordMessage("Chọn tab theVũ TV trong cửa sổ chia sẻ và bật ‘Chia sẻ âm thanh của thẻ’ để ghi cả tiếng.");
        stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true, preferCurrentTab: true } as DisplayMediaStreamOptions);
        if (!stream.getVideoTracks().some(track => track.readyState === "live")) throw new Error("no_video_track");
      }
      if (!stream) throw new Error("no_stream");
      const capture = stream;
      const mimeType = ["video/webm;codecs=vp9,opus", "video/webm;codecs=vp8,opus", "video/webm", "video/mp4"].find(type => MediaRecorder.isTypeSupported(type));
      if (!mimeType) throw new Error("unsupported_format");
      const recorder = new MediaRecorder(stream, { mimeType });
      const filename = `${(selected?.name || "TV").replace(/[^\p{L}\p{N} _-]/gu, "").trim().slice(0, 40) || "TV"}-${new Date().toISOString().replace(/[:.]/g, "-")}.${mimeType.includes("mp4") ? "mp4" : "webm"}`;
      let bytes = 0;
      recordChunks.current = [];
      recorder.ondataavailable = event => {
        if (event.data.size) { recordChunks.current.push(event.data); bytes += event.data.size; }
        if (bytes >= 250 * 1024 * 1024) stopRecording();
      };
      recorder.onstop = () => {
        const chunks = recordChunks.current;
        recordChunks.current = [];
        recorderRef.current = null;
        setRecording(false);
        capture.getTracks().forEach(track => track.stop());
        if (!chunks.length) { setRecordMessage("Bản ghi trống. Nguồn này không hỗ trợ ghi trên trình duyệt."); return; }
        const href = URL.createObjectURL(new Blob(chunks, { type: mimeType }));
        const link = document.createElement("a"); link.href = href; link.download = filename;
        document.body.appendChild(link); link.click(); link.remove();
        setRecordMessage("Đã tải bản ghi xuống thiết bị.");
        setTimeout(() => URL.revokeObjectURL(href), 60000);
      };
      recorder.onerror = () => { setRecordMessage("Không thể ghi luồng này trên trình duyệt."); stopRecording(); };
      capture.getVideoTracks().forEach(track => { track.onended = () => { if (recorder.state === "recording") stopRecording(); }; });
      recorderRef.current = recorder;
      recorder.start(1000);
      setRecording(true); setRecordMessage("Đang ghi hình. Giữ trang mở; bấm ‘Dừng và lưu bản ghi’ để tải tệp.");
      stopTimer.current = setTimeout(stopRecording, 30 * 60000);
    } catch (cause) {
      stream?.getTracks().forEach(track => track.stop());
      setRecordMessage(cause instanceof DOMException && cause.name === "NotAllowedError"
        ? "Đã hủy chọn tab. Bấm Ghi hình để thử lại và chọn tab theVũ TV."
        : "Không ghi được nguồn này. Hãy thử phát lại hoặc dùng Chrome/Edge trên máy tính.");
    }
  };

  useEffect(() => { stopRecording(); }, [selected?.url, sourceIndex, replay]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !selected?.url) return;
    let cancelled = false;
    let failed = false;
    let player: import("hls.js").default | undefined;
    setHasCaptions(false);
    setCaptionsOn(false);
    // Một track khai báo trong manifest có thể rỗng; chỉ xác nhận CC khi có cue thật.
    const verified = () => setHasCaptions(Array.from(video.textTracks).some(track => Array.from(track.cues || []).some(cue => Boolean((cue as VTTCue).text?.trim()))));
    const observeTracks = () => { for (const track of Array.from(video.textTracks)) { if (track.mode === "disabled") track.mode = "hidden"; track.addEventListener("cuechange", verified); } verified(); };
    video.textTracks.addEventListener("addtrack", observeTracks);
    const url = replay || selected.sources?.[sourceIndex] || selected.url;
    const fail = () => {
      if (cancelled || failed) return;
      failed = true;
      if (replay) { setError("Không phát lại được chương trình này. Hãy trở về kênh trực tiếp."); return; }
      if (sourceIndex + 1 < (selected.sources?.length || 1)) {
        setError(`Nguồn ${sourceIndex + 1} không phát được. Đang thử nguồn dự phòng…`);
        setSourceIndex(sourceIndex + 1);
      } else setError("Các nguồn hiện chưa phát được trên trình duyệt.");
    };
    const playing = () => setError("");
    video.addEventListener("error", fail);
    video.addEventListener("playing", playing);
    if (/\.m3u8(?:\?|$)/i.test(url) && !video.canPlayType("application/vnd.apple.mpegurl")) {
      import("hls.js").then(({ default: Hls }) => {
        if (cancelled) return;
        if (!Hls.isSupported()) { fail(); return; }
        player = new Hls({ enableWorker: true });
        player.loadSource(url);
        player.attachMedia(video);
        player.on(Hls.Events.MANIFEST_PARSED, () => { if (!cancelled) void video.play().catch(() => {}); });
        player.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, observeTracks);
        player.on(Hls.Events.ERROR, (_, data) => { if (data.fatal) fail(); });
      }).catch(fail);
    } else {
      video.src = url;
      void video.play().catch(() => {});
    }
    observeTracks();
    return () => { cancelled = true; video.textTracks.removeEventListener("addtrack", observeTracks); video.removeEventListener("error", fail); video.removeEventListener("playing", playing); for (const track of Array.from(video.textTracks)) track.removeEventListener("cuechange", verified); player?.destroy(); video.removeAttribute("src"); video.load(); };
  }, [selected?.url, selected?.sources, sourceIndex, replay]);

  useEffect(() => {
    if (!selected?.name) return;
    const controller = new AbortController();
    setScheduleError(""); setPrograms([]); setScheduleLoading(true);
    fetch(`/api/schedule?name=${encodeURIComponent(selected.name)}`, { signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error();
      return response.json();
    }).then(data => { if (!controller.signal.aborted) { setPrograms(data.items || []); setScheduleLoading(false); } }).catch(() => { if (!controller.signal.aborted) { setScheduleError("Không tải được lịch phát sóng."); setScheduleLoading(false); } });
    return () => controller.abort();
  }, [selected?.name]);

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
    const controller = new AbortController();
    setChannels(starter); setSelected(null);
    fetch(`/api/channels?category=${encodeURIComponent(category)}`, { signal: controller.signal })
      .then(async (r) => {
        if (r.ok) return r.json();
        throw new Error("playlist_unavailable");
      })
      .then((data) => { const list = Array.isArray(data) ? data as Channel[] : []; if (!controller.signal.aborted) { setChannels(list); if (list.length) { localStorage.setItem(`thevu-tv:channels:${category}`, JSON.stringify(list)); setSelected(list[0]); setSourceIndex(0); setError(""); } } })
      .catch(() => { if (controller.signal.aborted) return; try { const cached = JSON.parse(localStorage.getItem(`thevu-tv:channels:${category}`) || "[]") as Channel[]; if (cached.length) { setChannels(cached); setSelected(cached[0]); setSourceIndex(0); setError("Đang dùng danh sách kênh đã lưu vì chưa tải được bản cập nhật."); return; } } catch {} setChannels([]); setError("Chưa tải được danh sách kênh. Vui lòng thử lại sau."); });
    return () => controller.abort();
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
    stopRecording();
    setRecordMessage("");
    setSelected(channel);
    setReplay(null);
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
  const currentProgram = programs.find(program => program.startMs <= now && program.stopMs > now);
  const scheduleItems = programs.filter(program => program.stopMs > dayStart(-1) && program.startMs < dayStart(2)).sort((a, b) => a.startMs - b.startMs);

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
              <video ref={videoRef} key={selected.url} className="aspect-video w-full bg-black object-contain" controls playsInline poster={selected.logo || undefined} onEnded={() => { if (replay) setReplay(null); }} />
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
              <p className="mt-1 text-sm text-slate-400">{replay ? "Đang phát lại" : currentProgram ? `Đang phát: ${currentProgram.title}` : selected?.group || "Danh sách truyền hình công khai"}</p>
            </div>
            <div className="flex gap-2">
              {hasCaptions && <button className={`icon-button ${captionsOn ? "is-active" : ""}`} onClick={toggleCaptions} aria-label={captionsOn ? "Tắt phụ đề" : "Bật phụ đề"} title="Phụ đề CC"><Captions size={20} /></button>}
              {selected && <button className={`icon-button ${favorites.includes(selected.id) ? "is-active" : ""}`} onClick={() => toggleFavorite(selected.id)} aria-label="Yêu thích"><Heart size={19} fill={favorites.includes(selected.id) ? "currentColor" : "none"} /></button>}
              <button className="icon-button" onClick={() => videoRef.current?.requestFullscreen()} aria-label="Toàn màn hình"><Maximize size={19} /></button>
            </div>
          </div>

          {selected?.url && <div className="mt-3 flex flex-wrap items-center gap-2">
            <button className="secondary-action px-4" onClick={() => setScheduleOpen(!scheduleOpen)}><CalendarDays size={17} /> Lịch phát sóng</button>
            {replay && <button className="secondary-action px-4" onClick={() => { setReplay(null); setError(""); }}><RotateCcw size={17} /> Trở về trực tiếp</button>}
            {(selected.sources?.length || 0) > 1 && !replay && <label className="text-sm text-slate-300">Nguồn phát <select className="source-select" value={sourceIndex} onChange={e => { stopRecording(); setRecordMessage(""); setSourceIndex(Number(e.target.value)); setError(""); }} aria-label="Chọn nguồn phát">{selected.sources.map((_, index) => <option key={index} value={index}>Nguồn {index + 1}</option>)}</select></label>}
            <button className={recording ? "record-action active" : "record-action"} onClick={recording ? stopRecording : startRecording} aria-label={recording ? "Dừng và tải bản ghi" : "Bắt đầu ghi hình"} title="Tải tệp về khi dừng; giữ trang mở trong lúc ghi (tối đa 30 phút hoặc 250 MB)">{recording ? <Square size={16} fill="currentColor" /> : <Circle size={16} fill="currentColor" />} {recording ? "Dừng và lưu bản ghi" : "Ghi hình"}</button>
            <button className="secondary-action px-3" onClick={enterPictureInPicture} title="Cửa sổ nhỏ" aria-label="Cửa sổ nhỏ"><PictureInPicture2 size={18} /></button>
            <label className="text-sm text-slate-300"><Timer size={17} className="inline" /> Hẹn tắt <select className="source-select" value={sleepMinutes} onChange={e => setSleepMinutes(Number(e.target.value))} aria-label="Hẹn giờ tắt"><option value="0">Tắt</option>{[15, 30, 60, 90].map(value => <option key={value} value={value}>{value} phút</option>)}</select></label>
          </div>}

          {recordMessage && <p role="status" aria-live="polite" className="mt-2 text-sm text-amber-200">{recordMessage}</p>}

          {scheduleOpen && <div className="schedule-panel"><h3>Lịch phát sóng 3 ngày · {selected?.name}</h3><p>Hôm qua · Hôm nay · Ngày mai</p>{scheduleLoading ? <p>Đang tải lịch phát sóng…</p> : scheduleError ? <p>{scheduleError}</p> : scheduleItems.length ? <div className="schedule-list">{scheduleItems.map((program, i) => {
            const date = new Date(program.startMs).toDateString();
            const heading = i === 0 || new Date(scheduleItems[i - 1].startMs).toDateString() !== date;
            const live = program.startMs <= now && program.stopMs > now;
            const past = program.stopMs <= now;
            const url = selected ? replayUrl(selected, program) : null;
            return <div key={`${program.startMs}-${i}`}>{heading && <h4 className="schedule-day">{new Intl.DateTimeFormat("vi-VN", { weekday: "long", day: "2-digit", month: "2-digit" }).format(program.startMs)}</h4>}<div className={`schedule-row ${live ? "is-live" : ""}`}><time>{new Intl.DateTimeFormat("vi-VN", { hour: "2-digit", minute: "2-digit" }).format(program.startMs)}</time><div className="schedule-detail"><strong>{program.title}</strong><small>{live ? "ĐANG PHÁT" : past ? "ĐÃ PHÁT" : "SẮP PHÁT"} · đến {new Intl.DateTimeFormat("vi-VN", { hour: "2-digit", minute: "2-digit" }).format(program.stopMs)}</small>{program.desc && <small>{program.desc}</small>}</div>{past && url && <button onClick={() => { stopRecording(); setReplay(url); setError(""); setScheduleOpen(false); }}>Phát lại</button>}{program.startMs > now && <button onClick={() => remind(program)}>Nhắc xem</button>}</div></div>;
          })}</div> : <p>Chưa có lịch phát sóng cho kênh này.</p>}</div>}

          <p role="status" className="sr-only" aria-live="polite">{error}</p>
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
