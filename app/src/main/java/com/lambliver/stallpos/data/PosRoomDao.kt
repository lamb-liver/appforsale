package com.lambliver.stallpos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface PosRoomDao {
    @Query("SELECT * FROM categories ORDER BY sort_order, id")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM products ORDER BY sort_order, id")
    suspend fun products(): List<ProductEntity>

    @Query("SELECT * FROM bundle_categories ORDER BY sort_order, id")
    suspend fun bundleCategories(): List<BundleCategoryEntity>

    @Query("SELECT * FROM bundles ORDER BY sort_order, id")
    suspend fun bundles(): List<BundleEntity>

    @Query("SELECT * FROM bundle_components ORDER BY bundle_id, component_index")
    suspend fun bundleComponents(): List<BundleComponentEntity>

    @Query("SELECT * FROM cart_items ORDER BY item_type, item_id")
    suspend fun cartItems(): List<CartItemEntity>

    @Query("SELECT * FROM sales ORDER BY audit_order, id")
    suspend fun sales(): List<SaleEntity>

    @Query("SELECT * FROM sales WHERE id = :saleId LIMIT 1")
    suspend fun sale(saleId: String): SaleEntity?

    @Query("SELECT * FROM sale_lines ORDER BY sale_id, line_index")
    suspend fun saleLines(): List<SaleLineEntity>

    @Query("SELECT * FROM sale_lines WHERE sale_id = :saleId ORDER BY line_index")
    suspend fun saleLines(saleId: String): List<SaleLineEntity>

    @Query("SELECT * FROM sale_stock_deductions ORDER BY sale_id, product_id")
    suspend fun stockDeductions(): List<SaleStockDeductionEntity>

    @Query("SELECT * FROM sale_stock_deductions WHERE sale_id = :saleId ORDER BY product_id")
    suspend fun stockDeductions(saleId: String): List<SaleStockDeductionEntity>

    @Query("SELECT * FROM reversals ORDER BY audit_order, id")
    suspend fun reversals(): List<ReversalEntity>

    @Query("SELECT COUNT(*) FROM reversals WHERE sale_id = :saleId")
    suspend fun reversalCountForSale(saleId: String): Int

    @Query("SELECT * FROM last_checkout WHERE slot = 1 LIMIT 1")
    suspend fun lastCheckout(): LastCheckoutEntity?

    @Query(
        """
        SELECT
            COALESCE(SUM(s.total + s.tip_amount), 0) AS revenue,
            COUNT(*) AS tx_count
        FROM sales s
        WHERE NOT EXISTS (SELECT 1 FROM reversals r WHERE r.sale_id = s.id)
        """,
    )
    suspend fun activeSalesSummary(): ActiveSalesSummary

    @Query("SELECT COALESCE(MAX(audit_order), -1) + 1 FROM sales")
    suspend fun nextSaleAuditOrder(): Long

    @Query("SELECT COALESCE(MAX(audit_order), -1) + 1 FROM reversals")
    suspend fun nextReversalAuditOrder(): Long

    @Query("SELECT value FROM app_meta WHERE `key` = :key LIMIT 1")
    suspend fun metaValue(key: String): String?

    @Upsert suspend fun upsertCategories(rows: List<CategoryEntity>)
    @Upsert suspend fun upsertProducts(rows: List<ProductEntity>)
    @Upsert suspend fun upsertBundleCategories(rows: List<BundleCategoryEntity>)
    @Upsert suspend fun upsertBundles(rows: List<BundleEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBundleComponents(rows: List<BundleComponentEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCartItems(rows: List<CartItemEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSale(row: SaleEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSaleLines(rows: List<SaleLineEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertStockDeductions(rows: List<SaleStockDeductionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReversal(row: ReversalEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceLastCheckout(row: LastCheckoutEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(row: AppMetaEntity)

    @Query("UPDATE products SET stock = :stock WHERE id = :productId")
    suspend fun updateProductStock(productId: String, stock: Long?)

    @Query("DELETE FROM categories") suspend fun deleteAllCategories()
    @Query("DELETE FROM categories WHERE id NOT IN (:ids)") suspend fun deleteCategoriesNotIn(ids: List<String>)
    @Query("DELETE FROM products") suspend fun deleteAllProducts()
    @Query("DELETE FROM products WHERE id NOT IN (:ids)") suspend fun deleteProductsNotIn(ids: List<String>)
    @Query("DELETE FROM bundle_categories") suspend fun deleteAllBundleCategories()
    @Query("DELETE FROM bundle_categories WHERE id NOT IN (:ids)") suspend fun deleteBundleCategoriesNotIn(ids: List<String>)
    @Query("DELETE FROM bundles") suspend fun deleteAllBundles()
    @Query("DELETE FROM bundles WHERE id NOT IN (:ids)") suspend fun deleteBundlesNotIn(ids: List<String>)
    @Query("DELETE FROM bundle_components") suspend fun deleteAllBundleComponents()
    @Query("DELETE FROM cart_items") suspend fun deleteAllCartItems()
    @Query("DELETE FROM last_checkout") suspend fun deleteLastCheckout()
    @Query("DELETE FROM reversals") suspend fun deleteAllReversals()
    @Query("DELETE FROM sale_stock_deductions") suspend fun deleteAllStockDeductions()
    @Query("DELETE FROM sale_lines") suspend fun deleteAllSaleLines()
    @Query("DELETE FROM sales") suspend fun deleteAllSales()
}
