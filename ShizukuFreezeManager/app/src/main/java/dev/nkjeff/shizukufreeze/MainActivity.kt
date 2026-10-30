package dev.nkjeff.shizukufreeze

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sourceforge.pinyin4j.PinyinHelper
import rikka.shizuku.Shizuku

private val Bg = Color.Black
private val Muted = Color(0xFF8793A5)
private val Accent = Color(0xFF9BBCEB)

class MainActivity : ComponentActivity() {
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent { FreezeApp() }
    }
    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FreezeApp() {
    val context = LocalContext.current
    val shell = remember { ShizukuShell() }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("freeze_manager", 0) }
    var allApps by remember { mutableStateOf(emptyList<AppInstance>()) }
    var runningApps by remember { mutableStateOf(emptyList<AppInstance>()) }
    var whitelist by remember { mutableStateOf(prefs.getStringSet("whitelist", emptySet())?.toSet() ?: emptySet()) }
    var pinned by remember { mutableStateOf(prefs.getStringSet("pinned", emptySet())?.toSet() ?: emptySet()) }
    var launchCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("请连接并授权 Shizuku") }
    val rightListState = rememberLazyListState()
    val letters = ('A'..'Z').map { it.toString() }

    fun refresh() {
        if (busy) return
        scope.launch {
            busy = true
            message = "正在扫描应用和后台进程…"
            try {
                val result = withContext(Dispatchers.IO) {
                    check(Shizuku.pingBinder()) { "没有连接 Shizuku，请先启动 Shizuku 服务。" }
                    check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku 尚未授权。" }
                    val found = shell.scanUsers().flatMap { user ->
                        val (packages, disabled) = shell.scanPackages(user)
                        packages.map { pkg ->
                            val info = runCatching {
                                context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
                            }.getOrNull()
                            val label = info?.let {
                                runCatching { context.packageManager.getApplicationLabel(it).toString() }.getOrNull()
                            }?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')
                            AppInstance(
                                pkg, user, label,
                                info?.let { (it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 } ?: false,
                                pkg in disabled, user != 0
                            )
                        }
                    }.distinctBy { it.key }.filter {
                        it.packageName != context.packageName && it.packageName != "android"
                    }
                    val running = shell.scanRunningInstances()
                    val counts = readLaunchCounts(context)
                    Triple(found, running, counts)
                }
                allApps = result.first
                runningApps = result.first.filter { it.key in result.second && !it.isDisabled && !it.isSystem }
                launchCounts = result.third
                selectedLetter = null
                rightListState.scrollToItem(0)
                message = "扫描完成：运行中 " + runningApps.size + " 个，已安装 " + allApps.size + " 个"
            } catch (e: Exception) {
                message = "扫描失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun saveSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value.toSet()).apply()
    }
    fun toggleWhitelist(app: AppInstance) {
        whitelist = if (app.key in whitelist) whitelist - app.key else whitelist + app.key
        saveSet("whitelist", whitelist)
        message = if (app.key in whitelist) "已加入白名单：" + app.label + "（用户 " + app.userId + "）" else "已移出白名单：" + app.label
    }
    fun togglePinned(app: AppInstance) {
        pinned = if (app.key in pinned) pinned - app.key else pinned + app.key
        saveSet("pinned", pinned)
        message = if (app.key in pinned) "已置顶：" + app.label + "（用户 " + app.userId + "）" else "已取消置顶：" + app.label
    }

    val rightApps = allApps
        .filter { selectedLetter == null || pinyinInitial(it.label) == selectedLetter }
        .sortedWith(compareByDescending<AppInstance> { it.key in pinned }
            .thenByDescending { launchCounts[it.packageName] ?: 0 }
            .thenBy { pinyinKey(it.label) })

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Color(0xFF171B22), primary = Accent,
        onBackground = Color(0xFFE8EDF5), onSurface = Color(0xFFE8EDF5)
    )) {
        Scaffold(containerColor = Bg) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Spacer(Modifier.height(27.dp))
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text("运行中", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), textAlign = TextAlign.Center)
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 6.dp)) {
                            items(runningApps, key = { it.key }) { app ->
                                AppItem(app, app.key in whitelist, false, null,
                                    onClick = { message = app.label + " · 用户 " + app.userId },
                                    onLongClick = { toggleWhitelist(app) })
                            }
                        }
                    }
                    Column(
                        Modifier.width(25.dp).fillMaxHeight().background(Color(0xFF080A0D))
                            .pointerInput(letters) {
                                detectVerticalDragGestures(
                                    onDragStart = { pos ->
                                        val i = (pos.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                                        selectedLetter = letters[i]
                                    },
                                    onVerticalDrag = { change, _ ->
                                        val i = (change.position.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                                        selectedLetter = letters[i]
                                        change.consume()
                                    }
                                )
                            }
                    ) {
                        letters.forEach { letter ->
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(letter, color = if (selectedLetter == letter) Color.White else Muted,
                                    fontSize = 10.sp, fontWeight = if (selectedLetter == letter) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.background(if (selectedLetter == letter) Color(0xFF42536B) else Color.Transparent, CircleShape).padding(horizontal = 3.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text("全部应用", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), textAlign = TextAlign.Center)
                        LazyColumn(state = rightListState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 6.dp)) {
                            items(rightApps, key = { it.key }) { app ->
                                AppItem(app, app.key in whitelist, app.key in pinned, launchCounts[app.packageName] ?: 0,
                                    onClick = { message = app.label + " · 用户 " + app.userId },
                                    onLongClick = { togglePinned(app) })
                            }
                        }
                    }
                }
                Text(message, color = Muted, fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!busy) scope.launch {
                                busy = true
                                val targets = runningApps.filter { it.key !in whitelist && !it.isSystem && !it.isDisabled }
                                val failures = withContext(Dispatchers.IO) {
                                    targets.mapNotNull { app ->
                                        runCatching { shell.freeze(app) }.exceptionOrNull()?.let { app.label + "(" + app.userId + ")" }
                                    }
                                }
                                message = "一键停用完成：成功 " + (targets.size - failures.size) + "/" + targets.size +
                                    if (failures.isEmpty()) "" else "；失败：" + failures.take(4).joinToString("、")
                                busy = false
                                refresh()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp), enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF394B62))
                    ) { Text("一键停用", fontSize = 13.sp) }
                    Button(
                        onClick = { selectedLetter = null; refresh() },
                        modifier = Modifier.weight(1f).height(48.dp), enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF394B62))
                    ) { Text("刷新", fontSize = 13.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppItem(
    app: AppInstance, whitelist: Boolean, pinned: Boolean, count: Int?,
    onClick: () -> Unit, onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 3.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                val drawable = runCatching {
                    val info = context.packageManager.getApplicationInfo(app.packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
                    context.packageManager.getApplicationIcon(info)
                }.getOrNull()
                view.setImageDrawable(drawable)
            },
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = Color(0xFFE8EDF5), fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp)
            Text(
                buildString {
                    append("用户 " + app.userId)
                    if (whitelist) append(" · 白名单")
                    if (pinned) append(" · 置顶")
                    if (count != null) append(" · " + count + "次")
                },
                color = if (whitelist) Color(0xFF91B8E8) else Muted,
                fontSize = 9.sp, maxLines = 1
            )
        }
    }
}

