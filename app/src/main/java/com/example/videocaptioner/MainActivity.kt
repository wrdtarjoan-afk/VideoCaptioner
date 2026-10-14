package com.example.videocaptioner

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private var selectedVideoUri: Uri? = null
    private var outputFile: File? = null
    private lateinit var tvVideoInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnPlayResult: Button
    private lateinit var progressBar: ProgressBar

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedVideoUri = result.data?.data
            selectedVideoUri?.let { uri ->
                val (name, size) = getFileInfo(uri)
                tvVideoInfo.text = "$name - $size"
                tvStatus.text = "Video selected"
                btnStart.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvVideoInfo = findViewById(R.id.tvVideoInfo)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnPlayResult = findViewById(R.id.btnPlayResult)
        progressBar = findViewById(R.id.progressBar)
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
            pickVideo.launch(intent)
        }
        btnStart.setOnClickListener { startFullPipeline() }
        btnPlayResult.setOnClickListener { playResult() }
    }

    private fun startFullPipeline() {
        val uri = selectedVideoUri ?: return
        btnStart.isEnabled = false
        btnPlayResult.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Starting..."
        lifecycleScope.launch {
            try {
                setStatus("[1/5] Copying video...")
                val videoFile = withContext(Dispatchers.IO) {
                    copyUriToFile(uri, File(filesDir, "input.mp4"))
                }
                setStatus("[1/5] Copied ${videoFile.length() / 1024 / 1024} MB")

                setStatus("[2/5] Extracting audio...")
                val wavFile = File(filesDir, "audio.wav")
                val extractResult = withContext(Dispatchers.IO) {
                    AudioExtractor.extract(videoFile.absolutePath, wavFile.absolutePath)
                }
                if (!extractResult.success) {
                    setStatus("Failed to extract audio")
                    return@launch
                }
                setStatus("[2/5] Audio done (${extractResult.sampleRate} Hz, ${wavFile.length()/1024} KB)")

                setStatus("[3/5] Transcribing...")
                val segments = withContext(Dispatchers.IO) {
                    SpeechToText.transcribe(this@MainActivity, wavFile.absolutePath, extractResult.sampleRate)
                }
                if (segments.isEmpty()) {
                    setStatus("No speech detected")
                    return@launch
                }
                setStatus("[3/5] ${segments.size} segments")

                setStatus("[4/5] Generating SRT...")
                val srtFile = File(filesDir, "subs.srt")
                SrtGenerator.generate(segments, srtFile.absolutePath)
                setStatus("[4/5] SRT ready: ${srtFile.absolutePath}")

                setStatus("[5/5] Copying video...")
                val outFile = File(filesDir, "output.mp4")
                val ok = withContext(Dispatchers.IO) {
                    VideoBurner.burn(videoFile.absolutePath, srtFile.absolutePath, outFile.absolutePath)
                }
                if (ok && outFile.exists()) {
                    outputFile = outFile
                    setStatus("DONE\nVideo: ${outFile.absolutePath}\nSRT: ${srtFile.absolutePath}")
                    btnPlayResult.isEnabled = true
                } else {
                    setStatus("Failed to copy video")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "pipeline error", e)
                setStatus("Error: ${e.message}")
            } finally {
                progressBar.visibility = View.GONE
                btnStart.isEnabled = true
            }
        }
    }

    private fun playResult() {
        val f = outputFile ?: return
        try {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnPreparedListener { start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { tvStatus.text = msg }
    }

    private fun copyUriToFile(uri: Uri, dest: File): File {
        contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun getFileInfo(uri: Uri): Pair<String, String> {
        var name = "unknown"
        var size = "0"
        contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) {
                    val bytes = cursor.getLong(sizeIdx)
                    size = "%.2f MB".format(bytes / 1024.0 / 1024.0)
                }
            }
        }
        return name to size
    }
}
