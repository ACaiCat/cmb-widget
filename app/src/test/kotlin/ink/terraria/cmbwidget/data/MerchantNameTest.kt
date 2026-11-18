package ink.terraria.cmbwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantNameTest {
    @Test fun repeatedPaymentWrappersLeaveMerchant() {
        assertEquals("示例商店",merchantName("支付宝-支付宝-消费-示例商店"))
        assertEquals("示例商店",merchantName("财付通-微信支付-示例商店"))
        assertEquals("示例商店",merchantName("网银在线-京东商城-示例商店"))
    }
    @Test fun wechatWithdrawalDoesNotKeepTenpayWrapper() {
        assertEquals("微信零钱提现",merchantName("财付通-微信零钱提现"))
        assertEquals("微信零钱提现",merchantName("财付通 微信零钱提现"))
        assertEquals("微信零钱提现",merchantName("财付通-财付通-微信零钱提现"))
        assertEquals("微信零钱提现",merchantName("财付通　微信零钱提现"))
    }
    @Test fun separatorVariantsAreRecognized() {
        assertEquals("示例商店",merchantName(" 支付宝 ： 支付宝－消费—示例商店 "))
        assertEquals("示例商店",merchantName("微信支付 · 示例商店"))
    }
    @Test fun meituanAppPrefixDoesNotLeaveAStraySeparator() {
        assertEquals("示例商店",merchantName("美团-美团App-示例商店"))
        assertEquals("示例商店",merchantName("美团-美团App示例商店"))
    }
    @Test fun realNamesAndInternalPunctuationArePreserved() {
        assertEquals("示例-便利店",merchantName("示例-便利店"))
        assertEquals("支付宝便利店",merchantName("支付宝便利店"))
        assertEquals("消费电子-示例店",merchantName("消费电子-示例店"))
        assertEquals("示例-便利店",merchantName("支付宝-示例-便利店"))
    }
    @Test fun channelOnlyEntriesRemainReadable() {
        assertEquals("支付宝",merchantName("支付宝-支付宝-消费-"))
        assertEquals("美团",merchantName("美团-美团App"))
        assertEquals("支付宝",merchantName("支付宝"))
    }
}
