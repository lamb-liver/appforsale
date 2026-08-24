package com.lambliver.stallpos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CategoryEntity::class,
        ProductEntity::class,
        BundleCategoryEntity::class,
        BundleEntity::class,
        BundleComponentEntity::class,
        CartItemEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        SaleStockDeductionEntity::class,
        ReversalEntity::class,
        LastCheckoutEntity::class,
        AppMetaEntity::class,
        CategoryV2MetaEntity::class,
        ProductV2MetaEntity::class,
        BundleV2MetaEntity::class,
        EventEntity::class,
        InventoryLevelEntity::class,
        InventoryMovementEntity::class,
        SaleV2MetaEntity::class,
        SaleLineSnapshotEntity::class,
        BundleComponentAllocationEntity::class,
        ReversalV2MetaEntity::class,
        SyncOutboxEntity::class,
        CloudStateEntity::class,
        DeviceStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class StallPosV2Database : RoomDatabase() {
    abstract fun posDao(): PosRoomDao
    abstract fun v2Dao(): V2RoomDao

    companion object {
        @Volatile
        private var instance: StallPosV2Database? = null

        fun get(context: Context): StallPosV2Database = instance ?: synchronized(this) {
            instance ?: openCanonical(context).also { instance = it }
        }

        private fun openCanonical(context: Context): StallPosV2Database {
            val source = context.applicationContext.getDatabasePath(StallPosDatabase.NAME)
            DatabaseEncryptionUpgrader.recoverInterrupted(source)
            val passphrase = DatabasePassphraseStore.getOrCreate(context)
            return try {
                if (source.isFile && DatabaseEncryptionUpgrader.isPlaintext(source)) {
                    DatabaseEncryptionUpgrader.upgrade(context, source, passphrase)
                }
                open(context, source.absolutePath, passphrase.copyOf()).also {
                    it.openHelper.writableDatabase
                }
            } finally {
                passphrase.fill(0)
            }
        }

        fun open(context: Context, path: String, passphrase: ByteArray): StallPosV2Database {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, StallPosV2Database::class.java, path)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(V2Migration.FROM_1_TO_2)
                .build()
        }
    }
}
