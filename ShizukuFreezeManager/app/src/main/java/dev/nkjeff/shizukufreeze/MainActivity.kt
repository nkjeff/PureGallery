package dev.nkjeff.shizukufreeze

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        // UI observes permission state on the next refresh.
    }
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
    var columns by remember { mutableIntStateOf(5) }
    var sort by remember { mutableStateOf("首字母") }
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppInstance?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var whitelist by remember { mutableStateOf(setOf<String>()) }
    var pinned by remember { mutableStateOf(setOf<String>()) }
    var temporary by remember { mutableStateOf(setOf<String>()) }

    fun refresh() {
        scope.launch {
            busy = true
            message = "正在扫描用户与应用…"
            val result = withContext(Dispatchers.IO) {
                val users = shell.scanUsers()
                users.flatMap { user ->
                    val (packages, disabled) = shell.scanPackages(user)
                    packages.mapNotNull { pkg ->
                        try {
                            val info = context.packageManager.getApplicationInfo(pkg, 0)
                            val label = context.packageManager.getApplicationLabel(info).toString()
                            AppInstance(pkg, user, label,
                                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                pkg in disabled, user != 0)
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        }
                    }
                }.distinctBy { it.key }.filter { it.packageName != context.packageName }
            }
            apps = result
            temporary = temporary.filter { key -> result.any { it.key == key && !it.isDisabled } }.toSet()
            message = "识别到 ${result.size} 个应用实例"
            busy = false
        }
    }

    fun operate(app: AppInstance, action: String) {
        scope.launch {
            busy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    when (action) {
                        "freeze" -> shell.freeze(app)
                        "enable" -> shell.unfreeze(app)
                        "launch" -> {
                            if (app.isDisabled) shell.unfreeze(app)
                            shell.launch(app)
                        }
                        "all" -> Unit
                    }
                }
            }
            message = outcome.fold({ "操作完成：${app.label}" }, { "操作失败：${it.message}" })
            if (outcome.isSuccess) {
                if (action == "launch") temporary = temporary + app.key
                if (action == "freeze") temporary = temporary - app.key
                refresh()
            }
            busy = false
        }
    }

    val shown = apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    val manualApps = shown.filter { it.key in pinned }
    val tempApps = shown.filter { it.key in temporary && it.key !in pinned }
    val restApps = shown.filter { it.key !in pinned && it.key !in temporary }
        .let { list -> when (sort) {
            "最近安装" -> list.sortedByDescending { app ->
                runCatching { context.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime }.getOrDefault(0L)
            }
            "启动次数" -> list.sortedBy { it.label.lowercase() } // Usage access is intentionally not requested.
            else -> list.sortedBy { it.label.lowercase() }
        } }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Accent,
        onBackground = Color(0xFFE8EDF5), onSurface = Color(0xFFE8EDF5)
    )) {
        Scaffold(containerColor = Bg) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Spacer(Modifier.height(26.dp)) // status-bar breathing room
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("刷新", Icons.Default.Refresh, Modifier.weight(1f)) { refresh() }
                    ActionButton("一键冻结", Icons.Default.AcUnit, Modifier.weight(1f)) {
                        scope.launch {
                            busy = true
                            val targets = apps.filter { !it.isSystem && !it.isDisabled && it.key !in whitelist && it.packageName != context.packageName }
                            val failures = withContext(Dispatchers.IO) {
                                targets.mapNotNull { app -> runCatching { shell.freeze(app) }.exceptionOrNull()?.let { app.label to it.message } }
                            }
                            message = "冻结完成：${targets.size - failures.size}/${targets.size}，失败 ${failures.size}"
                            refresh()
                        }
                    }
                    ActionButton("搜索", Icons.Default.Search, Modifier.weight(1f)) { searchVisible = !searchVisible }
                }
                if (searchVisible) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp),
                        placeholder = { Text("搜索应用名称或包名") }, singleLine = true)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("布局", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4,5,6).forEach { n ->
                            FilterChip(selected = columns == n, onClick = { columns = n }, label = { Text("${n}列") })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("首字母","启动次数","最近安装").forEach { s ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s, fontSize = 11.sp) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (!Shizuku.pingBinder()) {
                        Text("等待 Shizuku", color = Accent, fontSize = 11.sp)
                    } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        TextButton(onClick = { Shizuku.requestPermission(1001) }) { Text("授予权限", fontSize = 11.sp) }
                    }
                }
                if (manualApps.isNotEmpty()) AppSection("手动置顶", manualApps, columns, whitelist, onClick = { operate(it, if (it.isDisabled) "launch" else "launch") }, onLong = { selected = it; menuExpanded = true })
                if (tempApps.isNotEmpty()) AppSection("临时置顶", tempApps, columns, whitelist, onClick = { operate(it, "launch") }, onLong = { selected = it; menuExpanded = true })
                if (manualApps.isNotEmpty() || tempApps.isNotEmpty()) HorizontalDivider(color = Color.White.copy(alpha = .75f), thickness = 1.dp)
                AppSection("所有应用", restApps, columns, whitelist, onClick = { operate(it, "launch") }, onLong = { selected = it; menuExpanded = true }, modifier = Modifier.weight(1f))
            }
        }
        if (selected != null && menuExpanded) {
            val app = selected!!
            Dialog(onDismissRequest = { menuExpanded = false; selected = null }) {
                Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(app.label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${app.packageName} · 用户 ${app.userId}", color = Muted, fontSize = 11.sp)
                        MenuRow(if (app.isDisabled) "解冻" else "冻结", Icons.Default.AcUnit) {
                            operate(app, if (app.isDisabled) "enable" else "freeze")
                            menuExpanded = false; selected = null
                        }
                        MenuRow(if (app.key in whitelist) "移出白名单" else "加入白名单", Icons.Default.Lock) {
                            whitelist = if (app.key in whitelist) whitelist - app.key else whitelist + app.key
                            menuExpanded = false; selected = null
                        }
                        MenuRow(if (app.key in pinned) "取消置顶" else "手动置顶", Icons.Default.PushPin) {
                            pinned = if (app.key in pinned) pinned - app.key else pinned + app.key
                            temporary = temporary - app.key
                            menuExpanded = false; selected = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(48.dp), contentPadding = PaddingValues(4.dp)) {
        Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label); Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppSection(
    title: String, apps: List<AppInstance>, columns: Int, whitelist: Set<String>,
    onClick: (AppInstance) -> Unit, onLong: (AppInstance) -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.weight(1f)); Text("${apps.size}", color = Muted, fontSize = 11.sp)
        }
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            items(apps, key = { it.key }) { app ->
                val dim = app.isDisabled
                Column(
                    Modifier.padding(4.dp).combinedClickable(onClick = { onClick(app) }, onLongClick = { onLong(app) })
                        .background(if (dim) Color(0xFF171B22) else Panel, RoundedCornerShape(13.dp))
                        .padding(vertical = 11.dp, horizontal = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(42.dp).background(Color(0xFF283445), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text(app.label.take(1).uppercase(), color = if (dim) Muted else Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(app.label, color = if (dim) Color(0xFF68717F) else Color(0xFFE5EAF2), fontSize = 10.sp, maxLines = 1)
                    if (app.userId != 0) Text("用户 ${app.userId}", color = Muted, fontSize = 8.sp)
                    if (app.key in whitelist) Icon(Icons.Default.Lock, null, tint = Color(0xFFD6B56E), modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}
