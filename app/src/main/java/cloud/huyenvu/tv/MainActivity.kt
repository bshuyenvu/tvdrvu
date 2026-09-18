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
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
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

private val PLAYLISTS = listOf(
    "Việt Nam" to "https://iptv-org.github.io/iptv/countries/vn.m3u",
    "Thể thao" to "https://iptv-org.github.io/iptv/categories/sports.m3u",
    "Phim quốc tế" to "https://iptv-org.github.io/iptv/categories/movies.m3u",
    "Giải trí" to "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
    "Tin tức" to "https://iptv-org.github.io/iptv/categories/news.m3u",
    "Thiếu nhi" to "https://iptv-org.github.io/iptv/categories/kids.m3u"
)
private const val PREFS = "tv_dr_vu"
private val DeepNavy = Color(0xFF050A12)
private val SurfaceNavy = Color(0xFF0C1726)
private val Teal = Color(0xFF2DD4BF)
private val Cyan = Color(0xFF22D3EE)
private val Muted = Color(0xFF94A3B8)

data class Channel(val id: String, val name: String, val logo: String, val group: String, val url: String, val category: String)
enum class ChannelTab { ALL, FAVORITES, RECENT }

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
        if (p.currentMediaItem?.mediaId != channel.url) {
            p.setMediaItem(channel.toMediaItem()); p.prepare(); p.play()
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
        val target = channels.firstOrNull { it.url == url } ?: return@LaunchedEffect
        choose(target)
        onOpenHandled()
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
                onRecordings = { openRecordings(context) }
            )
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PlayerPane(selected, recording, player, selected?.id in favorites, { id ->
                        favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                    }, { toggleRecording(context, selected, recording) },
                        { enterPip(context) }, { minutes -> selected?.let { channel ->
                            if (minutes > 0) {
                                scheduleReminder(context, channel, minutes)
                                showMessage("Đã hẹn nhắc sau $minutes phút.")
                            } else pickReminderDateTime(context, channel) { text -> showMessage(text) }
                        } }, Modifier.weight(1.65f))
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
                        selected?.let { VideoPlayer(player) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Chọn một kênh để bắt đầu", color = Muted)
                        }
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                        PlayerPane(selected, recording, player, selected?.id in favorites, { id ->
                            favorites = toggleId(favorites, id); saveIds(context, "favorites", favorites)
                        }, { toggleRecording(context, selected, recording) },
                            { enterPip(context) }, { minutes -> selected?.let { channel ->
                                if (minutes > 0) {
                                    scheduleReminder(context, channel, minutes)
                                    showMessage("Đã hẹn nhắc sau $minutes phút.")
                                } else pickReminderDateTime(context, channel) { text -> showMessage(text) }
                            } }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), showVideo = false)
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
    }
}

