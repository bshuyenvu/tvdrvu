package cloud.huyenvu.tv

import android.Manifest
import android.app.DatePickerDialog
import android.app.PictureInPictureParams
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import com.google.common.util.concurrent.ListenableFuture
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.common.model.DownloadConditions

private val PLAYLISTS = listOf(
    "Việt Nam" to "https://iptv-org.github.io/iptv/countries/vn.m3u",
    "Thể thao" to "https://iptv-org.github.io/iptv/categories/sports.m3u",
    "Phim quốc tế" to "https://iptv-org.github.io/iptv/categories/movies.m3u",
    "Giải trí" to "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
    "Tin tức" to "https://iptv-org.github.io/iptv/categories/news.m3u",
    "Thiếu nhi" to "https://iptv-org.github.io/iptv/categories/kids.m3u",
    // Kênh tiếng Việt phát từ nước ngoài: cũng là nguồn dự phòng cho kênh trùng tên (VTV4, VTV5...)
    "Tiếng Việt" to "https://iptv-org.github.io/iptv/languages/vie.m3u"
)
private const val PREFS = "tv_dr_vu"
private val DeepNavy = Color(0xFF050A12)
private val SurfaceNavy = Color(0xFF0C1726)
private val Teal = Color(0xFF2DD4BF)
private val Cyan = Color(0xFF22D3EE)
private val Muted = Color(0xFF94A3B8)

