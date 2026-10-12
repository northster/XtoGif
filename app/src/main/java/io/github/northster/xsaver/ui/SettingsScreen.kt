package io.github.northster.xsaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.northster.xsaver.data.Gallery
import io.github.northster.xsaver.data.Source
import io.github.northster.xsaver.data.YtDlp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeQuote by remember { mutableStateOf(s.includeQuote) }
    var gifAsGif by remember { mutableStateOf(s.gifAsGif) }
    var enabled by remember { mutableStateOf(Source.entries.associateWith { s.isEnabled(it) }) }
    var maxWidth by remember { mutableIntStateOf(s.maxGifWidth) }
    var defaultPercent by remember { mutableIntStateOf(s.sliderDefaultPercent) }
    var sliderMax by remember { mutableStateOf(s.sliderMaxMb.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }) }
    var ytdlp by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("인용 게시물 미디어 기본으로 포함")
                    Text("목록에서 게시물마다 켜고 끌 수 있음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = includeQuote, onCheckedChange = { includeQuote = it; s.includeQuote = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GIF 게시물은 'GIF'를 켠 채로 열기")
                    Text(
                        "X는 GIF를 MP4로 보관합니다. 켜 두면 GIF가 있는 게시물에서 저장 버튼 옆 'GIF'가 처음부터 켜져 있어, 저장하면 GIF 변환 화면으로 갑니다",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = gifAsGif, onCheckedChange = { gifAsGif = it; vm.setGifAsGif(it) })
            }
            HorizontalDivider()
            Text("미디어 정보 가져오기 (위에서부터 차례로 시도)", fontWeight = FontWeight.Bold)
            Source.entries.forEachIndexed { i, src ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${src.label}")
                        Text(src.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled[src] == true, onCheckedChange = {
                        s.setEnabled(src, it)
                        enabled = enabled + (src to it)
                    })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ytdlp = "업데이트 중… (처음에는 Python 준비로 시간이 걸림)"
                    scope.launch { ytdlp = runCatching { YtDlp.update(context) }.getOrElse { "실패: ${it.message}" } }
                }) { Text("yt-dlp 업데이트") }
                Text(ytdlp, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("GIF 변환 용량 슬라이더의 최대", fontWeight = FontWeight.Bold)
            Text(
                "슬라이더 끝(100%)이 원본 크기 GIF와 이 값 중 작은 쪽이 됩니다",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sliderMax,
                    onValueChange = { v ->
                        sliderMax = v.filter { it.isDigit() || it == '.' }
                        sliderMax.toFloatOrNull()?.takeIf { it > 0f }?.let { s.sliderMaxMb = it }
                    },
                    suffix = { Text("MB") },
                    singleLine = true,
                    isError = sliderMax.toFloatOrNull()?.takeIf { it > 0f } == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("10", "15", "20", "30", "50").forEach { mb ->
                        FilterChip(selected = sliderMax == mb, onClick = { sliderMax = mb; s.sliderMaxMb = mb.toFloat() }, label = { Text("${mb}MB") })
                    }
                }
            }
            Text("슬라이더 기본 위치: ${defaultPercent}%", fontWeight = FontWeight.Bold)
            Slider(
                value = defaultPercent.toFloat(),
                onValueChange = { defaultPercent = ((it / 5f).roundToInt() * 5).coerceIn(5, 100) },
                onValueChangeFinished = { s.sliderDefaultPercent = defaultPercent },
                valueRange = 5f..100f,
                steps = 18,
            )
            HorizontalDivider()
            Text("자동 GIF 변환의 최대 가로 해상도", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(480, 640, 720, 1080).forEach { w ->
                    FilterChip(selected = maxWidth == w, onClick = { maxWidth = w; s.maxGifWidth = w }, label = { Text("${w}px") })
                }
            }
            HorizontalDivider()
            Text(
                "저장 위치: 사진/${Gallery.ALBUM} (갤러리 앨범 '${Gallery.ALBUM}')\n" +
                    "파일 이름: 작성자아이디_게시물ID_순번\n" +
                    "GIF 변환: FFmpeg 8.1 (FFmpegKit, palettegen/paletteuse 2단계)\n" +
                    "권한: 인터넷만 사용 (갤러리 저장은 MediaStore, 영상 선택은 시스템 사진 선택기)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("XtoGIF $version")
                    Text("GPL-3.0 · github.com/northster/XtoGif", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { vm.navigate(Screen.LICENSES) }) { Text("오픈소스 고지") }
            }
        }
    }
}
