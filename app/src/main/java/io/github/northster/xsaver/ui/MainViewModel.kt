package io.github.northster.xsaver.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.northster.xsaver.core.Bytes
import io.github.northster.xsaver.core.FetchResult
import io.github.northster.xsaver.core.FileNames
import io.github.northster.xsaver.core.MediaItem
import io.github.northster.xsaver.core.MediaKind
import io.github.northster.xsaver.core.Post
import io.github.northster.xsaver.core.TweetLink
import io.github.northster.xsaver.core.TweetRef
import io.github.northster.xsaver.core.gif.AutoSizer
import io.github.northster.xsaver.core.gif.Dither
import io.github.northster.xsaver.core.gif.GifParams
import io.github.northster.xsaver.core.gif.GifSizeModel
import io.github.northster.xsaver.core.gif.SourceInfo
import io.github.northster.xsaver.data.Gallery
import io.github.northster.xsaver.data.Http
import io.github.northster.xsaver.data.MediaFetcher
import io.github.northster.xsaver.data.Settings
import io.github.northster.xsaver.data.Source
import io.github.northster.xsaver.gif.GifEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Screen { MAIN, CONVERT, SETTINGS, LICENSES }

enum class EntryState { IDLE, DOWNLOADING, SAVED, FAILED }

data class MediaEntry(
    val key: String,
    val item: MediaItem,
    val fromQuote: Boolean,
    val baseName: String,
    val selected: Boolean = true,
    val size: Long? = null,
    val sizeUnknown: Boolean = false,
    val state: EntryState = EntryState.IDLE,
    val progress: Float = 0f,
    val savedUri: Uri? = null,
    val error: String? = null,
) {
    val fileName get() = "$baseName.${item.extension}"
    val canConvert get() = item.kind != MediaKind.PHOTO
}

data class MainUi(
    val input: String = "",
    val ref: TweetRef? = null,
    val loading: Boolean = false,
    val log: List<String> = emptyList(),
    val source: Source? = null,
    val post: Post? = null,
    val unavailable: String? = null,
    val error: String? = null,
    val includeQuote: Boolean = false,
    val entries: List<MediaEntry> = emptyList(),
    val downloading: Boolean = false,
    /** The "GIF" switch next to the save button: videos and GIFs go through the converter instead of being saved as mp4. */
    val gifMode: Boolean = false,
    val message: String? = null,
) {
    val visible get() = entries.filter { !it.fromQuote || includeQuote }
    val chosenMotion get() = chosen.filter { it.item.kind != MediaKind.PHOTO }
    val chosen get() = visible.filter { it.selected }
    val hasQuoteMedia get() = entries.any { it.fromQuote }
}

enum class ConvertMode { AUTO, MANUAL }

data class GifResult(val file: File, val bytes: Long, val params: GifParams, val preview: Boolean, val fits: Boolean)

