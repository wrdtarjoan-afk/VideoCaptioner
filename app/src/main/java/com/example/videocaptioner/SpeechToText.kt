package com.example.videocaptioner

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream

data class CaptionSegment(val startMs: Long, val endMs: Long, val text: String)

object SpeechToText {
    private const val TAG = "SpeechToText"

    fun transcribe(context: Context, wavFile: String, sampleRate: Int): List<CaptionSegment> {
        val segments = mutableListOf<CaptionSegment>()
        val modelDir = File(context.filesDir, "vosk-model")
        if (!File(modelDir, "am").exists() && !File(modelDir, "conf").exists()) {
            Log.d(TAG, "Copying model from assets...")
            modelDir.deleteRecursively()
            modelDir.mkdirs()
            copyAssetFolder(context, "model-ar", modelDir.absolutePath)
        }
        val model = Model(modelDir.absolutePath)
        val rec = Recognizer(model, sampleRate.toFloat())
        val fis = FileInputStream(wavFile)
        fis.skip(44)
        val buffer = ByteArray(4096)
        var totalBytes = 0L
        var lastEndMs = 0L
        val bytesPerSec = sampleRate * 2
        try {
            while (true) {
                val n = fis.read(buffer)
                if (n < 0) break
                totalBytes += n
                if (rec.acceptWaveForm(buffer, n)) {
                    val json = JSONObject(rec.result)
                    val text = json.optString("text").trim()
                    if (text.isNotEmpty()) {
                        val endMs = totalBytes * 1000L / bytesPerSec
                        segments.add(CaptionSegment(lastEndMs, endMs, text))
                        lastEndMs = endMs
                    }
                }
            }
            val finalJson = JSONObject(rec.finalResult)
            val finalText = finalJson.optString("text").trim()
            if (finalText.isNotEmpty()) {
                val endMs = totalBytes * 1000L / bytesPerSec
                segments.add(CaptionSegment(lastEndMs, endMs, finalText))
            }
        } catch (e: Exception) {
            Log.e(TAG, "transcribe error", e)
        } finally {
            fis.close(); rec.close(); model.close()
        }
        return segments
    }

    private fun copyAssetFolder(context: Context, src: String, dst: String) {
        val assets = context.assets
        val list = assets.list(src) ?: return
        File(dst).mkdirs()
        if (list.isEmpty()) {
            assets.open(src).use { input ->
                File(dst).outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            for (item in list) copyAssetFolder(context, "$src/$item", "$dst/$item")
        }
    }
}
