package cloud.huyenvu.tv

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val PLAYLISTS = listOf(
    "Việt Nam" to "https://iptv-org.github.io/iptv/countries/vn.m3u",
    "Thể thao" to "https://iptv-org.github.io/iptv/categories/sports.m3u",
    "Phim quốc tế" to "https://iptv-org.github.io/iptv/categories/movies.m3u",
    "Giải trí" to "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
    "Tin tức" to "https://iptv-org.github.io/iptv/categories/news.m3u",
    "Thiếu nhi" to "https://iptv-org.github.io/iptv/categories/kids.m3u"
)
private val DeepNavy = Color(0xFF050A12)
private val SurfaceNavy = Color(0xFF0C1726)
private val Teal = Color(0xFF2DD4BF)
private val Cyan = Color(0xFF22D3EE)
private val Muted = Color(0xFF94A3B8)

data class Channel(val id: String, val name: String, val logo: String, val group: String, val url: String, val category: String)
enum class ChannelTab { ALL, FAVORITES, RECENT }

class MainActivity : ComponentActivity() {
    private var pipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent { TVDrVuTheme { TVDrVuApp(pipMode) } }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        pipMode = isInPictureInPictureMode
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
private fun TVDrVuApp(pipMode: Boolean) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Tất cả") }
    var tab by remember { mutableStateOf(ChannelTab.ALL) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(loadIds(context, "favorites")) }
    var recent by remember { mutableStateOf(loadIds(context, "recent")) }
    var dataSaver by remember { mutableStateOf(context.getSharedPreferences("tv_dr_vu", Context.MODE_PRIVATE).getBoolean("data_saver", false)) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepKey by remember { mutableIntStateOf(0) }
    val recording by RecorderState.isRecording.collectAsState()

    LaunchedEffect(sleepKey, sleepMinutes) {
        val minutes = sleepMinutes ?: return@LaunchedEffect
        kotlinx.coroutines.delay(minutes * 60_000L)
        selected = null
        sleepMinutes = null
        message = "Đã dừng phát theo hẹn giờ."
    }

    LaunchedEffect(Unit) {
        runCatching { loadChannels() }
            .onSuccess { list -> channels = list; selected = list.firstOrNull() }
            .onFailure { message = "Chưa tải được danh sách kênh. Hãy kiểm tra kết nối mạng." }
        loading = false
    }

    val visible by remember(channels, query, category, tab, favorites, recent) {
        derivedStateOf {
            channels.filter {
                (query.isBlank() || "${it.name} ${it.group}".contains(query, true)) &&
                    (category == "Tất cả" || it.category == category) &&
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

    if (pipMode) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            selected?.let { VideoPlayer(it.url, dataSaver, controls = false) }
        }
        return
    }

    val phoneLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenHeightDp < 600
    if (phoneLandscape && selected != null) {
        DisposableEffect(Unit) {
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose {
                activity?.window?.let { window ->
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        BackHandler {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayer(selected.url, dataSaver, controls = true, landscapeHost = true)
        }
        return
    }

    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF103141), DeepNavy), radius = 1100f)
        ).systemBarsPadding()
    ) {
        val wide = maxWidth >= 850.dp
        Column(Modifier.fillMaxSize()) {
            AppHeader(
                sleepMinutes = sleepMinutes,
                dataSaver = dataSaver,
                onSleep = { minutes -> sleepMinutes = minutes; sleepKey++ },
                onDataSaver = {
                    dataSaver = !dataSaver
                    context.getSharedPreferences("tv_dr_vu", Context.MODE_PRIVATE).edit().putBoolean("data_saver", dataSaver).apply()
                },
                onRecordings = { openRecordings(context) }
            )
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PlayerPane(selected, recording, dataSaver, selected?.id in favorites, { id ->
                        favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                    }, { toggleRecording(context, selected, recording) },
                        { enterPip(context) }, { minutes -> selected?.let { scheduleReminder(context, it, minutes); message = "Đã hẹn nhắc sau $minutes phút." } }, Modifier.weight(1.65f))
                    ChannelPane(visible, selected, query, category, tab, loading,
                        onQuery = { query = it }, onCategory = { category = it }, onTab = { tab = it }, onChoose = ::choose,
                        modifier = Modifier.weight(.85f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        PlayerPane(selected, recording, dataSaver, selected?.id in favorites, { id ->
                            favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                        }, { toggleRecording(context, selected, recording) },
                            { enterPip(context) }, { minutes -> selected?.let { scheduleReminder(context, it, minutes); message = "Đã hẹn nhắc sau $minutes phút." } }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                    item {
                        ChannelPane(visible, selected, query, category, tab, loading,
                            onQuery = { query = it }, onCategory = { category = it }, onTab = { tab = it }, onChoose = ::choose,
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
}

@Composable
private fun AppHeader(
    sleepMinutes: Int?, dataSaver: Boolean, onSleep: (Int?) -> Unit,
    onDataSaver: () -> Unit, onRecordings: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
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
            Text("TV • Thể thao • Phim • Tin tức", color = Muted, fontSize = 12.sp)
        }
        IconButton(onClick = onRecordings) { Icon(Icons.Default.VideoLibrary, "Bản ghi", tint = Teal) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.Tune, "Tiện ích") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (dataSaver) "Tắt tiết kiệm dữ liệu" else "Bật tiết kiệm dữ liệu") },
                    leadingIcon = { Icon(Icons.Default.DataSaverOn, null) },
                    onClick = { onDataSaver(); menu = false }
                )
                listOf(15, 30, 60, 90).forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text("Hẹn tắt sau $minutes phút") },
                        leadingIcon = { Icon(Icons.Default.Bedtime, null) },
                        onClick = { onSleep(minutes); menu = false }
                    )
                }
                if (sleepMinutes != null) DropdownMenuItem(
                    text = { Text("Hủy hẹn giờ tắt") },
                    leadingIcon = { Icon(Icons.Default.TimerOff, null) },
                    onClick = { onSleep(null); menu = false }
                )
            }
        }
    }
}