enum class ChannelTab { ALL, FAVORITES, RECENT }
private enum class SubtitleMode { OFF, ORIGINAL, VIETNAMESE }

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_URL = "open_url" }

    private var pipMode by mutableStateOf(false)
    private var pendingOpenUrl by mutableStateOf<String?>(null)
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        pendingOpenUrl = intent?.getStringExtra(EXTRA_OPEN_URL)
        setContent { TVDrVuTheme { TVDrVuApp(controller, pipMode, pendingOpenUrl) { pendingOpenUrl = null } } }
    }

    override fun onStart() {
        super.onStart()
        connectController()
        setVideoEnabled(true)
    }

    override fun onStop() {
        // Ở nền chỉ nghe tiếng: tắt luồng hình để tiết kiệm dữ liệu và pin
        if (!isInPictureInPictureMode) setVideoEnabled(false)
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenUrl = intent.getStringExtra(EXTRA_OPEN_URL)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = controller ?: return
        val auto = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pip_auto", false)
        if (auto && !pipMode && c.isPlaying && c.videoSize.width > 0) enterPip(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        pipMode = isInPictureInPictureMode
        // Người dùng đóng cửa sổ nhỏ (activity đã dừng) -> dừng phát thay vì tiếp tục ở nền
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) controller?.pause()
    }

    private fun connectController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            if (controller == null) controllerFuture = null
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) setVideoEnabled(true)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setVideoEnabled(enabled: Boolean) {
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
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
private fun TVDrVuApp(player: Player?, pipMode: Boolean, openUrl: String?, onOpenHandled: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Tất cả") }
    var tab by remember { mutableStateOf(ChannelTab.ALL) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var canRetry by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(loadIds(context, "favorites")) }
    var recent by remember { mutableStateOf(loadOrdered(context, "recent")) }
    var dataSaver by remember { mutableStateOf(context.getSharedPreferences("tv_dr_vu", Context.MODE_PRIVATE).getBoolean("data_saver", false)) }
    var pipAuto by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pip_auto", false)) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepKey by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var holdPortrait by remember { mutableStateOf(false) }
    var screenLocked by remember { mutableStateOf(false) }
    var srcIndex by remember { mutableIntStateOf(0) }
    var srcCount by remember { mutableIntStateOf(0) }
    var schedule by remember { mutableStateOf<List<Program>>(emptyList()) }
    var scheduleState by remember { mutableStateOf("") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showSchedule by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var customUrls by remember { mutableStateOf(loadCustomPlaylists(context)) }
    val configuration = LocalConfiguration.current
    val recording by RecorderState.isRecording.collectAsState()
    val recorderMessage by RecorderState.lastMessage.collectAsState()
    val playerRef by rememberUpdatedState(player)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun showMessage(text: String, retry: Boolean = false) { message = text; canRetry = retry }

    suspend fun reload() {
        runCatching { loadChannels(context) }
            .onSuccess { list -> channels = list; selected = pickInitial(context, list, selected) }
            .onFailure { showMessage("Chưa tải được danh sách kênh. Hãy kiểm tra kết nối mạng.", retry = true) }
        loading = false
    }

    LaunchedEffect(sleepKey, sleepMinutes) {
        val minutes = sleepMinutes ?: return@LaunchedEffect
        delay(minutes * 60_000L)
        playerRef?.let { it.stop(); it.clearMediaItems() }
        selected = null
        sleepMinutes = null
        showMessage("Đã dừng phát theo hẹn giờ.")
    }

    LaunchedEffect(Unit) { reload() }

    // Thông báo kết quả ghi hình (trước đây RecorderState.lastMessage không bao giờ hiển thị)
    LaunchedEffect(recorderMessage) {
        recorderMessage?.let { showMessage(it); RecorderState.lastMessage.value = null }
    }

    // Snackbar tự tắt (trước đây nằm mãi trên màn hình)
    LaunchedEffect(message, canRetry) {
        if (message != null) {
            delay(if (canRetry) 10_000L else 4_000L)
            message = null
        }
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
        recent = (listOf(channel.id) + recent.filterNot { it == channel.id }).take(20)
        saveOrdered(context, "recent", recent)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_channel_url", channel.url).apply()
    }

    // Đồng bộ kênh đang chọn -> trình phát dùng chung (chỉ nạp lại khi khác kênh đang phát)
    LaunchedEffect(player, selected) {
        val p = player ?: return@LaunchedEffect
        val channel = selected ?: return@LaunchedEffect
        val ids = channel.sources.map { it.url }
        if (p.currentMediaItem?.mediaId !in ids) {
            p.setMediaItem(channel.toMediaItem(0)); p.prepare(); p.play()
        }
    }

    LaunchedEffect(player, dataSaver) {
        val p = player ?: return@LaunchedEffect
        val params = p.trackSelectionParameters.buildUpon()
        if (dataSaver) params.setMaxVideoBitrate(1_200_000).setMaxVideoSizeSd()
        else params.clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
        p.trackSelectionParameters = params.build()
    }

    // Xin quyền thông báo một lần để hiện điều khiển phát nền
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Bấm thông báo nhắc lịch -> mở đúng kênh đã hẹn
    LaunchedEffect(openUrl, channels) {
        val url = openUrl ?: return@LaunchedEffect
        val target = channels.firstOrNull { it.hasUrl(url) } ?: return@LaunchedEffect
        choose(target)
        onOpenHandled()
    }

    val phoneLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenHeightDp < 600
    val immersive = selected != null && (fullscreen || phoneLandscape) && !pipMode

    fun exitImmersive() { fullscreen = false; screenLocked = false; holdPortrait = true }
    fun toggleFullscreen() {
        if (immersive) exitImmersive() else { holdPortrait = false; fullscreen = true }
    }
    fun nextSource() {
        val p = playerRef ?: return
        val channel = selected ?: return
        if (channel.sources.size <= 1) return
        val current = p.mediaMetadata.extras?.getInt(EXTRA_INDEX, 0) ?: 0
        p.setMediaItem(channel.toMediaItem((current + 1) % channel.sources.size))
        p.prepare(); p.play()
    }

    fun playReplay(program: Program) {
        val p = playerRef ?: return
        val channel = selected ?: return
        val replayUrl = channel.replayUrl(program) ?: run {
            showMessage("Nguồn này không hỗ trợ phát lại chương trình.")
            return
        }
        p.setMediaItem(channel.toReplayMediaItem(replayUrl, program))
        p.prepare()
        p.play()
        showSchedule = false
        showMessage("Đang phát lại: " + program.title)
    }

    LaunchedEffect(selected == null) { if (selected == null && fullscreen) exitImmersive() }

    // Một cơ chế xoay duy nhất: bấm nút toàn màn hình -> khóa ngang; thoát -> ép dọc cho tới khi
    // điện thoại thật sự được cầm dọc, rồi trả lại chế độ tự xoay (trước đây bị kẹt ở chế độ dọc).
    LaunchedEffect(fullscreen, holdPortrait) {
        activity?.requestedOrientation = when {
            fullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            holdPortrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(holdPortrait) {
        if (!holdPortrait) return@DisposableEffect onDispose { }
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                if (orientation <= 30 || orientation >= 330 || orientation in 150..210) holdPortrait = false
            }
        }
        if (listener.canDetectOrientation()) listener.enable() else holdPortrait = false
        onDispose { listener.disable() }
    }

    // Toàn màn hình thật trên cửa sổ chính: ẩn thanh hệ thống, giữ màn hình sáng khi xem
    DisposableEffect(immersive) {
        val window = activity?.window
        if (window != null && immersive) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (window != null && immersive) {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Nguồn đang phát / tổng số nguồn của kênh
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        fun sync() {
            val extras = p.mediaMetadata.extras
            srcIndex = extras?.getInt(EXTRA_INDEX, 0) ?: 0
            srcCount = extras?.getStringArray(EXTRA_SOURCES)?.size ?: 0
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { sync() }
        }
        p.addListener(listener)
        sync()
        onDispose { p.removeListener(listener) }
    }

    // Lịch phát sóng: tải khi đổi kênh; đồng hồ 30 giây/lần để cập nhật "đang phát"
    LaunchedEffect(selected?.name) {
        val channel = selected
        schedule = emptyList()
        if (channel == null) { scheduleState = ""; return@LaunchedEffect }
        scheduleState = "loading"
        runCatching { Epg.schedule(context, channel) }
            .onSuccess { schedule = it; scheduleState = if (it.isEmpty()) "none" else "ready" }
            .onFailure { scheduleState = "error" }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); nowMs = System.currentTimeMillis() } }
    val nowProgram = schedule.firstOrNull { nowMs >= it.start && nowMs < it.stop }
    val nowPlaying = nowProgram?.let { "Đang phát: ${it.title} · ${formatTime(it.start)}–${formatTime(it.stop)}" }
    val sourceText = if (srcCount > 1) "ĐỔI NGUỒN ${srcIndex + 1}/$srcCount" else null

    if (immersive) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayer(
                player, controls = true, fullscreen = true, locked = screenLocked,
                onFullscreenClick = ::exitImmersive, onToggleLock = { screenLocked = !screenLocked },
                onNextSource = if (srcCount > 1) ::nextSource else null
            )
        }
        return
    }

    if (pipMode) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            selected?.let { VideoPlayer(player, controls = false) }
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
                pipAuto = pipAuto,
                onPipAuto = {
                    pipAuto = !pipAuto
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pip_auto", pipAuto).apply()
                },
                onSleep = { minutes -> sleepMinutes = minutes; sleepKey++ },
                onDataSaver = {
                    dataSaver = !dataSaver
                    context.getSharedPreferences("tv_dr_vu", Context.MODE_PRIVATE).edit().putBoolean("data_saver", dataSaver).apply()
                },
                onRecordings = { openRecordings(context) },
                onSources = { showSources = true }
            )
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PlayerPane(selected, recording, player, selected?.id in favorites, { id ->
                        favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                    }, { toggleRecording(context, selected?.let { ch -> ch.copy(url = player?.currentMediaItem?.mediaId ?: ch.url) }, recording) },
                        { enterPip(context) }, { minutes -> selected?.let { channel ->
                            if (minutes > 0) {
                                scheduleReminder(context, channel, minutes)
                                showMessage("Đã hẹn nhắc sau $minutes phút.")
                            } else pickReminderDateTime(context, channel) { text -> showMessage(text) }
                        } }, Modifier.weight(1.65f),
                        nowPlaying = nowPlaying, sourceText = sourceText, onNextSource = ::nextSource,
                        onSchedule = { showSchedule = true }, onFullscreen = ::toggleFullscreen)
                    ChannelPane(visible, selected, query, category, tab, loading,
                        onQuery = { query = it }, onCategory = { category = it }, onTab = { tab = it }, onChoose = ::choose,
                        modifier = Modifier.weight(.85f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).aspectRatio(16 / 9f),
                        shape = RoundedCornerShape(18.dp), color = Color.Black, shadowElevation = 16.dp
                    ) {
                        selected?.let { VideoPlayer(player, onFullscreenClick = ::toggleFullscreen) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Chọn một kênh để bắt đầu", color = Muted)
                        }
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                        PlayerPane(selected, recording, player, selected?.id in favorites, { id ->
                            favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                        }, { toggleRecording(context, selected?.let { ch -> ch.copy(url = player?.currentMediaItem?.mediaId ?: ch.url) }, recording) },
                            { enterPip(context) }, { minutes -> selected?.let { channel ->
                                if (minutes > 0) {
                                    scheduleReminder(context, channel, minutes)
                                    showMessage("Đã hẹn nhắc sau $minutes phút.")
                                } else pickReminderDateTime(context, channel) { text -> showMessage(text) }
                            } }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), showVideo = false,
                            nowPlaying = nowPlaying, sourceText = sourceText, onNextSource = ::nextSource,
                            onSchedule = { showSchedule = true }, onFullscreen = ::toggleFullscreen)
                        }
                        item {
                        ChannelPane(visible, selected, query, category, tab, loading,
                            onQuery = { query = it }, onCategory = { category = it }, onTab = { tab = it }, onChoose = ::choose,
                            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 420.dp, max = 620.dp))
                        }
                    }
                }
            }
        }
        message?.let { text ->
            if (canRetry) {
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(20.dp), action = {
                    TextButton(onClick = {
                        message = null; loading = true
                        scope.launch { reload() }
                    }) { Text("THỬ LẠI") }
                }) { Text(text) }
            } else {
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(20.dp)) { Text(text) }
            }
        }
    }
    if (showSchedule && selected != null) {
        ScheduleDialog(selected!!, schedule, scheduleState, nowMs, onReplay = ::playReplay) { showSchedule = false }
    }
    if (showSources) {
        SourcesDialog(
            urls = customUrls,
            onAdd = { url ->
                if (url !in customUrls) {
                    customUrls = customUrls + url; saveCustomPlaylists(context, customUrls)
                    loading = true; scope.launch { reload() }
                }
            },
            onRemove = { url ->
                customUrls = customUrls - url; saveCustomPlaylists(context, customUrls)
                loading = true; scope.launch { reload() }
            },
            onDismiss = { showSources = false }
        )
    }
    }
}

