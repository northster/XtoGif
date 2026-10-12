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
import androidx.compose.ui.graphics.FilterQuality
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentHeight
import io.github.northster.xsaver.core.gif.Knob
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import coil3.size.Size as CoilSize
import java.io.File
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
        ) {
            Picture(ui)
            // the 100% GIF (source size, best settings), under the picture
            ui.fullBytes?.let { full ->
                Badge(
                    "원본 크기 GIF (100%) ${if (ui.estimateMeasured) "≈" else "약"} ${Bytes.format(full)}" +
                        (ui.fullLabel?.let { " · $it" } ?: "") +
                        (ui.sliderMaxBytes?.let { max -> if (max < full) " · 목표 최대 ${Bytes.format(max)}" else "" } ?: ""),
                    Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }
    }
}

/**
 * An image at its real size (1 image pixel = 1 screen pixel, never enlarged), so a small GIF looks as small as it is.
 * If it is bigger than the space, it is shrunk to fit and a "전체화면" button shows it alone on the whole screen.
 */
@Composable
private fun PixelImage(file: File, widthPx: Int, heightPx: Int, description: String, onTap: (() -> Unit)?) {
    val context = LocalContext.current
    var fullscreen by remember { mutableStateOf(false) }
    val request = remember(file) {
        ImageRequest.Builder(context).data(file).size(CoilSize.ORIGINAL)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fits = widthPx <= constraints.maxWidth && heightPx <= constraints.maxHeight
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Inside, // shrinks when needed, never enlarges
            filterQuality = FilterQuality.None, // real pixels: dithering and blockiness as they will be
            modifier = Modifier.fillMaxSize().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        )
        if (!fits) {
            FilledTonalButton(
                onClick = { fullscreen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text("전체화면") }
        }
    }
    if (fullscreen) FullscreenImage(request, widthPx, heightPx) { fullscreen = false }
}

/** The image alone on a black screen: fitted, or tap for the real size (1:1) and scroll around. */
@Composable
private fun FullscreenImage(request: ImageRequest, widthPx: Int, heightPx: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var actual by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (actual) {
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                        .clickable { actual = false },
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() }),
                    )
                }
            } else {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize().clickable { actual = true },
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "닫기", tint = Color.White)
            }
            Badge(
                if (actual) "실제 크기 ${widthPx}×${heightPx} · 누르면 화면에 맞춤" else "화면에 맞춤 · 누르면 실제 크기",
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
        }
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
            PixelImage(result.file, result.params.width, ui.info?.let { result.params.height(it) } ?: 0, "GIF", onTap = null)
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
        else -> {
            // before converting: one frame with the settings in view (tap: the source video instead)
            var showVideo by remember { mutableStateOf(false) }
            // the animated first 3 seconds once ready, the single frame until then
            val still = ui.clip ?: ui.still
            val shownParams = if (ui.clip != null) ui.clipParams else ui.stillParams
            val info = ui.info
            if (still != null && !showVideo) {
                PixelImage(
                    still, shownParams?.width ?: 0, info?.let { i -> shownParams?.height(i) } ?: 0, "미리보기",
                    onTap = { showVideo = true },
                )
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopStart) {
                    val what = if (ui.clip != null) "미리보기 앞 3초" else "미리보기 한 장 (움직이는 미리보기 만드는 중…)"
                    val params = shownParams?.let { p -> info?.let { "${p.width}×${p.height(it)} · ${p.fps}fps · ${p.colors}색" } }.orEmpty()
                    val stale = shownParams != null && shownParams != ui.estimateParams
                    Badge("$what · $params${if (stale) " · 새 설정 반영 중…" else ""} · 누르면 원본")
                }
            } else {
                Box(Modifier.fillMaxSize().clickable(enabled = still != null) { showVideo = false }) {
                    SourcePlayer(ui)
                    if (still != null) {
                        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopStart) { Badge("원본 · 누르면 미리보기") }
                    }
                }
            }
        }
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

