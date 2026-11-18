package ink.terraria.cmbwidget.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ink.terraria.cmbwidget.mail.BillParser
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MailAccount(val host: String, val port: Int, val user: String, val password: String) {
    val key: String get() = digest("$host:$port:${user.lowercase()}")
}
fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

class AccountVault(context: Context) {
    private val prefs = context.getSharedPreferences("mail_vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("cmb.mail", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("cmb.mail", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(account: MailAccount) {
        val json = JSONObject().put("host", account.host).put("port", account.port).put("user", account.user).put("password", account.password).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(json.toByteArray())
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }
    fun load(): MailAccount? = runCatching {
        val data = prefs.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        }
        val json = JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))))
        MailAccount(json.getString("host"), json.getInt("port"), json.getString("user"), json.getString("password"))
    }.getOrNull()
    fun clear() { prefs.edit().clear().commit() }
}

data class Bill(val id: Long, val date: String, val merchant: String, val currency: String, val card: String, val kind: String, val minor: Long, val excluded: Boolean = false)
data class ParseIssue(val subject: String, val reason: String)
class BillStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "bills.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bills (id INTEGER PRIMARY KEY, account TEXT NOT NULL, source TEXT NOT NULL, position INTEGER NOT NULL, date TEXT NOT NULL, merchant TEXT NOT NULL, currency TEXT NOT NULL, card TEXT NOT NULL, kind TEXT NOT NULL, minor INTEGER NOT NULL CHECK(minor>0), excluded INTEGER NOT NULL DEFAULT 0 CHECK(excluded IN(0,1)), UNIQUE(account,source,position))")
        db.execSQL("CREATE INDEX bill_day ON bills(account,date)")
        db.execSQL("CREATE TABLE issues (account TEXT NOT NULL, source TEXT NOT NULL, subject TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(account,source))")
        createProgress(db)
    }
    private fun createProgress(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS processed (account TEXT NOT NULL, folder TEXT NOT NULL, uid INTEGER NOT NULL, PRIMARY KEY(account,folder,uid))") }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if(old<2) createProgress(db)
        if(old<3) {
            db.execSQL("ALTER TABLE bills ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0 CHECK(excluded IN(0,1))")
            // Earlier transfer parsing kept the delimiter before a payee's name.
            db.execSQL("UPDATE bills SET merchant=ltrim(merchant, ' ：:') WHERE substr(merchant,1,1) IN ('：', ':')")
        }
    }
    fun setExcluded(account: String, id: Long, excluded: Boolean): Boolean = writableDatabase.update("bills",
        ContentValues().apply {put("excluded",if(excluded) 1 else 0)}, "account=? AND id=?",arrayOf(account,id.toString()))==1
    fun processed(account: String, folder: String): Set<Long> = readableDatabase.rawQuery("SELECT uid FROM processed WHERE account=? AND folder=?",arrayOf(account,folder)).use { c -> buildSet { while(c.moveToNext()) add(c.getLong(0)) } }
    fun markProcessed(account: String, folder: String, uid: Long) { writableDatabase.insertWithOnConflict("processed",null,ContentValues().apply { put("account",account); put("folder",folder); put("uid",uid) },SQLiteDatabase.CONFLICT_IGNORE) }
    fun resetProgress(account: String) { writableDatabase.delete("processed","account=?",arrayOf(account)) }
    fun save(account: String, source: String, entries: List<BillParser.Entry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            entries.forEachIndexed { index, e ->
                db.insertWithOnConflict("bills", null, ContentValues().apply {
                    put("account", account); put("source", source); put("position", index); put("date", e.date)
                    put("merchant", e.merchant); put("currency", e.currency); put("card", e.card); put("kind", e.kind); put("minor", e.minor)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.delete("issues", "account=? AND source=?", arrayOf(account, source))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun issue(account: String, source: String, subject: String, reason: String) {
        writableDatabase.insertWithOnConflict("issues", null, ContentValues().apply {
            put("account", account); put("source", source); put("subject", subject.take(160)); put("reason", reason)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun resolveIssue(account: String, source: String) { writableDatabase.delete("issues", "account=? AND source=?", arrayOf(account, source)) }
    fun issues(account: String): List<ParseIssue> = readableDatabase.rawQuery("SELECT subject,reason FROM issues WHERE account=? ORDER BY rowid DESC", arrayOf(account)).use { c ->
        buildList { while(c.moveToNext()) add(ParseIssue(c.getString(0),c.getString(1))) }
    }
    fun bills(account: String, today: Boolean = false): List<Bill> {
        val clause = if(today) " AND date>=? AND date<?" else ""
        val args = if(today) arrayOf(account, "${LocalDate.now()}T00:00:00", "${LocalDate.now().plusDays(1)}T00:00:00") else arrayOf(account)
        return readableDatabase.rawQuery("SELECT id,date,merchant,currency,card,kind,minor,excluded FROM bills WHERE account=?$clause ORDER BY date DESC,id DESC", args).use { c ->
            buildList { while(c.moveToNext()) add(Bill(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getInt(7)!=0)) }
        }
    }
}
fun money(minor: Long, currency: String = "CNY"): String = (if(minor<0) "−" else "") + (if(currency == "CNY") "¥" else "$currency ") + java.math.BigDecimal.valueOf(minor,2).abs().toPlainString()
fun kindName(kind: String): String = when(kind) { "REFUND" -> "退款"; "INCOME" -> "收入"; "TRANSFER" -> "理财划转"; "REPAYMENT" -> "还款"; else -> "支出" }
