package ink.terraria.cmbwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BillMathChecks {
    private fun bill(kind: String, minor: Long, currency: String = "CNY") =
        Bill(1, "2026-11-16T12:00:00", "示例店", currency, "1234", kind, minor)

    @Test fun refundsOffsetExpenses() {
        val fullRefund = listOf(bill("EXPENSE", 10000), bill("REFUND", 10000))
        assertEquals(0L, netSpending(fullRefund))
        assertEquals(0L, balanceChange(fullRefund))
        val partialAndIncome = listOf(bill("EXPENSE", 10000), bill("REFUND", 2500), bill("INCOME", 50000))
        assertEquals(7500L, netSpending(partialAndIncome))
        assertEquals(42500L, balanceChange(partialAndIncome))
    }

    @Test fun currenciesAndTransfersStaySeparate() {
        val entries = listOf(bill("EXPENSE", 10000), bill("REFUND", 50000, "USD"), bill("TRANSFER", 99999))
        assertEquals(10000L, netSpending(entries))
        assertEquals(-50000L, netSpending(entries, "USD"))
    }

    @Test fun signedTotals() {
        assertEquals("−¥97.17", signedMoney(-9717))
        assertEquals("+¥25.00", signedMoney(2500))
        assertEquals("¥0.00", signedMoney(0))
    }
}