@Composable
private fun AppHeader(
    sleepMinutes: Int?, dataSaver: Boolean, pipAuto: Boolean, onPipAuto: () -> Unit, onSleep: (Int?) -> Unit,
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
                DropdownMenuItem(
                    text = { Text(if (pipAuto) "Tắt tự mở cửa sổ nhỏ khi thoát app" else "Tự mở cửa sổ nhỏ khi thoát app") },
                    leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, null) },
                    onClick = { onPipAuto(); menu = false }
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
    showVideo: Boolean = true
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
            } else VideoPlayer(player)
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
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Chọn ngày và giờ…") },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, null) },
                        onClick = { onReminder(0); reminderMenu = false }
                    )
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
private fun VideoPlayer(
    player: Player?, controls: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val target = player
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var isFullscreen by remember { mutableStateOf(false) }
    var screenLocked by remember { mutableStateOf(false) }
    var hudKind by remember { mutableStateOf<String?>(null) }
    var hudPercent by remember { mutableIntStateOf(0) }
    var hudActive by remember { mutableStateOf(false) }

    fun readBrightness(): Float {
        val windowValue = activity?.window?.attributes?.screenBrightness ?: -1f
        if (windowValue >= 0f) return windowValue.coerceIn(0.02f, 1f)
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.02f, 1f)
    }

    var brightnessLevel by remember(activity) { mutableFloatStateOf(readBrightness()) }
    var volumeLevel by remember(audioManager) {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }

    var playbackError by remember(target) {
        mutableStateOf<String?>(
            if (target?.playerError != null)
                "Kênh tạm thời không phát được. Hãy thử lại hoặc chọn kênh khác."
            else null
        )
    }
    var buffering by remember(target) {
        mutableStateOf(target == null || target.playbackState == Player.STATE_BUFFERING)
    }

    LaunchedEffect(hudActive) {
        if (!hudActive && hudKind != null) {
            delay(650)
            if (!hudActive) hudKind = null
        }
    }

    DisposableEffect(target) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return
                buffering = false
                playbackError = "Kênh tạm thời không phát được. Hãy thử lại hoặc chọn kênh khác."
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) playbackError = null
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackError = null
                buffering = true
            }
        }
        target?.addListener(listener)
        onDispose { target?.removeListener(listener) }
    }

    fun setFullscreen(enabled: Boolean) {
        if (enabled == isFullscreen) return
        isFullscreen = enabled
        if (!enabled) screenLocked = false

        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                if (enabled) {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        activity?.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        if (!enabled) {
            activity?.window?.decorView?.postDelayed({
                if (!isFullscreen) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }, 650L)
        }
    }

    BackHandler(enabled = isFullscreen) {
        if (screenLocked) screenLocked = false else setFullscreen(false)
    }

    @Composable
    fun GestureZone(brightness: Boolean, modifier: Modifier) {
        val maxVolume = remember(audioManager) {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }

        Box(
            modifier.pointerInput(isFullscreen, screenLocked) {
                if (!isFullscreen || screenLocked) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = {
                        hudActive = true
                        if (brightness) {
                            brightnessLevel = readBrightness()
                            hudKind = "Độ sáng"
                            hudPercent = (brightnessLevel * 100).roundToInt()
                        } else {
                            volumeLevel =
                                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                            hudKind = "Âm lượng"
                            hudPercent =
                                ((volumeLevel / maxVolume.toFloat()) * 100).roundToInt()
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val height = size.height.coerceAtLeast(1).toFloat()
                        val delta = (-dragAmount / height) * 1.35f

                        if (brightness) {
                            brightnessLevel = (brightnessLevel + delta).coerceIn(0.02f, 1f)
                            activity?.window?.let { window ->
                                val attrs = window.attributes
                                attrs.screenBrightness = brightnessLevel
                                window.attributes = attrs
                            }
                            hudKind = "Độ sáng"
                            hudPercent = (brightnessLevel * 100).roundToInt()
                        } else {
                            volumeLevel =
                                (volumeLevel + delta * maxVolume).coerceIn(0f, maxVolume.toFloat())
                            val newVolume = volumeLevel.roundToInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            hudKind = "Âm lượng"
                            hudPercent =
                                ((volumeLevel / maxVolume.toFloat()) * 100).roundToInt()
                        }
                    },
                    onDragEnd = { hudActive = false },
                    onDragCancel = { hudActive = false }
                )
            }
        )
    }

    @Composable
    fun PlayerSurface(modifier: Modifier) {
        Box(modifier.background(Color.Black)) {
            AndroidView(
                factory = {
                    (LayoutInflater.from(it)
                        .inflate(R.layout.player_view, null) as PlayerView).apply {
                        this.player = target
                        useController = controls && !screenLocked
                        if (controls) {
                            setFullscreenButtonClickListener { enabled ->
                                setFullscreen(enabled)
                            }
                        }
                    }
                },
                update = {
                    it.player = target
                    it.useController = controls && !screenLocked
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize()
            )

            if (isFullscreen && !screenLocked) {
                GestureZone(
                    brightness = true,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(0.34f)
                )
                GestureZone(
                    brightness = false,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.34f)
                )
            }

            if (buffering) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(42.dp),
                    color = Teal,
                    strokeWidth = 4.dp
                )
            }

            playbackError?.let {
                Surface(
                    color = Color(0xCC7F1D1D),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(18.dp)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(it, fontSize = 13.sp)
                        TextButton(onClick = {
                            playbackError = null
                            buffering = true
                            target?.let { p ->
                                p.prepare()
                                p.play()
                            }
                        }) {
                            Text("THỬ LẠI", color = Color.White)
                        }
                    }
                }
            }

            if (isFullscreen && hudKind != null) {
                Surface(
                    color = Color(0xCC0F172A),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hudKind == "Độ sáng") Icons.Default.Brightness6
                            else Icons.Default.VolumeUp,
                            null,
                            tint = Color.White
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "$hudKind  $hudPercent%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isFullscreen) {
                if (screenLocked) {
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                            indication = null
                        ) {}
                    )
                }

                if (!screenLocked) {
                    FilledIconButton(
                        onClick = { setFullscreen(false) },
                        modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xAA0F172A)
                        )
                    ) {
                        Icon(Icons.Default.FullscreenExit, "Thu nhỏ", tint = Color.White)
                    }
                }

                FilledIconButton(
                    onClick = { screenLocked = !screenLocked },
                    modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xAA0F172A)
                    )
                ) {
                    Icon(
                        if (screenLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        if (screenLocked) "Mở khóa màn hình" else "Khóa màn hình",
                        tint = Color.White
                    )
                }
            }
        }
    }

    if (isFullscreen) {
        Dialog(
            onDismissRequest = {
                if (!screenLocked) setFullscreen(false)
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            PlayerSurface(Modifier.fillMaxSize())
        }
    } else {
        PlayerSurface(Modifier.fillMaxSize())
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isFullscreen) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.window?.let {
                    WindowInsetsControllerCompat(it, it.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
}

private fun Channel.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(url)
    .setUri(url)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build())
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(name).setArtist(group)
            .apply { if (logo.isNotBlank()) setArtworkUri(Uri.parse(logo)) }
            .build()
    )
    .build()

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

private suspend fun loadChannels(context: Context): List<Channel> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    val list = coroutineScope {
        PLAYLISTS.map { (category, url) ->
            async {
                // Có mạng: tải mới và lưu cache. Mất mạng/lỗi nguồn: dùng bản cache gần nhất.
                val cache = File(context.cacheDir, "playlist-${category.hashCode()}.m3u")
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
            .filterNot { it.name.contains("geo-blocked", true) || it.name.contains("[geo", true) }
            .distinctBy { it.url }
    }
    check(list.isNotEmpty()) { "Không có kênh nào" }
    list
}

private val M3U_ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

private fun extinfName(info: String): String {
    var inQuote = false
    for (i in info.indices) {
        val c = info[i]
        if (c == '"') inQuote = !inQuote
        else if (c == ',' && !inQuote) return info.substring(i + 1).trim()
    }
    return ""
}

// Một lượt quét O(n); dấu phẩy trong group-title không làm hỏng tên kênh
private fun parseM3u(text: String, category: String): List<Channel> {
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
        result[line] = Channel(id, name, attrs["tvg-logo"].orEmpty(), group, line, category)
    }
    return result.values.toList()
}

private fun pickInitial(context: Context, list: List<Channel>, current: Channel?): Channel? {
    current?.let { c -> list.firstOrNull { it.url == c.url }?.let { return it } }
    val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_channel_url", null)
    return list.firstOrNull { it.url == last } ?: list.firstOrNull()
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