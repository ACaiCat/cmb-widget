package ink.terraria.cmbwidget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ink.terraria.cmbwidget.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BillDetailsSheet(bill: Bill, saving: Boolean, onDismiss: () -> Unit, onExcludedChange: (Boolean) -> Unit) {
    val colors=MaterialTheme.colorScheme
    val incoming=bill.kind=="REFUND" || bill.kind=="INCOME"
    val sign=if(incoming) "+" else if(bill.kind=="EXPENSE" || bill.kind=="REPAYMENT") "−" else ""
    val amount=sign+money(bill.minor,bill.currency)
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=colors.surface,sheetMaxWidth=640.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("账单详情",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,color=colors.onSurfaceVariant)
                IconButton(onClick=onDismiss) { LedgerIcon(R.drawable.ic_close,"关闭详情") }
            }
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(merchantName(bill.merchant),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Text(amount,style=if(maxWidth<280.dp || amount.length>13) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                        fontWeight=FontWeight.Bold,color=if(incoming) colors.tertiary else colors.onSurface)
                }
            }
            Surface(shape=RoundedCornerShape(24.dp),color=colors.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    DetailField("类型",kindName(bill.kind))
                    DetailField("交易时间",bill.date.replace('T',' '))
                    if(bill.card.isNotBlank()) DetailField("卡尾号",bill.card)
                }
            }
            Surface(onClick={onExcludedChange(!bill.excluded)},enabled=!saving,shape=RoundedCornerShape(24.dp),color=colors.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("不计入统计",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    Switch(checked=bill.excluded,onCheckedChange=onExcludedChange,enabled=!saving)
                }
            }
        }
    }
}

@Composable
private fun DetailField(label: String,value: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
        Text(label,Modifier.width(72.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.End)
    }
}
