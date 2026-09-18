package cloud.huyenvu.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/vn.m3u"
private val DeepNavy = Color(0xFF050A12)
private val SurfaceNavy = Color(0xFF0C1726)
private val Teal = Color(0xFF2DD4BF)
private val Cyan = Color(0xFF22D3EE)
private val Muted = Color(0xFF94A3B8)

data class Channel(val id: String, val name: String, val logo: String, val group: String, val url: String)
enum class ChannelTab { ALL, FAVORITES, RECENT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent { TVDrVuTheme { TVDrVuApp() } }
    }
}

@Composable
private fun TVDrVuTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Teal, secondary = Cyan, background = DeepNavy,
        surface = SurfaceNavy, onPrimary = Color(0xFF031313),
        onBackground = Color.White, onSurface = Color.White
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
private fun TVDrVuApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(ChannelTab.ALL) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(loadIds(context, "favorites")) }
    var recent by remember { mutableStateOf(loadIds(context, "recent")) }
    val recording by RecorderState.isRecording.collectAsState()

    LaunchedEffect(Unit) {
        runCatching { loadChannels() }
            .onSuccess { list -> channels = list; selected = list.firstOrNull() }
            .onFailure { message = "Chưa tải được danh sách kênh. Hãy kiểm tra kết nối mạng." }
        loading = false
    }

    val visible by remember(channels, query, tab, favorites, recent) {
        derivedStateOf {
            channels.filter {
                (query.isBlank() || "${it.name} ${it.group}".contains(query, true)) &&
                    when (tab) {
                        ChannelTab.ALL -> true
                        ChannelTab.FAVORITES -> it.id in favorites
                        ChannelTab.RECENT -> it.id in recent
                    }
            }.let { list ->
                if (tab == ChannelTab.RECENT) list.sortedBy { recent.indexOf(it.id) }
                else list.sortedBy { it.name.lowercase() }
            }
        }
    }

    fun choose(channel: Channel) {
        selected = channel
        recent = (listOf(channel.id) + recent.filterNot { it == channel.id }).take(20).toSet()
        saveIds(context, "recent", recent)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF103141), DeepNavy), radius = 1100f)
        ).systemBarsPadding()
    ) {
        val wide = maxWidth >= 850.dp
        Column(Modifier.fillMaxSize()) {
            AppHeader()
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PlayerPane(selected, recording, selected?.id in favorites, { id ->
                        favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                    }, { toggleRecording(context, selected, recording) }, Modifier.weight(1.65f))
                    ChannelPane(visible, selected, query, tab, loading,
                        onQuery = { query = it }, onTab = { tab = it }, onChoose = ::choose,
                        modifier = Modifier.weight(.85f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        PlayerPane(selected, recording, selected?.id in favorites, { id ->
                            favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                        }, { toggleRecording(context, selected, recording) }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                    item {
                        ChannelPane(visible, selected, query, tab, loading,
                            onQuery = { query = it }, onTab = { tab = it }, onChoose = ::choose,
                            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 420.dp, max = 620.dp))
                    }
                }
            }
        }
        message?.let {
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(20.dp), action = {
                TextButton(onClick = {
                    message = null; loading = true
                    scope.launch {
                        runCatching { loadChannels() }.onSuccess { channels = it; selected = it.firstOrNull() }
                            .onFailure { message = "Vẫn chưa kết nối được nguồn kênh." }
                        loading = false
                    }
                }) { Text("THỬ LẠI") }
            }) { Text(it) }
        }
    }
}

@Composable
private fun AppHeader() {
    Row(
        Modifier.fillMaxWidth().height(70.dp).background(Color(0xDD07101D)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Teal, Cyan))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.LiveTv, null, tint = Color(0xFF031313)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("TV Dr Vũ", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("Truyền hình công khai • Việt Nam", color = Muted, fontSize = 12.sp)
        }
        AssistChip(onClick = {}, label = { Text("TRỰC TIẾP", fontWeight = FontWeight.Bold) },
            leadingIcon = { Box(Modifier.size(8.dp).background(Teal, CircleShape)) })
    }
}

