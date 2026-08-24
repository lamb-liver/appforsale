package com.lambliver.stallpos.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

internal enum class UpgradeStage {
    TEMP_CREATED,
    CONVERTED,
    VALIDATED,
    SOURCE_BACKED_UP,
    REPLACED,
    REOPENED,
}

internal object DatabaseEncryptionUpgrader {
    fun recoverInterrupted(source: File) {
        val temp = File(source.parentFile, "${source.name}.v2.tmp")
        val backup = File(source.parentFile, "${source.name}.v1.backup")
        if (backup.exists()) {
            deleteFileAndSidecars(source)
            check(backup.renameTo(source)) { "Interrupted migration backup could not be restored" }
        }
        deleteFileAndSidecars(temp)
    }

    fun upgrade(
        context: Context,
        source: File,
        passphrase: ByteArray,
        onStage: (UpgradeStage) -> Unit = {},
    ) {
        require(source.isFile) { "Source database does not exist" }
        require(isPlaintext(source)) { "Source database is not plaintext SQLite" }
        require(passphrase.size == 32) { "SQLCipher passphrase must contain 32 bytes" }
        val temp = File(source.parentFile, "${source.name}.v2.tmp")
        val backup = File(source.parentFile, "${source.name}.v1.backup")
        check(!backup.exists()) { "Plaintext migration backup already exists" }
        deleteFileAndSidecars(temp)
        var sourceBackedUp = false
        try {
            check(temp.createNewFile()) { "Encrypted temp database could not be created" }
            onStage(UpgradeStage.TEMP_CREATED)
            val expected = exportEncrypted(source, temp, passphrase)
            onStage(UpgradeStage.CONVERTED)
            validateRoomV2(context, temp, passphrase, expected)
            deleteSidecars(temp)
            onStage(UpgradeStage.VALIDATED)

            check(source.renameTo(backup)) { "Plaintext database could not be moved to backup" }
            sourceBackedUp = true
            deleteSidecars(source)
            onStage(UpgradeStage.SOURCE_BACKED_UP)
            check(temp.renameTo(source)) { "Encrypted database could not replace plaintext database" }
            onStage(UpgradeStage.REPLACED)
            validateRoomV2(context, source, passphrase, expected)
            onStage(UpgradeStage.REOPENED)
            check(backup.delete()) { "Validated plaintext backup could not be removed" }
        } catch (failure: Throwable) {
            deleteFileAndSidecars(temp)
            if (sourceBackedUp && backup.exists()) {
                deleteFileAndSidecars(source)
                check(backup.renameTo(source)) { "Migration failed and plaintext backup could not be restored" }
            }
            throw failure
        }
    }

    fun isPlaintext(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        return file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }

    private fun exportEncrypted(source: File, temp: File, passphrase: ByteArray): ValidationSnapshot {
        System.loadLibrary("sqlcipher")
        val plaintext = SQLiteDatabase.openDatabase(
            source.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        return try {
            plaintext.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            val expected = snapshot(plaintext)
            plaintext.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf(temp.absolutePath, passphrase),
            )
            try {
                plaintext.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                plaintext.rawExecSQL("PRAGMA encrypted.user_version = 1")
            } finally {
                plaintext.rawExecSQL("DETACH DATABASE encrypted")
            }
            expected
        } finally {
            plaintext.close()
        }
    }

    private fun validateRoomV2(
        context: Context,
        file: File,
        passphrase: ByteArray,
        expected: ValidationSnapshot,
    ) {
        val room = StallPosV2Database.open(context, file.absolutePath, passphrase.copyOf())
        try {
            val db = room.openHelper.writableDatabase
            check(db.version == 2) { "Encrypted database schema is not v2" }
            check(snapshot(db) == expected) { "Encrypted database validation totals changed" }
            check(scalarLong(db, "SELECT COUNT(*) FROM product_v2_meta") == expected.productCount)
            check(scalarLong(db, "SELECT COUNT(*) FROM sale_v2_meta") == expected.saleCount)
            check(scalarLong(db, "SELECT COUNT(*) FROM reversal_v2_meta") == expected.reversalCount)
            check(scalarLong(db, "SELECT COUNT(*) FROM inventory_levels") == expected.trackedProductCount)
            check(scalarLong(db, "SELECT COUNT(*) FROM inventory_movements") == expected.positiveStockCount)
            db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Foreign key validation failed" } }
            db.query("PRAGMA integrity_check").use {
                check(it.moveToFirst() && it.getString(0) == "ok") { "SQLite integrity check failed" }
            }
        } finally {
            room.close()
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase): ValidationSnapshot = ValidationSnapshot(
        productCount = scalarLong(db, "SELECT COUNT(*) FROM products"),
        bundleCount = scalarLong(db, "SELECT COUNT(*) FROM bundles"),
        saleCount = scalarLong(db, "SELECT COUNT(*) FROM sales"),
        reversalCount = scalarLong(db, "SELECT COUNT(*) FROM reversals"),
        trackedProductCount = scalarLong(db, "SELECT COUNT(*) FROM products WHERE stock IS NOT NULL"),
        positiveStockCount = scalarLong(db, "SELECT COUNT(*) FROM products WHERE stock > 0"),
        activeRevenue = scalarLong(
            db,
            "SELECT COALESCE(SUM(s.total + s.tip_amount), 0) FROM sales s " +
                "WHERE NOT EXISTS (SELECT 1 FROM reversals r WHERE r.sale_id = s.id)",
        ),
        lastCheckoutSaleId = scalarNullableString(db, "SELECT sale_id FROM last_checkout WHERE slot = 1"),
    )

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun scalarNullableString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor -> if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0) }

    private fun deleteFileAndSidecars(file: File) {
        if (file.exists()) check(file.delete()) { "Temporary migration file could not be removed" }
        deleteSidecars(file)
    }

    private fun deleteSidecars(file: File) {
        listOf(File("${file.path}-wal"), File("${file.path}-shm")).forEach { sidecar ->
            if (sidecar.exists()) check(sidecar.delete()) { "Database sidecar could not be removed" }
        }
    }

    private data class ValidationSnapshot(
        val productCount: Long,
        val bundleCount: Long,
        val saleCount: Long,
        val reversalCount: Long,
        val trackedProductCount: Long,
        val positiveStockCount: Long,
        val activeRevenue: Long,
        val lastCheckoutSaleId: String?,
    )

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()
}
