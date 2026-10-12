package io.github.northster.xsaver.ui

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.northster.xsaver.core.Bytes
import io.github.northster.xsaver.core.gif.Dither
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The picture fills the screen; a sheet at the bottom always shows the target size, the expected size and
 * "변환 및 저장". Dragging the sheet up (or tapping its handle) shows the detailed settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(vm: MainViewModel) {
    val ui by vm.convert.collectAsStateWithLifecycle()
    val sheet = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var peek by remember { mutableStateOf(200.dp) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            sheet.snackbarHostState.showSnackbar(it)
            vm.dismissConvertMessage()
        }
    }
    // FFmpeg runs in this process: keep the screen on while it works
    val view = LocalView.current
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }

    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = peek,
        sheetDragHandle = null,
        sheetContent = {
            Column(
                Modifier.onGloballyPositioned { peek = with(density) { it.size.height.toDp() } },
            ) {
                // the handle: drag the sheet up, or tap
                Box(
                    Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            if (sheet.bottomSheetState.currentValue == SheetValue.Expanded) sheet.bottomSheetState.partialExpand()
                            else sheet.bottomSheetState.expand()
                        }
                    }.padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(width = 36.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                    )
                }
                SimpleBar(ui, vm, Modifier.navigationBarsPadding())
            }
            Details(ui, vm)
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GIF 변환")
                        Text(ui.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(it) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = peek).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) { Picture(ui) }
    }
}

/** The result GIF once there is one, else the source video looping over the chosen range. */
@Composable
private fun Picture(ui: ConvertUi) {
    when {
        ui.preparing -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("원본 준비 중… ${(ui.prepareProgress * 100).roundToInt()}%", color = Color.White)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { ui.prepareProgress }, modifier = Modifier.fillMaxWidth())
        }
        ui.error != null -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.padding(24.dp),
        ) { Text(ui.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
        ui.info == null -> {}
        ui.result != null -> {
            val result = ui.result
            val context = LocalContext.current
            val request = remember(result) {
                ImageRequest.Builder(context).data(result.file)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
            }
            AsyncImage(model = request, contentDescription = "GIF", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            val target = ui.targetBytes
            val verdict = when {
                result.preview -> "미리보기 (앞 3초)"
                target == null -> ""
                result.fits -> "목표 이내 ✓"
                else -> "목표 초과 ✗"
            }
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopStart) {
                Badge("GIF ${Bytes.format(result.bytes)} · $verdict")
            }
        }
        else -> SourcePlayer(ui)
    }
}

@Composable
private fun SourcePlayer(ui: ConvertUi) {
    val file = ui.source ?: return
    val info = ui.info ?: return
    val range by rememberUpdatedState(ui.start to ui.end)
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.absolutePath)
                    setOnErrorListener { _, _, _ -> true } // no "can't play" dialog: the preview is optional
                    setOnPreparedListener { mp ->
                        mp.setVolume(0f, 0f)
                        mp.isLooping = true
                        seekTo((range.first * 1000).toInt())
                        start()
                    }
                }.also { videoView = it }
            },
            modifier = Modifier.aspectRatio(info.width.toFloat() / info.height),
            onRelease = { it.stopPlayback() },
        )
    }
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

/** Always visible: target size, expected size, the main button. */
@Composable
private fun SimpleBar(ui: ConvertUi, vm: MainViewModel, modifier: Modifier) {
    Column(modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = ui.targetMb,
                onValueChange = { vm.setTarget(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                label = { Text("목표 용량") },
                suffix = { Text("MB") },
                singleLine = true,
                enabled = !ui.running,
                isError = ui.targetBytes == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(140.dp),
            )
            Column(Modifier.weight(1f)) {
                val est = ui.estimate
                val target = ui.targetBytes
                Text(
                    when {
                        est == null -> if (ui.info == null) "예상 용량 -" else "예상 용량 계산 중…"
                        else -> "예상 ${if (ui.estimateMeasured) "≈" else "약"} ${Bytes.format(est)}" +
                            if (target != null && est > target) " ✗" else ""
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (ui.estimating) "샘플을 변환해 확인 중…" else ui.estimateLabel.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (ui.running || ui.saving) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ui.saving) "저장 중…" else "${ui.runLabel} (${(ui.progress * 100).roundToInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (ui.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                }
                if (ui.running) OutlinedButton(onClick = vm::cancelRun) { Text("취소") }
            }
        } else {
            val ready = ui.info != null && !ui.preparing
            val label = when {
                ui.result != null && !ui.result.preview && ui.fresh -> if (ui.savedUri == null) "저장" else "다시 저장"
                else -> "변환 및 저장"
            }
            Button(
                onClick = vm::convertAndSave,
                enabled = ready && (ui.mode == ConvertMode.MANUAL || ui.targetBytes != null),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(label, style = MaterialTheme.typography.titleMedium) }
        }
        if (ui.queueLeft > 0) {
            Text(
                "저장하면 다음 영상으로 넘어갑니다 (${ui.queueLeft}개 남음)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown when the sheet is dragged up. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Details(ui: ConvertUi, vm: MainViewModel) {
    val info = ui.info ?: return
    val enabled = !ui.running
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text("자주 쓰는 용량", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MainViewModel.presetsMb.forEach { mb ->
                FilterChip(selected = ui.targetMb == mb, onClick = { vm.setTarget(mb) }, label = { Text("${mb}MB") }, enabled = enabled)
            }
        }

        Text("구간: ${formatSeconds(ui.start.toDouble())} ~ ${formatSeconds(ui.end.toDouble())} (${formatSeconds(ui.duration)})", style = MaterialTheme.typography.labelLarge)
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
            if (ui.result != null && !ui.result.preview) {
                OutlinedButton(onClick = vm::tuneResult, enabled = enabled) { Text("찾은 설정을 수동으로 다듬기") }
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
            OutlinedButton(onClick = { vm.runManual(preview = true) }, enabled = enabled) { Text("미리보기 (앞 3초)") }
        }

        Text("디더링", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dither.entries.forEach { d ->
                FilterChip(selected = ui.dither == d, onClick = { vm.setDither(d) }, label = { Text(d.label) }, enabled = enabled)
            }
        }

        ui.result?.let { r ->
            Text("결과: ${Bytes.format(r.bytes)} · ${r.params.describe(info)}", style = MaterialTheme.typography.bodySmall)
        }
        if (ui.attempts.isNotEmpty()) {
            Text("시도 기록", style = MaterialTheme.typography.labelLarge)
            ui.attempts.forEachIndexed { i, a -> Text("${i + 1}. $a", style = MaterialTheme.typography.bodySmall) }
        }
        Text(
            "원본 ${info.width}×${info.height} · ${"%.1f".format(info.fps)}fps · ${formatSeconds(info.durationSec)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
