package ink.terraria.cmbwidget

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.terraria.cmbwidget.data.*
import ink.terraria.cmbwidget.mail.BillParser
import ink.terraria.cmbwidget.ui.theme.CMBWidgetTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillDetailsSheetTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun isolateDatabase() {
        check(context.packageName=="ink.terraria.cmbwidget.verification")
        context.deleteDatabase("bills.db")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        context.openFileOutput(name,0).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }

    @Test fun exclusionCanBeChangedAndRestoredFromDetails() {
        val original=BillStore(context).use { store ->
            store.save("test","source",listOf(BillParser.Entry("2026-01-02T12:00:00","示例转账收款人","CNY","1234","EXPENSE",12345)))
            store.bills("test").single()
        }
        compose.setContent {
            CMBWidgetTheme(darkTheme=true) {
                var bill by remember {mutableStateOf(original)}
                BillDetailsSheet(bill,false,onDismiss={},onExcludedChange={ excluded ->
                    BillStore(context).use {check(it.setExcluded("test",bill.id,excluded))}
                    bill=bill.copy(excluded=excluded)
                })
            }
        }
        compose.onNodeWithText("不计入统计").performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff()
        screenshot("bill-details-dark.png")
        compose.onNodeWithText("不计入统计").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        BillStore(context).use {
            val entries=it.bills("test")
            assertEquals(1,entries.size)
            assertTrue(entries.single().excluded)
            assertEquals(0L,netSpending(entries))
        }
        compose.onNodeWithText("不计入统计").performClick()
        compose.onNode(isToggleable()).assertIsOff()
        BillStore(context).use { assertEquals(12345L,netSpending(it.bills("test"))) }
    }

    @Test fun largeTextKeepsExclusionSwitchAccessible() {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                CMBWidgetTheme(darkTheme=false) {
                    var bill by remember {mutableStateOf(Bill(1,"2026-01-02T12:00:00","一个比较长的示例交易对方名称，用于检查文字换行后仍能查看交易信息和修改统计设置","CNY","1234","EXPENSE",12345678))}
                    BillDetailsSheet(bill,false,onDismiss={},onExcludedChange={bill=bill.copy(excluded=it)})
                }
            }
        }
        compose.onNodeWithText("不计入统计").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(isToggleable()).assertIsOn()
        screenshot("bill-details-large-text.png")
    }
}
