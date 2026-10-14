package com.example.videocaptioner

import java.io.File

object SrtGenerator {
    fun generate(segments: List<CaptionSegment>, outputSrt: String) {
        val sb = StringBuilder()
        segments.forEachIndexed { i, seg ->
            sb.append("${i + 1}\n")
            sb.append("${formatTime(seg.startMs)} --> ${formatTime(seg.endMs)}\n")
            sb.append("${seg.text}\n\n")
        }
        File(outputSrt).writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
