package com.nkjeff.puregallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

@Composable
private fun PureGalleryApp() {
    val context = LocalContext.current
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var columns by remember { mutableIntStateOf(3) }
    var sortBySize by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(true) }
    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }

    fun refresh() {
        val entries = loadMedia(context)
        media = if (sortBySize) {
            entries.sortedBy { it.size }.let { if (descending) it.reversed() else it }
        } else {
            entries.sortedBy { it.dateAdded }.let { if (descending) it.reversed() else it }
        }
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

    LaunchedEffect(Unit) {
        if (permissionGranted) refresh() else requestPermissions()
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF477FBA))) {
        Scaffold(
            topBar = {
                Surface(shadowElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pure Gallery", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                sortBySize = !sortBySize
                                refresh()
                            }) {
                                Icon(Icons.Default.FilterList, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(if (sortBySize) "大小" else "时间")
                            }
                            TextButton(onClick = {
                                descending = !descending
                                refresh()
                            }) { Text(if (descending) "↓" else "↑") }
                        }
                        Text("全部媒体 · ${media.size} 项", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        ) { padding ->
            if (!permissionGranted) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(48.dp))
                        Text("需要照片和视频访问权限")
                        Button(onClick = { requestPermissions() }) { Text("授予权限") }
                    }
                }
            } else if (media.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("还没有找到照片或视频")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize().padding(padding).pointerInput(columns) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom > 1.04f) columns = (columns + 1).coerceAtMost(9)
                            if (zoom < 0.96f) columns = (columns - 1).coerceAtLeast(3)
                        }
                    },
                    contentPadding = PaddingValues(1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(media, key = { it.uri.toString() }) { item ->
                        Box {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            )
                            if (item.isVideo) {
                                Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hasMediaPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
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
            resolver.query(
                collection, projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += MediaEntry(
                        ContentUris.withAppendedId(collection, id),
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
