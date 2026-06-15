package dji.sampleV5.aircraft.webrtc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes Bitmap frames from MockMp4VideoCapturer to H.264 via MediaCodec,
 * packetizes the NAL units into RTP packets, and sends them over a UDP DatagramSocket
 * to the MediaMTX `udp://` source path.
 *
 * Usage:
 *   val publisher = UdpRtpPublisher(context, "192.168.8.100", 8004, width=1280, height=720, fps=15)
 *   publisher.start()
 *   publisher.pushFrame(bitmap)   // call from MockMp4VideoCapturer's frame loop
 *   publisher.stop()
 *
 * MediaMTX must have the path configured as:
 *   paths:
 *     drone_1_udp:
 *       source: udp://localhost:8004
 */
class UdpRtpPublisher(
    private val context: Context,
    private val remoteHost: String,
    private val remotePort: Int = 8004,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 15,
    private val bitrate: Int = 2_000_000
) {
    companion object {
        private const val TAG = "UdpRtpPublisher"
        private const val MIME = "video/avc"
        private const val RTP_HEADER_SIZE = 12
        private const val RTP_MTU = 1300          // keep well under 1440 MediaMTX limit
        private const val RTP_VERSION = 0x80      // V=2, P=0, X=0, CC=0
        private const val RTP_PAYLOAD_TYPE = 96   // dynamic H.264
    }

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var remoteAddress: InetAddress? = null

    private val isRunning = AtomicBoolean(false)
    private var sequenceNumber: Int = 0
    private var ssrc: Int = (Math.random() * 0xFFFFFFFFL).toInt()
    private var startTimeUs: Long = 0L

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        try {
            remoteAddress = InetAddress.getByName(remoteHost)
            socket = DatagramSocket()

            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)   // keyframe every 2 s
            }

            codec = MediaCodec.createEncoderByType(MIME).also { c ->
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
            }

            startTimeUs = System.nanoTime() / 1000L
            Log.i(TAG, "UdpRtpPublisher started → udp://$remoteHost:$remotePort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UdpRtpPublisher", e)
            isRunning.set(false)
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            codec?.apply { stop(); release() }
            codec = null
            socket?.close()
            socket = null
            Log.i(TAG, "UdpRtpPublisher stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error during stop", e)
        }
    }

    // -----------------------------------------------------------------------
    // Frame input
    // -----------------------------------------------------------------------

    /**
     * Encode a Bitmap frame and send any ready output NAL units via UDP RTP.
     * Call this from the same thread (or a single background thread) that drives
     * MockMp4VideoCapturer's frame loop.
     */
    fun pushFrame(bitmap: Bitmap) {
        val c = codec ?: return
        if (!isRunning.get()) return

        try {
            val inputIndex = c.dequeueInputBuffer(0L)
            if (inputIndex >= 0) {
                val inputBuffer = c.getInputBuffer(inputIndex) ?: return
                val yuv = bitmapToNv12(bitmap, width, height)
                inputBuffer.clear()
                inputBuffer.put(yuv)
                val pts = (System.nanoTime() / 1000L) - startTimeUs
                c.queueInputBuffer(inputIndex, 0, yuv.size, pts, 0)
            }

            drainEncoder(c)
        } catch (e: Exception) {
            Log.e(TAG, "Error in pushFrame", e)
        }
    }

    // -----------------------------------------------------------------------
    // Encoder drain → RTP send
    // -----------------------------------------------------------------------

    private fun drainEncoder(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val outIndex = c.dequeueOutputBuffer(info, 0L)
            if (outIndex < 0) break

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // SPS/PPS config packet – send it as-is so the receiver can decode
                val buf = c.getOutputBuffer(outIndex)
                if (buf == null) {
                    c.releaseOutputBuffer(outIndex, false)
                    continue
                }
                val configData = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(configData)
                sendRtpPackets(configData, info.presentationTimeUs, isKeyFrame = true)
                c.releaseOutputBuffer(outIndex, false)
                continue
            }

            val buf = c.getOutputBuffer(outIndex)
            if (buf == null) {
                c.releaseOutputBuffer(outIndex, false)
                continue
            }
            val nalData = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(nalData)
            c.releaseOutputBuffer(outIndex, false)

            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            sendRtpPackets(nalData, info.presentationTimeUs, isKeyFrame = isKey)
        }
    }

    // -----------------------------------------------------------------------
    // RTP packetisation (single-NAL or FU-A fragmentation)
    // -----------------------------------------------------------------------

    private fun sendRtpPackets(nalData: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        // Strip 4-byte or 3-byte Annex-B start codes before packetisation
        val start = when {
            nalData.size >= 4 &&
                nalData[0] == 0.toByte() && nalData[1] == 0.toByte() &&
                nalData[2] == 0.toByte() && nalData[3] == 1.toByte() -> 4
            nalData.size >= 3 &&
                nalData[0] == 0.toByte() && nalData[1] == 0.toByte() &&
                nalData[2] == 1.toByte() -> 3
            else -> 0
        }
        val nal = if (start > 0) nalData.copyOfRange(start, nalData.size) else nalData
        if (nal.isEmpty()) return

        // RTP timestamp: 90 kHz clock
        val rtpTs = (ptsUs * 90L / 1_000L).toInt()

        if (nal.size <= RTP_MTU - RTP_HEADER_SIZE) {
            // Single NAL unit packet
            sendSingleRtpPacket(nal, rtpTs, marker = true)
        } else {
            // FU-A fragmentation (RFC 6184 §5.8)
            sendFuAPackets(nal, rtpTs)
        }
    }

    private fun sendSingleRtpPacket(nal: ByteArray, rtpTs: Int, marker: Boolean) {
        val packet = ByteArray(RTP_HEADER_SIZE + nal.size)
        buildRtpHeader(packet, marker, rtpTs)
        System.arraycopy(nal, 0, packet, RTP_HEADER_SIZE, nal.size)
        udpSend(packet)
    }

    private fun sendFuAPackets(nal: ByteArray, rtpTs: Int) {
        val nalHeader = nal[0]
        val nalType = nalHeader.toInt() and 0x1F
        val fuIndicator = (nalHeader.toInt() and 0xE0) or 28  // NRI + FU-A type=28
        val maxPayload = RTP_MTU - RTP_HEADER_SIZE - 2        // 2 = FU indicator + FU header

        var offset = 1  // skip original NAL header
        while (offset < nal.size) {
            val chunkSize = minOf(maxPayload, nal.size - offset)
            val isFirst = offset == 1
            val isLast = offset + chunkSize >= nal.size
            val fuHeader = (if (isFirst) 0x80 else 0) or
                           (if (isLast)  0x40 else 0) or
                           nalType

            val packet = ByteArray(RTP_HEADER_SIZE + 2 + chunkSize)
            buildRtpHeader(packet, isLast, rtpTs)
            packet[RTP_HEADER_SIZE]     = fuIndicator.toByte()
            packet[RTP_HEADER_SIZE + 1] = fuHeader.toByte()
            System.arraycopy(nal, offset, packet, RTP_HEADER_SIZE + 2, chunkSize)
            udpSend(packet)
            offset += chunkSize
        }
    }

    private fun buildRtpHeader(packet: ByteArray, marker: Boolean, rtpTs: Int) {
        val seq = sequenceNumber++ and 0xFFFF
        packet[0] = RTP_VERSION.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or RTP_PAYLOAD_TYPE).toByte()
        packet[2] = (seq shr 8).toByte()
        packet[3] = (seq and 0xFF).toByte()
        packet[4] = (rtpTs shr 24).toByte()
        packet[5] = (rtpTs shr 16).toByte()
        packet[6] = (rtpTs shr 8).toByte()
        packet[7] = (rtpTs and 0xFF).toByte()
        packet[8]  = (ssrc shr 24).toByte()
        packet[9]  = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
    }

    private fun udpSend(packet: ByteArray) {
        try {
            val addr = remoteAddress ?: return
            val dp = DatagramPacket(packet, packet.size, addr, remotePort)
            socket?.send(dp)
        } catch (e: Exception) {
            if (isRunning.get()) Log.w(TAG, "UDP send error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Colour conversion: Bitmap → NV12 (matches COLOR_FormatYUV420Flexible)
    // -----------------------------------------------------------------------

    private fun bitmapToNv12(bitmap: Bitmap, w: Int, h: Int): ByteArray {
        val scaled = if (bitmap.width != w || bitmap.height != h)
            Bitmap.createScaledBitmap(bitmap, w, h, false)
        else
            bitmap

        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val yuv = ByteArray(w * h * 3 / 2)
        val uvOffset = w * h

        for (j in 0 until h) {
            for (i in 0 until w) {
                val px = pixels[j * w + i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b =  px         and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[j * w + i] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uvIdx = uvOffset + (j / 2) * w + i
                    yuv[uvIdx]     = u.coerceIn(0, 255).toByte()
                    yuv[uvIdx + 1] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }
}
