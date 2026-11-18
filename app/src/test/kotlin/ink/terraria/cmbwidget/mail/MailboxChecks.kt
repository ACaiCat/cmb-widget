package ink.terraria.cmbwidget.mail

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in and read-only. Use a test mailbox with transaction emails. */
class MailboxChecks {
    @Test fun scansAllFoldersAndParsesTransactionMail() = withTestMailbox { mail ->
        val folders = mail.folders()
        assertTrue(folders.isNotEmpty())
        var checked = 0
        for (folder in folders) {
            mail.select(folder.wire)
            val ids = mail.search(0)
            for (batch in ids.chunked(30)) {
                val messages = mail.fetchBatch(batch)
                for (uid in batch) {
                    val message = MimeMail(messages.getValue(uid))
                    if (!relevantSubject.containsMatchIn(message.subject) ||
                        "交易流水" in message.subject || "附件已加密" in message.body) continue
                    assertTrue("Sender mismatch for UID " + uid, cmbSender.containsMatchIn(message.from.trim()))
                    verifyTransaction(message.body, message.received, "UID " + uid)
                    checked++
                }
            }
            ids.lastOrNull()?.let { assertTrue("UID incremental filtering", mail.search(it).isEmpty()) }
        }
        assertTrue("No transaction emails found", checked > 0)
        println("Validated " + checked + " transaction emails across " + folders.size + " folders")
    }

    @Test fun batchMatchesSingleFetch() = withTestMailbox { mail ->
        var tested = 0
        for (folder in mail.folders()) {
            mail.select(folder.wire)
            val batch = mail.search(0).takeLast(30)
            if (batch.isEmpty()) continue
            val messages = mail.fetchBatch(batch)
            for (uid in batch) {
                assertTrue("MIME subject missing", MimeMail(messages.getValue(uid)).subject.isNotEmpty())
                tested++
            }
            val last = batch.last()
            assertArrayEquals(messages.getValue(last), mail.fetch(last))
        }
        assertTrue("No messages found for batch test", tested > 0)
    }

    private fun withTestMailbox(block: (ImapClient) -> Unit) {
        val user = System.getenv("CMB_TEST_USER")
        val password = System.getenv("CMB_TEST_PASSWORD")
        assumeTrue(!user.isNullOrBlank() && !password.isNullOrBlank())
        ImapClient("imap.qq.com", 993, requireNotNull(user), requireNotNull(password)).use(block)
    }

    private companion object {
        val relevantSubject = Regex("账户.*通知|账单|交易.*通知|消费.*通知|还款.*通知")
        val cmbSender = Regex("""(?i)(?:<|^)(95555[^<>\s]*@(?:[a-z0-9-]+\.)*cmbchina\.com)>?$""")
    }
}

private val balanceOrDiscount = Regex("余额|已优惠")
private val monetaryValue = Regex("""(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*元?(?:[，,。\s]|$)""")

internal fun verifyTransaction(body: String, received: LocalDate, label: String) {
    val entries = BillParser.parse(body, received)
    assertEquals("Match count for " + label, 1, entries.size)
    val entry = entries.single()
    assertTrue("Missing card for " + label, entry.card.isNotEmpty())
    val prefix = BillParser.plain(body).split(balanceOrDiscount, limit = 2).first()
    val found = monetaryValue.findAll(prefix).lastOrNull()
    if (found != null) {
        val expected = BigDecimal(found.groupValues[1].replace(",", "")).movePointRight(2).longValueExact()
        assertEquals("Amount mismatch for " + label, expected, entry.minor)
    }
}
