package com.webdavcast.tv

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.media3.common.PlaybackParameters
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== 主题色 (10-foot 深色) =====
private val TvBg = Color(0xFF0E0E12)
private val TvCardBg = Color(0xFF1A1A20)
private val TvFocusBg = Color(0xFF26262E)
private val TvAccent = Color(0xFF2196F3)
private val TvAccentBright = Color(0xFF4FC3F7)
private val TvText = Color(0xFFF2F2F5)
private val TvTextDim = Color(0xFF9AA0A8)
private val TvError = Color(0xFFE5534B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 后台预热视频缓存(建 SQLite 索引/扫目录)。低优先级 + 延迟 3 秒启动,
        // 避免和启动渲染抢 CPU/IO。播放器用 LazyCacheFactory, 缓存没热好也能正常播。
        Thread {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            Thread.sleep(3_000)
            runCatching { VideoCache.get(applicationContext) }
        }.start()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = TvAccent,
                    onPrimary = Color.White,
                    background = TvBg,
                    onBackground = TvText,
                    surface = TvCardBg,
                    onSurface = TvText,
                    surfaceVariant = TvFocusBg,
                    onSurfaceVariant = TvTextDim,
                    error = TvError,
                )
            ) {
                TvApp()
            }
        }
    }
}

private enum class Screen { Setup, ServerPick, Browse, Player }

@Composable
fun TvApp() {
    val ctx = LocalContext.current
    // 有上次连接的服务器则冷启动直接进文件浏览(首页)，否则进设置
    var screen by rememberSaveable {
        val ls = Prefs.getLastServer(ctx).split("|")
        mutableStateOf(if (ls.size >= 4 && ls[1].toIntOrNull() != null) Screen.Browse else Screen.Setup)
    }
    var serverHost by rememberSaveable { mutableStateOf("") }
    var serverPort by rememberSaveable { mutableStateOf(0) }
    var serverName by rememberSaveable { mutableStateOf("") }
    var serverPath by rememberSaveable { mutableStateOf("") }
    var serverScheme by rememberSaveable { mutableStateOf("http") }
    // 冷启动恢复上次服务器
    remember {
        val ls = Prefs.getLastServer(ctx).split("|")
        if (serverHost.isBlank() && ls.size >= 5 && ls[1].toIntOrNull() != null) {
            serverHost = ls[0]; serverPort = ls[1].toInt()
            serverName = ls[2]; serverPath = ls[3]
            serverScheme = ls[4].ifEmpty { "http" }.lowercase()
        }
    }
    var path by rememberSaveable { mutableStateOf("/") }
    var videoUrl by rememberSaveable { mutableStateOf("") }
    var videoEntryRaw by rememberSaveable { mutableStateOf<String?>(null) }
    val videoEntry = videoEntryRaw?.let { raw ->
        val p = raw.split("|", limit = 5)
        if (p.size >= 3) DavEntry(p[0], p[1] == "1", p[2], p.getOrElse(3) { "" }.toLongOrNull() ?: 0L, p.getOrElse(4) { "" }) else null
    }
    val server = remember(serverHost, serverPort, serverName, serverPath, serverScheme) {
        if (serverHost.isNotBlank() && serverPort > 0) ServerEntry(serverHost, serverPort, serverName, serverPath, serverScheme) else null
    }
    // 浏览列表返回时恢复的条目索引
    var browseSavedIndex by rememberSaveable { mutableStateOf(0) }
    var fromHistory by rememberSaveable { mutableStateOf(false) }
    // ServerPick 解析出的服务器列表
    var pickServers by rememberSaveable { mutableStateOf("") } // AddressResolver.encode 序列化

    fun setServer(s: ServerEntry) {
        serverHost = s.host; serverPort = s.port; serverName = s.name; serverPath = s.basePath
        serverScheme = s.scheme
        Prefs.setLastServer(ctx, s.host, s.port, s.name, s.basePath, s.scheme)
    }

    when (screen) {
        Screen.Setup -> SetupScreen(
            onShowServers = { list ->
                pickServers = AddressResolver.encode(list)
                screen = Screen.ServerPick
            },
            onManualConnect = { s ->
                setServer(s); path = "/"; browseSavedIndex = 0; screen = Screen.Browse
            },
        )
        Screen.ServerPick -> ServerPickScreen(
            servers = AddressResolver.decode(pickServers),
            onPick = { s -> setServer(s); path = "/"; browseSavedIndex = 0; screen = Screen.Browse },
            onBack = { screen = Screen.Setup },
        )
        Screen.Browse -> BrowseScreen(
            server = server, path = path, restoreIndex = browseSavedIndex,
            onOpen = { p -> browseSavedIndex = 0; path = p },
            onWatch = { url, entry ->
                videoUrl = url
                videoEntryRaw = "${entry.name}|${if (entry.isDir) 1 else 0}|${entry.href}|${entry.size}|${entry.modified}"
                screen = Screen.Player
            },
            onPlayHistory = { h ->
                val srv = server ?: run {
                    val ls = Prefs.getLastServer(ctx).split("|")
                    if (ls.size >= 5 && ls[1].toIntOrNull() != null) ServerEntry(ls[0], ls[1].toInt(), ls[2], ls[3], ls[4].ifEmpty { "http" }.lowercase()) else null
                }
                videoUrl = relocateUrl(h.url, srv)
                videoEntryRaw = "${h.title}|0|${videoUrl}|${h.durationMs}|"
                val dir = if (h.dirPath.isNotEmpty()) h.dirPath else inferDirFromUrl(videoUrl, srv)
                path = dir
                fromHistory = true
                screen = Screen.Player
            },
            onBack = {
                if (path != "/") path = parentPathOf(path)
                else (ctx as? ComponentActivity)?.finish()
            },
            onSwitchServer = { screen = Screen.Setup },
            onSchemeResolved = { serverScheme = it },
        )
        Screen.Player -> PlayerScreen(
            server = server, url = videoUrl, entry = videoEntry, currentPath = path,
            onSwitchEpisode = { e ->
                val s = server
                if (s != null) {
                    videoUrl = urlFor(s, e.href)
                    videoEntryRaw = "${e.name}|0|${e.href}|${e.size}|${e.modified}"
                }
            },
            onSystemBack = { if (fromHistory) { fromHistory = false; path = "/" }; screen = Screen.Browse },
            onSchemeResolved = { serverScheme = it },
        )
    }
}

