package com.lambliver.stallpos.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "category_v2_meta", primaryKeys = ["category_type", "category_id"])
internal data class CategoryV2MetaEntity(
    @ColumnInfo(name = "category_type") val categoryType: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long?,
)

@Entity(
    tableName = "product_v2_meta",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("product_id")],
)
internal data class ProductV2MetaEntity(
    @PrimaryKey @ColumnInfo(name = "product_id") val productId: String,
    val cost: Long?,
    @ColumnInfo(name = "track_inventory") val trackInventory: Boolean,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long?,
)

@Entity(
    tableName = "bundle_v2_meta",
    foreignKeys = [
        ForeignKey(
            entity = BundleEntity::class,
            parentColumns = ["id"],
            childColumns = ["bundle_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bundle_id")],
)
internal data class BundleV2MetaEntity(
    @PrimaryKey @ColumnInfo(name = "bundle_id") val bundleId: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long?,
)

@Entity(tableName = "events", indices = [Index(value = ["code"], unique = true), Index("status")])
internal data class EventEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "start_at_millis") val startAtMillis: Long,
    @ColumnInfo(name = "end_at_millis") val endAtMillis: Long,
    @ColumnInfo(name = "actual_open_at_millis") val actualOpenAtMillis: Long?,
    @ColumnInfo(name = "actual_close_at_millis") val actualCloseAtMillis: Long?,
    val timezone: String,
    val location: String,
    val status: String,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long?,
)

@Entity(
    tableName = "inventory_levels",
    primaryKeys = ["product_id", "location_key"],
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("product_id"), Index("event_id")],
)
internal data class InventoryLevelEntity(
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "location_key") val locationKey: String,
    @ColumnInfo(name = "location_type") val locationType: String,
    @ColumnInfo(name = "event_id") val eventId: String?,
    val quantity: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

@Entity(
    tableName = "inventory_movements",
    indices = [Index("product_id"), Index("event_id"), Index("related_transaction_id")],
)
internal data class InventoryMovementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "event_id") val eventId: String?,
    @ColumnInfo(name = "from_location_key") val fromLocationKey: String?,
    @ColumnInfo(name = "to_location_key") val toLocationKey: String?,
    @ColumnInfo(name = "movement_type") val movementType: String,
    val quantity: Long,
    @ColumnInfo(name = "related_transaction_id") val relatedTransactionId: String?,
    @ColumnInfo(name = "occurred_at_millis") val occurredAtMillis: Long,
)

@Entity(
    tableName = "sale_v2_meta",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("sale_id"), Index(value = ["receipt_number"], unique = true), Index("event_id")],
)
internal data class SaleV2MetaEntity(
    @PrimaryKey @ColumnInfo(name = "sale_id") val saleId: String,
    @ColumnInfo(name = "event_id") val eventId: String?,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    @ColumnInfo(name = "receipt_number") val receiptNumber: String,
    @ColumnInfo(name = "discount_type") val discountType: String?,
    @ColumnInfo(name = "discount_value") val discountValue: Long?,
    @ColumnInfo(name = "discount_amount") val discountAmount: Long,
    @ColumnInfo(name = "net_adjustment") val netAdjustment: Long,
)

@Entity(
    tableName = "sale_line_snapshots",
    primaryKeys = ["sale_id", "line_index"],
    foreignKeys = [
        ForeignKey(
            entity = SaleLineEntity::class,
            parentColumns = ["sale_id", "line_index"],
            childColumns = ["sale_id", "line_index"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["sale_id", "line_index"])],
)
internal data class SaleLineSnapshotEntity(
    @ColumnInfo(name = "sale_id") val saleId: String,
    @ColumnInfo(name = "line_index") val lineIndex: Int,
    @ColumnInfo(name = "unit_cost_snapshot") val unitCostSnapshot: Long?,
    @ColumnInfo(name = "original_amount") val originalAmount: Long,
    @ColumnInfo(name = "allocated_discount") val allocatedDiscount: Long,
    @ColumnInfo(name = "allocated_adjustment") val allocatedAdjustment: Long,
    @ColumnInfo(name = "final_amount") val finalAmount: Long,
)

@Entity(
    tableName = "bundle_component_allocations",
    primaryKeys = ["sale_id", "line_index", "allocation_index"],
    indices = [Index(value = ["sale_id", "line_index"]), Index("product_id")],
)
internal data class BundleComponentAllocationEntity(
    @ColumnInfo(name = "sale_id") val saleId: String,
    @ColumnInfo(name = "line_index") val lineIndex: Int,
    @ColumnInfo(name = "allocation_index") val allocationIndex: Int,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "product_name_snapshot") val productNameSnapshot: String,
    @ColumnInfo(name = "unit_cost_snapshot") val unitCostSnapshot: Long?,
    val quantity: Long,
    @ColumnInfo(name = "allocated_revenue") val allocatedRevenue: Long,
)

@Entity(
    tableName = "reversal_v2_meta",
    foreignKeys = [
        ForeignKey(
            entity = ReversalEntity::class,
            parentColumns = ["id"],
            childColumns = ["reversal_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("reversal_id"), Index("event_id")],
)
internal data class ReversalV2MetaEntity(
    @PrimaryKey @ColumnInfo(name = "reversal_id") val reversalId: String,
    @ColumnInfo(name = "event_id") val eventId: String?,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    @ColumnInfo(name = "payment_method") val paymentMethod: String,
)

@Entity(tableName = "sync_outbox", indices = [Index("status"), Index("category"), Index("created_at_millis")])
internal data class SyncOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    val category: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "operation_type") val operationType: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "payload_hash") val payloadHash: String,
    val status: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
    @ColumnInfo(name = "last_error_message") val lastErrorMessage: String?,
    @ColumnInfo(name = "next_attempt_at_millis") val nextAttemptAtMillis: Long?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "synced_at_millis") val syncedAtMillis: Long?,
)

@Entity(tableName = "cloud_state")
internal data class CloudStateEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "device_state")
internal data class DeviceStateEntity(
    @PrimaryKey val slot: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "short_code") val shortCode: String,
    val name: String,
    val status: String,
    @ColumnInfo(name = "cloud_epoch") val cloudEpoch: Long,
    @ColumnInfo(name = "registered_at_millis") val registeredAtMillis: Long,
    @ColumnInfo(name = "last_seen_at_millis") val lastSeenAtMillis: Long,
    @ColumnInfo(name = "retired_at_millis") val retiredAtMillis: Long?,
)
