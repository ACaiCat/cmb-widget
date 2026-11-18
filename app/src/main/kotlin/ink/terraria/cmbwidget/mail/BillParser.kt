package ink.terraria.cmbwidget.mail

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Recognizes transaction amounts without treating a balance as a transaction. */
object BillParser {
    data class Entry(
        val date: String,
        val merchant: String,
        val currency: String,
        val card: String,
        val kind: String,
        val minor: Long
    )

    private const val MONEY = """([\d,]+(?:\.\d{1,2})?)"""
    private const val ACTION = "一网通支付退货入账|撤销实时转出|转账汇款|退货入账|实时转至他行|一网通支付|快捷支付|银联消费|网上支付|消费|支出|转出|转入|收入|退款|退货|退汇|取款|取现|扣款|付款|汇款|转账|收款|还款|存款|入账|支付|利息|交易"
    private val chunks = Regex("""(?=(?:您(?:的)?(?:账户|尾号)|您卡|卡号[：:]))""")
    private val date = Regex("""(?:(20\d{2})[年/.-])?(\d{1,2})[月/.-](\d{1,2})日?\s*(?:(\d{1,2})[:时](\d{2})(?:[:分](\d{2}))?秒?)?""")
    private val account = Regex("""(?:账户|尾号|末四位|卡号|储蓄卡)(?:为|是)?[：:（(\s*]*(\d{4})(?!\d)""")
    private val transaction = Regex("""(""" + ACTION + """)\s*(?:人民币|RMB|CNY|美元|USD|港币|HKD|欧元|EUR|[¥￥$])?\s*""" + MONEY + """\s*(元|人民币|美元|港币|欧元|CNY|USD|HKD|EUR)?""", RegexOption.IGNORE_CASE)
    private val salary = Regex("""(入账工资)\s*[，,]\s*人民币\s*""" + MONEY)
    private val labeled = Regex("""(?:交易金额|支出金额|收入金额|退款金额)[：:\s]*(人民币|RMB|CNY|美元|USD|港币|HKD|欧元|EUR|[¥￥$])?\s*""" + MONEY + """\s*(元|美元|港币|欧元)?""", RegexOption.IGNORE_CASE)
    private val cashback = Regex("""(人民币)\s*""" + MONEY + """\s*(元)""")
    private val actionPattern = Regex(ACTION)
    private val merchantPrefix = Regex("""(?:在|向|通过)(.+)""")
    private val merchantSuffix = Regex("""(?:发生)?[，,。；;\s]*$""")
    private val merchantNote = Regex("""(?:备注[：:]|元?[（(])(.+?)[）)]?$|(?:付方|收款人)[：:\s]*([^，。；;\n]+)""")
    private val namedMerchant = Regex("""(?:商户(?:名称)?|交易地点|交易摘要|对方户名)[：:\s]+([^，。；;\n]+)""")
    private val refund = Regex(".*退货.*|退款|退汇|撤销实时转出")
    private val income = Regex("转入|收入|收款|存款|入账|入账工资|利息")
    private val usd = Regex("""(?is).*(美元|USD|\$).*""")
    private val hkd = Regex("(?is).*(港币|HKD).*")
    private val eur = Regex("(?is).*(欧元|EUR).*")

    fun parse(body: String, received: LocalDate): List<Entry> =
        chunks.splitToSequence(plain(body)).mapNotNull { parseChunk(it, received) }.toList()

    private fun parseChunk(chunk: String, received: LocalDate): Entry? {
        val transactionAmount = transaction.find(chunk) ?: salary.find(chunk)
        val amount = transactionAmount ?: labeled.find(chunk) ?: run {
            if ("支付鼓励金" in chunk && "成功入账" in chunk) cashback.find(chunk) else null
        } ?: return null
        val action = transactionAmount?.groupValues?.get(1) ?: actionPattern.find(chunk)?.value ?: return null
        val dateMatch = date.find(chunk) ?: return null
        val timestamp = timestamp(dateMatch, received) ?: return null
        val card = account.find(chunk)?.groupValues?.get(1).orEmpty()
        val merchant = merchant(chunk, action, minOf(dateMatch.range.last + 1, amount.range.first), amount)
        val kind = when {
            action.matches(refund) || (action == "交易" && "支付退货" in chunk) -> "REFUND"
            "朝朝宝" in chunk -> "TRANSFER"
            action.matches(income) || "支付鼓励金" in chunk -> "INCOME"
            action == "还款" -> "REPAYMENT"
            else -> "EXPENSE"
        }
        val currency = when {
            amount.value.matches(usd) -> "USD"
            amount.value.matches(hkd) -> "HKD"
            amount.value.matches(eur) -> "EUR"
            else -> "CNY"
        }
        val minor = try {
            BigDecimal(amount.groupValues[2].replace(",", "")).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
        return if (minor > 0) Entry(timestamp, merchant, currency, card, kind, minor) else null
    }

    private fun timestamp(match: MatchResult, received: LocalDate): String? {
        val (year, month, dayOfMonth, hour, minute, second) = match.destructured
        return try {
            val date = LocalDate.of(year.toIntOrNull() ?: received.year, month.toInt(), dayOfMonth.toInt())
            val day = if (year.isEmpty() && date.isAfter(received.plusDays(2))) date.minusYears(1) else date
            val time = LocalTime.of(hour.toIntOrNull() ?: 0, minute.toIntOrNull() ?: 0, second.toIntOrNull() ?: 0)
            String.format(Locale.ROOT, "%sT%02d:%02d:%02d", day, time.hour, time.minute, time.second)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun merchant(chunk: String, action: String, start: Int, amount: MatchResult): String {
        if ("支付鼓励金" in chunk) return "招行支付鼓励金"
        namedMerchant.find(chunk)?.let { return it.groupValues[1].trim().take(160) }
        val prefix = chunk.substring(start, amount.range.first)
        val fromPrefix = merchantPrefix.find(prefix)?.groupValues?.get(1)?.replace(merchantSuffix, "")?.trim()
        if (fromPrefix != null && fromPrefix != action) return fromPrefix.take(160)
        val note = merchantNote.find(chunk.substring(amount.range.last + 1))
        return (note?.let { (it.groups[1]?.value ?: it.groupValues[2]).trim() } ?: action).take(160)
    }

    private val ignoredHtml = Regex("""(?is)<(style|script)\b[^>]*>.*?</\1>""")
    private val htmlBreak = Regex("""(?i)<br\s*/?>|</(?:tr|p|div)>""")
    private val htmlTag = Regex("<[^>]+>")
    private val entities = Regex("""&#(x[0-9a-fA-F]+|[0-9]+);""")
    private val horizontalSpace = Regex("""[\t\x0B\f\r ]+""")

    fun plain(html: String): String {
        val text = html.replace(ignoredHtml, "").replace(htmlBreak, "\n").replace(htmlTag, " ")
            .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        val decoded = entities.replace(text) { match ->
            val number = match.groupValues[1]
            val code = if (number.startsWith('x')) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else ""
        }
        return decoded.replace('\u3000', ' ').replace(horizontalSpace, " ").trim()
    }
}