// ===== 焦点卡片：D-pad 焦点 + OK 触发 + 三重焦点反馈 =====
@Composable
private fun FocusCard(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val m = modifier
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                onClick(); true
            } else false
        }
        .clickable(onClick = onClick)
        .graphicsLayer {
            val s = if (focused) 1.06f else 1f
            scaleX = s; scaleY = s
        }
        .background(if (focused) TvFocusBg else TvCardBg, RoundedCornerShape(14.dp))
        .border(3.dp, if (focused) TvAccentBright else Color.Transparent, RoundedCornerShape(14.dp))
        .padding(horizontal = 20.dp, vertical = 14.dp)
    Row(m, verticalAlignment = Alignment.CenterVertically, content = content)
}

// ===== 竖向网格焦点卡片：图标在上、名称在下，焦点时文件名流水灯滚动 =====
@Composable
private fun GridFocusCard(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val m = modifier
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                onClick(); true
            } else false
        }
        .clickable(onClick = onClick)
        .graphicsLayer {
            val s = if (focused) 1.08f else 1f
            scaleX = s; scaleY = s
        }
        .background(if (focused) TvFocusBg else TvCardBg, RoundedCornerShape(12.dp))
        .border(3.dp, if (focused) TvAccentBright else Color.Transparent, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp)
    Column(m, horizontalAlignment = Alignment.CenterHorizontally) { content(focused) }
}

