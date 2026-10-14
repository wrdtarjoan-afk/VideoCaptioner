package com.example.videocaptioner

import java.io.File

object VideoBurner {
    fun burn(videoPath: String, srtPath: String, outputPath: String): Boolean {
        return try {
            File(videoPath).copyTo(File(outputPath), overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }
}
