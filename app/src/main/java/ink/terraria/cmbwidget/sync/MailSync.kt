package ink.terraria.cmbwidget.sync

import android.content.Context
import android.content.Intent
import ink.terraria.cmbwidget.data.*
import ink.terraria.cmbwidget.mail.*
import ink.terraria.cmbwidget.widget.BillWidgets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object MailSync {
    const val CHANGED = "ink.terraria.cmbwidget.CHANGED"
    private val running = AtomicBoolean(false)
    val isRunning get() = running.get()
    private val executor = Executors.newSingleThreadExecutor()
    fun sync(context: Context) {
        if (AccountVault(context).load() == null) return
        val app=context.applicationContext
        start(app,AtomicBoolean(false)) { _, _ -> }
    }
    fun cancel(context: Context) {
        // Remove jobs created by older app versions; foreground sync uses no scheduler/service.
        context.getSystemService(android.app.job.JobScheduler::class.java).apply { cancel(710); cancel(711) }
    }
    fun notify(context: Context) {
        context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
        BillWidgets.refresh(context)
    }
    fun start(context: Context, stopped: AtomicBoolean, finished: (Boolean, Boolean) -> Unit) {
        if(!running.compareAndSet(false,true)) { finished(false,false); return }
        executor.execute {
            var more = false
            val prefs=context.getSharedPreferences("sync",Context.MODE_PRIVATE)
            try {
                val account=AccountVault(context).load() ?: return@execute
                prefs.edit().putString("phase","discover").putString("status", "正在连接并枚举文件夹…").apply(); notify(context)
                BillStore(context).use { store ->
                    ImapClient(account.host,account.port,account.user,account.password).use mailbox@ { mail ->
                        val folders=mail.folders()
                        var remaining=0
                        var checked=0
                        val plans=folders.map { folder ->
                            mail.select(folder.wire)
                            val folderKey=digest("${folder.wire}:${mail.validity}")
                            val seen=store.processed(account.key,folderKey)
                            Triple(folder,folderKey,mail.search(0).filterNot { it in seen }.sortedDescending())
                        }
                        val pending=plans.sumOf {it.third.size}
                        val previousTotal=prefs.getInt("total",0)
                        val total=if(prefs.getInt("processed",0)<previousTotal) maxOf(previousTotal,pending) else pending
                        val base=total-pending
                        prefs.edit().putString("phase","import").putInt("total",total).putInt("processed",base).apply()
                        notify(context)
                        for((folder,folderKey,ids) in plans) {
                            if(stopped.get() || AccountVault(context).load()?.key != account.key) return@mailbox
                            mail.select(folder.wire)
                            val batch=ids
                            for(chunk in batch.chunked(30)) {
                              val messages=mail.fetchBatch(chunk)
                              for(uid in chunk) {
                                if(stopped.get() || AccountVault(context).load()?.key != account.key) return@mailbox
                                val raw=messages.getValue(uid)
                                val fallback="${account.key}:$folderKey:$uid"
                                try {
                                    val message=MimeMail(raw)
                                    val source=if(message.id.isBlank()) digest(message.subject+message.received+message.body) else digest(message.id)
                                    val sender=Regex("(?i)(?:<|^)(95555[^<>\\s]*@(?:[a-z0-9-]+\\.)*cmbchina\\.com)>?$")
                                    if(!message.subject.contains("交易流水") && !message.body.contains("附件已加密") && sender.containsMatchIn(message.from.trim()) && Regex("账户.*通知|账单|交易.*通知|消费.*通知|还款.*通知").containsMatchIn(message.subject)) {
                                        val entries=BillParser.parse(message.subject,message.body,message.received)
                                        if(entries.isEmpty()) store.issue(account.key,source,message.subject,"未匹配交易格式 · ${folder.name}")
                                        else { store.save(account.key,source,entries); store.resolveIssue(account.key,source) }
                                    }
                                } catch(e: java.io.IOException) {
                                    store.issue(account.key,fallback,"邮件 #$uid","邮件编码无法解析 · ${folder.name}")
                                }
                                store.markProcessed(account.key,folderKey,uid)
                                checked++
                                prefs.edit().putInt("total",total).putInt("processed",base+checked)
                                    .putString("status","${folder.name.substringAfterLast('/')} · ${base+checked}/$total 封").apply()
                                if(checked%10==0) notify(context)
                            }
                            }
                            remaining+=ids.size-batch.size
                        }
                        if(!stopped.get()) {
                            prefs.edit().putLong("last_sync",System.currentTimeMillis())
                                .putString("status",if(remaining>0) "继续导入 $remaining 封历史邮件" else "全部 ${folders.size} 个文件夹已同步 · ${store.issues(account.key).size} 封待检查").apply()
                            more=remaining>0
                        }
                    }
                }
            } catch(e: Exception) {
                val status=when(e) {
                    is javax.net.ssl.SSLException -> "TLS 连接失败，请检查服务器证书和地址"
                    is java.net.UnknownHostException -> "无法解析邮箱地址，请检查网络"
                    is java.net.SocketTimeoutException -> "邮箱连接超时，点击同步重试"
                    else -> if(e.message?.contains("登录失败")==true) "邮箱登录失败，请确认 IMAP 已开启并更新授权码" else "同步未完成，请检查网络与邮箱配置后重试"
                }
                prefs.edit().putString("status",status).apply()
            } finally {
                running.set(false)
                try { notify(context) } finally { finished(false, more && !stopped.get()) }
            }
        }
    }
}
