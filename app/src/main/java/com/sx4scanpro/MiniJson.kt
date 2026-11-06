package com.sx4scanpro

/** Small JSON writer/parser so profiles can be saved and tested without Android's org.json. Supports objects, arrays, strings, numbers, booleans, null. */
internal object MiniJson {
    fun write(v: Any?, indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val pad2 = "  ".repeat(indent + 1)
        return when (v) {
            null -> "null"
            is Boolean, is Int, is Long -> v.toString()
            is Double -> if (v.isNaN() || v.isInfinite()) "null" else v.toString()
            is String -> quote(v)
            is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$pad}") { "$pad2${quote(it.key.toString())}: ${write(it.value, indent + 1)}" }
            is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$pad]") { pad2 + write(it, indent + 1) }
            else -> quote(v.toString())
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** Returns null for anything that is not valid JSON; never throws. */
    fun parse(text: String): Any? = try {
        val p = Parser(text); val v = p.value(); p.ws(); if (p.i != text.length) null else v
    } catch (_: Exception) { null }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> num()
            }
        }
        private fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw IllegalStateException(); i += w.length; return v }
        private fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        private fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) { out += value(); ws(); if (s[i] == ',') { i++; continue }; if (s[i] == ']') { i++; return out }; throw IllegalStateException() }
        }
        private fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); if (s[i] != ':') throw IllegalStateException(); i++
                out[k] = value(); ws()
                if (s[i] == ',') { i++; continue }; if (s[i] == '}') { i++; return out }; throw IllegalStateException()
            }
        }
    }
}
