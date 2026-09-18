package cloud.huyenvu.tv

import java.text.Normalizer

/** Một luồng phát của kênh. note: chất lượng và cờ chặn vùng, ví dụ "1080p · Chặn vùng". */
data class Source(
    val url: String,
    val note: String,
    val catchupSource: String? = null,
    val catchupDays: Int = 0
) {
    /** Tạo URL phát lại theo chuẩn catchup-source phổ biến của M3U/IPTV nếu nguồn có khai báo. */
    fun replayUrl(program: Program): String? {
        val template = catchupSource?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val now = System.currentTimeMillis()
        if (program.stop >= now) return null
        if (catchupDays > 0 && now - program.start > catchupDays * 86_400_000L) return null

        val startSec = program.start / 1000L
        val durationSec = ((program.stop - program.start) / 1000L).coerceAtLeast(1L)
        val expanded = template
            .replace("{utc}", startSec.toString())
            .replace("{lutc}", startSec.toString())
            .replace("{start}", startSec.toString())
            .replace("$" + "{start}", startSec.toString())
            .replace("{timestamp}", startSec.toString())
            .replace("$" + "{timestamp}", startSec.toString())
            .replace("{duration}", durationSec.toString())
            .replace("$" + "{duration}", durationSec.toString())

        return when {
            expanded.startsWith("http://") || expanded.startsWith("https://") -> expanded
            expanded.startsWith("?") -> url.substringBefore("?") + expanded
            expanded.startsWith("&") -> url + expanded
            else -> null
        }
    }
}

/**
 * Một kênh, có thể có nhiều nguồn (nguồn chính + dự phòng). url luôn là nguồn đầu tiên.
 */
data class Channel(
    val id: String,
    val name: String,
    val logo: String,
    val group: String,
    val url: String,
    val category: String,
    val sources: List<Source> = listOf(Source(url, ""))
) {
    fun hasUrl(u: String?): Boolean = u != null && sources.any { it.url == u }

    fun replayUrl(program: Program): String? =
        sources.firstNotNullOfOrNull { it.replayUrl(program) }
}

const val GEO_NOTE = "Chặn vùng"

// Siêu dữ liệu của MediaItem: toàn bộ nguồn của kênh + vị trí nguồn đang phát (dùng để tự chuyển nguồn khi lỗi)
const val EXTRA_SOURCES = "tvdrvu_sources"
const val EXTRA_INDEX = "tvdrvu_index"
const val EXTRA_IS_REPLAY = "tvdrvu_is_replay"

/** Chuẩn hóa tên để so khớp: bỏ chú thích, dấu tiếng Việt, ký tự lạ và hậu tố HD/SD. */
fun normalizeName(raw: String): String {
    var s = raw.substringBefore("(").substringBefore("[")
    s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
    s = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    return s.removeSuffix("hd").removeSuffix("sd")
}

/** Tên hiển thị: bỏ "(1080p)" và "[Geo-blocked]". */
fun displayName(raw: String): String =
    raw.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim().ifBlank { raw.trim() }

private val M3U_ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
private val QUALITY = Regex("""\((\d{3,4}p)\)""", RegexOption.IGNORE_CASE)

private fun extinfName(info: String): String {
    var inQuote = false
    for (i in info.indices) {
        val c = info[i]
        if (c == '"') inQuote = !inQuote
        else if (c == ',' && !inQuote) return info.substring(i + 1).trim()
    }
    return ""
}

/** Một lượt quét O(n). Mỗi URL là một Channel một nguồn; việc gộp nguồn làm ở mergeChannels. */
fun parseM3u(text: String, category: String): List<Channel> {
    val result = LinkedHashMap<String, Channel>()
    var pending: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            pending = line
            continue
        }
        if (line.isEmpty() || line.startsWith("#")) continue
        val info = pending
        pending = null
        if (info == null || !line.startsWith("http") || result.containsKey(line)) continue
        val attrs = M3U_ATTR.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val name = extinfName(info).ifBlank { attrs["tvg-name"].orEmpty() }
        if (name.isBlank()) continue
        val rawGroup = attrs["group-title"].orEmpty()
        val group = rawGroup.takeUnless { it.isBlank() || it.equals("undefined", true) } ?: "Việt Nam"
        val id = attrs["tvg-id"].orEmpty().ifBlank { name }
        val note = listOfNotNull(
            QUALITY.find(name)?.groupValues?.get(1),
            if (name.contains("geo", true)) GEO_NOTE else null
        ).joinToString(" · ")
        val catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
        val catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"]).orEmpty().toIntOrNull() ?: 0
        result[line] = Channel(
            id, name, attrs["tvg-logo"].orEmpty(), group, line, category,
            listOf(Source(line, note, catchupSource, catchupDays))
        )
    }
    return result.values.toList()
}

/**
 * Gộp các mục cùng tên chuẩn hóa thành một kênh nhiều nguồn. Nguồn thường đứng trước,
 * nguồn chặn vùng (Geo) xuống cuối làm dự phòng; thứ tự còn lại giữ như danh sách gốc.
 */
fun mergeChannels(all: List<Channel>): List<Channel> {
    val groups = LinkedHashMap<String, MutableList<Channel>>()
    for (c in all) {
        val key = normalizeName(c.name).ifBlank { c.name.lowercase() }
        groups.getOrPut(key) { mutableListOf() }.add(c)
    }
    return groups.values.map { g ->
        val head = g.first()
        val sources = g.flatMap { it.sources }
            .sortedBy { if (it.note.contains(GEO_NOTE)) 1 else 0 }
            .distinctBy { it.url }
        head.copy(name = displayName(head.name), url = sources.first().url, sources = sources)
    }
}

/** Ghép kênh trong app với mã kênh của dịch vụ lịch phát sóng. */
object EpgMatch {
    private val KEEP_TV = listOf("vtv", "htv", "sctv", "antv", "hitv", "tvb", "you")

    fun candidates(channelName: String, channelId: String): List<String> {
        val bases = listOf(normalizeName(channelName), normalizeName(channelId.substringBefore('.')))
        return bases.filter { it.isNotBlank() }.flatMap { listOf(it, stripTv(it)) }.distinct()
    }

    // Kênh địa phương ghi "Vinh Long TV 1" còn dịch vụ lịch ghi "Vĩnh Long 1": bỏ chữ "tv"
    private fun stripTv(k: String): String =
        if (KEEP_TV.any { k.startsWith(it) }) k else k.replace("tv", "")

    fun buildMap(entries: List<Pair<String, String>>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for ((id, name) in entries) {
            for (k in listOf(normalizeName(name), normalizeName(id))) if (k.isNotBlank()) map.putIfAbsent(k, id)
        }
        return map
    }

    private val ALIASES = mapOf("htvsports" to "htvthethao")

    fun resolve(map: Map<String, String>, candidates: List<String>): String? {
        for (c in candidates) map[ALIASES[c] ?: c]?.let { return it }
        for (c in candidates) {
            if (c.length < 4) continue
            map.entries.filter { it.key.startsWith(c) && it.key.length - c.length <= 12 }
                .minByOrNull { it.key.length }?.let { return it.value }
        }
        return null
    }
}
