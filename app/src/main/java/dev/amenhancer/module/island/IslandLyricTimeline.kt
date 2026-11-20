package dev.amenhancer.module.island

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/** Reads timed main lines from the current playback document. No network or XML entities. */
internal class IslandLyricTimeline private constructor(private val lines: List<Line>) {
    private data class Line(val startMs: Long, val endMs: Long?, val text: String)

    fun at(positionMs: Long): String? {
        val index = lines.binarySearchBy(positionMs) { it.startMs }
        val line = lines.getOrNull(if (index >= 0) index else -index - 2) ?: return null
        return line.text.takeIf { line.endMs == null || positionMs < line.endMs }
    }

    companion object {
        private val auxiliaryRoles = setOf("x-translation", "x-transliteration", "x-bg")
        private val whitespace = Regex("\\s+")

        fun parse(ttml: String): IslandLyricTimeline = runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(StringReader(ttml))
            val lines = mutableListOf<Line>()
            var skippedDepth = 0
            var paragraphDepth = 0
            var start: Long? = null
            var end: Long? = null
            val text = StringBuilder()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> if (skippedDepth == 0) {
                        val auxiliary = (0 until parser.attributeCount).any {
                            parser.getAttributeName(it) == "role" && parser.getAttributeValue(it) in auxiliaryRoles
                        }
                        if (auxiliary || parser.name == "head") skippedDepth = parser.depth
                        else if (parser.name == "p") {
                            paragraphDepth = parser.depth
                            start = millis(parser.getAttributeValue(null, "begin"))
                            end = millis(parser.getAttributeValue(null, "end"))
                            text.clear()
                        } else if (parser.name == "br" && paragraphDepth != 0) text.append(' ')
                    }
                    XmlPullParser.TEXT -> if (skippedDepth == 0 && paragraphDepth != 0) text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        if (skippedDepth == parser.depth) skippedDepth = 0
                        else if (paragraphDepth == parser.depth) {
                            val line = text.toString().trim().replace(whitespace, " ").take(180)
                            start?.takeIf { line.isNotBlank() }?.let { lines += Line(it, end, line) }
                            paragraphDepth = 0
                        }
                    }
                }
                parser.next()
            }
            IslandLyricTimeline(lines.sortedBy { it.startMs })
        }.getOrElse { IslandLyricTimeline(emptyList()) }

        private fun millis(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toLong()?.takeIf { it >= 0 }
            val parts = raw.removeSuffix("s").split(':')
            if (parts.size !in 1..3) return null
            var seconds = 0.0
            for (part in parts) {
                val value = part.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
                seconds = seconds * 60 + value
            }
            return (seconds * 1000.0).toLong()
        }
    }
}