data class ConvertUi(
    val title: String = "",
    val baseName: String = "",
    val preparing: Boolean = false,
    val prepareProgress: Float = 0f,
    val error: String? = null,
    val source: File? = null,
    val info: SourceInfo? = null,
    val targetMb: String = "8",
    val start: Float = 0f,
    val end: Float = 0f,
    val mode: ConvertMode = ConvertMode.AUTO,
    val allowTrim: Boolean = false,
    val dither: Dither = Dither.BAYER,
    val width: Int = 480,
    val fps: Int = 15,
    val colors: Int = 256,
    val estimate: Long? = null,
    val estimateMeasured: Boolean = false,
    val estimating: Boolean = false,
    val running: Boolean = false,
    val runLabel: String = "",
    val progress: Float = 0f,
    val attempts: List<String> = emptyList(),
    val result: GifResult? = null,
    val savedUri: Uri? = null,
    val saving: Boolean = false,
    /** The result was made with the current settings, so the main button only needs to save it. */
    val fresh: Boolean = false,
    /** Settings the automatic mode is expected to pick, for the estimate line. */
    val estimateLabel: String? = null,
    /** Expected size of the 100% GIF (best settings: the source's size up to the max width, up to 30 fps, 256 colours). */
    val fullBytes: Long? = null,
    val fullLabel: String? = null,
    val sliderCapBytes: Long = 20 * Bytes.MB,
    /** Settings behind [estimate]: what the automatic mode would pick, or the manual ones. */
    val estimateParams: GifParams? = null,
    /** One frame rendered with [stillParams], shown in place of the video before converting. */
    val still: File? = null,
    val stillParams: GifParams? = null,
    /** The first seconds of the range as an animated GIF with [clipParams]: shown once ready, over the still. */
    val clip: File? = null,
    val clipParams: GifParams? = null,
    /** The target was typed or slid; before that it follows the slider's default position. */
    val targetTouched: Boolean = false,
    val entryKey: String? = null,
    val queueLeft: Int = 0,
    val message: String? = null,
) {
    val targetBytes: Long?
        get() = targetMb.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * Bytes.MB).toLong() }
    val duration get() = (end - start).toDouble()
    /** Top of the size slider: the 100% GIF, at most the maximum from the settings. */
    val sliderMaxBytes: Long? get() = fullBytes?.takeIf { it > 0 }?.let { minOf(it, sliderCapBytes) }
    /** The target as a share of [sliderMaxBytes]. */
    val targetPercent: Float?
        get() {
            val max = sliderMaxBytes ?: return null
            val t = targetBytes ?: return null
            return (t * 100f / max).coerceIn(0f, 100f)
        }

    fun manualParams() = GifParams(width, fps, colors, dither, start.toDouble(), duration)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val fetcher = MediaFetcher(app, settings)
    private val workDir get() = File(getApplication<Application>().cacheDir, "convert")

    private val _screen = MutableStateFlow(Screen.MAIN)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    private val backStack = ArrayDeque<Screen>()

    private val _main = MutableStateFlow(MainUi(includeQuote = settings.includeQuote, gifMode = settings.gifAsGif))
    val main: StateFlow<MainUi> = _main.asStateFlow()

    private val _convert = MutableStateFlow(ConvertUi())
    val convert: StateFlow<ConvertUi> = _convert.asStateFlow()

    private var fetchJob: Job? = null
    private var downloadJob: Job? = null
    private var prepareJob: Job? = null
    private var estimateJob: Job? = null
    private var runJob: Job? = null
    /** Content constant of the size model, from the last real encode of the current source. */
    private var calibratedK = GifSizeModel.DEFAULT_K

    // ---- navigation

    fun navigate(to: Screen) {
        if (_screen.value == to) return
        backStack.addLast(_screen.value)
        _screen.value = to
    }

    /** False when there is nothing to go back to (the activity then closes). */
    fun back(): Boolean {
        if (_screen.value == Screen.CONVERT) {
            stopConvertJobs()
            convertQueue.clear()
        }
        val prev = backStack.removeLastOrNull() ?: return false
        _screen.value = prev
        return true
    }

    // ---- links and the media list

    fun setInput(text: String) = _main.update { it.copy(input = text) }

    fun dismissMessage() = _main.update { it.copy(message = null) }

    /** Text shared from the X app, or typed/pasted. */
    fun openLink(text: String) {
        stopConvertJobs()
        backStack.clear()
        _screen.value = Screen.MAIN
        _main.update { it.copy(input = text.trim()) }
        val ref = TweetLink.parse(text)
        if (ref != null) {
            fetch(ref)
            return
        }
        val short = TweetLink.findShortLink(text)
        if (short == null) {
            _main.update { it.copy(message = "X 게시물 링크를 찾지 못했습니다 (x.com/…/status/… 형식)") }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _main.update { MainUi(input = it.input, loading = true, includeQuote = settings.includeQuote, log = listOf("짧은 링크 확인 중…")) }
            val resolved = MediaFetcher.resolveShortLink(short)
            if (resolved == null) _main.update { it.copy(loading = false, error = "링크가 X 게시물을 가리키지 않습니다") }
            else fetch(resolved)
        }
    }

    fun retry() = _main.value.ref?.let { fetch(it) }

    private fun fetch(ref: TweetRef) {
        fetchJob?.cancel()
        downloadJob?.cancel()
        fetchJob = viewModelScope.launch {
            _main.update { MainUi(input = it.input, ref = ref, loading = true, includeQuote = settings.includeQuote) }
            val outcome = fetcher.fetch(ref) { line -> _main.update { it.copy(log = it.log + line) } }
            when (val r = outcome.result) {
                is FetchResult.Ok -> {
                    val entries = entriesOf(r.post, ref)
                    val mainHasMedia = entries.any { !it.fromQuote }
                    _main.update {
                        it.copy(
                            loading = false, post = r.post, source = outcome.source, entries = entries,
                            // a post that only quotes another one: its media is what was meant
                            includeQuote = it.includeQuote || !mainHasMedia,
                            gifMode = settings.gifAsGif,
                            error = if (entries.isEmpty()) "이 게시물에는 사진, 영상, GIF가 없습니다" else null,
                        )
                    }
                    loadSizes(entries)
                }
                is FetchResult.Unavailable -> _main.update {
                    it.copy(loading = false, unavailable = r.reason.message + if (r.detail.isNotBlank()) "\n${r.detail}" else "")
                }
                is FetchResult.Failed -> _main.update { it.copy(loading = false, error = "미디어 정보를 가져오지 못했습니다: ${r.message}") }
            }
        }
    }

    private fun entriesOf(post: Post, ref: TweetRef): List<MediaEntry> {
        fun of(p: Post, quote: Boolean): List<MediaEntry> {
            val author = p.author.ifBlank { if (quote) "unknown" else ref.user ?: "unknown" }
            val id = p.id.ifBlank { ref.id }
            return p.media.mapIndexed { i, m ->
                MediaEntry(key = "${if (quote) "q" else "m"}$i", item = m, fromQuote = quote, baseName = FileNames.base(author, id, i + 1))
            }
        }
        return of(post, false) + (post.quote?.let { of(it, true) } ?: emptyList())
    }

    private fun loadSizes(entries: List<MediaEntry>) {
        val limit = Semaphore(4)
        entries.forEach { e ->
            viewModelScope.launch(Dispatchers.IO) {
                val size = limit.withPermit { runCatching { Http.contentLength(e.item.url) }.getOrNull() }
                updateEntry(e.key) { it.copy(size = size, sizeUnknown = size == null) }
            }
        }
    }

    private fun updateEntry(key: String, f: (MediaEntry) -> MediaEntry) =
        _main.update { s -> s.copy(entries = s.entries.map { if (it.key == key) f(it) else it }) }

    fun toggle(key: String) = updateEntry(key) { it.copy(selected = !it.selected) }

    fun selectAll(on: Boolean) = _main.update { s -> s.copy(entries = s.entries.map { it.copy(selected = on) }) }

    fun setGifAsGif(on: Boolean) {
        settings.gifAsGif = on
    }

    fun setGifMode(on: Boolean) = _main.update { it.copy(gifMode = on) }

    fun setIncludeQuote(on: Boolean) = _main.update { it.copy(includeQuote = on) }

    fun downloadSelected() {
        if (downloadJob?.isActive == true) return
        val chosen = _main.value.chosen
        if (chosen.isEmpty()) {
            _main.update { it.copy(message = "선택한 항목이 없습니다") }
            return
        }
        // with the GIF switch on, videos and GIFs open the converter one after another; photos are saved meanwhile
        val motion = if (_main.value.gifMode) chosen.filter { it.item.kind != MediaKind.PHOTO } else emptyList()
        // the ones not saved yet; when all of them are, saving again makes new copies
        val todo = (chosen - motion.toSet()).let { rest -> rest.filter { it.state != EntryState.SAVED }.ifEmpty { rest } }
        if (motion.isNotEmpty()) {
            convertQueue.clear()
            convertQueue.addAll(motion.drop(1).map { it.key })
            convertEntry(motion.first().key)
        }
        if (todo.isEmpty()) return
        downloadJob = viewModelScope.launch {
            _main.update { it.copy(downloading = true) }
            var ok = 0
            for (e in todo) {
                updateEntry(e.key) { it.copy(state = EntryState.DOWNLOADING, progress = 0f, error = null) }
                try {
                    val progress = { p: Float -> updateEntry(e.key) { it.copy(progress = p) } }
                    val uri = when {
                        e.item.kind == MediaKind.PHOTO -> Gallery.download(getApplication(), e.item.url, e.fileName, e.item.mimeType, progress)
                        else -> saveVideo(e, progress)
                    }
                    updateEntry(e.key) { it.copy(state = EntryState.SAVED, savedUri = uri, progress = 1f) }
                    ok++
                } catch (c: CancellationException) {
                    updateEntry(e.key) { it.copy(state = EntryState.IDLE) }
                    throw c
                } catch (ex: Exception) {
                    updateEntry(e.key) { it.copy(state = EntryState.FAILED, error = ex.message ?: "실패") }
                }
            }
            val failed = todo.size - ok
            _main.update {
                it.copy(
                    downloading = false,
                    message = "${ok}개 저장됨 (갤러리 › ${Gallery.ALBUM})" + if (failed > 0) ", ${failed}개 실패" else "",
                )
            }
        }
    }

    /** Videos and GIFs kept as mp4: downloaded, then re-packaged by FFmpeg so the gallery can play them. */
    private suspend fun saveVideo(e: MediaEntry, onProgress: (Float) -> Unit): Uri {
        val dir = File(getApplication<Application>().cacheDir, "videosave").apply { mkdirs() }
        val src = File(dir, "${e.baseName}_x.mp4")
        val out = File(dir, "${e.baseName}.mp4")
        try {
            Gallery.downloadToFile(e.item.url, src) { onProgress(0.9f * it) }
            val file = try {
                GifEngine.remux(src, out)
                out
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                src // keep X's file rather than nothing
            }
            onProgress(1f)
            return Gallery.saveFile(getApplication(), file, e.fileName, "video/mp4")
        } finally {
            src.delete()
            out.delete()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _main.update { it.copy(downloading = false) }
    }

    // ---- GIF conversion

    /** Videos and GIFs still waiting for the converter after the current one. */
    private val convertQueue = ArrayDeque<String>()

    /** A video or GIF from the list, downloaded again from X (what was saved may already be a converted GIF). */
    fun convertEntry(key: String, message: String? = null) {
        val e = _main.value.entries.firstOrNull { it.key == key } ?: return
        val kind = if (e.item.kind == MediaKind.GIF) "GIF" else "영상"
        startConvert("$kind · ${e.baseName}", e.baseName, entryKey = key, message = message) { file, progress ->
            // re-packaged like saved videos, or the preview player can't open X's mp4 either
            val raw = File(file.parentFile, "source_x.mp4")
            Gallery.downloadToFile(e.item.url, raw, progress)
            try {
                GifEngine.remux(raw, file)
                raw.delete()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                raw.renameTo(file)
            }
        }
    }

    /** A video from the photo picker or shared from another app. */
    fun convertUri(uri: Uri) {
        convertQueue.clear()
        backStack.clear()
        _screen.value = Screen.MAIN
        val name = Gallery.displayName(getApplication(), uri)
        startConvert(name ?: "갤러리 영상", FileNames.fromDisplayName(name)) { file, _ ->
            Gallery.copyToFile(getApplication(), uri, file)
        }
    }

    private fun startConvert(
        title: String,
        baseName: String,
        entryKey: String? = null,
        message: String? = null,
        fetchSource: suspend (File, (Float) -> Unit) -> Unit,
    ) {
        stopConvertJobs()
        calibratedK = GifSizeModel.DEFAULT_K
        _convert.value = ConvertUi(
            title = title, baseName = baseName, preparing = true,
            // a first guess until the 100% size is known: the default position on the slider's maximum
            targetMb = String.format(Locale.US, "%.1f", settings.sliderMaxMb * settings.sliderDefaultPercent / 100f),
            allowTrim = settings.allowTrim, dither = settings.dither, entryKey = entryKey, queueLeft = convertQueue.size,
            sliderCapBytes = (settings.sliderMaxMb * Bytes.MB).toLong(),
            message = message,
        )
        navigate(Screen.CONVERT)
        prepareJob = viewModelScope.launch {
            try {
                val dir = workDir
                withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }
                val file = File(dir, "source")
                fetchSource(file) { p -> _convert.update { it.copy(prepareProgress = p) } }
                val info = GifEngine.probe(file)
                val width = min(info.width, 480).let { it - it % 2 }.coerceAtLeast(2)
                _convert.update {
                    it.copy(
                        preparing = false, source = file, info = info, start = 0f, end = info.durationSec.toFloat(),
                        width = width, fps = min(info.maxFps, 15), colors = 256,
                    )
                }
                requestEstimate()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _convert.update { it.copy(preparing = false, error = e.message ?: "영상을 준비하지 못했습니다") }
            }
        }
    }

    private fun stopConvertJobs() {
        prepareJob?.cancel()
        estimateJob?.cancel()
        runJob?.cancel()
    }

    fun dismissConvertMessage() = _convert.update { it.copy(message = null) }

    fun setTarget(mb: String) {
        _convert.update { it.copy(targetMb = mb, fresh = false, targetTouched = true) }
        requestEstimate()
    }

    fun setRange(start: Float, end: Float) {
        _convert.update { it.copy(start = start, end = max(end, start + 0.1f), fresh = false) }
        requestEstimate()
    }

    fun setMode(mode: ConvertMode) {
        _convert.update { it.copy(mode = mode, fresh = false) }
        requestEstimate()
    }

    fun setAllowTrim(on: Boolean) {
        settings.allowTrim = on
        _convert.update { it.copy(allowTrim = on, fresh = false) }
        requestEstimate()
    }

    fun setDither(d: Dither) {
        settings.dither = d
        _convert.update { it.copy(dither = d, fresh = false) }
        requestEstimate()
    }

    fun setManual(width: Int? = null, fps: Int? = null, colors: Int? = null) {
        _convert.update {
            it.copy(
                width = width?.let { w -> w - w % 2 }?.coerceAtLeast(2) ?: it.width,
                fps = fps ?: it.fps,
                colors = colors ?: it.colors,
                fresh = false,
            )
        }
        requestEstimate()
    }

    /** What the automatic mode would pick under [target] with the size constant [k], and whether it fits. */
    private fun autoPick(info: SourceInfo, s: ConvertUi, target: Long, k: Double): Pair<GifParams, Boolean> {
        val ladder = AutoSizer(info, target, s.dither, s.start.toDouble(), s.duration, s.allowTrim, maxWidth = settings.maxGifWidth).ladder
        val p = ladder.firstOrNull { GifSizeModel.predict(info, it, k) <= target * 0.97 }
        return (p ?: ladder.last()) to (p != null)
    }

    private fun estimateFor(info: SourceInfo, s: ConvertUi, k: Double): Triple<GifParams, Long, String>? {
        if (s.mode == ConvertMode.MANUAL) {
            val p = s.manualParams()
            return Triple(p, GifSizeModel.predict(info, p, k).toLong(), p.describe(info))
        }
        val target = s.targetBytes ?: return null
        val (p, fits) = autoPick(info, s, target, k)
        val label = p.describe(info) + when {
            fits -> ""
            s.allowTrim -> " · 넘치면 뒤를 잘라 맞춤"
            else -> " · 목표 초과"
        }
        return Triple(p, GifSizeModel.predict(info, p, k).toLong(), label)
    }

    /** The best settings the automatic mode can use (top of its ladder): the "100%" GIF the size slider is relative to. */
    private fun fullParams(info: SourceInfo, s: ConvertUi): GifParams =
        AutoSizer(info, 1, s.dither, s.start.toDouble(), s.duration, false, maxWidth = settings.maxGifWidth).ladder.first()

    private fun applyEstimates(info: SourceInfo, s: ConvertUi, k: Double, measured: Boolean) {
        val full = fullParams(info, s)
        val fullBytes = GifSizeModel.predict(info, full, k).toLong()
        // until the target is set by hand it sits at the slider's default position, following the 100% size
        val st = if (s.targetTouched) s.copy(fullBytes = fullBytes)
        else s.copy(fullBytes = fullBytes).let { it.copy(targetMb = percentToMb(it, settings.sliderDefaultPercent) ?: it.targetMb) }
        val main = estimateFor(info, st, k)
        _convert.update {
            it.copy(
                estimate = main?.second, estimateLabel = main?.third, estimateMeasured = measured, estimateParams = main?.first,
                fullBytes = fullBytes, fullLabel = full.describe(info),
                targetMb = if (it.targetTouched) it.targetMb else st.targetMb,
            )
        }
    }

    private fun percentToMb(s: ConvertUi, percent: Int): String? {
        val max = s.sliderMaxBytes ?: return null
        val mb = max * percent / 100.0 / Bytes.MB
        return when {
            mb >= 10 -> String.format(Locale.US, "%.0f", mb)
            mb >= 0.1 -> String.format(Locale.US, "%.1f", mb)
            else -> String.format(Locale.US, "%.2f", mb)
        }
    }

    /**
     * The expected size and the size of the 100% GIF: right away from the size model, then again after encoding a
     * short sample (up to 2 s from the middle of the range) once the controls rest. The sample uses the manual settings
     * in manual mode, else the 100% settings.
     */
    private fun requestEstimate() {
        estimateJob?.cancel()
        val s = _convert.value
        val info = s.info ?: return
        val source = s.source ?: return
        if (s.running) return
        applyEstimates(info, s, calibratedK, measured = false)
        estimateJob = viewModelScope.launch {
            delay(600)
            _convert.update { it.copy(estimating = true) }
            try {
                // the 100% settings are sampled at no more than 480 px wide (much faster; the model scales it up)
                val params = if (s.mode == ConvertMode.MANUAL) s.manualParams()
                else fullParams(info, s).let { full -> full.copy(width = min(full.width, 480).let { w -> w - w % 2 }) }
                val sampleLen = min(2.0, params.durationSec)
                val sample = params.copy(startSec = params.startSec + (params.durationSec - sampleLen) / 2, durationSec = sampleLen)
                val bytes = GifEngine.encode(source, sample, File(workDir, "sample.gif"))
                calibratedK = GifSizeModel.calibrate(info, sample, bytes)
                applyEstimates(info, _convert.value, calibratedK, measured = true)
                _convert.update { it.copy(estimating = false) }
                renderStill(info, source)
                renderClip(info, source)
            } catch (c: CancellationException) {
                _convert.update { it.copy(estimating = false) }
                throw c
            } catch (_: Exception) {
                _convert.update { it.copy(estimating = false) }
            }
        }
    }

    private var stillCounter = 0

    /** A frame from the middle of the range with the settings the estimate is about. */
    private suspend fun renderStill(info: SourceInfo, source: File) {
        val p = _convert.value.estimateParams ?: return
        if (_convert.value.stillParams == p) return
        val out = File(workDir, "still_${++stillCounter}.png")
        try {
            GifEngine.still(source, p, p.startSec + p.durationSec / 2, out)
            val old = _convert.value.still
            _convert.update { it.copy(still = out, stillParams = p) }
            if (old != out) old?.delete()
        } catch (c: CancellationException) {
            out.delete()
            throw c
        } catch (_: Exception) {
            out.delete()
        }
    }

    /** The first 3 seconds of the range with the estimate's settings, animated: shows motion and frame rate too. */
    private suspend fun renderClip(info: SourceInfo, source: File) {
        val p = _convert.value.estimateParams ?: return
        if (_convert.value.clipParams == p) return
        val clipParams = p.copy(durationSec = min(3.0, p.durationSec))
        val out = File(workDir, "clip_${++stillCounter}.gif")
        try {
            GifEngine.encode(source, clipParams, out)
            val old = _convert.value.clip
            _convert.update { it.copy(clip = out, clipParams = p) }
            if (old != out) old?.delete()
        } catch (c: CancellationException) {
            out.delete()
            throw c
        } catch (_: Exception) {
            out.delete()
        }
    }

    /** The size slider: [percent] of its top (the 100% GIF or the maximum from the settings), as the target. */
    fun setTargetPercent(percent: Int) {
        percentToMb(_convert.value, percent)?.let { setTarget(it) }
    }

    /** The main button: convert with the current settings and save, or only save when that result is already there. */
    fun convertAndSave() {
        val s = _convert.value
        if (s.running || s.saving || s.preparing || s.info == null) return
        val r = s.result
        if (r != null && !r.preview && s.fresh) {
            if (r.fits) saveResult() else _convert.update { it.copy(message = "목표 용량을 넘는 결과라 저장하지 않았습니다. 설정을 바꿔 다시 변환하세요") }
            return
        }
        if (s.mode == ConvertMode.AUTO) runAuto(andSave = true) else runManual(preview = false, andSave = true)
    }

    fun runAuto(andSave: Boolean = false) {
        val s = _convert.value
        val info = s.info ?: return
        val src = s.source ?: return
        val target = s.targetBytes ?: return _convert.update { it.copy(message = "목표 용량을 입력하세요") }
        estimateJob?.cancel()
        runJob = viewModelScope.launch {
            _convert.update { it.copy(running = true, attempts = emptyList(), result = null, savedUri = null, progress = 0f) }
            val files = mutableMapOf<GifParams, File>()
            try {
                val sizer = AutoSizer(
                    src = info, targetBytes = target, dither = s.dither, startSec = s.start.toDouble(), durationSec = s.duration,
                    allowTrim = s.allowTrim, maxWidth = settings.maxGifWidth, initialK = calibratedK,
                )
                var n = 0
                val outcome = sizer.run(
                    onAttempt = { a ->
                        val line = "${a.params.describe(info)} → ${Bytes.format(a.bytes)} ${if (a.fits) "✓" else "✗"}"
                        _convert.update { it.copy(attempts = it.attempts + line) }
                    },
                ) { p ->
                    n++
                    _convert.update { it.copy(runLabel = "시도 $n: ${p.describe(info)}", progress = 0f) }
                    val out = File(workDir, "try_$n.gif")
                    files[p] = out
                    GifEngine.encode(src, p, out) { f -> _convert.update { it.copy(progress = f) } }.also {
                        calibratedK = GifSizeModel.calibrate(info, p, it)
                    }
                }
                val (chosen, fits) = when (outcome) {
                    is AutoSizer.Outcome.Fits -> outcome.attempt to true
                    is AutoSizer.Outcome.TooBig -> outcome.smallest to false
                }
                val file = chosen?.let { files[it.params] }
                files.values.filter { it != file }.forEach { it.delete() }
                _convert.update {
                    it.copy(
                        running = false,
                        result = if (chosen != null && file != null) GifResult(file, chosen.bytes, chosen.params, false, fits) else null,
                        fresh = true,
                        message = if (fits) null else "가장 낮은 설정으로도 목표 용량을 넘습니다. " +
                            if (s.allowTrim) "구간을 더 짧게 잡아 보세요." else "세부 설정에서 '구간 자르기 허용'을 켜거나 구간을 줄여 보세요.",
                    )
                }
                if (andSave && fits) saveResult()
            } catch (c: CancellationException) {
                files.values.forEach { it.delete() }
                _convert.update { it.copy(running = false, runLabel = "") }
                throw c
            } catch (e: Exception) {
                _convert.update { it.copy(running = false, message = e.message ?: "변환 실패") }
            }
        }
    }

    /** Manual mode: the whole range ([preview] = false) or its first 3 seconds. */
    fun runManual(preview: Boolean, andSave: Boolean = false) {
        val s = _convert.value
        val info = s.info ?: return
        val src = s.source ?: return
        estimateJob?.cancel()
        runJob = viewModelScope.launch {
            val full = s.manualParams()
            val p = if (preview) full.copy(durationSec = min(3.0, full.durationSec)) else full
            _convert.update {
                it.copy(running = true, result = null, savedUri = null, progress = 0f,
                    runLabel = (if (preview) "미리보기: " else "변환: ") + p.describe(info))
            }
            try {
                val out = File(workDir, if (preview) "preview.gif" else "manual.gif")
                val bytes = GifEngine.encode(src, p, out) { f -> _convert.update { it.copy(progress = f) } }
                calibratedK = GifSizeModel.calibrate(info, p, bytes)
                val target = s.targetBytes
                _convert.update {
                    it.copy(
                        running = false,
                        result = GifResult(out, bytes, p, preview, target == null || bytes <= target),
                        estimate = if (preview) GifSizeModel.predict(info, full, calibratedK).toLong() else bytes,
                        estimateMeasured = true,
                        fresh = !preview,
                    )
                }
                if (andSave && !preview) {
                    if (target == null || bytes <= target) saveResult()
                    else _convert.update { it.copy(message = "결과 ${Bytes.format(bytes)}가 목표 용량을 넘어 저장하지 않았습니다") }
                }
            } catch (c: CancellationException) {
                _convert.update { it.copy(running = false, runLabel = "") }
                throw c
            } catch (e: Exception) {
                _convert.update { it.copy(running = false, message = e.message ?: "변환 실패") }
            }
        }
    }

    fun cancelRun() {
        runJob?.cancel()
    }

    fun saveResult() {
        val r = _convert.value.result ?: return
        if (r.preview || _convert.value.saving) return
        viewModelScope.launch {
            _convert.update { it.copy(saving = true) }
            try {
                val uri = Gallery.saveFile(getApplication(), r.file, "${_convert.value.baseName}.gif", "image/gif")
                val done = "GIF 저장됨 (${Bytes.format(r.bytes)}, 갤러리 › ${Gallery.ALBUM})"
                _convert.value.entryKey?.let { key -> updateEntry(key) { it.copy(state = EntryState.SAVED, savedUri = uri, error = null) } }
                val next = convertQueue.removeFirstOrNull()
                if (next != null) {
                    _convert.update { it.copy(saving = false) }
                    convertEntry(next, message = "$done · 다음 영상")
                    return@launch
                }
                _convert.update { it.copy(savedUri = uri, message = done) }
            } catch (e: Exception) {
                _convert.update { it.copy(message = "저장 실패: ${e.message}") }
            } finally {
                _convert.update { it.copy(saving = false) }
            }
        }
    }

    /** Puts the found settings into manual mode for fine tuning. */
    fun tuneResult() {
        val r = _convert.value.result ?: return
        _convert.update {
            it.copy(mode = ConvertMode.MANUAL, width = r.params.width, fps = r.params.fps, colors = r.params.colors,
                end = (r.params.startSec + r.params.durationSec).toFloat().coerceAtMost(it.info?.durationSec?.toFloat() ?: it.end))
        }
        requestEstimate()
    }

    companion object {
        val presetsMb = listOf("1", "2", "4", "5", "8", "10", "15")
        val colorSteps = listOf(16, 24, 32, 48, 64, 96, 128, 192, 256)
        fun roundFps(f: Float) = f.roundToInt()
    }
}
