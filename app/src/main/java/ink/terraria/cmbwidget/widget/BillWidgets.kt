package ink.terraria.cmbwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import ink.terraria.cmbwidget.MainActivity
import ink.terraria.cmbwidget.R
import ink.terraria.cmbwidget.data.*
import ink.terraria.cmbwidget.sync.MailSync
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object BillWidgets {
    const val REFRESH = "ink.terraria.cmbwidget.REFRESH"
    const val MIDNIGHT = "ink.terraria.cmbwidget.MIDNIGHT"
    fun refresh(context: Context) {
        val manager=context.getSystemService(AppWidgetManager::class.java)
        val account=AccountVault(context).load()
        val all=if(account==null) emptyList() else BillStore(context).use { it.bills(account.key) }
        val today=ledgerDay()
        val bills=all.filter {it.date.startsWith(today.toString())}
        val month=all.filter {it.date.startsWith(today.toString().take(7))}
        val previousDay=all.filter {it.date.startsWith(today.minusDays(1).toString())}
        val previousMonth=all.filter {it.date.startsWith(today.withDayOfMonth(1).minusMonths(1).toString().take(7))}
        val small=manager.getAppWidgetIds(ComponentName(context,SpendingWidget::class.java))
        val large=manager.getAppWidgetIds(ComponentName(context,EntriesWidget::class.java))
        if(small.isEmpty() && large.isEmpty()) return
        val colors=WidgetColors(context)
        small.forEach { id ->
            val sizes=sizes(manager,id,listOf(SizeF(110f,110f),SizeF(270f,110f)))
            manager.updateAppWidget(id,RemoteViews(sizes.associateWith {size -> spending(context,bills,month,previousDay,previousMonth,size.width>=270f,colors)}))
        }
        large.forEach { id ->
            val sizes=sizes(manager,id,listOf(SizeF(180f,180f),SizeF(280f,300f),SizeF(280f,400f),SizeF(280f,520f),SizeF(360f,640f)))
            manager.updateAppWidget(id,RemoteViews(sizes.associateWith {size -> entries(context,bills,month,account!=null,size,colors)}))
        }
        midnight(context)
    }
    @Suppress("DEPRECATION")
    private fun sizes(manager: AppWidgetManager,id: Int,fallback: List<SizeF>): List<SizeF> {
        val options=manager.getAppWidgetOptions(id)
        val sizes=if(android.os.Build.VERSION.SDK_INT>=33) options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,SizeF::class.java)
            else options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        val minWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,0)
        val minHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,0)
        val maxWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,minWidth)
        val maxHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,minHeight)
        val ranges=if(minWidth>0 && minHeight>0) listOf(SizeF(minWidth.toFloat(),maxHeight.toFloat()),SizeF(maxWidth.toFloat(),minHeight.toFloat()),SizeF(minWidth.toFloat(),minHeight.toFloat())) else fallback
        return (sizes?.takeIf {it.isNotEmpty()} ?: ranges).distinct().take(16)
    }
    private fun openApp(context: Context)=PendingIntent.getActivity(context,0,
        Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("sync_now",true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun spending(context: Context,bills: List<Bill>,month: List<Bill>,previousDay: List<Bill>,previousMonth: List<Bill>,wide: Boolean,colors: WidgetColors): RemoteViews {
        val total=netSpending(bills)
        return RemoteViews(context.packageName,if(wide) R.layout.widget_spending_wide_v4 else R.layout.widget_spending_v4).apply {
            colors.background(this,R.id.widget_root) {it.surface}
            colors.text(this,R.id.widget_title) {it.primary}
            colors.text(this,R.id.widget_amount) {it.primary}
            colors.text(this,R.id.widget_day_comparison) {it.onSurfaceVariant}
            if(wide) {
                colors.text(this,R.id.widget_month_label) {it.tertiary}
                colors.text(this,R.id.widget_month_amount) {it.tertiary}
                colors.text(this,R.id.widget_month_comparison) {it.onSurfaceVariant}
                colors.background(this,R.id.widget_divider) {it.outlineVariant}
            }
            setTextViewText(R.id.widget_amount,money(total))
            setTextViewText(R.id.widget_title,ledgerPeriodLabel()+"支出")
            val comparisonLabel=ledgerComparisonDayLabel()
            setTextViewText(R.id.widget_day_comparison,"$comparisonLabel ${money(netSpending(previousDay))}")
            if(wide) {
                setTextViewText(R.id.widget_month_amount,money(netSpending(month)))
                setTextViewText(R.id.widget_month_comparison,"上月 ${money(netSpending(previousMonth))}")
            }
            setOnClickPendingIntent(R.id.widget_root,openApp(context))
            setContentDescription(R.id.widget_root,ledgerPeriodLabel()+"人民币支出${money(total)}，点击打开账本")
        }
    }
    private fun entries(context: Context,bills: List<Bill>,month: List<Bill>,configured: Boolean,size: SizeF,colors: WidgetColors): RemoteViews {
        val total=netSpending(bills)
        val scale=context.resources.configuration.fontScale.coerceAtLeast(1f)
        val showDate=size.width>=240f
        val showMonth=size.width>=240f && size.height>=240f
        val rowHeight=56f*scale
        val horizontalPadding=(16f*context.resources.displayMetrics.density+0.5f).toInt()
        val verticalPadding=(10f*context.resources.displayMetrics.density+0.5f).toInt()
        val rowFootprint=rowHeight+4f
        val header=104f+24f*scale
        val available=(size.height-header).coerceAtLeast(0f)
        val capacity=(available/rowFootprint).toInt()
        val count=if(bills.size>capacity) ((available-rowHeight)/rowFootprint).toInt().coerceAtLeast(0) else bills.size
        val shown=bills.take(count)
        val remaining=bills.drop(count)
        return RemoteViews(context.packageName,R.layout.widget_entries_v6).apply {
            colors.background(this,R.id.widget_root) {it.surface}
            colors.background(this,R.id.widget_summary) {it.primary}
            colors.text(this,R.id.widget_title) {it.onPrimary}
            colors.text(this,R.id.widget_amount) {it.onPrimary}
            colors.text(this,R.id.widget_date) {it.onPrimary}
            colors.text(this,R.id.widget_month_label) {it.onPrimary}
            colors.text(this,R.id.widget_month_amount) {it.onPrimary}
            colors.text(this,R.id.widget_empty) {it.onSurfaceVariant}

            setTextViewText(R.id.widget_amount,money(total))
            setTextViewText(R.id.widget_title,ledgerPeriodLabel()+"账单")
            setTextViewText(R.id.widget_date,ledgerDay().format(DateTimeFormatter.ofPattern("MM/dd")))
            setViewVisibility(R.id.widget_date,if(showDate) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_month_group,if(showMonth) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_month_amount,money(netSpending(month)))
            setTextViewText(R.id.widget_empty,if(configured) "暂无账单" else "未连接邮箱")
            setViewVisibility(R.id.widget_empty,if(bills.isEmpty()) View.VISIBLE else View.GONE)
            removeAllViews(R.id.widget_rows)
            shown.forEachIndexed {index,bill ->
                val row=RemoteViews(context.packageName,R.layout.widget_bill_row_v3).apply {
                    val first=index==0
                    val last=index==shown.lastIndex && remaining.isEmpty()
                    val background=when {
                        first && last -> R.drawable.widget_row_single
                        first -> R.drawable.widget_row_first
                        last -> R.drawable.widget_row_last
                        else -> R.drawable.widget_row_middle
                    }
                    setInt(R.id.row_root,"setBackgroundResource",background)
                    val incoming=bill.kind=="REFUND" || bill.kind=="INCOME"
                    colors.background(this,R.id.row_root) {it.surfaceContainer}
                    colors.text(this,R.id.row_merchant) {it.onSurface}
                    colors.text(this,R.id.row_detail) {it.onSurfaceVariant}
                    colors.text(this,R.id.row_amount) {if(incoming) it.tertiary else it.onSurface}
                    colors.background(this,R.id.row_icon) {if(incoming) it.tertiaryContainer else it.secondaryContainer}
                    colors.icon(this,R.id.row_icon) {if(incoming) it.onTertiaryContainer else it.onSecondaryContainer}

                    setViewPadding(R.id.row_root,horizontalPadding,verticalPadding,horizontalPadding,verticalPadding)
                    val merchant=merchantName(bill.merchant)
                    setTextViewText(R.id.row_merchant,merchant)
                    val sign=if(bill.kind=="EXPENSE") "−" else if(bill.kind=="REFUND" || bill.kind=="INCOME") "+" else ""
                    setTextViewText(R.id.row_amount,sign+money(bill.minor,bill.currency))
                    setTextViewText(R.id.row_detail,bill.date.substring(11,16)+if(bill.excluded) " · 不计入统计" else "")
                    setViewLayoutHeight(R.id.row_root,rowHeight,android.util.TypedValue.COMPLEX_UNIT_DIP)
                    setContentDescription(R.id.row_root,"$merchant，${kindName(bill.kind)}${money(bill.minor,bill.currency)}")
                    setOnClickPendingIntent(R.id.row_root,openApp(context))
                }
                addView(R.id.widget_rows,row)
            }
            val sums=remaining.groupBy {it.currency}.entries.joinToString(" · ") {(currency,items) -> signedMoney(balanceChange(items,currency),currency)}
            setTextViewText(R.id.widget_overflow_title,"其余${remaining.size}笔账单")
            setTextViewText(R.id.widget_overflow_amount,sums)
            val incoming=remaining.isNotEmpty() && remaining.groupBy {it.currency}.all {(currency,items) -> balanceChange(items,currency)>0}
            colors.text(this,R.id.widget_overflow_amount) {if(incoming) it.tertiary else it.onSurface}
            colors.text(this,R.id.widget_overflow_title) {it.onSurface}
            colors.background(this,R.id.widget_overflow_icon) {it.tertiaryContainer}
            colors.icon(this,R.id.widget_overflow_icon) {it.onTertiaryContainer}
            setViewLayoutHeight(R.id.widget_overflow,rowHeight,android.util.TypedValue.COMPLEX_UNIT_DIP)
            setViewPadding(R.id.widget_overflow,horizontalPadding,verticalPadding,horizontalPadding,verticalPadding)
            setInt(R.id.widget_overflow,"setBackgroundResource",if(shown.isEmpty()) R.drawable.widget_row_single else R.drawable.widget_row_last)
            colors.background(this,R.id.widget_overflow) {it.surfaceContainer}
            setViewVisibility(R.id.widget_overflow,if(remaining.isEmpty()) View.GONE else View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_root,openApp(context))
            setOnClickPendingIntent(R.id.widget_overflow,openApp(context))
        }
    }
    fun midnight(context: Context) {
        val next=LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent=PendingIntent.getBroadcast(context,3,Intent(context,WidgetEvents::class.java).setAction(MIDNIGHT),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,intent)
    }
}
open class BaseBillWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { BillWidgets.refresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context,manager: AppWidgetManager,id: Int,options: Bundle) { BillWidgets.refresh(context) }
    override fun onEnabled(context: Context) { BillWidgets.refresh(context) }
}
class SpendingWidget : BaseBillWidget()
class EntriesWidget : BaseBillWidget()
class WidgetEvents : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        BillWidgets.refresh(context)
    }
}