/** Always visible: target size, resolution, frame rate and colours, the expected size and the main button. */
@Composable
private fun SimpleBar(ui: ConvertUi, vm: MainViewModel, modifier: Modifier) {
    val knobs = remember(ui.info) { vm.knobValues() }
    val enabled = !ui.running && ui.info != null
    Column(modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // target size in 5% steps of the slider's maximum, shown in MB; unlocked it shows the expected size
        val shown = if (ui.targetLocked) ui.targetBytes else ui.estimate
        val max = ui.sliderMaxBytes
        val percent = if (shown != null && max != null && max > 0) (shown * 100f / max).coerceIn(0f, 100f) else null
        KnobSlider(
            label = "목표 용량",
            index = (((percent ?: 50f) / 5f).roundToInt() - 1).coerceIn(0, 19),
            count = 20,
            valueText = shown?.let { Bytes.format(it) } ?: "-",
            enabled = enabled && ui.fullBytes != null,
            locked = ui.targetLocked,
            onLabelClick = vm::toggleTargetLock,
        ) { i -> vm.setTargetPercent((i + 1) * 5) }
        // the three settings: tap a name to lock it; unlocked ones follow the target
        if (knobs != null) {
            val info = ui.info
            for ((knob, label) in listOf(Knob.WIDTH to "해상도", Knob.FPS to "프레임", Knob.COLORS to "색상")) {
                val values = knobs.getValue(knob).sorted() // small on the left
                val current = when (knob) {
                    Knob.WIDTH -> ui.width
                    Knob.FPS -> ui.fps
                    Knob.COLORS -> ui.colors
                }
                val index = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
                KnobSlider(
                    label = label,
                    index = index,
                    count = values.size,
                    valueText = when (knob) {
                        Knob.WIDTH -> if (info != null) "${current}×${(current.toDouble() * info.height / info.width).roundToInt()}" else "$current"
                        Knob.FPS -> "${current}fps"
                        Knob.COLORS -> "${current}색"
                    },
                    enabled = enabled,
                    locked = knob in ui.locks,
                    onLabelClick = { vm.toggleKnobLock(knob) },
                ) { i -> vm.setKnob(knob, values[i]) }
            }
        }
        Spacer(Modifier.height(6.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // expected size on the left, the button on the right
                Column(Modifier.weight(1f)) {
                    val est = ui.estimate
                    val target = ui.targetBytes
                    val over = ui.targetLocked && est != null && target != null && est > target
                    Text(
                        if (est == null) "예상 …" else "예상 ${if (ui.estimateMeasured) "≈" else "약"} ${Bytes.format(est)}${if (over) " ✗" else ""}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        when {
                            ui.estimating -> "샘플로 확인 중…"
                            ui.estimateMeasured -> "샘플로 확인한 값"
                            else -> "대략값"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val ready = ui.info != null && !ui.preparing
                val label = when {
                    ui.result != null && !ui.result.preview && ui.fresh -> if (ui.savedUri == null) "저장" else "다시 저장"
                    else -> "변환 및 저장"
                }
                Button(
                    onClick = vm::convertAndSave,
                    enabled = ready && (!ui.targetLocked || ui.targetBytes != null),
                    modifier = Modifier.weight(1.2f).height(52.dp),
                ) { Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
            }
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

/** A thin slider in one row: "label [slider] value". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnobSlider(
    label: String,
    index: Int,
    count: Int,
    valueText: String,
    enabled: Boolean,
    locked: Boolean,
    onLabelClick: () -> Unit,
    onIndex: (Int) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
        // tap the name to lock / unlock: locked is bold and in the accent colour
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (locked) FontWeight.ExtraBold else FontWeight.Normal,
            color = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp).fillMaxHeight().clickable(onClick = onLabelClick).wrapContentHeight(Alignment.CenterVertically),
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 30.dp) {
            Slider(
                value = index.toFloat().coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat()),
                onValueChange = { onIndex(it.roundToInt().coerceIn(0, count - 1)) },
                modifier = Modifier.weight(1f).height(30.dp),
                enabled = enabled && count > 1,
                valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                steps = (count - 2).coerceAtLeast(0),
                interactionSource = interaction,
                thumb = { SliderDefaults.Thumb(interactionSource = interaction, enabled = enabled, thumbSize = DpSize(4.dp, 20.dp)) },
                track = { st -> SliderDefaults.Track(sliderState = st, enabled = enabled, modifier = Modifier.height(6.dp)) },
            )
        }
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (locked) FontWeight.Bold else FontWeight.Normal,
            color = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(84.dp),
        )
    }
}

/** Shown when the sheet is dragged up. */
@OptIn(ExperimentalLayoutApi::class)
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
        Text("구간: ${formatSeconds(ui.start.toDouble())} ~ ${formatSeconds(ui.end.toDouble())} (${formatSeconds(ui.duration)})", style = MaterialTheme.typography.labelLarge)
        RangeSlider(
            value = ui.start..ui.end,
            onValueChange = { vm.setRange(it.start, it.endInclusive) },
            valueRange = 0f..info.durationSec.toFloat(),
            enabled = enabled,
        )
        Text(
            "이름을 누르면 잠금(굵은 강조색), 잠긴 값은 손으로만 바뀝니다. 목표 용량 슬라이더의 끝은 잠금 상태에서 낼 수 있는 최대 용량입니다. 목표 용량이 잠겨 있으면 잠기지 않은 항목이 " +
                "프레임(최저 12fps) → 색상(최저 64색) → 해상도 순으로 줄어 목표에 맞춰지고, 한 항목만 남으면 프레임은 6fps, 색상은 " +
                "32색까지 내려갑니다. 해상도·프레임·색상 중 둘을 잠그면 목표 용량 잠금이 풀리고, 그때는 정한 값 그대로 변환합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("구간 자르기 허용")
                Text("해상도가 240px 아래로 내려가야 하면 대신 뒤를 자름", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.allowTrim, onCheckedChange = vm::setAllowTrim, enabled = enabled)
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
