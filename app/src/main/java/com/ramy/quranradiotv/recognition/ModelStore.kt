package com.ramy.quranradiotv.recognition

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ramy.quranradiotv.Prefs
import com.ramy.quranradiotv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The speech model on disk: fetching it, checking it, and saying whether it
 * is there.
 *
 * The model is five files and about 125 MB, so it is never bundled; the user
 * asks for it in Settings and it is fetched once. The system's DownloadManager
 * does the fetching, one request per file, because it carries on after the app
 * is gone, survives a dropped connection, and shows its own progress in the
 * shade. A device where that service has been disabled falls back to fetching
 * in-process, resumable from wherever it stopped.
 *
 * The files go straight into the model directory; a marker is written only
 * once every one of them is present at its full size, so a half-finished
 * fetch is never mistaken for a whole one.
 */
object ModelStore {

    /** Tarteel AI's Whisper-base fine-tuned on recitation, in Optimum's ONNX export. */
    const val MODEL_NAME = "tarteel-ai-whisper-base-ar-quran"
    private const val BASE_URL =
        "https://huggingface.co/eventhorizon0/tarteel-ai-onnx-whisper-base-ar-quran/resolve/main/onnx/"

    /** Expected size of each file, for progress and for knowing it arrived whole. */
    private val FILE_SIZES = mapOf(
        WhisperModel.ENCODER to 23_145_770L,
        WhisperModel.DECODER to 52_489_216L,
        WhisperModel.DECODER_WITH_PAST to 49_295_198L,
        "vocab.json" to 835_528L,
        "added_tokens.json" to 2_108L,
    )
    val DOWNLOAD_BYTES: Long = FILE_SIZES.values.sum()

    sealed class Status {
        object Missing : Status()
        class Downloading(val bytes: Long, val total: Long) : Status() {
            val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else -1
        }
        object Installing : Status()
        class Installed(val bytesOnDisk: Long) : Status()
        class Failed(val reason: String) : Status()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableSetOf<(Status) -> Unit>()

    private var appContext: Context? = null
    private var prefs: Prefs? = null

    @Volatile
    var status: Status = Status.Missing
        private set

    private var inProcessDownload: Job? = null

    // ---------------------------------------------------------------- paths

    /**
     * App-specific external storage when there is any, because DownloadManager
     * will write nowhere else; private internal storage otherwise, reached
     * only by the in-process fetch.
     */
    private fun modelDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "recognition/$MODEL_NAME")

    private fun readyMarker(ctx: Context) = File(modelDir(ctx), ".ready")

    private fun isComplete(dir: File): Boolean =
        FILE_SIZES.all { (name, size) -> File(dir, name).length() >= size * 95 / 100 }

    /** The directory to hand the recogniser, or null when not installed. */
    fun installedModelDir(ctx: Context): File? {
        val dir = modelDir(ctx)
        return if (readyMarker(ctx).exists() && isComplete(dir)) dir else null
    }

    fun isInstalled(ctx: Context) = installedModelDir(ctx) != null

    // ---------------------------------------------------------------- lifecycle

