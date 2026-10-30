package com.nkjeff.puregallery

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject

private data class MediaEntry(val uri: Uri, val name: String, val dateAdded: Long, val size: Long, val isVideo: Boolean)
private const val PREFS = "gallery_metadata"
private const val LABELS_KEY = "custom_labels"
private const val TAG_MAP_KEY = "media_tags"
private val Black = Color(0xFF000000)
private val Blue = Color(0xFF8AB4F8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0 // light status/navigation icons on dark bars
        setContent { GalleryScreen() }
    }
}

private fun readLabels(context: android.content.Context): List<String> {
    val raw = context.getSharedPreferences(PREFS, 0).getString(LABELS_KEY, "[]") ?: "[]"
    return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
}
private fun writeLabels(context: android.content.Context, labels: List<String>) {
    context.getSharedPreferences(PREFS, 0).edit().putString(LABELS_KEY, JSONArray(labels).toString()).apply()
}
private fun readTagMap(context: android.content.Context): Map<String, Set<String>> {
    val raw = context.getSharedPreferences(PREFS, 0).getString(TAG_MAP_KEY, "{}") ?: "{}"
    return runCatching {
        val obj = JSONObject(raw)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val a = obj.getJSONArray(key)
                put(key, (0 until a.length()).map { a.getString(it) }.toSet())
            }
        }
    }.getOrDefault(emptyMap())
}
private fun writeTagMap(context: android.content.Context, tags: Map<String, Set<String>>) {
    val obj = JSONObject()
    tags.forEach { (uri, labels) -> obj.put(uri, JSONArray(labels.toList())) }
    context.getSharedPreferences(PREFS, 0).edit().putString(TAG_MAP_KEY, obj.toString()).apply()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryScreen() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(setOf<String>()) }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var columns by remember { mutableIntStateOf(3) }
    var sortBySize by remember { mutableStateOf(false) }
    var customLabels by remember { mutableStateOf(readLabels(context)) }
    var tagMap by remember { mutableStateOf(readTagMap(context)) }
    var activeFilter by remember { mutableStateOf("全部") }
    var showLabelManager by remember { mutableStateOf(false) }
    var showAssignLabels by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }
    val gridState = rememberLazyGridState()

    fun refresh() {
        val all = loadMedia(context)
        media = if (sortBySize) all.sortedByDescending { it.size } else all.sortedByDescending { it.dateAdded }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = hasMediaPermission(context)
        if (permissionGranted) refresh()
    }
    fun requestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }
    fun openMedia(item: MediaEntry) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }
    LaunchedEffect(Unit) { if (permissionGranted) refresh() else requestPermissions() }

    val visibleMedia = remember(media, activeFilter, tagMap) {
        when (activeFilter) {
            "全部" -> media
            "图片" -> media.filter { !it.isVideo }
            "视频" -> media.filter { it.isVideo }
            "最近删除" -> emptyList()
            else -> media.filter { activeFilter in (tagMap[it.uri.toString()] ?: emptySet()) }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, onPrimary = Color.Black, background = Black, onBackground = Color.White,
        surface = Color(0xFF080808), onSurface = Color.White, surfaceVariant = Color(0xFF171717),
        onSurfaceVariant = Color.LightGray
    )) {
        Scaffold(
            containerColor = Black,
            topBar = {
                Surface(color = Black) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 4.dp, bottom = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            (listOf("全部", "图片", "视频", "最近删除") + customLabels).forEach { label ->
                                FilterChip(
                                    selected = activeFilter == label,
                                    onClick = { activeFilter = label; selected = emptySet() },
                                    label = { Text(label, maxLines = 1) },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                            IconButton(onClick = { showLabelManager = true }) {
                                Icon(Icons.Default.Add, contentDescription = "管理标签", tint = Blue)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("列数", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            listOf(3, 6, 9).forEach { count ->
                                FilterChip(
                                    selected = columns == count,
                                    onClick = { columns = count },
                                    label = { Text("${count}列") },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { sortBySize = !sortBySize; refresh() }) {
                                Text(if (sortBySize) "大小 ↓" else "时间 ↓")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            when {
                !permissionGranted -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Button(onClick = { requestPermissions() }) { Text("授予照片和视频权限") }
                }
                activeFilter == "最近删除" -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("最近删除功能正在接入", color = Color.Gray)
                }
                visibleMedia.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(if (activeFilter == "全部") "没有媒体文件" else "这个标签下还没有媒体", color = Color.Gray)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(padding).pointerInput(visibleMedia, columns) {
                        var addMode = true
                        fun selectAt(point: Offset) {
                            val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                point.x >= info.offset.x && point.x < info.offset.x + info.size.width &&
                                    point.y >= info.offset.y && point.y < info.offset.y + info.size.height
                            }
                            val key = hit?.let { visibleMedia.getOrNull(it.index)?.uri?.toString() } ?: return
                            selected = if (addMode) selected + key else selected - key
                        }
                        detectDragGesturesAfterLongPress(
                            onDragStart = { point ->
                                val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                    point.x >= info.offset.x && point.x < info.offset.x + info.size.width &&
                                        point.y >= info.offset.y && point.y < info.offset.y + info.size.height
                                }
                                val key = hit?.let { visibleMedia.getOrNull(it.index)?.uri?.toString() }
                                addMode = key == null || key !in selected
                                selectAt(point)
                            },
                            onDrag = { change, _ -> selectAt(change.position) }
                        )
                    },
                    contentPadding = PaddingValues(1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(visibleMedia, key = { it.uri.toString() }) { item ->
                        val key = item.uri.toString()
                        val isSelected = key in selected
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).combinedClickable(
                                onClick = {
                                    if (selected.isNotEmpty()) selected = if (isSelected) selected - key else selected + key
                                    else openMedia(item)
                                },
                                onLongClick = { selected = if (isSelected) selected - key else selected + key }
                            )
                        ) {
                            AsyncImage(model = item.uri, contentDescription = item.name, modifier = Modifier.fillMaxSize())
                            if (item.isVideo) Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp))
                            if (isSelected) Surface(Modifier.fillMaxSize(), color = Blue.copy(alpha = 0.34f)) {
                                Box(contentAlignment = Alignment.TopEnd) { Text("✓", color = Color.White, modifier = Modifier.padding(5.dp)) }
                            }
                            val labels = tagMap[key].orEmpty()
                            if (labels.isNotEmpty()) {
                                Text(labels.joinToString(" · "), color = Color.White, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                        .padding(3.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLabelManager) {
        LabelManagerDialog(
            labels = customLabels,
            onDismiss = { showLabelManager = false },
            onAdd = { name ->
                val clean = name.trim()
                if (clean.isNotEmpty() && clean !in listOf("全部", "图片", "视频", "最近删除") && clean !in customLabels) {
                    customLabels = customLabels + clean
                    writeLabels(context, customLabels)
                }
            },
            onRename = { old, new ->
                val clean = new.trim()
                if (clean.isNotEmpty() && clean !in customLabels && clean !in listOf("全部", "图片", "视频", "最近删除")) {
                    customLabels = customLabels.map { if (it == old) clean else it }
                    tagMap = tagMap.mapValues { (_, set) -> if (old in set) (set - old) + clean else set }
                    writeLabels(context, customLabels); writeTagMap(context, tagMap)
                    if (activeFilter == old) activeFilter = clean
                }
            },
            onDelete = { label ->
                customLabels = customLabels - label
                tagMap = tagMap.mapValues { (_, set) -> set - label }
                writeLabels(context, customLabels); writeTagMap(context, tagMap)
                if (activeFilter == label) activeFilter = "全部"
            }
        )
    }

    if (showAssignLabels) {
        LabelAssignmentDialog(
            labels = customLabels,
            initial = selected.flatMap { tagMap[it].orEmpty() }.toSet(),
            onDismiss = { showAssignLabels = false },
            onApply = { chosen ->
                val updated = tagMap.toMutableMap()
                selected.forEach { key ->
                    if (chosen.isEmpty()) updated.remove(key) else updated[key] = chosen
                }
                tagMap = updated
                writeTagMap(context, tagMap)
                selected = emptySet()
                showAssignLabels = false
            }
        )
    }
}

@Composable
private fun LabelManagerDialog(
    labels: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit, onDelete: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)), contentAlignment = Alignment.TopCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp),
                shape = MaterialTheme.shapes.large, color = Color(0xFF171717),
                contentColor = Color.White, tonalElevation = 8.dp
            ) {
                Column(Modifier.padding(18.dp).heightIn(max = 560.dp)) {
                    Text("标签管理", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it }, modifier = Modifier.weight(1f),
                            label = { Text("新标签名称") }, singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Blue, unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Blue, unfocusedLabelColor = Color.LightGray, cursorColor = Blue
                            )
                        )
                        TextButton(onClick = { onAdd(newName); newName = "" }) { Text("添加") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        labels.forEach { label ->
                            if (editing == label) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = editName, onValueChange = { editName = it },
                                        modifier = Modifier.weight(1f), singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                            focusedBorderColor = Blue, unfocusedBorderColor = Color.Gray, cursorColor = Blue
                                        )
                                    )
                                    TextButton(onClick = { onRename(label, editName); editing = null }) { Text("保存") }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, Modifier.weight(1f), color = Color.White)
                                    TextButton(onClick = { editing = label; editName = label }) { Text("改名") }
                                    TextButton(onClick = { onDelete(label) }) { Text("删除", color = Color(0xFFFF7777)) }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelAssignmentDialog(
    labels: List<String>, initial: Set<String>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit
) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为所选媒体添加标签") },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                if (labels.isEmpty()) Text("还没有自定义标签，请先创建标签")
                labels.forEach { label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = label in chosen, onCheckedChange = {
                            if (it) chosen.add(label) else chosen.remove(label)
                        })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(chosen.toSet()) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun hasMediaPermission(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun loadMedia(context: android.content.Context): List<MediaEntry> {
    val result = mutableListOf<MediaEntry>()
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE)
    val collections = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true)
    for ((collection, isVideo) in collections) {
        try {
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val date = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) result += MediaEntry(
                    ContentUris.withAppendedId(collection, cursor.getLong(id)),
                    cursor.getString(name) ?: "", cursor.getLong(date), cursor.getLong(size), isVideo
                )
            }
        } catch (_: SecurityException) { }
    }
    return result
}
