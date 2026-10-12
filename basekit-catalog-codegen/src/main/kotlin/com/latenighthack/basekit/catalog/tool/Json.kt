package com.latenighthack.basekit.catalog.tool

/**
 * Serializes maps, lists, strings, numbers, booleans and null as JSON. `<`, `>` and `&` are escaped so
 * the result can be embedded in an HTML `<script>` element without closing it.
 */
object Json {
    fun write(value: Any?): String = buildString { write(value, this) }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> string(value, out)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, entry) ->
                    if (index > 0) out.append(',')
                    string(key.toString(), out)
                    out.append(':')
                    write(entry, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    write(entry, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("cannot write ${value::class} as JSON")
        }
    }

    private fun string(value: String, out: StringBuilder) {
        out.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '<' -> out.append("\\u003c")
                '>' -> out.append("\\u003e")
                '&' -> out.append("\\u0026")
                else -> if (char.code < 0x20 || char == ' ' || char == ' ') {
                    out.append("\\u%04x".format(char.code))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }
}