@Composable
private fun AppHeader(
    sleepMinutes: Int?, dataSaver: Boolean, pipAuto: Boolean, onPipAuto: () -> Unit, onSleep: (Int?) -> Unit,
    onDataSaver: () -> Unit, onRecordings: () -> Unit, onSources: () -> Unit
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
                DropdownMenuItem(
                    text = { Text(if (pipAuto) "Tắt tự mở cửa sổ nhỏ khi thoát app" else "Tự mở cửa sổ nhỏ khi thoát app") },
                    leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, null) },
                    onClick = { onPipAuto(); menu = false }
                )
                DropdownMenuItem(
                    text = { Text("Nguồn phát dự phòng của bạn…") },
                    leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                    onClick = { onSources(); menu = false }
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
    selected: Channel?, recording: Boolean, player: Player?, favorite: Boolean, onFavorite: (String) -> Unit,
    onRecord: () -> Unit, onPip: () -> Unit, onReminder: (Int) -> Unit, modifier: Modifier = Modifier,
    showVideo: Boolean = true, nowPlaying: String? = null, sourceText: String? = null,
    onNextSource: () -> Unit = {}, onSchedule: () -> Unit = {}, onFullscreen: () -> Unit = {}
) {
    var reminderMenu by remember { mutableStateOf(false) }
    Column(modifier) {
        if (showVideo) Surface(
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
            } else VideoPlayer(player, onFullscreenClick = onFullscreen)
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
                if (nowPlaying != null) Text(nowPlaying, color = Teal, fontSize = 13.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
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
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Chọn ngày và giờ…") },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, null) },
                        onClick = { onReminder(0); reminderMenu = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSchedule, enabled = selected != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(6.dp)); Text("LỊCH PHÁT SÓNG", fontSize = 11.sp)
            }
            if (sourceText != null) OutlinedButton(onClick = onNextSource, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text(sourceText, fontSize = 11.sp)
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

/**
 * Vuốt dọc trên PlayerView khi toàn màn hình: nửa trái chỉnh độ sáng, nửa phải chỉnh âm lượng.
 * Dùng OnTouchListener của View nên chạm nhẹ ở bất kỳ đâu vẫn hiện/ẩn thanh điều khiển như thường.
 */
private class SwipeTouchListener(
    context: Context,
    private val activity: ComponentActivity?,
    private val onHud: (kind: Int, percent: Int) -> Unit
) : View.OnTouchListener {
    var enabled = false
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val resolver = context.contentResolver
    private val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private var width = 1
    private var height = 1
    private var scrolling = false
    private var leftSide = true
    private var brightness = 0.5f
    private var volume = 0f

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scrolling = false; return true }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!enabled || e1 == null) return false
            if (!scrolling) {
                if (abs(e2.y - e1.y) < abs(e2.x - e1.x)) return false   // vuốt ngang: bỏ qua
                scrolling = true
                leftSide = e1.x < width / 2f
                if (leftSide) brightness = currentBrightness()
                else volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
            val delta = distanceY / height * 1.3f   // vuốt lên = tăng
            if (leftSide) {
                brightness = (brightness + delta).coerceIn(0.02f, 1f)
                activity?.window?.let { w ->
                    val attrs = w.attributes
                    attrs.screenBrightness = brightness
                    w.attributes = attrs
                }
                onHud(0, (brightness * 100).roundToInt())
            } else {
                volume = (volume + delta * maxVolume).coerceIn(0f, maxVolume.toFloat())
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.roundToInt(), 0)
                onHud(1, (volume / maxVolume * 100).roundToInt())
            }
            return true
        }
    })

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        width = v.width.coerceAtLeast(1)
        height = v.height.coerceAtLeast(1)
        if (!enabled) return false
        detector.onTouchEvent(event)
        val consumed = scrolling
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scrolling = false
        return consumed
    }

    private fun currentBrightness(): Float {
        val w = activity?.window?.attributes?.screenBrightness ?: -1f
        if (w >= 0f) return w.coerceIn(0.02f, 1f)
        return runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            .getOrDefault(0.5f).coerceIn(0.02f, 1f)
    }

    /** Thoát toàn màn hình: trả độ sáng về theo hệ thống. */
    fun resetBrightness() {
        activity?.window?.let { w ->
            val attrs = w.attributes
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = attrs
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    player: Player?, controls: Boolean = true, fullscreen: Boolean = false, locked: Boolean = false,
    onFullscreenClick: () -> Unit = {}, onToggleLock: () -> Unit = {}, onNextSource: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val target = player
    val scope = rememberCoroutineScope()
    val fullscreenClick by rememberUpdatedState(onFullscreenClick)
    var hudKind by remember { mutableIntStateOf(-1) }
    var hudPercent by remember { mutableIntStateOf(0) }
    var playbackError by remember(target) {
        mutableStateOf<String?>(if (target?.playerError != null) ERROR_TEXT else null)
    }
    var buffering by remember(target) { mutableStateOf(target == null || target.playbackState == Player.STATE_BUFFERING) }
    val swipe = remember { SwipeTouchListener(context, activity) { kind, percent -> hudKind = kind; hudPercent = percent } }
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var channelKey by remember(target) { mutableStateOf(target?.currentMediaItem?.mediaId.orEmpty()) }
    var subtitleMode by remember(channelKey) {
        mutableStateOf(
            runCatching { SubtitleMode.valueOf(prefs.getString("subtitle_$channelKey", SubtitleMode.OFF.name)!!) }
                .getOrDefault(SubtitleMode.OFF)
        )
    }
    var originalSubtitle by remember(target) { mutableStateOf("") }
    var translatedSubtitle by remember(target) { mutableStateOf("") }
    var subtitleStatus by remember(target) { mutableStateOf<String?>(null) }
    var translator by remember { mutableStateOf<Translator?>(null) }
    val languageIdentifier = remember { LanguageIdentification.getClient() }

    fun saveMode(mode: SubtitleMode) {
        subtitleMode = mode
        prefs.edit().putString("subtitle_$channelKey", mode.name).apply()
        if (mode == SubtitleMode.VIETNAMESE && originalSubtitle.isBlank()) {
            subtitleStatus = "Kênh chưa cung cấp phụ đề"
        } else if (mode != SubtitleMode.VIETNAMESE) subtitleStatus = null
    }

    fun translateCue(text: String) {
        if (text.isBlank() || subtitleMode != SubtitleMode.VIETNAMESE) return
        subtitleStatus = "Đang nhận diện và tải bộ dịch…"
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { tag ->
                if (tag == "und") {
                    subtitleStatus = "Không nhận diện được ngôn ngữ phụ đề"
                    return@addOnSuccessListener
                }
                val source = TranslateLanguage.fromLanguageTag(tag)
                if (source == null) {
                    subtitleStatus = "Chưa hỗ trợ dịch ngôn ngữ này"
                    return@addOnSuccessListener
                }
                if (source == TranslateLanguage.VIETNAMESE) {
                    translatedSubtitle = text
                    subtitleStatus = null
                    return@addOnSuccessListener
                }
                translator?.close()
                val client = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                        .build()
                )
                translator = client
                client.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        client.translate(text)
                            .addOnSuccessListener { result ->
                                if (subtitleMode == SubtitleMode.VIETNAMESE && originalSubtitle == text) {
                                    translatedSubtitle = result
                                    subtitleStatus = null
                                }
                            }
                            .addOnFailureListener { subtitleStatus = "Không dịch được phụ đề" }
                    }
                    .addOnFailureListener { subtitleStatus = "Không tải được bộ dịch · kiểm tra Internet" }
            }
            .addOnFailureListener { subtitleStatus = "Không nhận diện được ngôn ngữ" }
    }

    LaunchedEffect(hudKind, hudPercent) {
        if (hudKind >= 0) { delay(700); hudKind = -1 }
    }
    DisposableEffect(fullscreen) {
        onDispose { if (fullscreen) swipe.resetBrightness() }
    }

    DisposableEffect(target, subtitleMode) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }
                    .filter { it.isNotBlank() }.joinToString("\n")
                originalSubtitle = text
                if (text.isBlank()) {
                    translatedSubtitle = ""
                } else if (subtitleMode == SubtitleMode.VIETNAMESE && text != translatedSubtitle) {
                    translateCue(text)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return
                buffering = true
                playbackError = null
                val failedId = target?.currentMediaItem?.mediaId
                scope.launch {
                    delay(18_000L)
                    val p = target ?: return@launch
                    if (p.currentMediaItem?.mediaId == failedId && p.playerError != null) {
                        buffering = false
                        playbackError = ERROR_TEXT
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) playbackError = null
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                channelKey = mediaItem?.mediaId.orEmpty()
                playbackError = null
                buffering = true
            }
        }
        target?.addListener(listener)
        onDispose { target?.removeListener(listener) }
    }

    DisposableEffect(Unit) {
        onDispose {
            translator?.close()
            languageIdentifier.close()
        }
    }

    LaunchedEffect(subtitleMode, originalSubtitle) {
        if (subtitleMode == SubtitleMode.VIETNAMESE && originalSubtitle.isNotBlank()) translateCue(originalSubtitle)
    }

    BackHandler(enabled = fullscreen) { if (locked) onToggleLock() else onFullscreenClick() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                (LayoutInflater.from(it).inflate(R.layout.player_view, null) as PlayerView).apply {
                    this.player = target
                    useController = controls && !locked
                    if (controls) setFullscreenButtonClickListener { _ -> fullscreenClick() }
                    setOnTouchListener(swipe)
                }
            },
            update = {
                it.player = target
                it.useController = controls && !locked
                it.subtitleView?.visibility = if (subtitleMode == SubtitleMode.ORIGINAL) View.VISIBLE else View.GONE
                swipe.enabled = fullscreen && !locked
            },
            // Nhiều PlayerView có thể cùng gắn một trình phát: view bị gỡ phải nhả trình phát ra
            onRelease = { it.player = null; it.setOnTouchListener(null) },
            modifier = Modifier.fillMaxSize()
        )
        val subtitleText = when (subtitleMode) {
            SubtitleMode.OFF -> ""
            SubtitleMode.ORIGINAL -> "" // Media3 tự vẽ phụ đề gốc đúng định dạng/định vị.
            SubtitleMode.VIETNAMESE -> translatedSubtitle
        }
        if (subtitleText.isNotBlank()) Text(
            text = subtitleText,
            color = Color.White,
            fontSize = if (fullscreen) 20.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = if (fullscreen) 72.dp else 54.dp)
                .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        if (subtitleMode == SubtitleMode.VIETNAMESE && subtitleStatus != null) Text(
            text = subtitleStatus!!,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = if (fullscreen) 70.dp else 52.dp)
                .background(Color(0xB30F172A), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        if (!locked) AssistChip(
            onClick = {
                saveMode(when (subtitleMode) {
                    SubtitleMode.OFF -> SubtitleMode.ORIGINAL
                    SubtitleMode.ORIGINAL -> SubtitleMode.VIETNAMESE
                    SubtitleMode.VIETNAMESE -> SubtitleMode.OFF
                })
            },
            label = { Text(when (subtitleMode) {
                SubtitleMode.OFF -> "CC TẮT"
                SubtitleMode.ORIGINAL -> "CC GỐC"
                SubtitleMode.VIETNAMESE -> "🌐 TIẾNG VIỆT"
            }, fontSize = 11.sp) },
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (subtitleMode == SubtitleMode.OFF) Color(0xAA0F172A) else Color(0xDD0F766E),
                labelColor = Color.White
            )
        )
        if (buffering) CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center).size(42.dp), color = Teal, strokeWidth = 4.dp
        )
        playbackError?.let {
            Surface(
                color = Color(0xCC7F1D1D), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center).padding(18.dp)
            ) {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it, fontSize = 13.sp)
                    Row {
                        TextButton(onClick = {
                            playbackError = null; buffering = true
                            target?.let { p ->
                                if (!p.restartFromFirstSource()) {
                                    p.prepare()
                                    p.play()
                                }
                            }
                        }) { Text("THỬ LẠI", color = Color.White) }
                        if (onNextSource != null) TextButton(onClick = {
                            playbackError = null; buffering = true; onNextSource()
                        }) { Text("NGUỒN KHÁC", color = Color.White) }
                    }
                }
            }
        }
        if (fullscreen) {
            if (hudKind >= 0) Surface(
                color = Color(0xCC0F172A), shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (hudKind == 0) Icons.Default.Brightness6 else Icons.Default.VolumeUp, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("${if (hudKind == 0) "Độ sáng" else "Âm lượng"}  $hudPercent%", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            if (locked) Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
            ) else FilledIconButton(
                onClick = onFullscreenClick,
                modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xAA0F172A))
            ) { Icon(Icons.Default.FullscreenExit, "Thu nhỏ", tint = Color.White) }
            FilledIconButton(
                onClick = onToggleLock,
                modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xAA0F172A))
            ) {
                Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    if (locked) "Mở khóa màn hình" else "Khóa màn hình", tint = Color.White)
            }
        }
    }
}