@Composable
private fun PlayerPane(
    selected: Channel?, recording: Boolean, favorite: Boolean, onFavorite: (String) -> Unit,
    onRecord: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(16 / 9f),
            shape = RoundedCornerShape(20.dp), color = Color.Black,
            shadowElevation = 24.dp, tonalElevation = 4.dp
        ) {
            if (selected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LiveTv, null, tint = Muted, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(10.dp)); Text("Chọn một kênh để bắt đầu", color = Muted)
                    }
                }
            } else VideoPlayer(selected.url)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Teal, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("ĐANG XEM", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
                Text(selected?.name ?: "TV Dr Vũ", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(selected?.group ?: "Danh sách kênh Việt Nam", color = Muted, fontSize = 14.sp)
            }
            FilledIconButton(onClick = { selected?.id?.let(onFavorite) },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (favorite) Color(0x332DD4BF) else Color(0xFF142033))) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Yêu thích", tint = if (favorite) Teal else Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRecord, enabled = selected != null, modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording) Color(0xFFDC2626) else Teal,
                contentColor = if (recording) Color.White else Color(0xFF031313)
            )) {
            Icon(if (recording) Icons.Default.StopCircle else Icons.Default.FiberManualRecord, null)
            Spacer(Modifier.width(9.dp))
            Text(if (recording) "DỪNG VÀ LƯU BẢN GHI" else "GHI CHƯƠNG TRÌNH", fontWeight = FontWeight.ExtraBold)
        }
        Text(if (recording) "Đang ghi cả hình và tiếng vào Movies/TV Dr Vũ."
            else "Bản ghi chỉ dùng cá nhân và phụ thuộc quyền truy cập của từng luồng phát.",
            color = Color(0xFF64748B), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(url: String) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var isFullscreen by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    val player = remember(url) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply {
            setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = "Không giải mã được hình ảnh của kênh này."
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        activity?.requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                if (enabled) {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = isFullscreen) { setFullscreen(false) }

    @Composable
    fun PlayerSurface(modifier: Modifier) {
        Box(modifier.background(Color.Black)) {
            AndroidView(
                factory = {
                    (LayoutInflater.from(it).inflate(R.layout.player_view, null) as PlayerView).apply {
                        this.player = player
                        setFullscreenButtonClickListener { setFullscreen(it) }
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            playbackError?.let {
                Surface(
                    color = Color(0xCC7F1D1D), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                ) { Text(it, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp) }
            }
        }
    }

    if (isFullscreen) {
        Dialog(onDismissRequest = { setFullscreen(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            PlayerSurface(Modifier.fillMaxSize())
        }
    } else {
        PlayerSurface(Modifier.fillMaxSize())
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isFullscreen) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            }
        }
    }
}

@Composable
private fun ChannelPane(
    channels: List<Channel>, selected: Channel?, query: String, tab: ChannelTab, loading: Boolean,
    onQuery: (String) -> Unit, onTab: (ChannelTab) -> Unit, onChoose: (Channel) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = Color(0xE60C1726), tonalElevation = 3.dp) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("Tìm VTV, HTV, THVL…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Xóa") } },
                shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().padding(14.dp)
            )
            TabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, contentColor = Teal) {
                ChannelTab.entries.forEach { item ->
                    Tab(selected = tab == item, onClick = { onTab(item) },
                        text = { Text(when (item) { ChannelTab.ALL -> "Tất cả"; ChannelTab.FAVORITES -> "Yêu thích"; ChannelTab.RECENT -> "Gần đây" }, fontSize = 12.sp) })
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Teal) }
                channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.TvOff, null, tint = Muted, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.height(10.dp)); Text("Chưa có kênh trong danh sách", color = Muted)
                    }
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                    items(channels, key = { it.id + it.url }) { channel ->
                        ChannelRow(channel, selected?.url == channel.url) { onChoose(channel) }
                    }
                }
            }
            Text("${channels.size} kênh • Nguồn IPTV-org", color = Color(0xFF64748B), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x222DD4BF) else Color.Transparent)
            .clickable(onClick = onClick).focusable().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(Modifier.size(width = 58.dp, height = 44.dp), shape = RoundedCornerShape(10.dp), color = Color.White) {
            if (channel.logo.isNotBlank()) AsyncImage(channel.logo, null, Modifier.padding(5.dp), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.LiveTv, null, tint = Color(0xFF64748B)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(channel.group, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.PlayArrow, "Phát", tint = if (selected) Teal else Muted)
    }
}

private suspend fun loadChannels(): List<Channel> = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val request = Request.Builder().url(PLAYLIST_URL).header("User-Agent", "TV-Dr-Vu-Android/1.0").build()
    client.newCall(request).execute().use { response ->
        check(response.isSuccessful)
        parseM3u(response.body?.string().orEmpty())
    }
}

private fun parseM3u(text: String): List<Channel> {
    val lines = text.lineSequence().toList()
    val result = mutableListOf<Channel>()
    val attr = { line: String, name: String -> Regex("""$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1).orEmpty() }
    lines.forEachIndexed { index, line ->
        if (!line.startsWith("#EXTINF")) return@forEachIndexed
        val url = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim().orEmpty()
        if (!url.startsWith("http")) return@forEachIndexed
        val name = line.substringAfter(",", attr(line, "tvg-name")).trim()
        val id = attr(line, "tvg-id").ifBlank { "$name-$index" }
        val rawGroup = attr(line, "group-title")
        val group = rawGroup.takeUnless { it.isBlank() || it.equals("undefined", true) } ?: "Việt Nam"
        result += Channel(id, name, attr(line, "tvg-logo"), group, url)
    }
    return result.distinctBy { it.url }
}

private fun loadIds(context: Context, key: String): Set<String> =
    context.getSharedPreferences("tv_dr_vu", Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()

private fun saveIds(context: Context, key: String, ids: Set<String>) {
    context.getSharedPreferences("tv_dr_vu", Context.MODE_PRIVATE).edit().putStringSet(key, ids).apply()
}

private fun toggleId(ids: Set<String>, id: String) = if (id in ids) ids - id else ids + id

private fun toggleRecording(context: Context, channel: Channel?, recording: Boolean) {
    if (channel == null) return
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        (context as? ComponentActivity)?.let {
            ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1101)
        }
    }
    val intent = Intent(context, RecordingService::class.java).apply {
        action = if (recording) RecordingService.ACTION_STOP else RecordingService.ACTION_START
        putExtra(RecordingService.EXTRA_URL, channel.url)
        putExtra(RecordingService.EXTRA_NAME, channel.name)
    }
    if (recording) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
}
