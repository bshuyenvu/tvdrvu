package cloud.huyenvu.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Calendar

data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

/**
 * Lịch phát sóng từ dịch vụ EPG Việt Nam (lichphatsong.io.vn, API JSON công khai).
 * Không ghép được kênh -> trả danh sách rỗng; lỗi mạng -> ném IOException (dùng bản cũ nếu còn).
 */
object Epg {
    private const val BASE = "https://lichphatsong.io.vn/api"
    private const val PROGRAM_TTL = 15 * 60_000L
    private const val CHANNEL_TTL = 24 * 3_600_000L

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    @Volatile private var idMap: Map<String, String>? = null
    private val cache = HashMap<String, Pair<Long, List<Program>>>()

    suspend fun schedule(context: Context, channel: Channel): List<Program> = withContext(Dispatchers.IO) {
        val map = idMap ?: loadMap(context).also { idMap = it }
        val id = EpgMatch.resolve(map, EpgMatch.candidates(channel.name, channel.id))
            ?: return@withContext emptyList()
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[id] }?.takeIf { now - it.first < PROGRAM_TTL }?.let { return@withContext threeDayWindow(it.second) }
        try {
            val items = JSONObject(get("$BASE/schedule/$id")).optJSONArray("items")
            val list = ArrayList<Program>()
            if (items != null) for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val p = Program(o.optLong("startMs"), o.optLong("stopMs"), o.optString("title").trim(), o.optString("desc").trim())
                if (p.stop > p.start && p.title.isNotEmpty()) list += p
            }
            list.sortBy { it.start }
            synchronized(cache) { cache[id] = now to list }
            threeDayWindow(list)
        } catch (e: Exception) {
            // Mất mạng: vẫn hiển thị bản cũ nếu có
            synchronized(cache) { cache[id] }?.second?.let { threeDayWindow(it) }
                ?: throw IOException("Không tải được lịch phát sóng", e)
        }
    }

    /** Hiển thị ba ngày: hôm qua, hôm nay và ngày mai. */
    private fun threeDayWindow(items: List<Program>): List<Program> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DATE, -1)
        }
        val from = cal.timeInMillis
        cal.add(Calendar.DATE, 3)
        val to = cal.timeInMillis
        return items.filter { it.stop > from && it.start < to }.sortedBy { it.start }
    }

    private fun loadMap(context: Context): Map<String, String> {
        val file = File(context.cacheDir, "epg-channels.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CHANNEL_TTL
        val text = if (fresh) file.readText()
        else runCatching { get("$BASE/channels").also { file.writeText(it) } }.getOrNull()
            ?: if (file.exists()) file.readText() else throw IOException("Không tải được danh sách kênh lịch")
        val arr = JSONObject(text).getJSONArray("channels")
        val entries = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("hasEpg", true)) entries += o.getString("id") to o.optString("name")
        }
        return EpgMatch.buildMap(entries)
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "TV-Dr-Vu-Android/${BuildConfig.VERSION_NAME}").build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }
}