/** Còn nguồn dự phòng sau nguồn đang phát (dịch vụ phát đang tự chuyển nguồn). */
private fun Player.hasBackupSource(): Boolean {
    val extras = mediaMetadata.extras ?: return false
    val total = extras.getStringArray(EXTRA_SOURCES)?.size ?: return false
    return extras.getInt(EXTRA_INDEX, 0) + 1 < total
}

/** Thử lại thủ công từ nguồn chính của kênh sau khi mọi nguồn đã lỗi. */
private fun Player.restartFromFirstSource(): Boolean {
    val item = currentMediaItem ?: return false
    val extras = item.mediaMetadata.extras ?: return false
    val urls = extras.getStringArray(EXTRA_SOURCES) ?: return false
    val first = urls.firstOrNull() ?: return false
    val uri = Uri.parse(first)
    val reset = item.buildUpon()
        .setMediaId(first)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            item.mediaMetadata.buildUpon()
                .setExtras(Bundle(extras).apply {
                    putInt(EXTRA_INDEX, 0)
                    putBoolean(EXTRA_IS_REPLAY, false)
                })
                .build()
        )
        .build()
    setMediaItem(reset)
    prepare()
    play()
    return true
}

private const val ERROR_TEXT = "Đã thử kết nối lại và các nguồn dự phòng nhưng kênh hiện chưa phát được."

