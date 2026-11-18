package ink.terraria.cmbwidget.data

/** Income never reduces spending; refunds offset consumption in their own date period. */
fun netSpending(bills: Iterable<Bill>, currency: String = "CNY"): Long = bills.filter {!it.excluded && it.currency==currency}.sumOf {
    when(it.kind) { "EXPENSE" -> it.minor; "REFUND" -> -it.minor; else -> 0L }
}

/** Signed cash movement for a group of visible or collapsed entries. */
fun balanceChange(bills: Iterable<Bill>, currency: String = "CNY"): Long = bills.filter {!it.excluded && it.currency==currency}.sumOf {
    when(it.kind) { "EXPENSE", "REPAYMENT" -> -it.minor; "REFUND", "INCOME" -> it.minor; else -> 0L }
}

fun signedMoney(change: Long, currency: String = "CNY"): String =
    (if(change>0) "+" else "") + money(change,currency)