@Composable
private fun PlayerPane(
    selected: Channel?, recording: Boolean, dataSaver: Boolean, favorite: Boolean, onFavorite: (String) -> Unit,
    onRecord: () -> Unit, onPip: () -> Unit, onReminder: (Int) -> Unit, modifier: Modifier = Modifier
) {
    var reminderMenu by remember { mutableStateOf(false) }
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
            } else VideoPlayer(selected.url, dataSaver)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPip, enabled = selected != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PictureInPictureAlt, null); Spacer(Modifier.width(6.dp)); Text("CỬA SỔ NHỎ", fontSize = 11.sp)
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { reminderMenu = true }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(6.dp)); Text("NHẮC XEM", fontSize = 11.sp)
                }
                DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                    listOf(5, 15, 30, 60).forEach { minutes ->
                        DropdownMenuItem(text = { Text("Sau $minutes phút") }, onClick = { onReminder(minutes); reminderMenu = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(9.dp))
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
private fun VideoPlayer(url: String, dataSaver: Boolean, controls: Boolean = true, landscapeHost: Boolean = false) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var isFullscreen by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    val player = remember(url, dataSaver) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val trackSelector = DefaultTrackSelector(context).apply {
            if (dataSaver) setParameters(buildUponParameters().setMaxVideoBitrate(1_200_000).setMaxVideoSizeSd())
        }
        ExoPlayer.Builder(context, renderers).setTrackSelector(trackSelector).build().apply {
            setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                buffering = false
                playbackError = "Kênh tạm thời không phát được. Hãy thử lại hoặc chọn kênh khác."
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == androidx.media3.common.Player.STATE_BUFFERING
                if (state == androidx.media3.common.Player.STATE_READY) playbackError = null
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
                        useController = controls
                        if (controls) setFullscreenButtonClickListener { enabled ->
                            if (landscapeHost && !enabled) {
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else setFullscreen(enabled)
                        }
                    }
                },
                update = { it.player = player; it.useController = controls },
                modifier = Modifier.fillMaxSize()
            )
            if (buffering) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(42.dp),
                color = Teal, strokeWidth = 4.dp
            )
            playbackError?.let {
                Surface(
                    color = Color(0xCC7F1D1D), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(18.dp)
                ) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(it, fontSize = 13.sp)
                        TextButton(onClick = { playbackError = null; buffering = true; player.prepare(); player.play() }) {
                            Text("THỬ LẠI", color = Color.White)
                        }
                    }
                }
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
    channels: List<Channel>, selected: Channel?, query: String, category: String, tab: ChannelTab, loading: Boolean,
    onQuery: (String) -> Unit, onCategory: (String) -> Unit, onTab: (ChannelTab) -> Unit, onChoose: (Channel) -> Unit,
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
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(listOf("Tất cả", "Việt Nam", "Thể thao", "Phim quốc tế", "Giải trí", "Tin tức", "Thiếu nhi")) { item ->
                    FilterChip(
                        selected = category == item,
                        onClick = { onCategory(item) },
                        label = { Text(item, fontSize = 11.sp) }
                    )
                }
            }
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
    coroutineScope {
        PLAYLISTS.map { (category, url) ->
            async {
                runCatching {
                    val request = Request.Builder().url(url).header("User-Agent", "TV-Dr-Vu-Android/2.1").build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful)
                        parseM3u(response.body?.string().orEmpty(), category)
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
            .filterNot { it.name.contains("geo-blocked", true) || it.name.contains("[geo", true) }
            .distinctBy { it.url }
    }
}

private fun parseM3u(text: String, category: String): List<Channel> {
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
        result += Channel(id, name, attr(line, "tvg-logo"), group, url, category)
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

private fun enterPip(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        (context as? ComponentActivity)?.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        )
    }
}

private fun scheduleReminder(context: Context, channel: Channel, minutes: Int) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        (context as? ComponentActivity)?.let {
            ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1102)
        }
    }
    val intent = Intent(context, ReminderReceiver::class.java).putExtra("channel_name", channel.name)
    val pending = PendingIntent.getBroadcast(
        context, (channel.id + System.currentTimeMillis()).hashCode(), intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val alarm = context.getSystemService(AlarmManager::class.java)
    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pending)
}

private fun openRecordings(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