private sealed interface ScheduleRow {
    data class Day(val label: String) : ScheduleRow
    data class Item(val program: Program) : ScheduleRow
}

private fun buildScheduleRows(programs: List<Program>): List<ScheduleRow> {
    val dayFmt = SimpleDateFormat("EEEE, dd/MM", Locale("vi", "VN"))
    val rows = ArrayList<ScheduleRow>()
    var lastDay = ""
    for (p in programs) {
        val day = dayFmt.format(Date(p.start))
        if (day != lastDay) {
            rows += ScheduleRow.Day(day.replaceFirstChar { it.uppercase() })
            lastDay = day
        }
        rows += ScheduleRow.Item(p)
    }
    return rows
}

@Composable
private fun ScheduleDialog(channel: Channel, programs: List<Program>, state: String, nowMs: Long, onReplay: (Program) -> Unit, onDismiss: () -> Unit) {
    val rows = remember(programs) { buildScheduleRows(programs) }
    val listState = rememberLazyListState()
    val nowIndex = rows.indexOfFirst { it is ScheduleRow.Item && nowMs >= it.program.start && nowMs < it.program.stop }
    LaunchedEffect(rows) { if (nowIndex > 0) listState.scrollToItem(maxOf(0, nowIndex - 1)) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f),
            shape = RoundedCornerShape(24.dp), color = Color(0xFF0C1726), contentColor = Color.White
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lịch phát sóng 3 ngày", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(channel.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Hôm qua • Hôm nay • Ngày mai", color = Muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Đóng") }
                }
                when {
                    state == "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Teal)
                    }
                    state == "error" -> ScheduleMessage("Chưa tải được lịch phát sóng. Hãy kiểm tra kết nối mạng và mở lại.")
                    rows.isEmpty() -> ScheduleMessage("Chưa có lịch phát sóng cho kênh này.")
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp)) {
                        items(rows) { row ->
                            when (row) {
                                is ScheduleRow.Day -> Text(
                                    row.label, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp)
                                )
                                is ScheduleRow.Item -> {
                                    val p = row.program
                                    val live = nowMs >= p.start && nowMs < p.stop
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                                            .background(if (live) Color(0x222DD4BF) else Color.Transparent).padding(10.dp)
                                    ) {
                                        Text(formatTime(p.start), color = if (live) Teal else Muted,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(p.title, fontWeight = if (live) FontWeight.ExtraBold else FontWeight.SemiBold)
                                            if (live) {
                                                Text("ĐANG PHÁT · đến ${formatTime(p.stop)}", color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            } else if (p.stop <= nowMs) {
                                                Text("ĐÃ PHÁT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            if (p.desc.isNotBlank()) Text(p.desc, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                                            if (p.stop <= nowMs && channel.replayUrl(p) != null) {
                                                TextButton(
                                                    onClick = { onReplay(p) },
                                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.Replay, null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(5.dp))
                                                    Text("PHÁT LẠI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Schedule, null, tint = Muted, modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(10.dp))
            Text(text, color = Muted)
        }
    }
}

@Composable
private fun SourcesDialog(urls: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nguồn phát dự phòng của bạn") },
        text = {
            Column {
                Text(
                    "Dán liên kết danh sách M3U/M3U8 của bạn. Kênh trùng tên (ví dụ VTV1) sẽ được gộp vào làm nguồn dự phòng; " +
                        "kênh mới sẽ xuất hiện trong mục Tất cả. Chỉ dùng nguồn bạn có quyền xem.",
                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("https://…/danh-sach.m3u") }, modifier = Modifier.fillMaxWidth()
                )
                urls.forEach { u ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(u, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(u) }) { Icon(Icons.Default.Delete, "Xóa nguồn") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.trim()); text = "" }, enabled = text.trim().startsWith("http")) { Text("THÊM") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } }
    )
}

/**
 * Chỉ nạp MỘT nguồn (chỉ số index). Toàn bộ nguồn của kênh nằm trong siêu dữ liệu để dịch vụ phát
 * tự chuyển sang nguồn dự phòng khi luồng hiện tại lỗi.
 */
private fun Channel.toMediaItem(index: Int): MediaItem {
    val src = sources[index.coerceIn(0, sources.lastIndex)]
    val extras = Bundle().apply {
        putStringArray(EXTRA_SOURCES, sources.map { it.url }.toTypedArray())
        putInt(EXTRA_INDEX, sources.indexOf(src))
        putBoolean(EXTRA_IS_REPLAY, false)
    }
    return MediaItem.Builder()
        .setMediaId(src.url)
        .setUri(src.url)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(src.url)).build())
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(name).setArtist(group).setExtras(extras)
                .apply {
                    if (src.note.isNotBlank()) setSubtitle(src.note)
                    if (logo.isNotBlank()) setArtworkUri(Uri.parse(logo))
                }
                .build()
        )
        .build()
}

