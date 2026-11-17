@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ink.terraria.cmbwidget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.terraria.cmbwidget.data.*
import ink.terraria.cmbwidget.sync.MailSync
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun LedgerIcon(resource: Int,description: String?=null,modifier: Modifier=Modifier.size(22.dp)) {
    Icon(painterResource(resource),description,modifier)
}

@Composable internal fun SpendingCard(bills: List<Bill>) {
    val colors=MaterialTheme.colorScheme
    val expenses=bills.filter {!it.excluded && it.kind=="EXPENSE"}
    val amount=java.math.BigDecimal.valueOf(netSpending(bills),2).toPlainString()
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Surface(color=colors.primary,contentColor=colors.onPrimary,shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp,bottomEnd=8.dp,bottomStart=8.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(ledgerPeriodLabel()+"支出",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    Surface(color=colors.onPrimary.copy(alpha=.15f),contentColor=colors.onPrimary,shape=RoundedCornerShape(50)) {
                        Text(ledgerDay().format(DateTimeFormatter.ofPattern("MM月dd日")),Modifier.padding(horizontal=12.dp,vertical=7.dp),style=MaterialTheme.typography.labelMedium)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrow=maxWidth<230.dp
                    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("¥",Modifier.padding(bottom=8.dp),style=MaterialTheme.typography.headlineMedium)
                        AnimatedContent(amount,label="今日开销") { value ->
                            Text(value,style=if(value.length>10 || narrow) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge,
                                fontWeight=FontWeight.Bold,letterSpacing=(-2).sp,maxLines=1)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${expenses.size} 笔支出",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                        Text("人民币合计",style=MaterialTheme.typography.labelSmall,color=colors.onPrimary.copy(alpha=.75f))
                    }
                    Surface(shape=MaterialShapes.Cookie12Sided.toShape(),color=colors.primaryContainer,contentColor=colors.onPrimaryContainer,modifier=Modifier.size(48.dp)) {
                        Box(contentAlignment=Alignment.Center) {LedgerIcon(R.drawable.ic_receipt)}
                    }
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            @Composable fun Metric(label: String,kind: String,icon: Int,color: Color,foreground: Color,shape: RoundedCornerShape,modifier: Modifier) {
                Surface(color=color,contentColor=foreground,shape=shape,modifier=modifier) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) { LedgerIcon(icon,modifier=Modifier.size(16.dp)); Text(label,style=MaterialTheme.typography.labelLarge) }
                        Text(money(bills.filter {!it.excluded && it.kind==kind && it.currency=="CNY"}.sumOf {it.minor}),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=1)
                    }
                }
            }
            Metric("退款","REFUND",R.drawable.ic_down,colors.tertiaryContainer,colors.onTertiaryContainer,RoundedCornerShape(8.dp,8.dp,8.dp,28.dp),Modifier.weight(1f))
            Metric("收入","INCOME",R.drawable.ic_up,colors.secondaryContainer,colors.onSecondaryContainer,RoundedCornerShape(8.dp,8.dp,28.dp,8.dp),Modifier.weight(1f))
        }
        if(bills.any {!it.excluded && it.currency!="CNY"}) Text("外币单独展示，不折算汇率",style=MaterialTheme.typography.labelSmall)
    }
}

@Composable internal fun SyncCard(state: LedgerState,loading: Boolean,onSync: () -> Unit,onIssues: () -> Unit) {
    val busy=MailSync.isRunning
    val known=state.phase=="import" && state.total>0
    val colors=MaterialTheme.colorScheme
    val progress by animateFloatAsState(if(state.total>0) (state.processed.toFloat()/state.total).coerceIn(0f,1f) else 0f,MaterialTheme.motionScheme.defaultEffectsSpec(),label="邮件导入进度")
    Surface(color=colors.surfaceContainerLow,shape=RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=RoundedCornerShape(16.dp),color=colors.secondaryContainer,contentColor=colors.onSecondaryContainer,modifier=Modifier.size(44.dp)) {
                    Box(contentAlignment=Alignment.Center) {
                        if(busy && !known) LoadingIndicator(Modifier.size(36.dp)) else LedgerIcon(if(busy) R.drawable.ic_mail else R.drawable.ic_check)
                    }
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(if(loading) "读取账本" else if(busy) "正在整理邮件" else if(state.account==null) "连接账单邮箱" else "账本已就绪",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
                    if(busy) Text(state.status,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(state.account!=null) {
                        val lastUpdate=if(state.last>0) Instant.ofEpochMilli(state.last).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) else "尚未同步"
                        Text("上次更新：$lastUpdate",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                if(busy && known) Text("${((state.processed.toLong()*100)/state.total).coerceIn(0,100)}%",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=colors.primary)
                if(!busy) FilledIconButton(onClick=onSync,modifier=Modifier.size(48.dp)) { LedgerIcon(R.drawable.ic_sync,"立即同步") }
            }
            if(busy) {
                if(known) LinearWavyProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(12.dp),amplitude={1f},wavelength=24.dp)
                else LinearWavyProgressIndicator(modifier=Modifier.fillMaxWidth().height(12.dp),amplitude=1f,wavelength=24.dp)
            }
            if(!busy && state.status.contains("失败") || !busy && state.status.contains("超时") || !busy && state.status.contains("未完成")) Text(state.status,color=colors.error,style=MaterialTheme.typography.bodySmall)
            if(state.issues.isNotEmpty()) TextButton(onClick=onIssues,contentPadding=PaddingValues(horizontal=0.dp)) { Text("${state.issues.size} 封邮件待检查"); Spacer(Modifier.width(8.dp)); LedgerIcon(R.drawable.ic_arrow) }
        }
    }
}

@Composable internal fun DateHeading(day: String,count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(if(day==LocalDate.now().toString()) "今天" else LocalDate.parse(day).format(DateTimeFormatter.ofPattern("MM月dd日")),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$count 笔",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun BillCard(bill: Bill,first: Boolean,last: Boolean,onClick: (Bill) -> Unit) {
    val colors=MaterialTheme.colorScheme
    val incoming=bill.kind=="REFUND" || bill.kind=="INCOME"
    val merchant=bill.merchant.replace(Regex("^(支付宝-支付宝-消费-|财付通-微信支付-|美团-美团App|支付宝-|网银在线-京东商城-)"),"")
    val cornerTop=if(first) 24.dp else 6.dp
    val cornerBottom=if(last) 24.dp else 6.dp
    Surface(onClick={onClick(bill)},shape=RoundedCornerShape(cornerTop,cornerTop,cornerBottom,cornerBottom),color=colors.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(shape=if(incoming) RoundedCornerShape(50) else RoundedCornerShape(14.dp),color=if(incoming) colors.tertiaryContainer else colors.secondaryContainer,contentColor=if(incoming) colors.onTertiaryContainer else colors.onSecondaryContainer,modifier=Modifier.size(40.dp)) {
                Box(contentAlignment=Alignment.Center) { LedgerIcon(if(incoming) R.drawable.ic_down else R.drawable.ic_receipt,modifier=Modifier.size(20.dp)) }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(merchant,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("${bill.date.substring(11,16)} · ${if(bill.excluded) "不计入统计" else kindName(bill.kind)}",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
            }
            Text((if(incoming) "+" else if(bill.kind=="EXPENSE") "−" else "")+money(bill.minor,bill.currency),modifier=Modifier.widthIn(max=140.dp),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=if(incoming) colors.tertiary else colors.onSurface,maxLines=2)
        }
    }
}
