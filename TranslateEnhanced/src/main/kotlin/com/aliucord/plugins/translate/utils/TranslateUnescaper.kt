package com.aliucord.plugins.translate.utils

import java.io.StringWriter
import java.io.Writer

object TranslateUnescaper {

    fun unescape(input: String): String {
        val writer = StringWriter(input.length * 2)
        var pos = 0
        val len = input.length

        while (pos < len) {
            val consumed = translate(input, pos, writer)
            if (consumed == 0) {
                val c1 = input[pos]
                writer.write(c1.code)
                pos++
                if (Character.isHighSurrogate(c1) && pos < len) {
                    val c2 = input[pos]
                    if (Character.isLowSurrogate(c2)) {
                        writer.write(c2.code)
                        pos++
                    }
                }
                continue
            }
            for (pt in 0 until consumed) {
                pos += Character.charCount(Character.codePointAt(input, pos))
            }
        }
        return writer.toString()
    }

    private fun translate(input: String, index: Int, writer: Writer): Int {
        if (input[index] == '\\' && index + 1 < input.length && input[index + 1] == 'u') {
            var i = 2
            while (index + i < input.length && input[index + i] == 'u') i++
            if (index + i < input.length && input[index + i] == '+') i++

            if (index + i + 4 <= input.length) {
                val hex = input.subSequence(index + i, index + i + 4)
                try {
                    writer.write(hex.toString().toInt(16).toChar().code)
                } catch (_: NumberFormatException) { }
                return i + 4
            }
        }
        return 0
    }
}