    /**
     * Called once from the Application. Works out what state things were left
     * in: downloads finished while the app was gone, or still running.
     */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = Prefs(app)
        scope.launch { removeEarlierEngine(app) }
        refresh()
    }

    /**
     * An earlier build used a different recogniser with a much larger model in
     * a different place. Anyone who installed that gets the space back.
     */
    private fun removeEarlierEngine(ctx: Context) {
        File(ctx.filesDir, "recognition/model").deleteRecursively()
        File(ctx.filesDir, "recognition/model.tmp").deleteRecursively()
        ctx.getExternalFilesDir(null)?.let { File(it, "vosk-model-ar-mgb2-0.4.zip").delete() }
    }

    /** Re-reads reality and publishes it. Safe to call at any time. */
    fun refresh() {
        val ctx = appContext ?: return
        if (inProcessDownload?.isActive == true) return

        installedModelDir(ctx)?.let {
            publish(Status.Installed(sizeOf(it)))
            return
        }

        val ids = downloadIds()
        if (ids.isNotEmpty()) {
            when (val s = queryDownloads(ctx, ids)) {
                is Status.Downloading -> {
                    publish(s)
                    schedulePoll()
                    return
                }
                is Status.Failed -> {
                    clearDownloads(ctx, ids)
                    publish(s)
                    return
                }
                is Status.Installing -> {
                    // All done according to the system; check and mark.
                    setDownloadIds(emptyList())
                    finishInstall(ctx)
                    return
                }
                else -> setDownloadIds(emptyList())
            }
        }

        if (isComplete(modelDir(ctx))) {
            finishInstall(ctx)
            return
        }
        publish(Status.Missing)
    }

    fun addListener(l: (Status) -> Unit) {
        listeners.add(l)
        l(status)
        if (status is Status.Downloading) schedulePoll()
    }

    fun removeListener(l: (Status) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(s: Status) {
        status = s
        handler.post { listeners.toList().forEach { it(s) } }
    }

    // ---------------------------------------------------------------- download

    /** Starts (or restarts) the fetch. A no-op while one is already running. */
    fun download() {
        val ctx = appContext ?: return
        if (status is Status.Downloading || status is Status.Installing) return
        if (isInstalled(ctx)) return

        val dir = modelDir(ctx)
        dir.mkdirs()
        if ((dir.usableSpace) < DOWNLOAD_BYTES * 3 / 2) {
            publish(Status.Failed(ctx.getString(R.string.model_error_space)))
            return
        }

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        val external = ctx.getExternalFilesDir(null) != null
        if (dm != null && external) {
            try {
                val ids = ArrayList<Long>()
                for ((name, _) in FILE_SIZES) {
                    val target = File(dir, name)
                    if (target.length() >= (FILE_SIZES[name] ?: 0L) * 95 / 100) continue // already here
                    target.delete() // a stale partial would be appended to
                    val request = DownloadManager.Request(Uri.parse(BASE_URL + name))
                        .setTitle(ctx.getString(R.string.model_download_title))
                        .setDescription(name)
                        .setDestinationUri(Uri.fromFile(target))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                    ids.add(dm.enqueue(request))
                }
                setDownloadIds(ids)
                if (ids.isEmpty()) {
                    finishInstall(ctx)
                } else {
                    publish(Status.Downloading(0, DOWNLOAD_BYTES))
                    schedulePoll()
                }
                return
            } catch (e: Exception) {
                // The Downloads provider is disabled on some boxes; fall through.
                Log.w(TAG, "DownloadManager unavailable, fetching in-process", e)
            }
        }
        downloadInProcess(dir)
    }

    /** Stops a running fetch and forgets the partial files. */
    fun cancelDownload() {
        val ctx = appContext ?: return
        inProcessDownload?.cancel()
        inProcessDownload = null
        clearDownloads(ctx, downloadIds())
        modelDir(ctx).deleteRecursively()
        publish(Status.Missing)
    }

    /** Removes the installed model, freeing the space. */
    fun delete() {
        val ctx = appContext ?: return
        cancelDownload()
        scope.launch {
            modelDir(ctx).deleteRecursively()
            publish(Status.Missing)
            Recitation.onModelChanged()
        }
    }

    /** From [ModelDownloadReceiver]: the system says a download finished. */
    fun onDownloadComplete(id: Long) {
        if (id !in downloadIds()) return
        refresh()
    }

    private fun downloadIds(): List<Long> =
        prefs?.modelDownloadIds.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    private fun setDownloadIds(ids: List<Long>) {
        prefs?.modelDownloadIds = ids.joinToString(",")
    }

    private fun clearDownloads(ctx: Context, ids: List<Long>) {
        if (ids.isNotEmpty()) {
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.let { dm ->
                runCatching { dm.remove(*ids.toLongArray()) }
            }
        }
        setDownloadIds(emptyList())
    }

    /** The five downloads as one status: failed if any did, done if all did. */
    private fun queryDownloads(ctx: Context, ids: List<Long>): Status? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray())) }.getOrNull()
            ?: return null
        var seen = 0
        var done = 0L
        var allSuccessful = true
        cursor.use {
            while (it.moveToNext()) {
                seen++
                val state = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (state) {
                    DownloadManager.STATUS_FAILED -> {
                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        return Status.Failed(ctx.getString(R.string.model_error_download, reason))
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> Unit
                    else -> allSuccessful = false
                }
                done += it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
            }
        }
        if (seen == 0) return null // the system forgot them
        // Files that were already complete before this batch count as done.
        val alreadyHad = FILE_SIZES.values.sum() - DOWNLOAD_BYTES + 0L
        return if (allSuccessful) Status.Installing else Status.Downloading(done + alreadyHad, DOWNLOAD_BYTES)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) return
            refresh()
            if (status is Status.Downloading) handler.postDelayed(this, POLL_MS)
        }
    }

    private fun schedulePoll() {
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    /**
     * The fallback fetch, for devices without a DownloadManager. Lives only as
     * long as the process; partial files are resumed with a Range request the
     * next time it is asked for.
     */
    private fun downloadInProcess(dir: File) {
        val ctx = appContext ?: return
        inProcessDownload?.cancel()
        inProcessDownload = scope.launch {
            try {
                var doneBefore = 0L
                publish(Status.Downloading(0, DOWNLOAD_BYTES))
                for ((name, expected) in FILE_SIZES) {
                    val target = File(dir, name)
                    var have = if (target.exists()) target.length() else 0L
                    if (have >= expected * 95 / 100) {
                        doneBefore += have
                        continue
                    }
                    val connection = (URL(BASE_URL + name).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        if (have > 0) setRequestProperty("Range", "bytes=$have-")
                    }
                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_OK) {
                        have = 0L
                        target.delete()
                    } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                        throw IOException("HTTP $code for $name")
                    }
                    connection.inputStream.use { input ->
                        FileOutputStream(target, have > 0).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var lastReport = 0L
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                have += read
                                if (have - lastReport > 1_000_000L) {
                                    lastReport = have
                                    publish(Status.Downloading(doneBefore + have, DOWNLOAD_BYTES))
                                }
                            }
                        }
                    }
                    if (!isActive) return@launch
                    doneBefore += have
                }
                finishInstall(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "in-process download failed", e)
                publish(Status.Failed(ctx.getString(R.string.model_error_network)))
            }
        }
    }

    // ---------------------------------------------------------------- install

    /** Everything is on disk: check the sizes and write the marker. */
    private fun finishInstall(ctx: Context) {
        val dir = modelDir(ctx)
        if (!isComplete(dir)) {
            Log.w(TAG, "model files incomplete after download")
            dir.deleteRecursively()
            publish(Status.Failed(ctx.getString(R.string.model_error_unpack)))
            return
        }
        readyMarker(ctx).writeText(MODEL_NAME)
        publish(Status.Installed(sizeOf(dir)))
        Recitation.onModelChanged()
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private const val TAG = "ModelStore"
    private const val POLL_MS = 1000L
}
