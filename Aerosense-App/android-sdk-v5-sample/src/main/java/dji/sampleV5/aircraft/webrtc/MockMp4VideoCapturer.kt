package dji.sampleV5.aircraft.webrtc

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MockMp4VideoCapturer(
    private val droneName: String,
    private val assetPath: String = "mock_video/Raw_drone_footage_round_2.mp4"
) : VideoCapturer {

    companion object {
        private const val TAG = "MockMp4VideoCapturer"
        private const val DEFAULT_DURATION_US = 10_000_000L
    }

    var metadataListener: DJIV5VideoCapturer.FrameMetadataListener? = null
    var metricsListener: ((WebRTCStreamMetrics) -> Unit)? = null

    /** Called on every captured frame with the raw bitmap. Used for in-app preview. */
    var bitmapFrameListener: ((Bitmap) -> Unit)? = null

    /** Optional UDP/RTP publisher. Set before startCapture() to stream frames via UDP. */
    var udpPublisher: UdpRtpPublisher? = null

    private var appContext: Context? = null
    private var capturerObserver: CapturerObserver? = null
    private var retriever: MediaMetadataRetriever? = null
    private var executor: ScheduledExecutorService? = null
    private val isCapturing = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val observerLock = Object()
    private val frameWaitLock = Object()
    private val cacheLock = Any()
    private val frameCache = mutableListOf<Bitmap>()
    private val framePtsUs = mutableListOf<Long>() // presentation timestamp per cached frame

    @Volatile private var targetWidth = WebRTCMediaOptions.hd().videoResolutionWidth
    @Volatile private var targetHeight = WebRTCMediaOptions.hd().videoResolutionHeight
    @Volatile private var targetFps = WebRTCMediaOptions.hd().fps
    @Volatile private var durationUs = DEFAULT_DURATION_US
    @Volatile private var sourceWidth = 0
    @Volatile private var sourceHeight = 0
    @Volatile private var lastError: String? = null

    private var sentFramesInWindow = 0L
    private var processingTimeNsInWindow = 0L
    private var lastMetricsAtNs = System.nanoTime()

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        appContext = applicationContext.applicationContext
        synchronized(observerLock) {
            this.capturerObserver = capturerObserver
        }
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        applyResolutionRequest(width, height)
        targetFps = framerate.coerceIn(1, 60)

        if (!isCapturing.compareAndSet(false, true)) return

        try {
            val context = appContext ?: throw IllegalStateException("Capturer not initialized")
            retriever = openRetriever(context)
            loadFrameCache()

            if (!isCapturing.get()) return
            synchronized(observerLock) {
                capturerObserver?.onCapturerStarted(true)
            }
            scheduleFrameLoop()
            Log.i(TAG, "Started MP4 mock capture: ${effectiveTargetWidth()}x${effectiveTargetHeight()}@${targetFps}fps from $assetPath")
        } catch (e: Exception) {
            lastError = e.message
            isCapturing.set(false)
            synchronized(observerLock) {
                capturerObserver?.onCapturerStarted(false)
            }
            Log.e(TAG, "Failed to start mock capture: ${e.message}", e)
        }
    }

    private fun emitFrameDirect(sourceBitmap: Bitmap) {
        if (!isCapturing.get()) return
        val frameStartNs = System.nanoTime()
        try {
            val nextFrameNumber = frameCounter.get() + 1L
            val buffer = bitmapToI420(sourceBitmap)

            val frameNumber = frameCounter.incrementAndGet()
            synchronized(frameWaitLock) {
                frameWaitLock.notifyAll()
            }

            metadataListener?.onFrameMetadata(
                TelemetryProvider.captureMetadata(
                    frameNumber = frameNumber,
                    timestampNs = frameStartNs,
                    frameWidth = effectiveTargetWidth(),
                    frameHeight = effectiveTargetHeight(),
                    droneName = droneName
                )
            )

            val videoFrame = VideoFrame(buffer, 0, frameStartNs)
            try {
                synchronized(observerLock) {
                    if (isCapturing.get()) {
                        capturerObserver?.onFrameCaptured(videoFrame)
                    }
                }
            } finally {
                videoFrame.release()
            }

            // Also push raw frame to UDP/RTP publisher if one is attached
            udpPublisher?.pushFrame(sourceBitmap)

            // Deliver to in-app preview (same frame, same timing as WebRTC)
            bitmapFrameListener?.invoke(sourceBitmap)

            sentFramesInWindow++
            processingTimeNsInWindow += System.nanoTime() - frameStartNs
            maybeEmitMetrics(System.nanoTime())
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "Error emitting mock MP4 frame: ${e.message}", e)
        }
    }

    private fun openRetriever(context: Context): MediaMetadataRetriever {
        return MediaMetadataRetriever().apply {
            context.assets.openFd(assetPath).use { descriptor ->
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            durationUs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.times(1000L)
                ?.takeIf { it > 0L }
                ?: DEFAULT_DURATION_US
            sourceWidth = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            sourceHeight = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        }
    }

    private fun loadFrameCache() {
        val activeRetriever = retriever ?: return
        val cachedFrames = mutableListOf<Bitmap>()
        val cachedPts = mutableListOf<Long>()
        val durationSec = durationUs / 1_000_000.0
        // Sample at targetFps across the full duration — no arbitrary cap
        val frameCount = (targetFps * durationSec).toInt().coerceIn(10, 600)
        val duration = durationUs.coerceAtLeast(1_000_000L)
        val outputWidth = effectiveTargetWidth()
        val outputHeight = effectiveTargetHeight()

        for (index in 0 until frameCount) {
            val presentationUs = (duration * index) / frameCount
            val decoded = activeRetriever.getFrameAtTime(presentationUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (decoded == null) {
                Log.w(TAG, "Could not decode cached mock frame $index at ${presentationUs}us")
                continue
            }
            val prepared = prepareBitmap(decoded, outputWidth, outputHeight)
            if (prepared !== decoded) decoded.recycle()
            cachedFrames.add(prepared)
            cachedPts.add(presentationUs)
        }

        if (cachedFrames.isEmpty()) {
            throw IllegalStateException("Could not decode any frames from $assetPath")
        }

        synchronized(cacheLock) {
            clearFrameCacheLocked()
            frameCache.addAll(cachedFrames)
            framePtsUs.clear()
            framePtsUs.addAll(cachedPts)
        }
        Log.i(TAG, "Cached ${cachedFrames.size} mock MP4 frames at ${outputWidth}x${outputHeight} duration=${durationSec}s")
    }

    @Synchronized
    private fun scheduleFrameLoop() {
        stopExecutor(waitForTermination = true)
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AeroSenseMockMp4Capturer").apply { isDaemon = true }
        }.also { scheduledExecutor ->
            scheduledExecutor.execute(::ptsFrameLoop)
        }
    }

    /** Emits frames paced by their PTS so playback is always real-time. */
    private fun ptsFrameLoop() {
        var loopStartWallNs = System.nanoTime()
        var frameIndex = 0
        while (isCapturing.get()) {
            val pair = synchronized(cacheLock) {
                if (frameCache.isEmpty() || framePtsUs.isEmpty()) null
                else {
                    val i = frameIndex % frameCache.size
                    Pair(frameCache[i], framePtsUs[i])
                }
            }
            if (pair == null) {
                Thread.sleep(5)
                continue
            }
            val (bitmap, pts) = pair

            // On each new loop reset the wall-clock anchor so drift doesn't accumulate
            if (frameIndex % frameCache.size == 0) {
                loopStartWallNs = System.nanoTime()
            }

            val targetWallNs = loopStartWallNs + pts * 1_000L
            val nowNs = System.nanoTime()
            val sleepNs = targetWallNs - nowNs
            if (sleepNs > 0) {
                try { Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt()) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }

            emitFrameDirect(bitmap)
            frameIndex++
        }
    }

    private fun prepareBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        val argbSource = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        return if (argbSource.width == width && argbSource.height == height) {
            argbSource
        } else {
            Bitmap.createScaledBitmap(argbSource, width, height, true).also {
                if (argbSource !== source) argbSource.recycle()
            }
        }
    }

    private fun bitmapToI420(bitmap: Bitmap): JavaI420Buffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val buffer = JavaI420Buffer.allocate(width, height)
        val dataY = buffer.dataY
        val dataU = buffer.dataU
        val dataV = buffer.dataV
        val strideY = buffer.strideY
        val strideU = buffer.strideU
        val strideV = buffer.strideV

        for (row in 0 until height) {
            for (column in 0 until width) {
                val pixel = pixels[row * width + column]
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF

                val yValue = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                dataY.put(row * strideY + column, yValue.coerceIn(0, 255).toByte())

                if (row % 2 == 0 && column % 2 == 0) {
                    val uValue = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                    val vValue = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                    val chromaIndex = (row / 2) * strideU + (column / 2)
                    dataU.put(chromaIndex, uValue.coerceIn(0, 255).toByte())
                    dataV.put((row / 2) * strideV + (column / 2), vValue.coerceIn(0, 255).toByte())
                }
            }
        }
        return buffer
    }

    private fun maybeEmitMetrics(nowNs: Long) {
        val elapsedNs = nowNs - lastMetricsAtNs
        if (elapsedNs < 1_000_000_000L) return
        val elapsedSeconds = elapsedNs / 1_000_000_000.0
        val sentFrames = sentFramesInWindow
        val outputFps = sentFrames / elapsedSeconds
        val averageProcessingMs = if (sentFrames > 0) processingTimeNsInWindow / sentFrames / 1_000_000.0 else 0.0
        sentFramesInWindow = 0L
        processingTimeNsInWindow = 0L
        lastMetricsAtNs = nowNs

        metricsListener?.invoke(
            WebRTCStreamMetrics(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = effectiveTargetWidth(),
                outputHeight = effectiveTargetHeight(),
                requestedWidth = targetWidth,
                requestedHeight = targetHeight,
                targetFps = targetFps,
                inputFps = outputFps,
                outputFps = outputFps,
                droppedFps = 0.0,
                averageFrameProcessingMs = averageProcessingMs,
                totalFrames = frameCounter.get(),
                observerCount = if (capturerObserver != null) 1 else 0,
                activeCamera = "MOCK_MP4",
                status = if (isCapturing.get()) "mock" else "idle",
                lastError = lastError
            )
        )
    }

    fun changeResolution(width: Int, height: Int) {
        val previousWidth = targetWidth
        val previousHeight = targetHeight
        applyResolutionRequest(width, height)
        val nextWidth = targetWidth
        val nextHeight = targetHeight
        if (previousWidth == nextWidth && previousHeight == nextHeight) return
        Log.d(
            TAG,
            "Changing mock target resolution: ${if (previousWidth > 0 && previousHeight > 0) "${previousWidth}x${previousHeight}" else "native"} -> ${if (nextWidth > 0 && nextHeight > 0) "${nextWidth}x${nextHeight}" else "native"}"
        )
        if (isCapturing.get()) {
            executor?.execute {
                runCatching { loadFrameCache() }
                    .onFailure { error ->
                        lastError = error.message
                        Log.e(TAG, "Failed to reload mock frame cache: ${error.message}", error)
                    }
            }
        }
    }

    fun changeFrameRate(fps: Int) {
        targetFps = fps.coerceIn(1, 60)
        if (isCapturing.get()) scheduleFrameLoop()
    }

    fun totalOutputFrames(): Long = frameCounter.get()

    fun waitForOutputFrameAfter(frameCount: Long, timeoutMs: Long): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        synchronized(frameWaitLock) {
            while (frameCounter.get() <= frameCount) {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0L) return false
                runCatching { frameWaitLock.wait(remainingMs) }
            }
        }
        return true
    }

    override fun stopCapture() {
        if (!isCapturing.compareAndSet(true, false)) return
        stopExecutor(waitForTermination = true)
        retriever?.release()
        retriever = null
        synchronized(cacheLock) {
            clearFrameCacheLocked()
        }
        synchronized(observerLock) {
            capturerObserver?.onCapturerStopped()
        }
        Log.i(TAG, "Stopped MP4 mock capture")
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        changeResolution(width, height)
        changeFrameRate(framerate)
    }

    private fun applyResolutionRequest(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            targetWidth = 0
            targetHeight = 0
            return
        }
        targetWidth = even(width.coerceAtLeast(2))
        targetHeight = even(height.coerceAtLeast(2))
    }

    private fun effectiveTargetWidth(): Int = if (targetWidth > 0) targetWidth else even(sourceWidth.coerceAtLeast(2))

    private fun effectiveTargetHeight(): Int = if (targetHeight > 0) targetHeight else even(sourceHeight.coerceAtLeast(2))

    override fun dispose() {
        stopCapture()
        synchronized(observerLock) {
            capturerObserver = null
        }
        appContext = null
        metadataListener = null
        metricsListener = null
        bitmapFrameListener = null
    }

    override fun isScreencast(): Boolean = false

    private fun even(value: Int): Int = (value - value % 2).coerceAtLeast(2)

    @Synchronized
    private fun stopExecutor(waitForTermination: Boolean) {
        val activeExecutor = executor ?: return
        executor = null
        activeExecutor.shutdownNow()
        if (!waitForTermination || Thread.currentThread().name == "AeroSenseMockMp4Capturer") return
        runCatching {
            if (!activeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for mock frame executor to stop")
            }
        }.onFailure { error ->
            Log.d(TAG, "Interrupted while stopping mock frame executor: ${error.message}")
            Thread.currentThread().interrupt()
        }
    }

    private fun clearFrameCacheLocked() {
        frameCache.forEach { it.recycle() }
        frameCache.clear()
        framePtsUs.clear()
    }
}