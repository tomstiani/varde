package no.varde.app.inbox

internal data class ExactNumber(val token: String)
internal class ExactObject : LinkedHashMap<String, Any?>() {
    var byteCount: Int = 0
    val duplicateKeys = mutableSetOf<String>()
}

// JSONObject rounds numeric tokens on Android; rich facts must retain the supplied spelling.
internal class ExactJson(private val input: String, private val originalsOnly: Boolean = false) {
    private var position = 0
    private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun decode(): ExactObject {
        val result = value(0) as ExactObject
        whitespace()
        require(position == input.length)
        return result
    }

    private fun value(depth: Int): Any? {
        whitespace()
        require(position < input.length)
        return when (input[position]) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> {
                val match = number.matchAt(input, position) ?: error("Invalid number")
                position = match.range.last + 1
                ExactNumber(match.value)
            }
        }
    }

    private fun objectValue(depth: Int): ExactObject {
        require(depth < 8)
        val start = position++
        val result = ExactObject()
        whitespace()
        if (!take('}')) {
            do {
                whitespace()
                val key = string()
                if (result.containsKey(key)) {
                    require(originalsOnly)
                    result.duplicateKeys += key
                }
                whitespace()
                require(take(':'))
                result[key] = if (originalsOnly && key in setOf("data", "dataDefaults", "richView")) {
                    skipRichValue()
                    null
                } else value(depth + 1)
                whitespace()
                if (take('}')) break
                require(take(','))
            } while (true)
        }
        result.byteCount = input.substring(start, position).toByteArray(Charsets.UTF_8).size
        return result
    }

    private fun arrayValue(depth: Int): List<Any?> {
        require(depth < 8)
        position++
        val result = mutableListOf<Any?>()
        whitespace()
        if (take(']')) return result
        do {
            result += value(depth + 1)
            whitespace()
            if (take(']')) break
            require(take(','))
        } while (true)
        return result
    }

    private fun string(): String {
        require(take('"'))
        val result = StringBuilder()
        while (true) {
            require(position < input.length)
            val character = input[position++]
            if (character == '"') break
            require(character.code >= 32)
            if (character != '\\') result.append(character) else {
                require(position < input.length)
                result.append(when (val escaped = input[position++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(position + 4 <= input.length)
                        val digits = input.substring(position, position + 4)
                        require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                        position += 4
                        digits.toInt(16).toChar()
                    }
                    else -> error("Invalid escape")
                })
            }
        }
        var index = 0
        while (!originalsOnly && index < result.length) {
            val character = result[index++]
            if (character.isHighSurrogate()) {
                require(index < result.length && result[index++].isLowSurrogate())
            } else require(!character.isLowSurrogate())
        }
        return result.toString()
    }

    // Original narrative reads must not depend on decoding typed facts or a rejected rich view.
    private fun skipRichValue() {
        whitespace()
        val start = position
        require(start < input.length)
        val container = input[start] == '{' || input[start] == '['
        var depth = 0
        var quoted = false
        while (position < input.length) {
            val character = input[position]
            if (quoted) {
                position++
                if (character == '\\') position++ else if (character == '"') quoted = false
                continue
            }
            when (character) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> { if (depth == 0) break else depth-- }
                ',', ' ', '\t', '\r', '\n' -> if (depth == 0) break
            }
            position++
            if (container && !quoted && depth == 0) break
        }
        require(position > start && position <= input.length && depth == 0 && !quoted)
    }

    private fun literal(text: String, result: Any?): Any? {
        require(input.startsWith(text, position))
        position += text.length
        return result
    }

    private fun take(character: Char): Boolean {
        if (position >= input.length || input[position] != character) return false
        position++
        return true
    }

    private fun whitespace() {
        while (position < input.length && input[position] in " \t\r\n") position++
    }
}
