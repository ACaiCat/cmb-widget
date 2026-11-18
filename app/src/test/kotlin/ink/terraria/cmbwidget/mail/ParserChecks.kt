package ink.terraria.cmbwidget.mail

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Base64
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ParserChecks {
    private fun check(text: String, minor: Long, kind: String, merchant: String) {
        val entry = BillParser.parse(text, LocalDate.of(2026, 1, 2)).single()
        assertEquals(minor, entry.minor)
        assertEquals(kind, entry.kind)
        assertEquals(merchant, entry.merchant)
    }

    @Test fun transactionFormats() {
        check("您账户1234于01月02日14:27在财付通-微信支付-示例商店快捷支付3.00元，余额837.33",300,"EXPENSE","财付通-微信支付-示例商店")
        check("您的招商银行储蓄卡1234于01月02日15:55，在哈啰-顺风车一网通支付人民币29.91元，已优惠1.49元，余额0.00元。",2991,"EXPENSE","哈啰-顺风车")
        check("您账户1234于01月02日12:18在哈啰-顺风车发生一网通支付退货入账人民币21.50元，余额21.50元",2150,"REFUND","哈啰-顺风车")
        check("您尾号1234的账户于01月02日发起朝朝宝转入，交易金额：人民币3,000元。",300000,"TRANSFER","转入")
        check("您账户1234于01月02日19:25银联扣款人民币1.52元，余额3.08元（银联在线支付，示例店）",152,"EXPENSE","银联在线支付，示例店")
        check("您账户1234于01月02日23:25转账汇款人民币40.00，余额80.00，收款人：示例收款人，请以收款人实际到账为准",4000,"EXPENSE","示例收款人")
        check("您账户1234于01月02日00:21转账汇款人民币50.00，余额30.00，收款人: 示例收款人，请以收款人实际到账为准",5000,"EXPENSE","示例收款人")
        check("您账户1234于01月02日他行实时转入人民币42.12，余额80.00，付方：示例付款人。",4212,"INCOME","示例付款人")
    }

    @Test fun payrollCreditUsesAmountBeforeBalance() {
        check("您账户1234于01月02日09:00入账工资，人民币1500.00，余额2400.00。", 150000, "INCOME", "入账工资")
    }

    @Test fun datesAndMarketing() {
        val received = LocalDate.of(2026, 1, 1)
        val rollover = BillParser.parse("您账户1234于12月31日23:59消费人民币1.00元，余额99.00", received).single()
        assertEquals("2025-12-31T23:59:00", rollover.date)
        assertTrue(BillParser.parse("存款年利率3.00%，余额200.00元", received).isEmpty())
        assertTrue(BillParser.parse("您账户1234于02月30日12:00消费3.00元", received).isEmpty())
        assertTrue(BillParser.parse("您账户1234于01月01日25:00消费3.00元", received).isEmpty())
    }

    @Test fun mimeAlternatives() {
        val raw = """
            Subject: =?UTF-8?B?5rWL6K+V?=
            Content-Type: multipart/alternative; boundary=abc

            --abc
            Content-Type: text/plain; charset=UTF-8

            plain
            --abc
            Content-Type: text/html; charset=UTF-8
            Content-Transfer-Encoding: base64

            PGI+aHRtbDwvYj4=
            --abc--
        """.trimIndent().replace("\n", "\r\n")
        val mail = MimeMail(raw.toByteArray())
        assertEquals("测试", mail.subject)
        assertEquals("<b>html</b>", mail.body.trim())
    }

    @Test fun optionalFixtureFile() {
        val file = System.getenv("CMB_TEST_FIXTURES")
        assumeTrue(!file.isNullOrBlank())
        Files.lines(Path.of(requireNotNull(file))).use { lines ->
            lines.iterator().asSequence().forEachIndexed { index, line ->
                val (date, encoded) = line.split('\t', limit = 2)
                val body = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                verifyTransaction(body, LocalDate.parse(date), "fixture " + (index + 1))
            }
        }
    }
}
