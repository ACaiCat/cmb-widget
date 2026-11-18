package ink.terraria.cmbwidget.mail

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

class MimeMail(bytes: ByteArray) {
    val subject: String
    val from: String
    val id: String
    val body: String
    val received: LocalDate

    init {
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val headers = headers(raw)
        subject = decodeHeader(headers["subject"].orEmpty())
        from = decodeHeader(headers["from"].orEmpty())
        id = headers["message-id"].orEmpty()
        received = try {
            val date = headers["date"].orEmpty().replace(dateComment, "")
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
        } catch (_: DateTimeException) {
            LocalDate.now()
        }
        body = part(raw, 0)
    }

    private companion object {
        val dateComment = Regex("""\s*\([^)]*\)""")
        val foldedHeader = Regex("""\r?\n[ \t]+""")
        val initialNewline = Regex("""^\r?\n""")
        val adjacentWords = Regex("""(?<=\?=)\s+(?==\?)""")
        val encodedWord = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")

        fun separator(raw: String): Pair<Int, Int> {
            val crlf = raw.indexOf("\r\n\r\n")
            return if (crlf >= 0) crlf to 4 else raw.indexOf("\n\n") to 2
        }

        fun headers(raw: String): Map<String, String> {
            val (split) = separator(raw)
            val head = if (split < 0) raw else raw.substring(0, split)
            return buildMap {
                for (line in foldedHeader.replace(head, " ").lineSequence()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) put(line.substring(0, colon).lowercase(), line.substring(colon + 1).trim())
                }
            }
        }

        fun parameter(header: String, key: String, fallback: String): String {
            val match = Regex("""(?:^|;)\s*""" + key + """\s*=\s*(?:"([^"]*)"|([^;\s]+))""",
                RegexOption.IGNORE_CASE).find(header) ?: return fallback
            return match.groups[1]?.value ?: match.groupValues[2]
        }

        fun part(raw: String, depth: Int): String {
            if (depth > 12) throw IOException("邮件嵌套过深")
            val (split, offset) = separator(raw)
            if (split < 0) return ""
            val headers = headers(raw)
            if (headers["content-disposition"].orEmpty().startsWith("attachment", ignoreCase = true)) return ""
            val type = headers["content-type"] ?: "text/plain"
            val payload = raw.substring(split + offset)
            if (type.startsWith("multipart/", ignoreCase = true)) return multipart(payload, type, depth)
            if (!type.startsWith("text/", ignoreCase = true)) return ""
            try {
                val bytes = payload.toByteArray(Charsets.ISO_8859_1)
                val encoding = headers["content-transfer-encoding"].orEmpty()
                val decoded = when {
                    encoding.equals("base64", ignoreCase = true) -> Base64.getMimeDecoder().decode(bytes)
                    encoding.equals("quoted-printable", ignoreCase = true) -> quoted(bytes)
                    else -> bytes
                }
                val charset = parameter(type, "charset", "UTF-8")
                return decoded.toString(Charset.forName(if (charset.equals("gb2312", true)) "GB18030" else charset))
            } catch (e: IllegalArgumentException) {
                throw IOException("邮件编码无法识别", e)
            }
        }

        fun multipart(payload: String, type: String, depth: Int): String {
            val boundary = parameter(type, "boundary", "")
            if (boundary.isEmpty()) throw IOException("邮件缺少 MIME 边界")
            val delimiter = Regex("(?m)^--" + Regex.escape(boundary) + """(?:--)?[ \t]*\r?\n?""")
            val all = StringBuilder()
            var best = ""
            for (section in delimiter.splitToSequence(payload)) {
                if (!section.contains("content-type:", ignoreCase = true)) continue
                val value = part(initialNewline.replaceFirst(section, ""), depth + 1)
                if (value.isBlank()) continue
                all.appendLine(value)
                if (best.isEmpty() || headers(section)["content-type"].orEmpty().contains("text/html")) best = value
            }
            return if (type.contains("alternative", ignoreCase = true)) best else all.toString()
        }

        fun quoted(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var index = 0
            while (index < bytes.size) {
                val byte = bytes[index++]
                if (byte == '='.code.toByte() && index < bytes.size) {
                    if (bytes[index] == '\n'.code.toByte()) {
                        index++
                        continue
                    }
                    if (index + 1 < bytes.size) {
                        if (bytes[index] == '\r'.code.toByte() && bytes[index + 1] == '\n'.code.toByte()) {
                            index += 2
                            continue
                        }
                        val high = bytes[index].toInt().toChar().digitToIntOrNull(16)
                        val low = bytes[index + 1].toInt().toChar().digitToIntOrNull(16)
                        if (high != null && low != null) {
                            out.write(high * 16 + low)
                            index += 2
                            continue
                        }
                    }
                }
                out.write(byte.toInt())
            }
            return out.toByteArray()
        }

        fun decodeHeader(text: String): String = encodedWord.replace(adjacentWords.replace(text, "")) { match ->
            val (charset, encoding, word) = match.destructured
            try {
                val data = if (encoding.equals("B", ignoreCase = true)) Base64.getDecoder().decode(word)
                    else quoted(word.replace('_', ' ').toByteArray(Charsets.ISO_8859_1))
                data.toString(Charset.forName(charset))
            } catch (e: IllegalArgumentException) {
                throw IOException("邮件标题编码无法识别", e)
            }
        }
    }
}