private fun pinyinKey(label: String): String {
    val digitPinyin = mapOf('0' to "ling", '1' to "yi", '2' to "er", '3' to "san", '4' to "si", '5' to "wu", '6' to "liu", '7' to "qi", '8' to "ba", '9' to "jiu")
    return label.trim().lowercase().map { ch ->
        if (ch in '0'..'9') digitPinyin[ch] ?: ch.toString()
        else if (ch.code in 0x4E00..0x9FFF) PinyinHelper.toHanyuPinyinStringArray(ch)?.firstOrNull()?.lowercase() ?: ch.toString()
        else ch.toString()
    }.joinToString("")
}

private fun pinyinInitial(label: String): String {
    val first = pinyinKey(label).firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

@Suppress("DEPRECATION")
private fun readLaunchCounts(context: Context): Map<String, Int> {
    if (Build.VERSION.SDK_INT < 29) return emptyMap()
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    if (mode != AppOpsManager.MODE_ALLOWED) return emptyMap()
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val end = System.currentTimeMillis()
    val start = end - 30L * 24L * 60L * 60L * 1000L
    return runCatching {
        val events = manager.queryEvents(start, end)
        val event = UsageStatsManager.Event()
        val counts = mutableMapOf<String, Int>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageStatsManager.Event.ACTIVITY_RESUMED) {
                val pkg = event.packageName ?: continue
                counts[pkg] = (counts[pkg] ?: 0) + 1
            }
        }
        counts
    }.getOrDefault(emptyMap())
}
