package ink.terraria.cmbwidget.mail

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Uses verified TLS and read-only IMAP commands. */
class ImapClient(
    host: String,
    port: Int,
    user: String,
    password: String,
    onConnection: (Closeable) -> Unit = {}
) : Closeable {
    private val socket: SSLSocket
    private val input: InputStream
    private val output: OutputStream
    private var tag = 0
    var validity = ""
        private set

    init {
        val transport = Socket()
        try {
            onConnection(transport)
            transport.connect(InetSocketAddress(host, port), 20_000)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = factory.createSocket(transport, host, port, true) as SSLSocket
            socket.soTimeout = 25_000
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.startHandshake()
            input = BufferedInputStream(socket.inputStream)
            output = socket.outputStream
            if (!readLine().startsWith("* OK")) throw IOException("IMAP 服务未就绪")
            command("LOGIN " + quote(user) + " " + quote(password))
            select("INBOX")
        } catch (e: Exception) {
            try {
                transport.close()
            } catch (closeError: IOException) {
                e.addSuppressed(closeError)
            }
            throw e
        }
    }

    class Folder internal constructor(val wire: String) {
        val name: String = decodeFolder(wire)
    }

    fun folders(): List<Folder> = command("LIST \"\" \"*\"").mapNotNull { line ->
        val match = listResponse.find(line) ?: return@mapNotNull null
        if (match.groupValues[1].contains("\\noselect", ignoreCase = true)) return@mapNotNull null
        val wire = match.groupValues[2]
        Folder(if (wire.startsWith('"') && wire.endsWith('"')) {
            wire.substring(1, wire.lastIndex).replace("\\\"", "\"").replace("\\\\", "\\")
        } else wire)
    }.sortedBy { it.wire.equals("INBOX", ignoreCase = true) }

    fun select(folder: String) {
        validity = command("EXAMINE " + quote(folder))
            .mapNotNull { uidValidity.find(it)?.groupValues?.get(1) }.lastOrNull()
            ?: throw IOException("邮箱未提供 UIDVALIDITY")
    }

    fun search(after: Long): List<Long> {
        // QQ IMAP matches 95555 in the sender's local part.
        val range = (after + 1).toString() + ":*"
        return command("UID SEARCH UID " + range + " FROM \"95555\"")
            .filter { it.startsWith("* SEARCH") }
            .flatMap { it.substring(8).trim().split(spaces).filter(String::isNotEmpty) }
            .map(String::toLong)
            .filter { it > after }
            .sorted()
    }

    fun fetch(uid: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        exchange("UID FETCH " + uid + " (BODY.PEEK[])", bytes)
        if (bytes.size() == 0) throw IOException("邮件已移动或无法读取，请重新同步")
        return bytes.toByteArray()
    }

    fun fetchBatch(ids: List<Long>): Map<Long, ByteArray> {
        if (ids.isEmpty()) return emptyMap()
        val key = send("UID FETCH " + ids.joinToString(",") + " (UID BODY.PEEK[])", Charsets.US_ASCII)
        val result = mutableMapOf<Long, ByteArray>()
        var uid: Long? = null
        var data: ByteArray? = null
        while (true) {
            val line = readLine()
            fetchUid.find(line)?.let { uid = it.groupValues[1].toLong() }
            literalMarker.find(line)?.let { literal ->
                data = ByteArray(literalLength(literal)).also { readFully(it) }
            }
            val messageUid = uid
            val message = data
            if (messageUid != null && message != null) {
                result[messageUid] = message
                uid = null
                data = null
            }
            if (commandComplete(line, key, "批量读取邮件失败")) {
                if (result.keys != ids.toSet()) throw IOException("部分邮件已移动，请重新同步")
                return result
            }
        }
    }

    private fun command(command: String): List<String> = exchange(command, null)

    private fun exchange(command: String, body: ByteArrayOutputStream?): List<String> {
        val key = send(command)
        val lines = mutableListOf<String>()
        val failure = if (command.startsWith("LOGIN")) {
            "邮箱登录失败，请检查 IMAP 开关和授权码"
        } else "IMAP 操作失败，请稍后重试"
        while (true) {
            val line = readLine()
            lines.add(line)
            val literal = literalMarker.find(line)
            if (literal != null) {
                val length = literalLength(literal)
                val folderName = if (command.startsWith("LIST ")) ByteArrayOutputStream() else null
                val block = ByteArray(minOf(length, 8192))
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, block.size)
                    readFully(block, count)
                    body?.write(block, 0, count)
                    folderName?.write(block, 0, count)
                    remaining -= count
                }
                if (folderName != null) {
                    lines[lines.lastIndex] = line.substring(0, literal.range.first) +
                        quote(folderName.toByteArray().toString(Charsets.US_ASCII))
                }
            }
            if (commandComplete(line, key, failure)) return lines
        }
    }

    private fun send(command: String, charset: Charset = Charsets.UTF_8): String {
        val key = "A" + ++tag
        output.write((key + " " + command + "\r\n").toByteArray(charset))
        output.flush()
        return key
    }

    private fun readLine(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            when (val value = input.read()) {
                -1 -> throw EOFException("邮箱连接中断")
                '\n'.code -> return bytes.toByteArray().toString(Charsets.UTF_8)
                else -> {
                    if (bytes.size() > 1024 * 1024) throw IOException("IMAP 响应过大")
                    if (value != '\r'.code) bytes.write(value)
                }
            }
        }
    }

    private fun readFully(bytes: ByteArray, length: Int = bytes.size) {
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("邮箱连接中断")
            offset += count
        }
    }

    override fun close() = socket.close()

    private companion object {
        val listResponse = Regex("""(?i)^\* LIST \(([^)]*)\) (?:"[^"]*"|NIL) (.+)$""")
        val uidValidity = Regex("""UIDVALIDITY (\d+)""")
        val literalMarker = Regex("""\{(\d+)\}$""")
        val fetchUid = Regex("""\bUID (\d+)""")
        val folderEncoding = Regex("""&([^-]*)-""")
        val spaces = Regex(" +")

        fun literalLength(match: MatchResult): Int {
            val length = match.groupValues[1].toIntOrNull()
            if (length == null || length > 8 * 1024 * 1024) throw IOException("邮件超过 8 MB，请单独处理")
            return length
        }

        fun decodeFolder(wire: String): String = folderEncoding.replace(wire) { match ->
            val encoded = match.groupValues[1]
            if (encoded.isEmpty()) "&"
            else try {
                Base64.getDecoder().decode(encoded.replace(',', '/')).toString(Charsets.UTF_16BE)
            } catch (_: IllegalArgumentException) {
                match.value
            }
        }

        fun quote(value: String): String {
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                throw IOException("邮箱配置包含非法字符")
            }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        fun commandComplete(line: String, key: String, failure: String): Boolean {
            if (line.startsWith("* BYE")) throw IOException("邮箱主动断开连接")
            if (!line.startsWith(key + " ")) return false
            if (!line.startsWith(key + " OK")) throw IOException(failure)
            return true
        }
    }
}
