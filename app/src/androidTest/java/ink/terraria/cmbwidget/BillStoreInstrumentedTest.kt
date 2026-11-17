package ink.terraria.cmbwidget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.terraria.cmbwidget.data.*
import ink.terraria.cmbwidget.mail.BillParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillStoreInstrumentedTest {
    private lateinit var context: Context
    @Before fun isolateDatabase() {
        context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="ink.terraria.cmbwidget.verification") { "Run using the isolated verification application ID" }
        context.deleteDatabase("bills.db")
    }

    @Test fun versionTwoUpgradeFixesPayeeAndPreservesLedger() {
        val file=context.getDatabasePath("bills.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE bills (id INTEGER PRIMARY KEY, account TEXT NOT NULL, source TEXT NOT NULL, position INTEGER NOT NULL, date TEXT NOT NULL, merchant TEXT NOT NULL, currency TEXT NOT NULL, card TEXT NOT NULL, kind TEXT NOT NULL, minor INTEGER NOT NULL CHECK(minor>0), UNIQUE(account,source,position))")
            db.execSQL("CREATE TABLE issues (account TEXT NOT NULL, source TEXT NOT NULL, subject TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(account,source))")
            db.execSQL("CREATE TABLE processed (account TEXT NOT NULL, folder TEXT NOT NULL, uid INTEGER NOT NULL, PRIMARY KEY(account,folder,uid))")
            db.execSQL("INSERT INTO bills VALUES(17,'account-a','source-a',0,'2026-01-02T12:00:00','：示例收款人','CNY','1234','EXPENSE',12345)")
            db.execSQL("INSERT INTO bills VALUES(18,'account-a','source-b',0,'2026-01-02T11:00:00','商户：示例店','CNY','1234','EXPENSE',500)")
            db.execSQL("INSERT INTO processed VALUES('account-a','folder-a',42)")
            db.version=2
        }
        BillStore(context).use { store ->
            val entries=store.bills("account-a")
            assertEquals(2,entries.size)
            assertEquals(17L,entries.first().id)
            assertEquals("示例收款人",entries.first().merchant)
            assertEquals(12345L,entries.first().minor)
            assertFalse(entries.first().excluded)
            assertEquals("商户：示例店",entries.last().merchant)
            assertEquals(setOf(42L),store.processed("account-a","folder-a"))
            assertEquals(3,store.readableDatabase.version)
        }
    }

    @Test fun exclusionSurvivesRescanReopenAndAccountIsolation() {
        val entry=BillParser.Entry("2026-01-02T12:00:00","示例收款人","CNY","1234","EXPENSE",1000)
        var id=0L
        BillStore(context).use { store ->
            store.save("account-a","source-a",listOf(entry))
            id=store.bills("account-a").single().id
            assertFalse(store.setExcluded("account-b",id,true))
            assertTrue(store.setExcluded("account-a",id,true))
            store.resetProgress("account-a")
            store.save("account-a","source-a",listOf(entry))
        }
        BillStore(context).use { store ->
            val entryAfterReopen=store.bills("account-a").single()
            assertEquals(id,entryAfterReopen.id)
            assertTrue(entryAfterReopen.excluded)
            assertEquals(0L,netSpending(listOf(entryAfterReopen)))
            assertTrue(store.setExcluded("account-a",id,false))
            assertEquals(1000L,netSpending(store.bills("account-a")))
        }
    }
}
