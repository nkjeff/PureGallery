package dev.nkjeff.shizukufreeze

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.Intent
import android.provider.Settings
import android.os.Process
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sourceforge.pinyin4j.PinyinHelper
import rikka.shizuku.Shizuku

private val Bg = Color.Black
private val Panel = Color(0xFF555B65)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FreezeApp() {
    val context = LocalContext.current
    val shell = remember { ShizukuShell() }
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<AppInstance>()) }
    var message by remember { mutableStateOf("请连接并授权 Shizuku") }
    var busy by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var whitelist by remember { mutableStateOf(setOf<String>()) }
    var pinned by remember { mutableStateOf(setOf<String>()) }
    var temporary by remember { mutableStateOf(setOf<String>()) }
    var launchCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var usageAccess by remember { mutableStateOf(hasUsageAccess(context)) }
    var touchedPackage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (busy) return
        scope.launch {
            busy = true
            message = "正在刷新…"
            try {
                val result = withContext(Dispatchers.IO) {
                    check(Shizuku.pingBinder()) { "没有连接 Shizuku，请先启动 Shizuku 服务。" }
                    check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku 尚未授权。" }
                    shell.scanUsers().flatMap { user ->
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
                    }.distinctBy { it.key }
                        .filter { it.packageName != context.packageName && it.packageName != "android" }
                }
                apps = result
                usageAccess = hasUsageAccess(context)
                launchCounts = if (usageAccess) withContext(Dispatchers.IO) { readLaunchCounts(context) } else emptyMap()
                temporary = temporary.filter { key -> result.any { it.key == key && !it.isDisabled } }.toSet()
                message = "已刷新 " + result.size + " 个应用"
            } catch (e: Exception) {
                message = "刷新失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun operate(app: AppInstance, action: String) {
        if (busy) return
        scope.launch {
            busy = true
            message = if (action == "freeze") "正在冻结…" else "正在解冻…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (action) {
                        "freeze" -> shell.freeze(app)
                        "enable" -> shell.unfreeze(app)
                        "launch" -> {
                            if (app.isDisabled) shell.unfreeze(app)
                            shell.launch(app)
                        }
                        else -> error("未知操作")
                    }
                }
            }
            result.onSuccess {
                if (action == "launch") temporary = temporary + app.key
                if (action == "freeze") temporary = temporary - app.key
            }
            message = result.fold({ "操作成功，正在更新状态…" }, { "操作失败：" + it.message })
            busy = false
            if (result.isSuccess) refresh()
        }
    }

    val tempApps = apps.filter { it.key in temporary && it.key !in pinned }
    val sortedRemaining = apps.filter { it.key !in pinned && it.key !in temporary }.sortedBy { pinyinKey(it.label) }
    val letters = ('A'..'Z').map { it.toString() }
    val letterApps = if (selectedLetter == null) sortedRemaining else sortedRemaining.filter {
        pinyinInitial(it.label) == selectedLetter
    }
    val primaryApps = apps.filter { it.key !in temporary }.sortedWith(
        compareByDescending<AppInstance> { it.key in pinned }
            .thenByDescending { launchCounts[it.packageName] ?: 0 }
            .thenBy { pinyinKey(it.label) }
    )

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Accent,
        onBackground = Color(0xFFE8EDF5), onSurface = Color(0xFFE8EDF5)
    )) {
        Scaffold(containerColor = Bg) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Spacer(Modifier.height(26.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TopAction("刷新", Icons.Default.Refresh, Modifier.weight(1f), enabled = !busy) { refresh() }
                    TopAction("一键冻结", Icons.Default.AcUnit, Modifier.weight(1f), enabled = !busy) {
                        scope.launch {
                            busy = true
                            val targets = apps.filter {
                                !it.isSystem && !it.isDisabled && it.key !in whitelist &&
                                    it.packageName != context.packageName && it.packageName != "android"
                            }
                            val failures = withContext(Dispatchers.IO) {
                                targets.mapNotNull { app ->
                                    runCatching { shell.freeze(app) }.exceptionOrNull()?.let { app.label }
                                }
                            }
                            temporary = temporary - targets.map { it.key }.toSet()
                            message = "冻结完成：" + (targets.size - failures.size) + "/" + targets.size + "，失败 " + failures.size
                            busy = false
                            refresh()
                        }
                    }

                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp)) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        AppRail(primaryApps, whitelist, Modifier.weight(1f), { touchedPackage = it.packageName; operate(it, "launch") }, {
                            touchedPackage = it.packageName
                            pinned = if (it.key in pinned) pinned - it.key else pinned + it.key
                            temporary = temporary - it.key
                        }, launchCounts = launchCounts, showUsagePermission = !usageAccess)
                        AppRail(tempApps, whitelist, Modifier.weight(1f), { touchedPackage = it.packageName; operate(it, "launch") }, {
                            touchedPackage = it.packageName
                            operate(it, "freeze")
                        })
                        AppRail(letterApps, whitelist, Modifier.weight(1f), { touchedPackage = it.packageName; operate(it, "launch") }, {
                            touchedPackage = it.packageName
                            pinned = if (it.key in pinned) pinned - it.key else pinned + it.key
                            temporary = temporary - it.key
                        })
                    }
                    Column(
                        Modifier.align(Alignment.CenterStart)
                            .offset(x = maxWidth * (2f / 3f) - 14.dp)
                            .width(28.dp).fillMaxHeight()
                            .background(Color.Black.copy(alpha = .94f))
                            .pointerInput(letters) {
                                detectTapGestures { offset ->
                                    val index = (offset.y / size.height * letters.size).toInt()
                                        .coerceIn(0, letters.lastIndex)
                                    selectedLetter = letters[index]
                                }
                            }
                            .pointerInput(letters) {
                                detectVerticalDragGestures(
                                    onDragStart = { offset ->
                                        val index = (offset.y / size.height * letters.size).toInt()
                                            .coerceIn(0, letters.lastIndex)
                                        selectedLetter = letters[index]
                                    },
                                    onVerticalDrag = { change, _ ->
                                        val index = (change.position.y / size.height * letters.size).toInt()
                                            .coerceIn(0, letters.lastIndex)
                                        selectedLetter = letters[index]
                                        change.consume()
                                    }
                                )
                            },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        letters.forEach { letter ->
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier.size(22.dp)
                                        .background(
                                            if (selectedLetter == letter) Color(0xFF42536B) else Color.Transparent,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        letter,
                                        color = if (selectedLetter == letter) Color.White else Muted,
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedLetter == letter) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                Text(touchedPackage ?: message, color = Muted, fontSize = 11.sp, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
                if (!Shizuku.pingBinder()) {
                    Text("等待 Shizuku", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    TextButton(onClick = { Shizuku.requestPermission(1001) }) { Text("授予 Shizuku 权限") }
                }
            }
        }
    }
}

