package com.example.videocaptioner

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class ExtractResult(val success: Boolean, val sampleRate: Int)

object AudioExtractor {
    private const val TAG = "AudioExtractor"

    fun extract(videoPath: String, outputWav: String): ExtractResult {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(videoPath)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) return ExtractResult(false, 0)
            extractor.selectTrack(idx)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val pcm = File(outputWav + ".pcm")
            val pcmOut = FileOutputStream(pcm)
            val info = MediaCodec.BufferInfo()
            var eos = false
            while (!eos) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear()
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else { codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val arr = ByteArray(info.size); ob.get(arr)
                    pcmOut.write(arr)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
            codec.stop(); codec.release(); codec = null
            extractor.release(); extractor = null
            pcmOut.close()

            val wav = File(outputWav)
            val wavOut = FileOutputStream(wav)
            writeWavHeader(wavOut, 0, rate, 1)
            val pcmIn = pcm.inputStream()
            val frameSize = 2 * ch
            val bufIn = ByteArray(4096)
            val bufOut = ByteArray(2048)
            var total = 0L
            var n: Int
            while (pcmIn.read(bufIn).also { n = it } > 0) {
                val frames = n / frameSize
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until ch) {
                        val lo = bufIn[i * frameSize + c * 2].toInt() and 0xff
                        val hi = bufIn[i * frameSize + c * 2 + 1].toInt() and 0xff
                        sum += (hi shl 8) or lo
                    }
                    val avg = sum / ch
                    bufOut[i * 2] = (avg and 0xff).toByte()
                    bufOut[i * 2 + 1] = ((avg shr 8) and 0xff).toByte()
                }
                wavOut.write(bufOut, 0, frames * 2)
                total += frames * 2L
            }
            pcmIn.close(); wavOut.close(); pcm.delete()
            fixWavHeader(wav, total, rate, 1)
            return ExtractResult(true, rate)
        } catch (e: Exception) {
            Log.e(TAG, "error", e)
            return ExtractResult(false, 0)
        }
    }

    private fun writeWavHeader(o: FileOutputStream, pcm: Long, rate: Int, ch: Int) {
        val br = rate * ch * 2
        val h = ByteArray(44)
        h[0]='R'.code.toByte(); h[1]='I'.code.toByte(); h[2]='F'.code.toByte(); h[3]='F'.code.toByte()
        putInt(h,4,(pcm+36).toInt())
        h[8]='W'.code.toByte(); h[9]='A'.code.toByte(); h[10]='V'.code.toByte(); h[11]='E'.code.toByte()
        h[12]='f'.code.toByte(); h[13]='m'.code.toByte(); h[14]='t'.code.toByte(); h[15]=' '.code.toByte()
        putInt(h,16,16); putShort(h,20,1); putShort(h,22,ch); putInt(h,24,rate); putInt(h,28,br)
        putShort(h,32,ch*2); putShort(h,34,16)
        h[36]='d'.code.toByte(); h[37]='a'.code.toByte(); h[38]='t'.code.toByte(); h[39]='a'.code.toByte()
        putInt(h,40,pcm.toInt())
        o.write(h)
    }

    private fun fixWavHeader(f: File, pcm: Long, rate: Int, ch: Int) {
        val r = RandomAccessFile(f, "rw")
        r.seek(4); r.write(i2b((pcm+36).toInt()))
        r.seek(28); r.write(i2b(rate*ch*2))
        r.seek(40); r.write(i2b(pcm.toInt()))
        r.close()
    }

    private fun putInt(a: ByteArray, o: Int, v: Int) {
        a[o]=(v and 0xff).toByte(); a[o+1]=((v shr 8) and 0xff).toByte()
        a[o+2]=((v shr 16) and 0xff).toByte(); a[o+3]=((v shr 24) and 0xff).toByte()
    }
    private fun putShort(a: ByteArray, o: Int, v: Int) {
        a[o]=(v and 0xff).toByte(); a[o+1]=((v shr 8) and 0xff).toByte()
    }
    private fun i2b(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )
}
