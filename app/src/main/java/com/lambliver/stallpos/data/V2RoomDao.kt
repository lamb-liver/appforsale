package com.lambliver.stallpos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    @Upsert
    suspend fun putDeviceState(row: DeviceStateEntity)

    @Query("SELECT value FROM cloud_state WHERE key = :key LIMIT 1")
    suspend fun cloudValue(key: String): String?

    @Query("SELECT value FROM cloud_state WHERE key = :key LIMIT 1")
    fun observeCloudValue(key: String): Flow<String?>

    @Upsert
    suspend fun putCloudState(rows: List<CloudStateEntity>)

    @Query("DELETE FROM cloud_state WHERE key IN (:keys)")
    suspend fun deleteCloudState(keys: List<String>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(row: SyncOutboxEntity)

    @Query(
        "SELECT * FROM sync_outbox WHERE status = 'PENDING' " +
            "AND (next_attempt_at_millis IS NULL OR next_attempt_at_millis <= :nowMillis) " +
            "ORDER BY CASE category WHEN 'MASTER' THEN 0 WHEN 'INVENTORY' THEN 1 ELSE 2 END, " +
            "CASE entity_type WHEN 'CATEGORY' THEN 0 WHEN 'PRODUCT' THEN 1 WHEN 'BUNDLE' THEN 2 WHEN 'EVENT' THEN 3 ELSE 4 END, " +
            "created_at_millis, operation_id LIMIT :limit",
    )
    suspend fun pendingOutbox(nowMillis: Long, limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = 'PENDING'")
    suspend fun pendingOutboxCount(): Int

    @Query("SELECT * FROM sync_outbox ORDER BY created_at_millis, operation_id")
    suspend fun outboxRows(): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox")
    suspend fun deleteAllOutbox()

    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count, " +
            "COALESCE(SUM(CASE WHEN status = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked_count " +
            "FROM sync_outbox",
    )
    fun observeOutboxCounts(): Flow<SyncOutboxCounts>

    @Query(
        "UPDATE sync_outbox SET status = 'SYNCED', last_error_code = NULL, last_error_message = NULL, " +
            "next_attempt_at_millis = NULL, updated_at_millis = :nowMillis, synced_at_millis = :nowMillis " +
            "WHERE operation_id IN (:operationIds)",
    )
    suspend fun markOutboxSynced(operationIds: List<String>, nowMillis: Long)

    @Query(
        "UPDATE sync_outbox SET status = 'BLOCKED', attempt_count = attempt_count + 1, " +
            "last_error_code = :code, last_error_message = :message, next_attempt_at_millis = NULL, " +
            "updated_at_millis = :nowMillis WHERE operation_id = :operationId",
    )
    suspend fun markOutboxBlocked(operationId: String, code: String, message: String?, nowMillis: Long)

    @Query(
        "UPDATE sync_outbox SET status = 'PENDING', attempt_count = attempt_count + 1, " +
            "last_error_code = NULL, last_error_message = :message, next_attempt_at_millis = :nextAttemptAtMillis, " +
            "updated_at_millis = :nowMillis WHERE operation_id IN (:operationIds)",
    )
    suspend fun markOutboxPending(
        operationIds: List<String>,
        message: String?,
        nextAttemptAtMillis: Long,
        nowMillis: Long,
    )
}

internal data class SyncOutboxCounts(
    @androidx.room.ColumnInfo(name = "pending_count") val pendingCount: Int,
    @androidx.room.ColumnInfo(name = "blocked_count") val blockedCount: Int,
)
