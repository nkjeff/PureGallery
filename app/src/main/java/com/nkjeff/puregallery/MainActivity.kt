package com.nkjeff.puregallery

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage

private data class MediaEntry(
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val size: Long,
    val isVideo: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PureGalleryApp() }
    }
}

private val OledBlack = Color(0xFF000000)
private val OledSurface = Color(0xFF080808)
private val AccentBlue = Color(0xFF8AB4F8)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PureGalleryApp() {
    val context = LocalContext.current
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var columns by remember { mutableIntStateOf(3) }
    var sortBySize by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }

    fun refresh() {
        val entries = loadMedia(context)
        media = if (sortBySize) entries.sortedByDescending { it.size }
        else entries.sortedByDescending { it.dateAdded }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = hasMediaPermission(context)
        if (permissionGranted) refresh()
    }

    fun requestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    fun openMedia(item: MediaEntry) {
        val mime = if (item.isVideo) "video/*" else "image/*"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(viewIntent, "选择媒体查看器"))
        }
    }

    LaunchedEffect(Unit) {
        if (permissionGranted) refresh() else requestPermissions()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentBlue,
            onPrimary = Color.Black,
            background = OledBlack,
            onBackground = Color.White,
            surface = OledSurface,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF161616),
            onSurfaceVariant = Color(0xFFBBBBBB)
        )
    ) {
        Scaffold(
            containerColor = OledBlack,
            topBar = {
                Surface(color = OledBlack, shadowElevation = 0.dp) {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (selected.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Pure Gallery",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    sortBySize = !sortBySize
                                    refresh()
                                }) {
                                    Text(if (sortBySize) "大小 ↓" else "时间 ↓")
                                }
                            }
                            Text(
                                "全部媒体 · ${media.size} 项",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selected = emptySet() }) { Text("取消") }
                                Text(
                                    "已选 ${selected.size} 项",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                TextButton(onClick = {
                                    selected = if (selected.size == media.size) emptySet()
                                    else media.map { it.uri.toString() }.toSet()
                                }) {
                                    Text(if (selected.size == media.size) "取消全选" else "全选")
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            when {
                !permissionGranted -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("需要照片和视频访问权限")
                        Button(onClick = { requestPermissions() }) { Text("授予权限") }
                    }
                }
                media.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有找到照片或视频", color = Color.Gray)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize().padding(padding)
                        .pointerInput(columns) {
                            detectTransformGestures { _, _, zoom, _ ->
                                // Spread fingers = enlarge thumbnails = fewer columns.
                                if (zoom > 1.035f) columns = (columns - 1).coerceAtLeast(3)
                                // Pinch fingers together = shrink thumbnails = more columns.
                                if (zoom < 0.965f) columns = (columns + 1).coerceAtMost(9)
                            }
                        },
                    contentPadding = PaddingValues(1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(media, key = { it.uri.toString() }) { item ->
                        val key = item.uri.toString()
                        val isSelected = key in selected
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) {
                                            selected = if (isSelected) selected - key else selected + key
                                        } else openMedia(item)
                                    },
                                    onLongClick = {
                                        selected = if (isSelected) selected - key else selected + key
                                    }
                                )
                        ) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (item.isVideo) {
                                Text(
                                    "▶",
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                                )
                            }
                            if (isSelected) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = AccentBlue.copy(alpha = 0.34f)
                                ) {
                                    Box(contentAlignment = Alignment.TopEnd) {
                                        Text("✓", color = Color.White, modifier = Modifier.padding(5.dp))
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

private fun hasMediaPermission(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

private fun loadMedia(context: android.content.Context): List<MediaEntry> {
    val result = mutableListOf<MediaEntry>()
    val resolver = context.contentResolver
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.SIZE
    )
    val collections = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
    )
    for ((collection, isVideo) in collections) {
        try {
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    result += MediaEntry(
                        ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                        cursor.getString(nameCol) ?: "",
                        cursor.getLong(dateCol),
                        cursor.getLong(sizeCol),
                        isVideo
                    )
                }
            }
        } catch (_: SecurityException) { }
    }
    return result
}
