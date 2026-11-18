package ink.terraria.cmbwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BillExclusionTest {
    private fun bill(kind: String, minor: Long, excluded: Boolean = false, currency: String = "CNY") =
        Bill(1,"2026-01-02T12:00:00","示例商户",currency,"1234",kind,minor,excluded)

    @Test fun excludedExpenseIsKeptButDoesNotAffectTotals() {
        val entries=listOf(bill("EXPENSE",1200),bill("EXPENSE",5000,true))
        assertEquals(2,entries.size)
        assertEquals(1200L,netSpending(entries))
        assertEquals(-1200L,balanceChange(entries))
    }
    @Test fun excludingRefundDoesNotOffsetAnIncludedExpense() {
        val entries=listOf(bill("EXPENSE",5000),bill("REFUND",5000,true))
        assertEquals(5000L,netSpending(entries))
        assertEquals(-5000L,balanceChange(entries))
    }
    @Test fun includedRefundStillOffsetsIncludedExpense() {
        val entries=listOf(bill("EXPENSE",5000),bill("REFUND",1200),bill("INCOME",9000,true))
        assertEquals(3800L,netSpending(entries))
        assertEquals(-3800L,balanceChange(entries))
    }
    @Test fun exclusionAndCurrencyIsolationApplyTogether() {
        val entries=listOf(bill("EXPENSE",2000,true,"USD"),bill("REFUND",300,false,"USD"),bill("EXPENSE",1000))
        assertEquals(1000L,netSpending(entries))
        assertEquals(-300L,netSpending(entries,"USD"))
        assertEquals(300L,balanceChange(entries,"USD"))
    }
    @Test fun restoringEntryRestoresTotals() {
        val excluded=bill("EXPENSE",8900,true)
        assertEquals(0L,netSpending(listOf(excluded)))
        assertEquals(8900L,netSpending(listOf(excluded.copy(excluded=false))))
    }
}