private fun Channel.toReplayMediaItem(url: String, program: Program): MediaItem {
    val extras = Bundle().apply {
        putStringArray(EXTRA_SOURCES, arrayOf(url))
        putInt(EXTRA_INDEX, 0)
        putBoolean(EXTRA_IS_REPLAY, true)
    }
    return MediaItem.Builder()
        .setMediaId(url)
        .setUri(url)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(program.title)
                .setArtist(name)
                .setSubtitle("Phát lại · " + formatTime(program.start))
                .setExtras(extras)
                .apply { if (logo.isNotBlank()) setArtworkUri(Uri.parse(logo)) }
                .build()
        )
        .build()
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
                        ChannelRow(channel, selected?.name == channel.name) { onChoose(channel) }
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
            Text(if (channel.sources.size > 1) "${channel.group} · ${channel.sources.size} nguồn" else channel.group,
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.PlayArrow, "Phát", tint = if (selected) Teal else Muted)
    }
}

private suspend fun loadChannels(context: Context): List<Channel> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    // Nguồn riêng của người dùng xếp cuối: đóng vai trò nguồn dự phòng cho kênh trùng tên
    val playlists = PLAYLISTS + loadCustomPlaylists(context).map { "Nguồn riêng" to it }
    val all = coroutineScope {
        playlists.map { (category, url) ->
            async {
                // Có mạng: tải mới và lưu cache. Mất mạng/lỗi nguồn: dùng bản cache gần nhất.
                val cache = File(context.cacheDir, "playlist-${url.hashCode()}.m3u")
                val text = runCatching {
                    val request = Request.Builder().url(url)
                        .header("User-Agent", "TV-Dr-Vu-Android/${BuildConfig.VERSION_NAME}").build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful)
                        response.body?.string().orEmpty()
                    }
                }.getOrNull()?.takeIf { it.contains("#EXTINF") }?.also { runCatching { cache.writeText(it) } }
                    ?: runCatching { if (cache.exists()) cache.readText() else null }.getOrNull()
                text?.let { parseM3u(it, category) }.orEmpty()
            }
        }.awaitAll().flatten()
    }
    // Gộp nguồn cùng kênh; nguồn chặn vùng (Geo) không còn bị ẩn mà xếp cuối làm dự phòng
    val list = mergeChannels(all)
    check(list.isNotEmpty()) { "Không có kênh nào" }
    list
}

