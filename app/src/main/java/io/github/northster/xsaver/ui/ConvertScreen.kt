package io.github.northster.xsaver.ui

import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.northster.xsaver.core.Bytes
import io.github.northster.xsaver.core.gif.Dither
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(vm: MainViewModel) {
    val ui by vm.convert.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissConvertMessage()
        }
    }
    // FFmpeg runs in this process: keep the screen on while it works
    val view = LocalView.current
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GIF 변환")
                        Text(ui.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= 600.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { PreviewPane(ui, vm) }
                    Column(
                        Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { Controls(ui, vm) }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PreviewPane(ui, vm)
                    Controls(ui, vm)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PreviewPane(ui: ConvertUi, vm: MainViewModel) {
    if (ui.preparing) {
        Text("원본 준비 중… ${(ui.prepareProgress * 100).roundToInt()}%")
        LinearProgressIndicator(progress = { ui.prepareProgress }, modifier = Modifier.fillMaxWidth())
        return
    }
    ui.error?.let {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        return
    }
    val info = ui.info ?: return
    val result = ui.result
    if (result != null) {
        val context = LocalContext.current
        val request = remember(result) {
            ImageRequest.Builder(context).data(result.file)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
        }
        Text(if (result.preview) "미리보기 (앞 3초)" else "결과 미리보기", fontWeight = FontWeight.Bold)
        AsyncImage(
            model = request,
            contentDescription = "GIF",
            modifier = Modifier.fillMaxWidth().aspectRatio(info.width.toFloat() / info.height).heightIn(max = 520.dp),
        )
        val target = ui.targetBytes
        val verdict = when {
            result.preview -> "전체 예상 ≈ ${ui.estimate?.let { Bytes.format(it) } ?: "?"}"
            target == null -> ""
            result.fits -> "목표 ${Bytes.format(target)} 이내 ✓"
            else -> "목표 ${Bytes.format(target)} 초과 ✗"
        }
        Text("${Bytes.format(result.bytes)} · $verdict")
        Text(result.params.describe(info), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!result.preview) {
                Button(onClick = vm::saveResult, enabled = !ui.running && !ui.saving) {
                    Text(if (ui.savedUri == null) "갤러리에 저장" else "다시 저장")
                }
            }
            if (ui.mode == ConvertMode.AUTO) OutlinedButton(onClick = vm::tuneResult) { Text("수동으로 다듬기") }
        }
        Text("원본", style = MaterialTheme.typography.labelLarge)
    }
    SourcePlayer(ui)
    Text(
        "원본 ${info.width}×${info.height} · ${"%.1f".format(info.fps)}fps · ${formatSeconds(info.durationSec)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The source video, looping over the chosen range. */
@Composable
private fun SourcePlayer(ui: ConvertUi) {
    val file = ui.source ?: return
    val info = ui.info ?: return
    val range by rememberUpdatedState(ui.start to ui.end)
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.setVolume(0f, 0f)
                    mp.isLooping = true
                    seekTo((range.first * 1000).toInt())
                    start()
                }
            }.also { videoView = it }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(info.width.toFloat() / info.height).heightIn(max = 420.dp),
        onRelease = { it.stopPlayback() },
    )
    LaunchedEffect(ui.start) { videoView?.seekTo((ui.start * 1000).toInt()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            val v = videoView ?: continue
            val pos = v.currentPosition / 1000f
            if (v.isPlaying && (pos > range.second || pos < range.first - 0.5f)) v.seekTo((range.first * 1000).toInt())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Controls(ui: ConvertUi, vm: MainViewModel) {
    val info = ui.info ?: return
    val enabled = !ui.running

    // target size first: everything else is about meeting it
    Text("목표 용량", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = ui.targetMb,
        onValueChange = { vm.setTarget(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        suffix = { Text("MB") },
        singleLine = true,
        enabled = enabled,
        isError = ui.targetBytes == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MainViewModel.presetsMb.forEach { mb ->
            FilterChip(selected = ui.targetMb == mb, onClick = { vm.setTarget(mb) }, label = { Text("${mb}MB") }, enabled = enabled)
        }
    }

    Text("구간: ${formatSeconds(ui.start.toDouble())} ~ ${formatSeconds(ui.end.toDouble())} (${formatSeconds(ui.duration)})", fontWeight = FontWeight.Bold)
    RangeSlider(
        value = ui.start..ui.end,
        onValueChange = { vm.setRange(it.start, it.endInclusive) },
        valueRange = 0f..info.durationSec.toFloat(),
        enabled = enabled,
    )

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = ui.mode == ConvertMode.AUTO, onClick = { vm.setMode(ConvertMode.AUTO) },
            shape = SegmentedButtonDefaults.itemShape(0, 2), enabled = enabled,
        ) { Text("자동") }
        SegmentedButton(
            selected = ui.mode == ConvertMode.MANUAL, onClick = { vm.setMode(ConvertMode.MANUAL) },
            shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = enabled,
        ) { Text("수동") }
    }

    Text("디더링", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dither.entries.forEach { d ->
            FilterChip(selected = ui.dither == d, onClick = { vm.setDither(d) }, label = { Text(d.label) }, enabled = enabled)
        }
    }

    if (ui.mode == ConvertMode.AUTO) {
        Text(
            "목표 용량 안에서 가장 좋은 화질을 찾습니다. 프레임(30→15fps)을 먼저 줄이고, 그다음 해상도와 색상 수를 함께 낮춥니다. " +
                "실제로 변환해 보며 맞추므로 보통 2~5번 시도합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("구간 자르기 허용")
                Text("240px · 10fps · 64색 아래로 내려가야 하면 대신 뒤를 자름", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.allowTrim, onCheckedChange = vm::setAllowTrim, enabled = enabled)
        }
        Button(onClick = vm::runAuto, enabled = enabled && ui.targetBytes != null, modifier = Modifier.fillMaxWidth()) {
            Text("자동 변환")
        }
    } else {
        val maxW = minOf(info.width, 1920).coerceAtLeast(122)
        Text("가로 해상도: ${ui.width}px (세로 ${(ui.width.toFloat() * info.height / info.width).roundToInt()}px)")
        Slider(
            value = ui.width.toFloat().coerceIn(120f, maxW.toFloat()),
            onValueChange = { vm.setManual(width = it.roundToInt()) },
            valueRange = 120f..maxW.toFloat(),
            enabled = enabled,
        )
        val maxFps = info.maxFps.coerceAtLeast(3)
        Text("프레임: ${ui.fps}fps")
        Slider(
            value = ui.fps.toFloat().coerceIn(2f, maxFps.toFloat()),
            onValueChange = { vm.setManual(fps = it.roundToInt()) },
            valueRange = 2f..maxFps.toFloat(),
            steps = (maxFps - 3).coerceAtLeast(0),
            enabled = enabled,
        )
        val steps = MainViewModel.colorSteps
        val idx = steps.indexOfFirst { it >= ui.colors }.let { if (it < 0) steps.lastIndex else it }
        Text("색상 수 (화질): ${ui.colors}색")
        Slider(
            value = idx.toFloat(),
            onValueChange = { vm.setManual(colors = steps[it.roundToInt().coerceIn(0, steps.lastIndex)]) },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = (steps.size - 2).coerceAtLeast(0),
            enabled = enabled,
        )
        val target = ui.targetBytes
        val est = ui.estimate
        Card {
            Column(Modifier.padding(12.dp)) {
                val label = when {
                    est == null -> "예상 용량 계산 중…"
                    else -> "예상 용량 ${if (ui.estimateMeasured) "≈" else "약"} ${Bytes.format(est)}" +
                        if (target != null) (if (est <= target) "  (목표 이내 ✓)" else "  (목표 초과 ✗)") else ""
                }
                Text(label, fontWeight = FontWeight.Bold)
                Text(
                    if (ui.estimating) "짧은 구간을 실제로 변환해 확인하는 중…"
                    else if (ui.estimateMeasured) "2초 샘플을 실제로 변환해 계산한 값" else "모델로 계산한 대략값",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.runManual(preview = true) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("미리보기 (3초)") }
            Button(onClick = { vm.runManual(preview = false) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("변환") }
        }
    }

    if (ui.running) {
        Text(ui.runLabel, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = vm::cancelRun) { Text("취소") }
    }
    if (ui.attempts.isNotEmpty()) {
        Text("시도 기록", style = MaterialTheme.typography.labelLarge)
        ui.attempts.forEachIndexed { i, a -> Text("${i + 1}. $a", style = MaterialTheme.typography.bodySmall) }
    }
}
