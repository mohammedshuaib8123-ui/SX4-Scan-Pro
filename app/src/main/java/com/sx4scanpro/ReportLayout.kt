package com.sx4scanpro

/** Text layout for the PDF reports, kept free of Android so it can be tested. Nothing is ever cut off: long lines wrap, long reports paginate. */
internal object ReportLayout {
    /** Wraps every line to [width] characters. Continuation lines keep the original indent plus four spaces. No character is dropped. */
    fun wrap(lines: List<String>, width: Int = 86): List<String> {
        require(width >= 20) { "width too small" }
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.length <= width) { out += line; continue }
            val indent = line.takeWhile { it == ' ' }
            val contIndent = indent + "    "
            var rest = line.removePrefix(indent)
            var first = true
            while (true) {
                val prefix = if (first) indent else contIndent
                val room = width - prefix.length
                if (rest.length <= room) { out += prefix + rest; break }
                var cut = rest.lastIndexOf(' ', room)
                if (cut <= 0) cut = room // a single token longer than the line (raw hex): hard break
                out += prefix + rest.substring(0, cut).trimEnd()
                rest = rest.substring(cut).trimStart()
                first = false
                if (rest.isEmpty()) break
            }
        }
        return out
    }

    /** Splits lines into pages of at most [perPage] lines. An empty report still has one page. */
    fun paginate(lines: List<String>, perPage: Int): List<List<String>> {
        require(perPage >= 1) { "perPage must be positive" }
        if (lines.isEmpty()) return listOf(emptyList())
        return lines.chunked(perPage)
    }
}
