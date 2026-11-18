package ink.terraria.cmbwidget.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import ink.terraria.cmbwidget.data.*
import ink.terraria.cmbwidget.mail.*
import ink.terraria.cmbwidget.widget.BillWidgets
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal enum class SyncOutcome { SUCCESS, RETRY, FAILURE, CANCELLED }

object MailSync {
    const val CHANGED = "ink.terraria.cmbwidget.CHANGED"
    private const val PERIODIC = "mail-sync-periodic"
    private const val IMMEDIATE = "mail-sync-immediate"
    private val gate = ReentrantLock()
    @Volatile private var active: SyncControl? = null
    private var completedAt = Long.MIN_VALUE
    private var completedAccount: String? = null
    val isRunning get() = gate.isLocked

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        // Remove pre-WorkManager jobs, never the current periodic work.
        context.getSystemService(android.app.job.JobScheduler::class.java).apply {
            listOf(710, 711).forEach { id ->
                if (getPendingJob(id)?.service?.className?.startsWith("ink.terraria.cmbwidget.sync.") == true) cancel(id)
            }
        }
        if (AccountVault(context).load() == null) return
        val request = PeriodicWorkRequestBuilder<MailSyncWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("origin" to "periodic"))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun sync(context: Context, replace: Boolean = false) {
        if (AccountVault(context).load() == null) return
        schedule(context)
        if (replace) active?.cancel() else if (isRunning) return
        val request = OneTimeWorkRequestBuilder<MailSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf("origin" to "foreground"))
            .build()
        // A manual retry replaces queued/backing-off work, so it need not wait for backoff.
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        active?.cancel()
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(IMMEDIATE)
            cancelUniqueWork(PERIODIC)
        }
    }

    fun notify(context: Context) {
        context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
        runCatching { BillWidgets.refresh(context) }.onFailure {
            Log.w("MailSync", "Widget update failed: ${it.javaClass.simpleName}")
        }
    }

    internal fun perform(context: Context, control: SyncControl): SyncOutcome {
        val requestedAt = System.nanoTime()
        var acquired = false
        try {
            control.check()
            while (!gate.tryLock(250, TimeUnit.MILLISECONDS)) control.check()
            acquired = true
            control.check()
            active = control
            val account = AccountVault(context).load() ?: return SyncOutcome.SUCCESS
            // Coalesce a foreground request overlapping an already successful periodic sync.
            if (completedAccount == account.key && completedAt >= requestedAt) return SyncOutcome.SUCCESS
            return importMail(context, account, control).also {
                if (it == SyncOutcome.SUCCESS) {
                    completedAt = System.nanoTime()
                    completedAccount = account.key
                }
            }
        } catch (_: CancellationException) {
            return SyncOutcome.CANCELLED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return SyncOutcome.CANCELLED
        } finally {
            if (acquired) {
                active = null
                gate.unlock()
                notify(context)
            }
        }
    }

    private fun importMail(context: Context, account: MailAccount, control: SyncControl): SyncOutcome {
        val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val startedAt = System.nanoTime()
        var folderCount = 0
        var issueCount = 0
        var checked = 0
        fun checkActive() {
            control.check()
            if (AccountVault(context).load() != account) throw CancellationException("Account changed")
            // Leave room before WorkManager's limit; every saved UID is resumable.
            if (System.nanoTime() - startedAt > TimeUnit.MINUTES.toNanos(8)) throw IOException("Sync time budget reached")
        }
        try {
            checkActive()
            prefs.edit().putString("phase", "discover").putString("status", "正在连接并枚举文件夹…").apply()
            notify(context)
            BillStore(context).use { store ->
                ImapClient(account.host, account.port, account.user, account.password, control::attach).use { mail ->
                    val folders = mail.folders()
                    folderCount = folders.size
                    val plans = folders.map { folder ->
                        checkActive()
                        mail.select(folder.wire)
                        val folderKey = digest("${folder.wire}:${mail.validity}")
                        val seen = store.processed(account.key, folderKey)
                        Triple(folder, folderKey, mail.search(0).filterNot { it in seen }.sortedDescending())
                    }
                    val pending = plans.sumOf { it.third.size }
                    val previousTotal = prefs.getInt("total", 0)
                    val total = if (prefs.getInt("processed", 0) < previousTotal) maxOf(previousTotal, pending) else pending
                    val base = total - pending
                    prefs.edit().putString("phase", "import").putInt("total", total).putInt("processed", base).apply()
                    notify(context)
                    for ((folder, folderKey, ids) in plans) {
                        checkActive()
                        if (ids.isEmpty()) continue
                        mail.select(folder.wire)
                        if (digest("${folder.wire}:${mail.validity}") != folderKey) throw IOException("Mailbox UIDVALIDITY changed")
                        for (chunk in ids.chunked(30)) {
                            checkActive()
                            val messages = mail.fetchBatch(chunk)
                            for (uid in chunk) {
                                control.check()
                                val raw = messages.getValue(uid)
                                val fallback = "${account.key}:$folderKey:$uid"
                                try {
                                    val message = MimeMail(raw)
                                    val source = if (message.id.isBlank()) digest(message.subject + message.received + message.body) else digest(message.id)
                                    val sender = Regex("(?i)(?:<|^)(95555[^<>\\s]*@(?:[a-z0-9-]+\\.)*cmbchina\\.com)>?$")
                                    if (!message.subject.contains("交易流水") && !message.body.contains("附件已加密") && sender.containsMatchIn(message.from.trim()) && Regex("账户.*通知|账单|交易.*通知|消费.*通知|还款.*通知").containsMatchIn(message.subject)) {
                                        val entries = BillParser.parse(message.body, message.received)
                                        if (entries.isEmpty()) store.issue(account.key, source, message.subject, "未匹配交易格式 · ${folder.name}")
                                        else { store.save(account.key, source, entries); store.resolveIssue(account.key, source) }
                                    }
                                } catch (_: IOException) {
                                    store.issue(account.key, fallback, "邮件 #$uid", "邮件编码无法解析 · ${folder.name}")
                                }
                                store.markProcessed(account.key, folderKey, uid)
                                checked++
                                prefs.edit().putInt("total", total).putInt("processed", base + checked)
                                    .putString("status", "${folder.name.substringAfterLast('/')} · ${base + checked}/$total 封").apply()
                                if (checked % 10 == 0) notify(context)
                            }
                        }
                    }
                    checkActive()
                }
                issueCount = store.issues(account.key).size
            }
            checkActive()
            check(prefs.edit().putLong("last_sync", System.currentTimeMillis()).putString("phase", "idle")
                .putString("status", "全部 $folderCount 个文件夹已同步 · $issueCount 封待检查").commit())
            Log.i("MailSync", "Mailbox sync complete: folders=$folderCount, processed=$checked")
            return SyncOutcome.SUCCESS
        } catch (_: CancellationException) {
            return SyncOutcome.CANCELLED
        } catch (e: Exception) {
            if (control.isCancelled || AccountVault(context).load() != account) return SyncOutcome.CANCELLED
            val loginFailed = e.message?.contains("登录失败") == true
            val status = when (e) {
                is javax.net.ssl.SSLException -> "TLS 连接失败，请检查服务器证书和地址"
                is java.net.UnknownHostException -> "无法解析邮箱地址，网络恢复后重试"
                is java.net.SocketTimeoutException -> "邮箱连接超时，将自动重试"
                else -> if (loginFailed) "邮箱登录失败，请确认 IMAP 已开启并更新授权码" else "同步未完成，将自动重试"
            }
            prefs.edit().putString("phase", "idle").putString("status", status).apply()
            Log.w("MailSync", "Mailbox sync failed: ${e.javaClass.simpleName}")
            return if (loginFailed || e is javax.net.ssl.SSLException || e !is IOException) SyncOutcome.FAILURE else SyncOutcome.RETRY
        }
    }
}
