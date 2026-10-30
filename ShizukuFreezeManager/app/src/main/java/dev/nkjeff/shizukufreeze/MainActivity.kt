package dev.nkjeff.shizukufreeze

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private val Bg = Color(0xFF0D1118)
private val Panel = Color(0xFF171E29)
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
    var sortRecent by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<AppInstance?>(null) }
    var whitelist by remember { mutableStateOf(setOf<String>()) }
    var pinned by remember { mutableStateOf(setOf<String>()) }
    var temporary by remember { mutableStateOf(setOf<String>()) }

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

    val manualApps = apps.filter { it.key in pinned }
    val tempApps = apps.filter { it.key in temporary && it.key !in pinned }
    val remaining = apps.filter { it.key !in pinned && it.key !in temporary }
    val sortedRemaining = if (sortRecent) {
        remaining.sortedByDescending { app ->
            runCatching { context.packageManager.getPackageInfo(app.packageName, PackageManager.MATCH_DISABLED_COMPONENTS).firstInstallTime }.getOrDefault(0L)
        }
    } else remaining.sortedBy { it.label.lowercase() }
    val letters = sortedRemaining.map { it.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }.distinct().sorted()
    val letterApps = selectedLetter?.let { letter ->
        sortedRemaining.filter { (it.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#") == letter }
    }.orEmpty()

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
                    IconButton(
                        onClick = { sortRecent = !sortRecent; selectedLetter = null },
                        enabled = !busy,
                        modifier = Modifier.weight(0.65f).height(48.dp).background(Panel, RoundedCornerShape(12.dp))
                    ) {
                        Icon(if (sortRecent) Icons.Default.Schedule else Icons.Default.SortByAlpha,
                            contentDescription = if (sortRecent) "按最近安装排序" else "按首字母排序",
                            tint = Accent)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppRail(manualApps, whitelist, Modifier.weight(1f), { operate(it, "launch") }, { selected = it })
                    AppRail(tempApps, whitelist, Modifier.weight(1f), { operate(it, "launch") }, { selected = it })
                    Column(
                        Modifier.weight(1.55f).fillMaxHeight()
                            .background(Panel.copy(alpha = .55f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        if (selectedLetter == null) {
                            LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
                                items(letters) { letter ->
                                    Text(
                                        letter,
                                        color = Accent,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.fillMaxWidth().combinedClickable(
                                            onClick = { selectedLetter = letter },
                                            onLongClick = { selectedLetter = letter }
                                        ).padding(vertical = 5.dp)
                                    )
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedLetter = null }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.ArrowBack, null, tint = Accent)
                                }
                                Text(selectedLetter!!, color = Accent, fontWeight = FontWeight.Bold)
                            }
                            LazyColumn {
                                items(letterApps, key = { it.key }) { app ->
                                    AppRow(app, whitelist, { operate(app, "launch") }, { selected = app })
                                }
                            }
                        }
                    }
                }
                Text(message, color = Muted, fontSize = 10.sp, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
                if (!Shizuku.pingBinder()) {
                    Text("等待 Shizuku", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    TextButton(onClick = { Shizuku.requestPermission(1001) }) { Text("授予 Shizuku 权限") }
                }
            }
        }
        selected?.let { app ->
            Dialog(onDismissRequest = { selected = null }) {
                Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(app.label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(app.packageName + " · 用户 " + app.userId, color = Muted, fontSize = 11.sp)
                        MenuAction(if (app.isDisabled) "解冻" else "冻结", Icons.Default.AcUnit) {
                            val action = if (app.isDisabled) "enable" else "freeze"
                            selected = null
                            operate(app, action)
                        }
                        MenuAction(if (app.key in whitelist) "移出白名单" else "加入白名单", Icons.Default.Lock) {
                            whitelist = if (app.key in whitelist) whitelist - app.key else whitelist + app.key
                            selected = null
                        }
                        MenuAction(if (app.key in pinned) "取消置顶" else "手动置顶", Icons.Default.PushPin) {
                            pinned = if (app.key in pinned) pinned - app.key else pinned + app.key
                            temporary = temporary - app.key
                            selected = null
                        }
                    }
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
    onTap: (AppInstance) -> Unit, onLong: (AppInstance) -> Unit
) {
    LazyColumn(
        modifier.fillMaxHeight().background(Panel.copy(alpha = .55f), RoundedCornerShape(12.dp)).padding(horizontal = 3.dp, vertical = 4.dp)
    ) {
        items(apps, key = { it.key }) { app ->
            AppRow(app, whitelist, { onTap(app) }, { onLong(app) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(app: AppInstance, whitelist: Set<String>, onTap: () -> Unit, onLong: () -> Unit) {
    val context = LocalContext.current
    val dim = app.isDisabled
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLong)
            .padding(vertical = 5.dp, horizontal = 2.dp),
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
                view.alpha = if (dim) .35f else 1f
            },
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = if (dim) Color(0xFF68717F) else Color(0xFFE5EAF2),
                fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp)
            if (app.key in whitelist) Icon(Icons.Default.Lock, null, tint = Color(0xFFD6B56E), modifier = Modifier.size(10.dp))
        }
    }
}