private fun loadCustomPlaylists(context: Context): List<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("custom_playlists", "").orEmpty()
        .split('\n').map { it.trim() }.filter { it.startsWith("http") }

private fun saveCustomPlaylists(context: Context, urls: List<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("custom_playlists", urls.joinToString("\n")).apply()
}

private fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

private fun pickInitial(context: Context, list: List<Channel>, current: Channel?): Channel? {
    current?.let { c -> list.firstOrNull { it.name == c.name }?.let { return it } }
    val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_channel_url", null)
    return list.firstOrNull { it.hasUrl(last) } ?: list.firstOrNull()
}

private fun loadIds(context: Context, key: String): Set<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()

private fun saveIds(context: Context, key: String, ids: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(key, ids).apply()
}

// StringSet của SharedPreferences không giữ thứ tự -> lưu danh sách "Gần đây" dạng chuỗi có thứ tự
private fun loadOrdered(context: Context, key: String): List<String> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("${key}_ordered", null)
    return if (raw != null) raw.split('\n').filter { it.isNotBlank() }
    else prefs.getStringSet(key, emptySet()).orEmpty().toList()
}

private fun saveOrdered(context: Context, key: String, ids: List<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("${key}_ordered", ids.joinToString("\n")).apply()
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
    scheduleReminderAt(context, channel, System.currentTimeMillis() + minutes * 60_000L)
}

private fun scheduleReminderAt(context: Context, channel: Channel, triggerAt: Long) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        (context as? ComponentActivity)?.let {
            ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1102)
        }
    }
    Reminders.schedule(context, channel.name, channel.url, triggerAt)
}

private fun pickReminderDateTime(context: Context, channel: Channel, onResult: (String) -> Unit) {
    val now = Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val chosen = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (chosen.timeInMillis <= System.currentTimeMillis()) {
                        onResult("Thời gian nhắc phải ở tương lai.")
                    } else {
                        scheduleReminderAt(context, channel, chosen.timeInMillis)
                        val label = java.text.SimpleDateFormat("HH:mm • dd/MM/yyyy", Locale("vi", "VN")).format(chosen.time)
                        onResult("Đã hẹn ${channel.name} lúc $label.")
                    }
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true
            ).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)
    ).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
}

private fun openRecordings(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
