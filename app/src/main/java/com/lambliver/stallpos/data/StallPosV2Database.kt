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

    companion object {
        fun open(context: Context, path: String, passphrase: ByteArray): StallPosV2Database {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, StallPosV2Database::class.java, path)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(V2Migration.FROM_1_TO_2)
                .build()
        }
    }
}
