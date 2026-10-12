package io.github.northster.xsaver.ui

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.northster.xsaver.core.Bytes
import io.github.northster.xsaver.data.Gallery

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.main.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.convertUri(it) }
    }
    val pickVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("XtoGIF") },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.SETTINGS) }) { Icon(Icons.Filled.Settings, "설정") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (ui.visible.isNotEmpty()) SaveBar(ui, vm) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // unfolded inner screen: the link and status on the left, the media on the right
            if (maxWidth >= 600.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LinkInput(ui, vm)
                        OutlinedButton(onClick = pickVideo, modifier = Modifier.fillMaxWidth()) { Text("갤러리 영상으로 GIF 만들기") }
                        Status(ui, vm)
                    }
                    MediaGrid(ui, vm, Modifier.weight(1f).fillMaxHeight(), minCell = 200) { }
                }
            } else {
                MediaGrid(ui, vm, Modifier.fillMaxSize(), minCell = 150) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinkInput(ui, vm)
                            if (ui.post == null && !ui.loading) {
                                OutlinedButton(onClick = pickVideo, modifier = Modifier.fillMaxWidth()) { Text("갤러리 영상으로 GIF 만들기") }
                            }
                            Status(ui, vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkInput(ui: MainUi, vm: MainViewModel) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = ui.input,
            onValueChange = vm::setInput,
            label = { Text("X 게시물 링크") },
            placeholder = { Text("https://x.com/…/status/…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); vm.openLink(ui.input) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrBlank()) vm.openLink(text)
            }) { Text("붙여넣고 가져오기") }
            Button(onClick = { focus.clearFocus(); vm.openLink(ui.input) }, enabled = ui.input.isNotBlank() && !ui.loading) {
                Text("가져오기")
            }
        }
        if (ui.post == null && !ui.loading && ui.unavailable == null && ui.error == null) {
            Text(
                "X 앱에서 게시물 공유 → 'X 미디어 저장'을 고르면 바로 열립니다. 사진은 원본 화질, 영상은 가장 높은 화질로 받아 " +
                    "갤러리의 '${Gallery.ALBUM}' 앨범에 저장합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Status(ui: MainUi, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("미디어 정보를 가져오는 중…")
            }
        }
        ui.unavailable?.let { reason ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Column {
                        Text("다운로드 불가", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(reason, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        ui.error?.let { err ->
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(err)
                    if (ui.ref != null) TextButton(onClick = { vm.retry() }) { Text("다시 시도") }
                }
            }
        }
        if (ui.log.isNotEmpty() && (ui.loading || ui.post == null)) {
            Column {
                ui.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        ui.post?.let { post ->
            HorizontalDivider()
            Text("@${post.author}", fontWeight = FontWeight.Bold)
            if (post.text.isNotBlank()) Text(post.text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                "게시물 ${post.id} · 출처: ${ui.source?.label ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.hasQuoteMedia) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("인용된 게시물의 미디어도 받기")
                        post.quote?.let {
                            Text("@${it.author} · ${it.media.size}개", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(checked = ui.includeQuote, onCheckedChange = vm::setIncludeQuote)
                }
            }
            if (ui.visible.isNotEmpty()) {
                val allOn = ui.visible.all { it.selected }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allOn, onCheckedChange = { vm.selectAll(!allOn) })
                    Text("전체 선택 (${ui.chosen.size}/${ui.visible.size})")
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(ui: MainUi, vm: MainViewModel, modifier: Modifier, minCell: Int, header: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCell.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header()
        items(ui.visible, key = { it.key }) { e ->
            MediaCard(e, onToggle = { vm.toggle(e.key) })
        }
    }
}

@Composable
private fun MediaCard(e: MediaEntry, onToggle: () -> Unit) {
    Card(onClick = onToggle) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = e.item.thumbnailUrl ?: e.item.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(shape = MaterialTheme.shapes.small, color = Color(0x88000000), modifier = Modifier.padding(4.dp)) {
                Checkbox(checked = e.selected, onCheckedChange = { onToggle() })
            }
            Row(Modifier.align(Alignment.BottomStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(kindLabel(e.item.kind))
                if (e.item.durationMs > 0) Badge(formatSeconds(e.item.durationMs / 1000.0))
                if (e.fromQuote) Badge("인용")
            }
            when (e.state) {
                EntryState.SAVED -> Icon(
                    Icons.Filled.CheckCircle, "저장됨", tint = Color(0xFF4CAF50),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp),
                )
                EntryState.DOWNLOADING -> LinearProgressIndicator(
                    progress = { e.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
                else -> {}
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(e.fileName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val res = if (e.item.width > 0) "${e.item.width}×${e.item.height} · " else ""
            val size = when {
                e.size != null -> Bytes.format(e.size)
                e.sizeUnknown -> "용량 ?"
                else -> "용량 확인 중…"
            }
            Text(res + size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
        }
    }
}

@Composable
private fun SaveBar(ui: MainUi, vm: MainViewModel) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val known = ui.chosen.mapNotNull { it.size }
            val total = if (known.isEmpty()) "" else Bytes.format(known.sum()) + if (known.size < ui.chosen.size) "+" else ""
            Column(Modifier.weight(1f)) {
                Text("${ui.chosen.size}개 선택", style = MaterialTheme.typography.bodyMedium)
                if (total.isNotEmpty()) Text(total, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.downloading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = vm::cancelDownload) { Text("취소") }
            } else {
                // videos and GIFs: save as mp4, or (switch on) open the GIF converter for each of them; photos are always saved
                run {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.large)
                            .toggleable(value = ui.gifMode, role = Role.Switch, onValueChange = vm::setGifMode)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("GIF", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = ui.gifMode, onCheckedChange = null)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                val again = ui.chosen.isNotEmpty() && ui.chosen.all { it.state == EntryState.SAVED }
                val label = when {
                    ui.gifMode && ui.chosenMotion.isNotEmpty() -> "GIF 변환"
                    again -> "다시 저장"
                    else -> "저장"
                }
                Button(onClick = vm::downloadSelected) { Text(label) }
            }
        }
    }
}