@Composable
private fun TopAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled, contentPadding = PaddingValues(4.dp)) {
        Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MenuAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label)
        Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRail(
    apps: List<AppInstance>, whitelist: Set<String>, modifier: Modifier = Modifier,
    onTap: (AppInstance) -> Unit, onLong: (AppInstance) -> Unit,
    launchCounts: Map<String, Int> = emptyMap(), showUsagePermission: Boolean = false
) {
    val context = LocalContext.current
    if (showUsagePermission) {
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Text("开启使用情况访问", fontSize = 9.sp, color = Accent, lineHeight = 11.sp)
        }
    }
    LazyColumn(
        modifier.fillMaxHeight().padding(horizontal = 3.dp, vertical = 4.dp)
    ) {
        items(apps, key = { it.key }) { app ->
            AppRow(app, whitelist, { onTap(app) }, { onLong(app) }, launchCounts[app.packageName] ?: 0)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(app: AppInstance, whitelist: Set<String>, onTap: () -> Unit, onLong: () -> Unit, launchCount: Int = 0) {
    val context = LocalContext.current
    val dim = app.isDisabled
    BoxWithConstraints(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLong)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        val iconSize = maxWidth * 0.60f
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                val drawable = runCatching {
                    val info = context.packageManager.getApplicationInfo(app.packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
                    context.packageManager.getApplicationIcon(info)
                }.getOrNull()
                view.setImageDrawable(drawable)
                view.alpha = if (dim) .35f else 1f
            },
            modifier = Modifier.size(iconSize).align(Alignment.Center)
        )
        Text(
            app.userId.toString(),
            color = if (dim) Color(0xFF68717F) else Color(0xFFE5EAF2),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp)
        )
    }
}

 
private fun pinyinKey(label: String): String {
    return label.trim().lowercase().map { ch ->
        if (ch.code in 0x4E00..0x9FFF) {
            PinyinHelper.toHanyuPinyinStringArray(ch)?.firstOrNull()?.lowercase() ?: ch.toString()
        } else ch.toString()
    }.joinToString("")
}

private fun pinyinInitial(label: String): String {
    val first = pinyinKey(label).firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

private fun hasUsageAccess(context: android.content.Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}

private fun readLaunchCounts(context: android.content.Context): Map<String, Int> {
    val manager = context.getSystemService(UsageStatsManager::class.java)
    val end = System.currentTimeMillis()
    val start = end - 30L * 24L * 60L * 60L * 1000L
    val events = manager.queryEvents(start, end)
    val event = UsageEvents.Event()
    val counts = mutableMapOf<String, Int>()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
            val pkg = event.packageName ?: continue
            counts[pkg] = (counts[pkg] ?: 0) + 1
        }
    }
    return counts
}
