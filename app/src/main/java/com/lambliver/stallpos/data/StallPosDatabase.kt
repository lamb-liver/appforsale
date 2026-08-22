package com.lambliver.stallpos.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

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
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class StallPosDatabase : RoomDatabase() {
    abstract fun posDao(): PosRoomDao

    companion object {
        const val NAME = "stallpos.db"

        @Volatile
        private var instance: StallPosDatabase? = null

        fun get(context: Context): StallPosDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder<StallPosDatabase>(
                    context.applicationContext,
                    NAME,
                ).setDriver(BundledSQLiteDriver())
                    .build()
                    .also { instance = it }
            }
    }
}
