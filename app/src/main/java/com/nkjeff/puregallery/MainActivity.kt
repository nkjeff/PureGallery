package com.nkjeff.puregallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

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
        appResolver = contentResolver\n        setContent { PureGalleryApp() }
    }
}

@Composable
private fun PureGalleryApp() {
    val context = LocalContext.current
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var columns by remember { mutableIntStateOf(3) }
    var sortBySize by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(true) }
    var permissionGranted by remember { mutableStateOf(hasMediaPermission()) }

    fun refresh() {
        media = loadMedia().let { entries ->
            if (sortBySize) entries.sortedBy { it.size }.let { if (descending) it.reversed() else it }
            else entries.sortedBy { it.dateAdded }.let { if (descending) it.reversed() else it }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = hasMediaPermission()
        if (permissionGranted) refresh()
    }

    LaunchedEffect(Unit) {
        if (permissionGranted) refresh()
        else {
            val permissions = if (Build.VERSION.SDK_INT >= 34) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            } else if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(permissions)
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF477FBA))) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("全部", "图片", "视频", "最近删除").forEachIndexed { index, label ->
                                FilterChip(
                                    selected = index == 0,
                                    onClick = { if (index == 0) refresh() },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            if (!permissionGranted) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(48.dp))
                        Text("需要照片和视频访问权限")
                        Button(onClick = {
                            val p = if (Build.VERSION.SDK_INT >= 33)
                                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            permissionLauncher.launch(p)
                        }) { Text("授予权限") }
                    }
                }
            } else if (media.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("还没有找到照片或视频")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .pointerInput(columns) {
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                            )
                            if (item.isVideo) {
                                Text("▶", color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun MainActivity.hasMediaPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
} else {
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun hasMediaPermission(): Boolean {
    val context = LocalContext.current
    return remember {
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun loadMedia(): List<MediaEntry> {
    val resolver = appResolver ?: return emptyList()
    return emptyList()
}

private var appResolver: android.content.ContentResolver? = null
