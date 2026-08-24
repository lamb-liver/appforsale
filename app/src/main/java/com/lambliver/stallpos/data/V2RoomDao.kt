package com.lambliver.stallpos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface V2RoomDao {
    @Upsert
    suspend fun upsertCategoryMeta(rows: List<CategoryV2MetaEntity>)

    @Query("DELETE FROM category_v2_meta WHERE category_type = :type")
    suspend fun deleteCategoryMeta(type: String)

    @Query("DELETE FROM category_v2_meta WHERE category_type = :type AND category_id NOT IN (:ids)")
    suspend fun deleteCategoryMetaNotIn(type: String, ids: List<String>)

    @Query("SELECT * FROM product_v2_meta ORDER BY product_id")
    suspend fun productMeta(): List<ProductV2MetaEntity>

    @Query("SELECT * FROM product_v2_meta WHERE product_id = :productId LIMIT 1")
    suspend fun productMeta(productId: String): ProductV2MetaEntity?

    @Upsert
    suspend fun upsertProductMeta(rows: List<ProductV2MetaEntity>)

    @Query("DELETE FROM product_v2_meta")
    suspend fun deleteAllProductMeta()

    @Query("DELETE FROM product_v2_meta WHERE product_id NOT IN (:ids)")
    suspend fun deleteProductMetaNotIn(ids: List<String>)

    @Upsert
    suspend fun upsertBundleMeta(rows: List<BundleV2MetaEntity>)

    @Query("DELETE FROM bundle_v2_meta")
    suspend fun deleteAllBundleMeta()

    @Query("DELETE FROM bundle_v2_meta WHERE bundle_id NOT IN (:ids)")
    suspend fun deleteBundleMetaNotIn(ids: List<String>)

    @Query("SELECT * FROM events WHERE deleted_at_millis IS NULL ORDER BY start_at_millis, id")
    suspend fun events(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    suspend fun event(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE status = 'ACTIVE' AND deleted_at_millis IS NULL ORDER BY id")
    suspend fun activeEvents(): List<EventEntity>

    @Upsert
    suspend fun upsertEvent(row: EventEntity)

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()

    @Query("SELECT COUNT(*) FROM sale_v2_meta WHERE event_id = :eventId")
    suspend fun saleCountForEvent(eventId: String): Int

    @Query("SELECT * FROM inventory_levels ORDER BY product_id, location_key")
    suspend fun inventoryLevels(): List<InventoryLevelEntity>

    @Query("SELECT * FROM inventory_levels WHERE product_id = :productId AND location_key = :locationKey LIMIT 1")
    suspend fun inventoryLevel(productId: String, locationKey: String): InventoryLevelEntity?

    @Upsert
    suspend fun upsertInventoryLevel(row: InventoryLevelEntity)

    @Query("DELETE FROM inventory_levels WHERE product_id = :productId")
    suspend fun deleteInventoryLevels(productId: String)

    @Query("DELETE FROM inventory_levels WHERE product_id NOT IN (:ids)")
    suspend fun deleteInventoryLevelsNotIn(ids: List<String>)

    @Query("DELETE FROM inventory_levels")
    suspend fun deleteAllInventoryLevels()

    @Query("DELETE FROM inventory_movements")
    suspend fun deleteAllInventoryMovements()

    @Query("DELETE FROM sale_line_snapshots")
    suspend fun deleteAllSaleLineSnapshots()

    @Query("DELETE FROM bundle_component_allocations")
    suspend fun deleteAllBundleAllocations()

    @Query("DELETE FROM sale_v2_meta")
    suspend fun deleteAllSaleMeta()

    @Query("DELETE FROM reversal_v2_meta")
    suspend fun deleteAllReversalMeta()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInventoryMovement(row: InventoryMovementEntity)

    @Query("SELECT * FROM inventory_movements ORDER BY occurred_at_millis, id")
    suspend fun inventoryMovements(): List<InventoryMovementEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSaleMeta(row: SaleV2MetaEntity)

    @Query("SELECT * FROM sale_v2_meta WHERE sale_id = :saleId LIMIT 1")
    suspend fun saleMeta(saleId: String): SaleV2MetaEntity?

    @Query("SELECT * FROM sale_v2_meta ORDER BY sale_id")
    suspend fun saleMeta(): List<SaleV2MetaEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSaleLineSnapshots(rows: List<SaleLineSnapshotEntity>)

    @Query("SELECT * FROM sale_line_snapshots WHERE sale_id = :saleId ORDER BY line_index")
    suspend fun saleLineSnapshots(saleId: String): List<SaleLineSnapshotEntity>

    @Query("SELECT * FROM sale_line_snapshots ORDER BY sale_id, line_index")
    suspend fun saleLineSnapshots(): List<SaleLineSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBundleAllocations(rows: List<BundleComponentAllocationEntity>)

    @Query("SELECT * FROM bundle_component_allocations WHERE sale_id = :saleId ORDER BY line_index, allocation_index")
    suspend fun bundleAllocations(saleId: String): List<BundleComponentAllocationEntity>

    @Query("SELECT * FROM bundle_component_allocations ORDER BY sale_id, line_index, allocation_index")
    suspend fun bundleAllocations(): List<BundleComponentAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReversalMeta(row: ReversalV2MetaEntity)

    @Query("SELECT * FROM reversal_v2_meta ORDER BY reversal_id")
    suspend fun reversalMeta(): List<ReversalV2MetaEntity>

    @Upsert
    suspend fun upsertInventoryLevels(rows: List<InventoryLevelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInventoryMovements(rows: List<InventoryMovementEntity>)

    @Query("SELECT * FROM device_state WHERE slot = 1 LIMIT 1")
    suspend fun deviceState(): DeviceStateEntity?
}
