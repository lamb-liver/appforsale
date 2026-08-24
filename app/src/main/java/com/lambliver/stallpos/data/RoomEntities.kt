package com.lambliver.stallpos.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
internal data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
)

@Entity(tableName = "products")
internal data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val price: Long,
    @ColumnInfo(name = "category_id")
    val categoryId: String,
    val stock: Long?,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
)

@Entity(tableName = "bundle_categories")
internal data class BundleCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
)

@Entity(tableName = "bundles")
internal data class BundleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val price: Long,
    @ColumnInfo(name = "category_id")
    val categoryId: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
)

@Entity(
    tableName = "bundle_components",
    primaryKeys = ["bundle_id", "component_index"],
    foreignKeys = [
        ForeignKey(
            entity = BundleEntity::class,
            parentColumns = ["id"],
            childColumns = ["bundle_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("bundle_id"), Index("product_id")],
)
internal data class BundleComponentEntity(
    @ColumnInfo(name = "bundle_id")
    val bundleId: String,
    @ColumnInfo(name = "component_index")
    val componentIndex: Int,
    @ColumnInfo(name = "product_id")
    val productId: String,
    val quantity: Long,
)

@Entity(tableName = "cart_items", primaryKeys = ["item_type", "item_id"])
internal data class CartItemEntity(
    @ColumnInfo(name = "item_type")
    val itemType: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    val quantity: Int,
)

@Entity(tableName = "sales", indices = [Index(value = ["audit_order"], unique = true)])
internal data class SaleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "audit_order")
    val auditOrder: Long,
    @ColumnInfo(name = "ts_millis")
    val tsMillis: Long,
    @ColumnInfo(name = "date_key")
    val dateKey: String,
    val subtotal: Long,
    val discount: Long,
    val total: Long,
    @ColumnInfo(name = "payment_method")
    val paymentMethod: String,
    @ColumnInfo(name = "tip_amount")
    val tipAmount: Long,
)

@Entity(
    tableName = "sale_lines",
    primaryKeys = ["sale_id", "line_index"],
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("sale_id")],
)
internal data class SaleLineEntity(
    @ColumnInfo(name = "sale_id")
    val saleId: String,
    @ColumnInfo(name = "line_index")
    val lineIndex: Int,
    @ColumnInfo(name = "item_type")
    val itemType: String,
    @ColumnInfo(name = "item_ref_id")
    val itemRefId: String,
    @ColumnInfo(name = "cart_quantity")
    val cartQuantity: Int?,
    @ColumnInfo(name = "checkout_quantity")
    val checkoutQuantity: Int?,
    @ColumnInfo(name = "unit_price")
    val unitPrice: Long?,
    @ColumnInfo(name = "line_subtotal")
    val lineSubtotal: Long?,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
)

@Entity(
    tableName = "sale_stock_deductions",
    primaryKeys = ["sale_id", "product_id"],
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("sale_id")],
)
internal data class SaleStockDeductionEntity(
    @ColumnInfo(name = "sale_id")
    val saleId: String,
    @ColumnInfo(name = "product_id")
    val productId: String,
    val quantity: Long,
)

@Entity(
    tableName = "reversals",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["audit_order"], unique = true), Index(value = ["sale_id"], unique = true)],
)
internal data class ReversalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "audit_order")
    val auditOrder: Long,
    @ColumnInfo(name = "sale_id")
    val saleId: String,
    @ColumnInfo(name = "ts_millis")
    val tsMillis: Long,
    val reason: String,
)

@Entity(
    tableName = "last_checkout",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["sale_id"], unique = true)],
)
internal data class LastCheckoutEntity(
    @PrimaryKey val slot: Int = 1,
    @ColumnInfo(name = "sale_id")
    val saleId: String,
)

@Entity(tableName = "app_meta")
internal data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

internal data class ActiveSalesSummary(
    val revenue: Long,
    @ColumnInfo(name = "tx_count")
    val txCount: Long,
)

internal const val ROOM_ITEM_PRODUCT = "PRODUCT"
internal const val ROOM_ITEM_BUNDLE = "BUNDLE"
internal const val LEGACY_IMPORT_VERSION_KEY = "legacy_import_version"
