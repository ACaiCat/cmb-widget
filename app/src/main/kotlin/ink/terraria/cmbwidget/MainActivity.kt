package ink.terraria.cmbwidget

import android.appwidget.AppWidgetManager
import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ink.terraria.cmbwidget.data.*
import ink.terraria.cmbwidget.sync.MailSync
import ink.terraria.cmbwidget.ui.theme.CMBWidgetTheme
import ink.terraria.cmbwidget.widget.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val selected = mutableStateOf<Long?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        selected.value=intent.getLongExtra("bill_id",-1).takeIf { it>=0 }
        setContent { CMBWidgetTheme { LedgerApp(selected.value) { selected.value=null } } }
    }
    override fun onStart() { super.onStart(); MailSync.sync(this); MailSync.notify(this) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); selected.value=intent.getLongExtra("bill_id",-1).takeIf { it>=0 }; if(intent.getBooleanExtra("sync_now",false)) MailSync.sync(this) }
}
internal data class LedgerState(val account: MailAccount?=null,val bills: List<Bill> = emptyList(),val issues: List<ParseIssue> = emptyList(),val status: String="添加邮箱，开始自动记账",val last: Long=0,val processed: Int=0,val total: Int=0,val phase: String="discover")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LedgerApp(selectedId: Long?,dismissSelection: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var state by remember { mutableStateOf(LedgerState()) }
    var loading by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savingBill by remember { mutableStateOf<Long?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(ledgerDay()) }
    LaunchedEffect(revision) {
        state=withContext(Dispatchers.IO) {
            val account=AccountVault(context).load()
            val prefs=context.getSharedPreferences("sync",Context.MODE_PRIVATE)
            BillStore(context).use { store -> LedgerState(account,if(account==null) emptyList() else store.bills(account.key),if(account==null) emptyList() else store.issues(account.key),
                if(account==null) "添加邮箱，开始自动记账" else prefs.getString("status","等待首次同步")!!,prefs.getLong("last_sync",0),prefs.getInt("processed",0),prefs.getInt("total",0),prefs.getString("phase","discover")!!) }
        }
        loading=false; now=ledgerDay()
    }
    DisposableEffect(context) {
        val receiver=object: BroadcastReceiver() { override fun onReceive(c: Context,i: Intent) { revision++ } }
        val filter=IntentFilter(MailSync.CHANGED).apply { addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    fun sync() {
        if(state.account==null) config=true else {
            MailSync.sync(context); revision++
        }
    }
    val today=state.bills.filter { it.date.startsWith(now.toString()) }
    val visible=state.bills
    Scaffold(containerColor=MaterialTheme.colorScheme.surface,topBar={
        TopAppBar(title={ Text(stringResource(R.string.app_name),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold) },actions={
            FilledTonalIconButton(onClick={config=true},modifier=Modifier.padding(end=16.dp)) { LedgerIcon(R.drawable.ic_mail,"邮箱设置") }
        },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            @Composable fun Overview() {
                Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    SpendingCard(today)
                    SyncCard(state,loading,onSync={sync()},onIssues={showIssues=true})

                }
            }
            @Composable fun Heading() {
                Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("全部账单",modifier=Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("${visible.size} 笔",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            @Composable fun Empty() {
                Column(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(shape=MaterialShapes.Cookie12Sided.toShape(),color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.size(80.dp)) {
                        Box(contentAlignment=Alignment.Center) { LedgerIcon(R.drawable.ic_receipt,modifier=Modifier.size(32.dp)) }
                    }
                    Text(if(loading) "正在读取账本" else if(state.account==null) "连接邮箱，开始记账" else "等待第一笔账单",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(if(state.account==null) "从招行邮件自动整理每笔收支" else "同步后，交易明细会按日期显示在这里",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.account==null) Button(onClick={sync()},shapes=ButtonDefaults.shapes(),modifier=Modifier.height(56.dp)) { LedgerIcon(R.drawable.ic_mail); Spacer(Modifier.width(8.dp)); Text("连接账单邮箱") }
                }
            }
            fun select(bill: Bill) { context.startActivity(Intent(context,MainActivity::class.java).putExtra("bill_id",bill.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            if(maxWidth>=720.dp) Row(Modifier.fillMaxSize().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                LazyColumn(Modifier.width(320.dp),contentPadding=PaddingValues(bottom=24.dp)) {item {Overview()}}
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                    item {Heading()}; if(visible.isEmpty()) item {Empty()}
                    visible.groupBy {it.date.take(10)}.forEach { (day,entries) ->
                        item(key="day:$day") { DateHeading(day,entries.size) }
                        itemsIndexed(entries,key={_,bill -> bill.id}) { index,bill -> BillCard(bill,index==0,index==entries.lastIndex,::select) }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                item {Overview()}; item {Heading()}; if(visible.isEmpty()) item {Empty()}
                visible.groupBy {it.date.take(10)}.forEach { (day,entries) ->
                        item(key="day:$day") { DateHeading(day,entries.size) }
                        itemsIndexed(entries,key={_,bill -> bill.id}) { index,bill -> BillCard(bill,index==0,index==entries.lastIndex,::select) }
                    }
            }
        }
    }
    if(config) AccountDialog(state.account,onDismiss={config=false},onSave={account ->
        scope.launch {
            val result=withContext(Dispatchers.IO) {runCatching {
                val vault=AccountVault(context)
                val changed=vault.load()?.key!=account.key
                vault.save(account)
                if(changed) context.getSharedPreferences("sync",Context.MODE_PRIVATE).edit().clear().apply()
            }}
            result.onSuccess {config=false; MailSync.sync(context,replace=true); revision++}.onFailure {error="配置保存失败，请重试"}
        }
    },onDisconnect={
        AccountVault(context).clear(); MailSync.cancel(context); context.getSharedPreferences("sync",Context.MODE_PRIVATE).edit().clear().apply()
        config=false; revision++; BillWidgets.refresh(context)
    })
    if(showIssues) AlertDialog(onDismissRequest={showIssues=false},title={Text("待检查邮件 · ${state.issues.size}")},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            items(state.issues) {issue -> Column {Text(issue.subject,fontWeight=FontWeight.Bold); Text(issue.reason)}}
        }
    },confirmButton={TextButton(onClick={scope.launch { withContext(Dispatchers.IO) { state.account?.let { BillStore(context).use { db -> db.resetProgress(it.key) } } }; showIssues=false; sync() }}) {Text("重新扫描")}},dismissButton={TextButton(onClick={showIssues=false}) {Text("关闭")}})
    if(error!=null) AlertDialog(onDismissRequest={error=null},title={Text("提示")},text={Text(error!!)},confirmButton={TextButton(onClick={error=null}) {Text("知道了")}})
    val selected=state.bills.find {it.id==selectedId}
    if(selected!=null) BillDetailsSheet(selected,savingBill==selected.id,onDismiss=dismissSelection,onExcludedChange={excluded ->
        val accountKey=state.account?.key
        if(accountKey!=null && savingBill==null) scope.launch {
            savingBill=selected.id
            val result=withContext(Dispatchers.IO) {runCatching {
                check(BillStore(context).use { it.setExcluded(accountKey,selected.id,excluded) })
                MailSync.notify(context)
            }}
            result.onSuccess {
                if(state.account?.key==accountKey) state=state.copy(bills=state.bills.map {if(it.id==selected.id) it.copy(excluded=excluded) else it})
                revision++
            }.onFailure {error="修改统计设置失败，请重试"}
            savingBill=null
        }
    })
}
@Composable private fun AccountDialog(account: MailAccount?,onDismiss: () -> Unit,onSave: (MailAccount) -> Unit,onDisconnect: () -> Unit) {
    var server by remember {mutableStateOf(account?.let {"${it.host}:${it.port}"} ?: "imap.qq.com:993")}
    var user by remember {mutableStateOf(account?.user ?: "")}
    var password by remember {mutableStateOf("")}
    var invalid by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("账单邮箱")},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {OutlinedTextField(value=server,onValueChange={server=it},label={Text("IMAP 服务器")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(value=user,onValueChange={user=it},label={Text("邮箱账号")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(value=password,onValueChange={password=it},label={Text("IMAP 授权码")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,modifier=Modifier.fillMaxWidth())}
            if(invalid!=null) item {Text(invalid!!,color=MaterialTheme.colorScheme.error)}
            if(account!=null) item {TextButton(onClick=onDisconnect) {Text("断开邮箱连接")}}
        }
    },confirmButton={TextButton(onClick={
        val match=Regex("^([a-zA-Z0-9.-]+):(\\d{1,5})$").matchEntire(server.trim())
        val port=match?.groupValues?.get(2)?.toIntOrNull()
        val same=account?.let {server.trim()=="${it.host}:${it.port}" && user.trim()==it.user} ?: false
        val secret=password.trim().ifEmpty {if(same) account!!.password else ""}
        if(match==null || port==null || port !in 1..65535 || !user.trim().contains('@') || secret.isBlank()) invalid="请填写正确的服务器、端口、邮箱和授权码"
        else {onSave(MailAccount(match.groupValues[1].lowercase(),port,user.trim(),secret)); password=""}
    }) {Text("保存并同步")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
