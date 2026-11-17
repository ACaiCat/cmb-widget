package ink.terraria.cmbwidget.data

private const val MERCHANT_SEPARATOR = "[-－—–:：·\\s　]+"
private val channelPrefix = Regex("^(支付宝|财付通|微信支付|网银在线|京东商城|京东支付|美团App|美团)\\s*$MERCHANT_SEPARATOR\\s*",RegexOption.IGNORE_CASE)
private val consumptionPrefix = Regex("^消费(?:\\s*$MERCHANT_SEPARATOR\\s*|$)")
private val duplicatedMeituan = Regex("^美团\\s*$MERCHANT_SEPARATOR\\s*美团App\\s*(?:$MERCHANT_SEPARATOR\\s*)?",RegexOption.IGNORE_CASE)

/** Display only: keep the original merchant and meaningful hyphens in the saved bill. */
fun merchantName(raw: String): String {
    var name=raw.trim()
    var fallback=name
    var stripped=false
    duplicatedMeituan.find(name)?.let {
        name=name.substring(it.range.last+1).trim()
        fallback="美团"
        stripped=true
    }
    while(name.isNotEmpty()) {
        val prefix=channelPrefix.find(name) ?: if(stripped) consumptionPrefix.find(name) else null
        if(prefix==null) break
        if(!stripped) fallback=prefix.groupValues[1]
        stripped=true
        name=name.substring(prefix.range.last+1).trim()
    }
    return name.ifEmpty {fallback}
}
