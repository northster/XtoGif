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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Screen { MAIN, CONVERT, SETTINGS }

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
    val message: String? = null,
) {
    val visible get() = entries.filter { !it.fromQuote || includeQuote }
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
    val message: String? = null,
) {
    val targetBytes: Long?
        get() = targetMb.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * Bytes.MB).toLong() }
    val duration get() = (end - start).toDouble()

    fun manualParams() = GifParams(width, fps, colors, dither, start.toDouble(), duration)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val fetcher = MediaFetcher(app, settings)
    private val workDir get() = File(getApplication<Application>().cacheDir, "convert")

    private val _screen = MutableStateFlow(Screen.MAIN)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    private val backStack = ArrayDeque<Screen>()

    private val _main = MutableStateFlow(MainUi(includeQuote = settings.includeQuote))
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
        if (_screen.value == Screen.CONVERT) stopConvertJobs()
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

    fun setIncludeQuote(on: Boolean) = _main.update { it.copy(includeQuote = on) }

    fun downloadSelected() {
        if (downloadJob?.isActive == true) return
        val chosen = _main.value.chosen
        if (chosen.isEmpty()) {
            _main.update { it.copy(message = "선택한 항목이 없습니다") }
            return
        }
        // the ones not saved yet; when all of them are, saving again makes new copies
        val todo = chosen.filter { it.state != EntryState.SAVED }.ifEmpty { chosen }
        downloadJob = viewModelScope.launch {
            _main.update { it.copy(downloading = true) }
            var ok = 0
            for (e in todo) {
                updateEntry(e.key) { it.copy(state = EntryState.DOWNLOADING, progress = 0f, error = null) }
                try {
                    val uri = Gallery.download(getApplication(), e.item.url, e.fileName, e.item.mimeType) { p ->
                        updateEntry(e.key) { it.copy(progress = p) }
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

    fun cancelDownload() {
        downloadJob?.cancel()
        _main.update { it.copy(downloading = false) }
    }

    // ---- GIF conversion

    /** A video or GIF from the list: the saved copy if there is one, else downloaded to the cache. */
    fun convertEntry(key: String) {
        val e = _main.value.entries.firstOrNull { it.key == key } ?: return
        val kind = if (e.item.kind == MediaKind.GIF) "GIF" else "영상"
        startConvert("$kind · ${e.baseName}", e.baseName) { file, progress ->
            val saved = e.savedUri
            if (saved != null) Gallery.copyToFile(getApplication(), saved, file)
            else Gallery.downloadToFile(e.item.url, file, progress)
        }
    }

    /** A video from the photo picker or shared from another app. */
    fun convertUri(uri: Uri) {
        backStack.clear()
        _screen.value = Screen.MAIN
        val name = Gallery.displayName(getApplication(), uri)
        startConvert(name ?: "갤러리 영상", FileNames.fromDisplayName(name)) { file, _ ->
            Gallery.copyToFile(getApplication(), uri, file)
        }
    }

    private fun startConvert(title: String, baseName: String, fetchSource: suspend (File, (Float) -> Unit) -> Unit) {
        stopConvertJobs()
        calibratedK = GifSizeModel.DEFAULT_K
        _convert.value = ConvertUi(
            title = title, baseName = baseName, preparing = true, targetMb = settings.targetMb,
            allowTrim = settings.allowTrim, dither = settings.dither,
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
        _convert.update { it.copy(targetMb = mb) }
        if (_convert.value.targetBytes != null) settings.targetMb = mb
        requestEstimate()
    }

    fun setRange(start: Float, end: Float) {
        _convert.update { it.copy(start = start, end = max(end, start + 0.1f)) }
        requestEstimate()
    }

    fun setMode(mode: ConvertMode) {
        _convert.update { it.copy(mode = mode) }
        requestEstimate()
    }

    fun setAllowTrim(on: Boolean) {
        settings.allowTrim = on
        _convert.update { it.copy(allowTrim = on) }
    }

    fun setDither(d: Dither) {
        settings.dither = d
        _convert.update { it.copy(dither = d) }
        requestEstimate()
    }

    fun setManual(width: Int? = null, fps: Int? = null, colors: Int? = null) {
        _convert.update {
            it.copy(
                width = width?.let { w -> w - w % 2 }?.coerceAtLeast(2) ?: it.width,
                fps = fps ?: it.fps,
                colors = colors ?: it.colors,
            )
        }
        requestEstimate()
    }

    /**
     * Manual mode: an instant estimate from the size model, then a real one from encoding a short sample
     * (up to 2 s from the middle of the range) once the sliders rest.
     */
    private fun requestEstimate() {
        estimateJob?.cancel()
        val s = _convert.value
        val info = s.info ?: return
        if (s.mode != ConvertMode.MANUAL || s.running || s.source == null) return
        val params = s.manualParams()
        _convert.update { it.copy(estimate = GifSizeModel.predict(info, params, calibratedK).toLong(), estimateMeasured = false) }
        estimateJob = viewModelScope.launch {
            delay(600)
            _convert.update { it.copy(estimating = true) }
            try {
                val sampleLen = min(2.0, params.durationSec)
                val sample = params.copy(startSec = params.startSec + (params.durationSec - sampleLen) / 2, durationSec = sampleLen)
                val bytes = GifEngine.encode(s.source, sample, File(workDir, "sample.gif"))
                calibratedK = GifSizeModel.calibrate(info, sample, bytes)
                _convert.update {
                    it.copy(estimate = GifSizeModel.predict(info, params, calibratedK).toLong(), estimateMeasured = true, estimating = false)
                }
            } catch (c: CancellationException) {
                _convert.update { it.copy(estimating = false) }
                throw c
            } catch (_: Exception) {
                _convert.update { it.copy(estimating = false) }
            }
        }
    }

    fun runAuto() {
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
                    allowTrim = s.allowTrim, maxWidth = settings.maxGifWidth,
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
                        message = if (fits) null else "가장 낮은 설정으로도 목표 용량을 넘습니다. " +
                            if (s.allowTrim) "구간을 더 짧게 잡아 보세요." else "'구간 자르기 허용'을 켜거나 구간을 줄여 보세요.",
                    )
                }
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
    fun runManual(preview: Boolean) {
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
                    )
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
                _convert.update { it.copy(savedUri = uri, message = "갤러리 › ${Gallery.ALBUM}에 저장했습니다 (${Bytes.format(r.bytes)})") }
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
