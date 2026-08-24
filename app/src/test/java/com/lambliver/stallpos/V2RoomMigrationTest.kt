package com.lambliver.stallpos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lambliver.stallpos.data.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class V2RoomMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseNames = mutableListOf<String>()

    @After
    fun cleanup() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun v1Database_migratesLegacyDataIntoValidatedV2Schema() = runBlocking {
        val name = databaseName()
        createLegacyDatabase(name)

        val migrated = Room.databaseBuilder(context, StallPosV2Database::class.java, name)
            .addMigrations(V2Migration.FROM_1_TO_2)
            .allowMainThreadQueries()
            .build()
        val db = migrated.openHelper.writableDatabase

        assertEquals(2, db.version)
        assertEquals(3L, scalarLong(db, "SELECT COUNT(*) FROM products"))
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM sales"))
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM reversals"))
        assertEquals(20L, scalarLong(db, "SELECT quantity FROM inventory_levels WHERE product_id = 'p-stock'"))
        assertEquals(0L, scalarLong(db, "SELECT quantity FROM inventory_levels WHERE product_id = 'p-zero'"))
        assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM inventory_levels WHERE product_id = 'p-untracked'"))
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM inventory_movements"))
        assertEquals(20L, scalarLong(db, "SELECT quantity FROM inventory_movements"))
        assertEquals("ADJUSTMENT", scalarString(db, "SELECT movement_type FROM inventory_movements"))
        assertEquals("LEGACY-A-0001", scalarString(db, "SELECT receipt_number FROM sale_v2_meta"))
        assertEquals("DIGITAL", scalarString(db, "SELECT payment_method FROM reversal_v2_meta"))
        assertEquals(100L, scalarLong(db, "SELECT final_amount FROM sale_line_snapshots"))
        assertNull(scalarNullableLong(db, "SELECT cost FROM product_v2_meta WHERE product_id = 'p-stock'"))
        assertNull(scalarNullableLong(db, "SELECT unit_cost_snapshot FROM sale_line_snapshots"))
        assertEquals("1", scalarString(db, "SELECT value FROM app_meta WHERE `key` = 'v2_legacy_conversion'"))
        val movementId = scalarString(db, "SELECT id FROM inventory_movements")
        assertEquals(
            UUID.nameUUIDFromBytes("stallpos:v2:initial-stock:p-stock".toByteArray()).toString(),
            movementId,
        )
        migrated.close()
    }

    @Test
    fun malformedLegacyValue_abortsMigrationAndLeavesV1Readable() = runBlocking {
        val name = databaseName()
        val legacy = Room.databaseBuilder(context, StallPosDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        legacy.posDao().upsertProducts(listOf(ProductEntity("bad", "Bad", 10, "", -1, 0)))
        legacy.close()

        val migrated = Room.databaseBuilder(context, StallPosV2Database::class.java, name)
            .addMigrations(V2Migration.FROM_1_TO_2)
            .allowMainThreadQueries()
            .build()
        assertTrue(runCatching { migrated.openHelper.writableDatabase }.isFailure)
        migrated.close()

        val reopened = Room.databaseBuilder(context, StallPosDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        assertEquals(-1L, reopened.posDao().products().single().stock)
        assertFalse(context.getDatabasePath(name).readBytes().isEmpty())
        reopened.close()
    }

    @Test
    fun orphanReversal_abortsMigrationAndLeavesV1FileAtVersionOne() = runBlocking {
        val name = databaseName()
        createLegacyDatabase(name)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.setForeignKeyConstraintsEnabled(false)
            db.execSQL(
                "INSERT INTO reversals(id, audit_order, sale_id, ts_millis, reason) " +
                    "VALUES('orphan', 2, 'missing', 1, 'UNDO_LAST_CHECKOUT')",
            )
        }

        val migrated = Room.databaseBuilder(context, StallPosV2Database::class.java, name)
            .addMigrations(V2Migration.FROM_1_TO_2)
            .allowMainThreadQueries()
            .build()
        assertTrue(runCatching { migrated.openHelper.writableDatabase }.isFailure)
        migrated.close()

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(1, db.version)
            assertEquals(2L, db.rawQuery("SELECT COUNT(*) FROM reversals", null).use { it.moveToFirst(); it.getLong(0) })
        }
    }

    @Test
    fun duplicateSaleUuid_isRejectedByV1PrimaryKey() = runBlocking {
        val name = databaseName()
        val legacy = Room.databaseBuilder(context, StallPosDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val sale = SaleEntity("duplicate", 0, 1, "2026-08-24", 1, 0, 1, "CASH", 0)
        legacy.posDao().insertSale(sale)
        assertTrue(runCatching { legacy.posDao().insertSale(sale.copy(auditOrder = 1)) }.isFailure)
        assertEquals(1, legacy.posDao().sales().size)
        legacy.close()
    }

    private suspend fun createLegacyDatabase(name: String) {
        val legacy = Room.databaseBuilder(context, StallPosDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val dao = legacy.posDao()
        dao.upsertCategories(listOf(CategoryEntity("c", "商品", 0)))
        dao.upsertProducts(
            listOf(
                ProductEntity("p-stock", "徽章", 100, "c", 20, 0),
                ProductEntity("p-zero", "零庫存", 50, "c", 0, 1),
                ProductEntity("p-untracked", "無限", 30, "c", null, 2),
            ),
        )
        dao.insertSale(SaleEntity("sale", 0, 1_700_000_000_000, "2023-11-15", 100, 0, 100, "DIGITAL", 10))
        dao.insertSaleLines(
            listOf(SaleLineEntity("sale", 0, ROOM_ITEM_PRODUCT, "p-stock", 1, 1, 100, 100, "舊名")),
        )
        dao.insertStockDeductions(listOf(SaleStockDeductionEntity("sale", "p-stock", 1)))
        dao.insertReversal(ReversalEntity("void", 0, "sale", 1_700_000_100_000, "UNDO_LAST_CHECKOUT"))
        dao.replaceLastCheckout(LastCheckoutEntity(saleId = "sale"))
        legacy.close()
    }

    private fun databaseName() = "m1-${UUID.randomUUID()}.db".also(databaseNames::add)

    private fun scalarLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun scalarNullableLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long? =
        db.query(sql).use { cursor -> check(cursor.moveToFirst()); if (cursor.isNull(0)) null else cursor.getLong(0) }

    private fun scalarString(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
}