// ===== 流水灯文字：聚焦且超长时左右循环滚动 =====
@Composable
private fun MarqueeText(
    text: String,
    focused: Boolean,
    fontSize: TextUnit = 16.sp,
    color: Color = TvText,
    modifier: Modifier = Modifier,
) {
    var textWidth by remember { mutableStateOf(0) }
    var boxWidth by remember { mutableStateOf(0) }
    val transition = rememberInfiniteTransition(label = "marquee")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 4200, easing = LinearEasing), RepeatMode.Restart
        ), label = "marqueeT"
    )
    if (!focused) {
        Text(text, fontSize = fontSize, color = color, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    Box(modifier.onSizeChanged { boxWidth = it.width }.clipToBounds()) {
        Text(
            text, fontSize = fontSize, color = color, maxLines = 1, softWrap = false,
            onTextLayout = { textWidth = it.size.width },
            modifier = Modifier.graphicsLayer {
                val dist = (textWidth - boxWidth).coerceAtLeast(0)
                translationX = if (dist > 0) -dist * t else 0f
            }
        )
    }
}

// ===== TV 文本输入：聚焦字段 → OK 弹编辑框（避免软键盘挡 D-pad 导航） =====
@Composable
private fun TvTextInputField(label: String, value: String, onValueChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    FocusCard(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(label, fontSize = 15.sp, color = TvTextDim)
            Spacer(Modifier.height(2.dp))
            Text(if (value.isBlank()) "（未填写）" else value, fontSize = 20.sp, color = TvText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (editing) {
        TvEditDialog(title = label, initial = value,
            onConfirm = { v -> onValueChange(v); editing = false },
            onCancel = { editing = false })
    }
}

@Composable
private fun TvEditDialog(title: String, initial: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.fillMaxWidth(0.85f).background(TvCardBg, RoundedCornerShape(16.dp))
                .border(2.dp, TvAccent, RoundedCornerShape(16.dp)).padding(24.dp)
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TvText)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusCard(onClick = { onConfirm(text) }, modifier = Modifier.weight(1f)) {
                    Text("确定", fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                FocusCard(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("取消", fontSize = 20.sp, color = TvTextDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(text, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = TvText,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 20.dp))
}

// ===== 设置页 =====
@Composable
private fun SetupScreen(
    onShowServers: (List<ServerEntry>) -> Unit,
    onManualConnect: (ServerEntry) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bcom by remember { mutableStateOf(Prefs.getBcom(ctx)) }
    var user by remember { mutableStateOf(Prefs.getUser(ctx)) }
    var pass by remember { mutableStateOf(Prefs.getPass(ctx)) }
    var showManualDialog by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    // 扫码填写
    var showQr by remember { mutableStateOf(false) }
    var qrUrl by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrServer by remember { mutableStateOf<LanQrSync?>(null) }

    fun stopQr() { qrServer?.stop(); qrServer = null; showQr = false }

    fun startQr() {
        val ip = lanIp()
        if (ip == null) { error = "没检测到局域网 IP，请检查电视的网络连接"; return }
        val server = LanQrSync { b, u, p ->
            bcom = b; user = u; pass = p
            Prefs.setBcom(ctx, b); Prefs.setUser(ctx, u); Prefs.setPass(ctx, p)
            stopQr()
        }
        try {
            server.start()
            qrServer = server
            qrUrl = "http://$ip:${server.listeningPort}/"
            qrBitmap = generateQr(qrUrl)
            showQr = true
        } catch (e: Exception) {
            error = "启动扫码服务失败：${e.message}"
            server.stop()
        }
    }

    DisposableEffect(Unit) { onDispose { qrServer?.stop() } }

    fun query() {
        Prefs.setBcom(ctx, bcom); Prefs.setUser(ctx, user); Prefs.setPass(ctx, pass)
        loading = true; error = ""; result = ""
        scope.launch {
            val list = AddressResolver(ctx).fetch()
            loading = false
            if (list.isEmpty()) {
                error = "没拿到服务器列表，请检查清单页地址或网络"
            } else {
                Prefs.setServerCache(ctx, AddressResolver.encode(list))
                result = "找到 ${list.size} 台服务器"
                onShowServers(list)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WebDAV 电视播放器", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TvText)
        Text("① 填清单页地址 → ② 查询/选服务器 → ③ 遥控器看视频", fontSize = 16.sp, color = TvTextDim)

        TvTextInputField("服务器清单页地址", bcom) { bcom = it }
        TvTextInputField("WebDAV 用户名", user) { user = it }
        TvTextInputField("WebDAV 密码", pass) { pass = it }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusCard(onClick = { query() }, modifier = Modifier.weight(1f)) {
                Text("查询可用服务器", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TvText,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            FocusCard(onClick = { startQr() }, modifier = Modifier.weight(1f)) {
                Text("📱 扫码填写", fontSize = 20.sp, color = TvAccentBright,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        FocusCard(onClick = { showManualDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("＋ 手动连接（内网地址）", fontSize = 20.sp, color = TvText)
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text("正在查询…")
            }
        }
        if (error.isNotEmpty()) Text(error, color = TvError, fontSize = 16.sp)
        if (result.isNotEmpty()) Text(result, color = TvAccentBright, fontSize = 16.sp)

        Spacer(Modifier.weight(1f))
    }

    if (showManualDialog) {
        TvEditDialog(title = "手动服务器地址", initial = "",
            onConfirm = { v ->
                showManualDialog = false
                val s = parseManualServer(v)
                if (s == null) error = "地址格式不对，示例：192.168.1.10:8080/dav"
                else { Prefs.setUser(ctx, user); Prefs.setPass(ctx, pass); onManualConnect(s) }
            },
            onCancel = { showManualDialog = false })
    }

    if (showQr) {
        Dialog(onDismissRequest = { stopQr() }) {
            Column(Modifier.fillMaxWidth(0.8f).background(TvCardBg, RoundedCornerShape(16.dp))
                .border(2.dp, TvAccent, RoundedCornerShape(16.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("手机扫码填写", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TvText)
                Spacer(Modifier.height(8.dp))
                Text("用手机扫二维码，在手机上填服务器地址/用户名/密码，提交后自动同步到电视",
                    fontSize = 15.sp, color = TvTextDim, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                val bmp = qrBitmap
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = "二维码", modifier = Modifier.size(280.dp))
                } else {
                    CircularProgressIndicator(Modifier.size(40.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(qrUrl, fontSize = 15.sp, color = TvAccentBright, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                FocusCard(onClick = { stopQr() }, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭", fontSize = 20.sp, color = TvTextDim,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 解析手动地址 [scheme://]host:port/path → ServerEntry；不合法返回 null。 */
private fun parseManualServer(input: String): ServerEntry? {
    val rawScheme = when {
        input.contains("https://") -> "https"
        input.contains("http://") -> "http"
        else -> "http"
    }
    val t = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (t.isBlank()) return null
    val hostPort = t.substringBefore('/')
    val path = t.substringAfter('/', "")
    val host = hostPort.substringBefore(':')
    val port = hostPort.substringAfter(':', "").toIntOrNull() ?: 80
    if (host.isBlank()) return null
    val basePath = if (path.isBlank()) "" else "/" + path.trim('/')
    return ServerEntry(host, port, "", basePath, rawScheme)
}

// ===== 服务器选择页 =====
@Composable
private fun ServerPickScreen(
    servers: List<ServerEntry>,
    onPick: (ServerEntry) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    BackHandler(onBack = onBack)

    // 逐个轻量探测可达性
    LaunchedEffect(servers) {
        status = servers.associate { keyOf(it) to null }
        servers.forEach { s ->
            val ok = withContext(Dispatchers.IO) {
                try {
                    WebDavClient("${s.scheme}://${s.host}:${s.port}${s.basePath}", Prefs.getUser(ctx), Prefs.getPass(ctx))
                        .list("/").error == null
                } catch (e: Exception) { false }
            }
            status = status + (keyOf(s) to ok)
        }
    }

    // 可达的排前，内网优先
    val sorted = remember(servers, status) {
        servers.sortedWith(
            compareByDescending<ServerEntry> {
                when (status[keyOf(it)]) { true -> 2; null -> 1; else -> 0 }
            }.thenByDescending { if (isLan(it.host)) 1 else 0 }
        )
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(sorted) { delay(200); firstFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 40.dp)) {
        ScreenTitle("选择服务器（${servers.size} 台）")
        if (servers.isEmpty()) {
            Text("没有解析到服务器，请返回重新查询", fontSize = 20.sp, color = TvTextDim)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(sorted) { index, s ->
                    val st = status[keyOf(s)]
                    FocusCard(onClick = { onPick(s) }, focusRequester = if (index == 0) firstFocus else null, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(s.display, fontSize = 22.sp, color = TvText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (st) {
                                    true -> "✓ 可达" + if (isLan(s.host)) "（内网）" else ""
                                    false -> "✗ 连接失败"
                                    else -> "探测中…"
                                },
                                fontSize = 16.sp,
                                color = when (st) { true -> TvAccentBright; false -> TvError; else -> TvTextDim }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FocusCard(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("◀ 返回设置", fontSize = 20.sp, color = TvTextDim)
        }
    }
}

private fun keyOf(s: ServerEntry): String = "${s.host}:${s.port}${s.basePath}"

private fun isLan(host: String): Boolean {
    val h = host.lowercase()
    return h == "localhost" || h.startsWith("127.") || h.startsWith("192.168.") ||
        h.startsWith("10.") || (h.startsWith("172.") && h.substringAfter("172.").substringBefore(".").toIntOrNull()?.let { it in 16..31 } == true)
}

// ===== 目录浏览页(首页) =====
@Composable
private fun BrowseScreen(
    server: ServerEntry?,
    path: String,
    restoreIndex: Int,
    onOpen: (String) -> Unit,
    onWatch: (String, DavEntry) -> Unit,
    onPlayHistory: (PlayHistory) -> Unit,
    onBack: () -> Unit,
    onSwitchServer: () -> Unit,
    onSchemeResolved: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val restoreFocus = remember { FocusRequester() }
    var entries by remember { mutableStateOf<List<DavEntry>?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var reqSeq by remember { mutableStateOf(0) }
    val historyList = remember { Prefs.getHistoryForList(ctx) }

    fun load() {
        val s = server ?: return
        val currentPath = path
        val seq = ++reqSeq
        loading = true; entries = null; error = ""
        scope.launch {
            val client = WebDavClient("${s.scheme}://${s.host}:${s.port}${s.basePath}", Prefs.getUser(ctx), Prefs.getPass(ctx))
            val result = client.list(currentPath)
            if (seq != reqSeq) return@launch
            loading = false
            // 自动切换协议成功: 把实际生效的 scheme 写回, 保证后续播放 URL / 选集也正确
            if (result.baseUsed != null && result.baseUsed != s.scheme) onSchemeResolved(result.baseUsed)
            when {
                result.error != null -> error = result.error
                result.entries.isEmpty() -> error = "目录是空的"
                else -> entries = result.entries
            }
        }
    }
    LaunchedEffect(path, server) { load() }

    // 数据加载后恢复焦点到之前选中的条目（默认第一项）
    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        if (list.isNotEmpty()) {
            val target = restoreIndex.coerceIn(0, list.lastIndex)
            delay(80)
            gridState.scrollToItem(target)
            delay(80)
            restoreFocus.requestFocus()
        }
    }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        // 顶部：返回 + 面包屑 + 换服务器
        Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = onBack) {
                Text("◀", fontSize = 22.sp, color = TvText)
            }
            Spacer(Modifier.width(10.dp))
            Text(path.ifEmpty { "/" }, fontSize = 20.sp, color = TvTextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            FocusCard(onClick = onSwitchServer) {
                Text("换服务器", fontSize = 18.sp, color = TvAccentBright)
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(48.dp))
            }
            error.isNotEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(error, color = TvError, fontSize = 22.sp)
                Spacer(Modifier.height(20.dp))
                FocusCard(onClick = onBack) { Text("◀ 返回", fontSize = 20.sp) }
            }
            else -> {
                Column(Modifier.weight(1f)) {
                    // 继续观看（仅首页根目录显示）
                    if (path == "/" && historyList.isNotEmpty()) {
                        Text("继续观看", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TvText,
                            modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(historyList) { h ->
                                GridFocusCard(onClick = { onPlayHistory(h) }) { focused ->
                                    Text("🎬", fontSize = 36.sp)
                                    Spacer(Modifier.height(6.dp))
                                    MarqueeText(h.title, focused = focused, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (h.durationMs > 0 && h.positionMs > 0)
                                            "已看 ${formatMs(h.positionMs)} / ${formatMs(h.durationMs)}"
                                        else "已看完 · 重新播放",
                                        fontSize = 12.sp, color = TvAccentBright, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    // 文件网格：文件夹在前、视频在后，框式多行布局
                    val list = entries.orEmpty()
                    val sorted = remember(list) {
                        list.sortedWith(compareByDescending<DavEntry> { it.isDir }.thenBy { naturalCompare(it.name, "") })
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(190.dp),
                        state = gridState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        gridItemsIndexed(sorted) { i, e ->
                            val (icon, typeText, playable) = if (e.isDir) Triple("📁", "文件夹", false) else fileType(e.name)
                            val focus = if (i == restoreIndex) restoreFocus else null
                            val sub = if (e.isDir) "文件夹" else {
                                val base = "$typeText · ${formatSize(e.size)}"
                                val hv = if (playable) Prefs.getHistoryByUrl(ctx, urlFor(server!!, e.href)) else null
                                if (hv != null && hv.durationMs > 0 && hv.positionMs > 0) "$base · 已看 ${formatMs(hv.positionMs)}" else base
                            }
                            GridFocusCard(focusRequester = focus, onClick = {
                                when {
                                    e.isDir -> onOpen(relativePath(server!!, e.href))
                                    playable -> onWatch(urlFor(server!!, e.href), e)
                                    else -> {}
                                }
                            }) { focused ->
                                Text(icon, fontSize = 40.sp)
                                Spacer(Modifier.height(8.dp))
                                MarqueeText(e.name, focused = focused, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                Text(sub, fontSize = 12.sp, color = TvTextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== 播放页 =====
@Composable
private fun PlayerScreen(
    server: ServerEntry?,
    url: String,
    entry: DavEntry?,
    currentPath: String = "",
    onSwitchEpisode: (DavEntry) -> Unit,
    onSystemBack: () -> Unit,
    onSchemeResolved: (String) -> Unit = {},
) {
    val name = entry?.name ?: ""
    val ctx = LocalContext.current
    val user = Prefs.getUser(ctx)
    val pass = Prefs.getPass(ctx)

    var controlsVisible by remember { mutableStateOf(true) }
    var lastControlAction by remember { mutableStateOf(System.currentTimeMillis()) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var playError by remember { mutableStateOf("") }
    var seekHint by remember { mutableStateOf("") }
    var showEpisodes by remember { mutableStateOf(false) }

    // 选集
    var episodes by remember { mutableStateOf<List<DavEntry>?>(null) }
    var episodesLoading by remember { mutableStateOf(false) }
    val currentName = remember(url, entry) {
        val raw = entry?.name?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
        try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
    }
    val currentIdx = remember(episodes, currentName) { episodes?.indexOfFirst { it.name == currentName } ?: -1 }

    // OkHttp 数据源(原逻辑不变: 自动处理中文URL编码/302/Basic认证)
    val okHttpDsFactory = remember(user, pass) {
        val okHttp = UnsafeOkHttp.client()
        OkHttpDataSource.Factory(okHttp)
            .setUserAgent("WebDAVCast-TV/1.0")
            .setDefaultRequestProperties(mapOf("Authorization" to BasicAuth.header(user, pass)))
    }
    // 预存 + 本地缓存: 起播门槛 2.5 秒不变, 播放中自动预存 120 秒并落盘
    // (缓存上限按本机容量/剩余空间自动算, 天花板 1GB / 超 7 天自动清)
    // LazyCacheFactory: 缓存未就绪时自动降级纯网络, 就绪后自动生效, 全程不阻塞 UI
    val player = remember(ctx) {
        VideoCache.playerBuilder(ctx)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(VideoCache.LazyCacheFactory(ctx, okHttpDsFactory))
            ).build()
    }

    val latestUrl by rememberUpdatedState(url)
    val latestName by rememberUpdatedState(name)

    // 播放页根节点始终持焦点，方向键全部手动处理（避免 Compose 焦点遍历在覆盖层内失效）
    val rootFocus = remember { FocusRequester() }
    var selectedBtn by remember { mutableStateOf(1) } // 0上集 1播放 2下集 3选集 4倍速 5预存 6进度条
    var selectedEpisode by remember { mutableStateOf(0) }
    // 倍速
    val speedOptions = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    var speedIdx by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }

    // 长按方向键连续快进/快退
    var isHolding by remember { mutableStateOf(false) }
    var holdDirection by remember { mutableIntStateOf(0) } // -1=左, +1=右


    fun showOverlay() { controlsVisible = true; lastControlAction = System.currentTimeMillis() }
    fun hideOverlay() { controlsVisible = false }
    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        isPlaying = player.isPlaying
        lastControlAction = System.currentTimeMillis()
    }
    fun seekBy(deltaMs: Long) {
        val d = player.duration
        val target = (player.currentPosition + deltaMs).coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
        if (d > 0) player.seekTo(target)
        seekHint = "${if (deltaMs < 0) "⏪ 快退" else "⏩ 快进"} ${formatMs(target)} / ${formatMs(if (d > 0) d else 0L)}"
    }

    // 拉取当前目录视频清单
    LaunchedEffect(server, currentPath) {
        val s = server ?: return@LaunchedEffect
        if (currentPath.isBlank()) return@LaunchedEffect
        episodesLoading = true; episodes = null
        val client = WebDavClient("${s.scheme}://${s.host}:${s.port}${s.basePath}", user, pass)
        val result = client.list(currentPath)
        episodesLoading = false
        if (result.baseUsed != null && result.baseUsed != s.scheme) onSchemeResolved(result.baseUsed)
        if (result.error == null) {
            episodes = result.entries.filter { !it.isDir && fileType(it.name).third }
                .sortedWith { x, y -> naturalCompare(x.name, y.name) }
        }
    }

    fun switchToEpisode(e: DavEntry) {
        if (server == null) return
        val pos = player.currentPosition; val dur = player.duration
        if (dur > 0 && pos > 0) Prefs.saveHistory(ctx, latestUrl, latestName.ifEmpty { latestUrl.substringAfterLast('/') }, pos, dur, currentPath)
        onSwitchEpisode(e)
        showEpisodes = false
        lastControlAction = System.currentTimeMillis()
    }
    fun gotoPrev() {
        val list = episodes
        if (list == null || currentIdx <= 0) { seekHint = if (list == null) "选集加载中…" else "已经是第一集"; return }
        switchToEpisode(list[currentIdx - 1])
    }
    fun gotoNext() {
        val list = episodes
        if (list == null || currentIdx < 0 || currentIdx >= list.size - 1) { seekHint = if (list == null) "选集加载中…" else "已经是最后一集"; return }
        switchToEpisode(list[currentIdx + 1])
    }
    fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speedOptions.size
        playbackSpeed = speedOptions[speedIdx]
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        seekHint = "倍速 ${playbackSpeed}x"
    }
    fun activate(btn: Int) {
        when (btn) {
            0 -> gotoPrev()
            1 -> togglePlayPause()
            2 -> gotoNext()
            3 -> { selectedEpisode = currentIdx.coerceAtLeast(0); showEpisodes = true }
            4 -> cycleSpeed()
        }
        lastControlAction = System.currentTimeMillis()
    }

    // 长按左右键：连续快进/快退，渐进加速
    LaunchedEffect(isHolding, holdDirection) {
        if (isHolding && holdDirection != 0) {
            // step 递进: 1s 2s 5s 10s 20s 30s 60s
            val steps = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000, 30_000, 60_000)
            val intervals = intArrayOf(200, 180, 150, 120, 100, 80, 60)
            var lvl = 0
            var elapsed = 0
            while (isHolding && holdDirection != 0) {
                val ms = steps[lvl]
                delay(intervals[lvl].toLong())
                elapsed += intervals[lvl]
                seekBy(holdDirection * ms)
                // 随时间升级: 1s→2s→5s→10s→20s→30s→60s
                when {
                    elapsed > 8000 && lvl < 6 -> lvl = 6
                    elapsed > 5000 && lvl < 5 -> lvl = 5
                    elapsed > 3000 && lvl < 4 -> lvl = 4
                    elapsed > 2000 && lvl < 3 -> lvl = 3
                    elapsed > 1000 && lvl < 2 -> lvl = 2
                    elapsed > 500  && lvl < 1 -> lvl = 1
                }
            }
        }
    }

    // 选集列表滚动跟随
    val epListState = rememberLazyListState()
    LaunchedEffect(selectedEpisode, showEpisodes) {
        if (showEpisodes && episodes?.isNotEmpty() == true) {
            epListState.animateScrollToItem(selectedEpisode)
        }
    }

    // 进度轮询
    LaunchedEffect(url) {
        while (true) {
            if (url.isNotEmpty()) {
                isPlaying = player.isPlaying
                positionMs = player.currentPosition
                val d = player.duration
                if (d > 0) durationMs = d
            }
            delay(300)
        }
    }

    // 装载并续播
    LaunchedEffect(url) {
        if (url.isNotEmpty()) {
            playError = ""
            player.setMediaItem(MediaItem.Builder().setUri(url).build())
            val hv = Prefs.getHistoryByUrl(ctx, url)
            if (hv != null && hv.durationMs > 0 && hv.positionMs >= 10_000 && hv.positionMs < hv.durationMs - 5_000) {
                player.seekTo(hv.positionMs)
                seekHint = "⏯ 从 ${formatMs(hv.positionMs)} 继续播放"
            }
            player.prepare()
            player.playWhenReady = true
        }
    }

    // 自动保存进度（每 5 秒）
    LaunchedEffect(url) {
        if (url.isEmpty()) return@LaunchedEffect
        var lastSaved = 0L
        while (true) {
            delay(5000)
            val pos = player.currentPosition; val dur = player.duration
            if (dur > 0 && pos > 0 && pos > lastSaved + 3_000 && pos < dur - 1_000) {
                lastSaved = pos
                Prefs.saveHistory(ctx, url, name.ifEmpty { url.substringAfterLast('/') }, pos, dur, currentPath)
            }
        }
    }

    // 播放错误 / 播完
    LaunchedEffect(player) {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                var detail = error.message ?: error.cause?.message ?: "未知原因"
                val cause = error.cause
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    detail = "HTTP ${cause.responseCode}: $detail"
                }
                playError = "播放失败 [${error.errorCodeName}]: $detail"
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    seekHint = "🎬 播放完毕"
                    Prefs.saveHistory(ctx, latestUrl, latestName.ifEmpty { latestUrl.substringAfterLast('/') }, 0L, 0L, currentPath)
                }
            }
        })
    }

    // 退出保存 + 释放
    DisposableEffect(player) {
        onDispose {
            val pos = player.currentPosition; val dur = player.duration
            if (dur > 0 && pos > 0) Prefs.saveHistory(ctx, latestUrl, latestName.ifEmpty { latestUrl.substringAfterLast('/') }, pos, dur, currentPath)
            player.release()
        }
    }

    // 覆盖层自动隐藏
    LaunchedEffect(controlsVisible) {
        while (controlsVisible) {
            delay(1000)
            if (System.currentTimeMillis() - lastControlAction > 5000) {
                hideOverlay()
            }
        }
    }

    // seekHint 自动消失
    LaunchedEffect(seekHint) {
        if (seekHint.isNotEmpty()) { delay(1800); seekHint = "" }
    }

    BackHandler(onBack = {
        if (controlsVisible) hideOverlay() else onSystemBack()
    })

    Box(Modifier.fillMaxSize().background(Color.Black)
        .focusRequester(rootFocus)
        .focusable()
        .onKeyEvent { ev ->
            // 松开方向键：停止长按连续 seek
            if (ev.type == KeyEventType.KeyUp) {
                if (isHolding) {
                    isHolding = false; holdDirection = 0
                    return@onKeyEvent true
                }
            }
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            val menuKey = ev.nativeKeyEvent?.keyCode == AndroidKeyEvent.KEYCODE_MENU
            // 菜单键：召唤/隐藏控制栏
            if (menuKey && !showEpisodes) {
                if (!controlsVisible) showOverlay() else hideOverlay()
                return@onKeyEvent true
            }
            when {
                // 选集弹层：上下选、OK 播放、返回关闭
                showEpisodes -> {
                    val list = episodes
                    when (ev.key) {
                        Key.DirectionUp -> { selectedEpisode = (selectedEpisode - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { selectedEpisode = (selectedEpisode + 1).coerceAtMost((list?.size ?: 1) - 1); true }
                        Key.DirectionCenter, Key.Enter -> {
                            if (list != null && list.isNotEmpty()) switchToEpisode(list[selectedEpisode.coerceIn(0, list.lastIndex)])
                            else { showEpisodes = false; lastControlAction = System.currentTimeMillis() }
                            true
                        }
                        Key.Back -> { showEpisodes = false; lastControlAction = System.currentTimeMillis(); true }
                        else -> false
                    }
                }
                // 控制栏显示：方向键做按键选择
                controlsVisible -> {
                    lastControlAction = System.currentTimeMillis()
                    when (ev.key) {
                        Key.DirectionLeft -> {
                            if (selectedBtn == 5) seekBy(-10_000) else selectedBtn = (selectedBtn - 1).coerceAtLeast(0)
                            true
                        }
                        Key.DirectionRight -> {
                            if (selectedBtn == 5) seekBy(10_000) else selectedBtn = (selectedBtn + 1).coerceAtMost(4)
                            true
                        }
                        Key.DirectionDown -> { selectedBtn = 5; true } // 下到进度条
                        Key.DirectionUp -> { if (selectedBtn == 5) selectedBtn = 1; true } // 从进度条回按钮
                        Key.DirectionCenter, Key.Enter -> { activate(selectedBtn); true }
                        Key.Back -> { hideOverlay(); true }
                        else -> false
                    }
                }
                // 控制栏隐藏：左/右=长按连续快进/快退，OK=播放/暂停，上下=唤出控制栏
                else -> {
                    when (ev.key) {
                        Key.DirectionLeft -> {
                            if (ev.nativeKeyEvent?.repeatCount == 0) {
                                isHolding = true; holdDirection = -1
                                seekBy(-1_000)
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (ev.nativeKeyEvent?.repeatCount == 0) {
                                isHolding = true; holdDirection = 1
                                seekBy(1_000)
                            }
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> { showOverlay(); togglePlayPause(); true }
                        Key.DirectionUp, Key.DirectionDown -> { showOverlay(); true }
                        Key.Back -> { onSystemBack(); true }
                        else -> false
                    }
                }
            }
        }
    ) {
        // 视频
        if (url.isNotEmpty()) {
            AndroidView(
                factory = { c ->
                    androidx.media3.ui.PlayerView(c).also { v ->
                        v.player = player
                        v.useController = false
                        v.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        v.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        v.setShutterBackgroundColor(android.graphics.Color.BLACK)
                        v.setBackgroundColor(android.graphics.Color.BLACK)
                        v.isFocusable = false
                    }
                },
                update = { v -> v.player = player },
                modifier = Modifier.matchParentSize()
            )
        }

        // seekHint 提示
        if (seekHint.isNotEmpty()) {
            Text(seekHint, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp))
        }

        // 右上角系统时间
        var currentTime by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                val cal = java.util.Calendar.getInstance()
                currentTime = "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                delay(1000)
            }
        }
        Text(currentTime, fontSize = 16.sp, color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 40.dp, vertical = 16.dp))

        // 播放错误
        if (playError.isNotEmpty()) {
            Text(playError, fontSize = 20.sp, color = TvError, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp))
        }

        // ===== 覆盖层 =====
        if (controlsVisible) {
            // 顶栏：文件名
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 40.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(name.ifEmpty { "视频" }, fontSize = 24.sp, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }

            // 底栏：按钮行 + 进度条
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f)).padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 60.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TvControlButton("⏮", "上集", selected = selectedBtn == 0) { activate(0) }
                    TvControlButton(
                        if (isPlaying) "⏸" else "▶", if (isPlaying) "暂停" else "播放",
                        selected = selectedBtn == 1
                    ) { activate(1) }
                    TvControlButton("⏭", "下集", selected = selectedBtn == 2) { activate(2) }
                    TvControlButton("☰", "选集", selected = selectedBtn == 3) { activate(3) }
                    TvControlButton("×${playbackSpeed}", "倍速", selected = selectedBtn == 4) { activate(4) }
                }
                Spacer(Modifier.height(14.dp))
                // 进度条（选中后 ←/→ 拖动 seek）
                TvSeekBar(positionMs = positionMs, durationMs = durationMs, selected = selectedBtn == 5)
            }
        }

        // 选集弹层
        if (showEpisodes) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).fillMaxHeight(0.7f)
                .background(TvCardBg, RoundedCornerShape(16.dp)).border(2.dp, TvAccent, RoundedCornerShape(16.dp))
                .padding(20.dp)) {
                Text("选集${episodes?.let { "（${it.size} 集）" } ?: ""}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TvText)
                Spacer(Modifier.height(12.dp))
                when {
                    episodesLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)); Text("加载中…")
                    }
                    episodes.isNullOrEmpty() -> Text("该目录暂无视频或未连接服务器", color = TvTextDim, fontSize = 18.sp)
                    else -> {
                        val list = episodes!!
                        LazyColumn(state = epListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(list) { i, e ->
                                val isSel = i == selectedEpisode
                                val isCur = i == currentIdx
                                Row(Modifier.fillMaxWidth()
                                    .background(if (isSel) TvFocusBg else Color.Transparent, RoundedCornerShape(8.dp))
                                    .border(2.dp, if (isSel) TvAccentBright else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { switchToEpisode(e) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}.", fontSize = 18.sp,
                                        fontWeight = if (isCur || isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCur) TvAccentBright else TvText)
                                    Spacer(Modifier.width(10.dp))
                                    Text(e.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isCur || isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCur) TvAccentBright else TvText)
                                    val hv = Prefs.getHistoryByUrl(ctx, urlFor(server!!, e.href))
                                    if (hv != null && hv.durationMs > 0 && hv.positionMs > 0) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("已看 ${formatMs(hv.positionMs)}", fontSize = 13.sp, color = TvAccentBright)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()
                    .background(TvCardBg, RoundedCornerShape(10.dp))
                    .clickable { showEpisodes = false; lastControlAction = System.currentTimeMillis() }
                    .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center) {
                    Text("关闭", fontSize = 18.sp, color = TvTextDim)
                }
            }
        }
    }

    // 根节点持焦点接收方向键
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
}

// ===== 覆盖层按钮 =====
@Composable
private fun TvControlButton(symbol: String, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .graphicsLayer { val s = if (selected) 1.12f else 1f; scaleX = s; scaleY = s }
            .background(if (selected) TvFocusBg else Color.Transparent, RoundedCornerShape(10.dp))
            .border(2.dp, if (selected) TvAccentBright else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(symbol, fontSize = 30.sp, color = Color.White)
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
    }
}

// ===== 可选中进度条 =====
@Composable
private fun TvSeekBar(positionMs: Long, durationMs: Long, selected: Boolean = false) {
    val prog = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 60.dp).height(40.dp)
            .border(2.dp, if (selected) TvAccentBright else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(formatMs(positionMs), fontSize = 15.sp, color = Color.White)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(6.dp)
            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(prog).height(6.dp).background(TvAccentBright, RoundedCornerShape(3.dp)))
        }
        Text(formatMs(durationMs), fontSize = 15.sp, color = Color.White)
    }
}

// ===== 通用工具函数 =====

private fun urlFor(s: ServerEntry, href: String): String {
    if (href.startsWith("http")) return href
    var p = href
    if (s.basePath.isNotEmpty() && !p.startsWith(s.basePath)) p = s.basePath + p
    val encoded = try {
        val decoded = java.net.URLDecoder.decode(p, "UTF-8")
        val parts = decoded.split("/")
        parts.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    } catch (e: Exception) { p }
    return "${s.scheme}://${s.host}:${s.port}$encoded"
}

private fun relocateUrl(oldUrl: String, server: ServerEntry?): String {
    if (server == null || oldUrl.isBlank()) return oldUrl
    val scheme = if (oldUrl.startsWith("https://")) "https" else "http"
    val afterScheme = oldUrl.substringAfter("://", "")
    val pathPart = afterScheme.substringAfter("/", "")
    return "${server.scheme}://${server.host}:${server.port}/${pathPart.trimStart('/')}"
}

/** 路径解码: 服务器 href / 旧历史里存的路径可能是 URL 编码的(%E7%94%B5%E5%BD%B1),
 *  显示与请求前统一解码, 保证界面显示中文(与手机版 1.0.8 一致)。 */
private fun decodePathSafe(p: String): String =
    if (p.isBlank()) p else try { java.net.URLDecoder.decode(p, "UTF-8") } catch (e: Exception) { p }

private fun inferDirFromUrl(url: String, server: ServerEntry?): String {
    if (url.isBlank()) return "/"
    val pathPart = url.substringAfter("://", "").substringAfter("/", "")
    val dir = pathPart.substringBeforeLast("/", "")
    var rel = dir.trim('/')
    val bp = server?.basePath?.trim('/') ?: ""
    if (bp.isNotEmpty() && rel.startsWith(bp)) rel = rel.removePrefix(bp).trimStart('/')
    // URL 解码: 旧历史里的路径可能是编码过的(%E7%94%B5%E5%BD%B1), 显示/回目录需要中文
    val dec = decodePathSafe(rel)
    return if (dec.isEmpty()) "/" else "/$dec/"
}

private fun relativePath(s: ServerEntry, href: String): String {
    var p = href
    if (p.startsWith("http://") || p.startsWith("https://")) {
        p = p.substringAfter("://").substringAfter("/", "").let { "/$it" }
    }
    // href 通常是 URL 编码的(%E7%94%B5%E5%BD%B1), 先解码得到中文路径
    // (显示与请求都用解码后的相对路径, urlFor() 时会统一再编码)
    val decoded = decodePathSafe(p)
    if (s.basePath.isNotEmpty() && decoded.startsWith(s.basePath)) {
        return decoded.removePrefix(s.basePath).trimStart('/')
            .let { if (it.isEmpty()) "/" else "/$it".let { q -> if (q.endsWith("/")) q else "$q/" } }
    }
    return decoded.trimStart('/').let { if (it.isEmpty()) "/" else "/$it".let { q -> if (q.endsWith("/")) q else "$q/" } }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:%02d:%02d".format(m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}

private fun parentPathOf(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    val parent = trimmed.substringBeforeLast('/')
    return if (parent.isEmpty()) "/" else "$parent/"
}

private fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ni = i; while (ni < a.length && a[ni].isDigit()) ni++
            var nj = j; while (nj < b.length && b[nj].isDigit()) nj++
            val na = a.substring(i, ni).trimStart('0').let { if (it.isEmpty()) "0" else it }
            val nb = b.substring(j, nj).trimStart('0').let { if (it.isEmpty()) "0" else it }
            val cmp = when { na.length != nb.length -> na.length - nb.length; else -> na.compareTo(nb) }
            if (cmp != 0) return if (cmp > 0) 1 else -1
            i = ni; j = nj
        } else {
            val c = ca.compareTo(cb)
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private val VIDEO_EXTS = setOf("mp4","mkv","avi","mov","wmv","flv","webm","m4v","3gp","ts","m2ts","mts","mpg","mpeg","rmvb","rm","vob")
private val IMAGE_EXTS = setOf("jpg","jpeg","png","gif","bmp","webp","heic")
private val AUDIO_EXTS = setOf("mp3","aac","flac","wav","ogg","m4a","opus")
private val ARCHIVE_EXTS = setOf("zip","rar","7z","tar","gz","bz2","xz")

private fun fileType(name: String): Triple<String, String, Boolean> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in VIDEO_EXTS -> Triple("🎬", "视频", true)
        ext in IMAGE_EXTS -> Triple("🖼️", "图片", false)
        ext in AUDIO_EXTS -> Triple("🎵", "音频", false)
        ext in ARCHIVE_EXTS -> Triple("📦", "压缩包", false)
        ext.isBlank() -> Triple("📄", "文件", false)
        else -> Triple("📄", "文件", false)
    }
}
