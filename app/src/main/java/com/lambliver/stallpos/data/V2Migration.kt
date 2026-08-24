package com.lambliver.stallpos.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object V2Migration {
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            validateLegacy(db)
            CREATE_TABLES.forEach(db::execSQL)
            convertLegacy(db)
        }
    }

    private fun validateLegacy(db: SupportSQLiteDatabase) {
        val invalid = listOf(
            "SELECT id FROM products WHERE id = '' OR price < 0 OR stock < 0 LIMIT 1",
            "SELECT id FROM bundles WHERE id = '' OR price < 0 LIMIT 1",
            "SELECT sale_id FROM sale_stock_deductions WHERE quantity <= 0 LIMIT 1",
            "SELECT id FROM sales WHERE id = '' OR subtotal < 0 OR discount < 0 OR total < 0 OR tip_amount < 0 LIMIT 1",
        ).any { sql -> db.query(sql).use { it.moveToFirst() } }
        check(!invalid) { "Legacy database contains invalid values" }
        db.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst()) { "Legacy database contains orphan rows" }
        }
    }

    private fun convertLegacy(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO category_v2_meta(category_type, category_id, updated_at_millis, deleted_at_millis) " +
                "SELECT 'PRODUCT', id, 0, NULL FROM categories",
        )
        db.execSQL(
            "INSERT INTO category_v2_meta(category_type, category_id, updated_at_millis, deleted_at_millis) " +
                "SELECT 'BUNDLE', id, 0, NULL FROM bundle_categories",
        )
        db.execSQL(
            "INSERT INTO product_v2_meta(product_id, cost, track_inventory, is_active, updated_at_millis, deleted_at_millis) " +
                "SELECT id, NULL, stock IS NOT NULL, 1, 0, NULL FROM products",
        )
        db.execSQL(
            "INSERT INTO bundle_v2_meta(bundle_id, is_active, updated_at_millis, deleted_at_millis) " +
                "SELECT id, 1, 0, NULL FROM bundles",
        )
        db.execSQL(
            "INSERT INTO inventory_levels(product_id, location_key, location_type, event_id, quantity, updated_at_millis) " +
                "SELECT id, 'GENERAL', 'GENERAL', NULL, stock, 0 FROM products WHERE stock IS NOT NULL",
        )
        db.query("SELECT id, stock FROM products WHERE stock > 0 ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val productId = cursor.getString(0)
                val movementId = UUID.nameUUIDFromBytes(
                    "stallpos:v2:initial-stock:$productId".toByteArray(StandardCharsets.UTF_8),
                ).toString()
                db.execSQL(
                    "INSERT INTO inventory_movements(" +
                        "id, product_id, event_id, from_location_key, to_location_key, movement_type, quantity, " +
                        "related_transaction_id, occurred_at_millis) VALUES(?, ?, NULL, NULL, 'GENERAL', 'ADJUSTMENT', ?, NULL, 0)",
                    arrayOf(movementId, productId, cursor.getLong(1)),
                )
            }
        }
        db.execSQL(
            "INSERT INTO sale_v2_meta(" +
                "sale_id, event_id, device_id, receipt_number, discount_type, discount_value, discount_amount, net_adjustment) " +
                "SELECT id, NULL, NULL, printf('LEGACY-A-%04d', audit_order + 1), NULL, NULL, discount, 0 FROM sales",
        )
        db.execSQL(
            "INSERT INTO sale_line_snapshots(" +
                "sale_id, line_index, unit_cost_snapshot, original_amount, allocated_discount, allocated_adjustment, final_amount) " +
                "SELECT sale_id, line_index, NULL, line_subtotal, 0, 0, line_subtotal FROM sale_lines " +
                "WHERE checkout_quantity IS NOT NULL AND line_subtotal IS NOT NULL",
        )
        db.execSQL(
            "INSERT INTO reversal_v2_meta(reversal_id, event_id, device_id, payment_method) " +
                "SELECT r.id, NULL, NULL, s.payment_method FROM reversals r JOIN sales s ON s.id = r.sale_id",
        )
        db.execSQL("INSERT OR REPLACE INTO app_meta(`key`, value) VALUES('v2_legacy_conversion', '1')")
    }

    private val CREATE_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `category_v2_meta` (`category_type` TEXT NOT NULL, `category_id` TEXT NOT NULL, `updated_at_millis` INTEGER NOT NULL, `deleted_at_millis` INTEGER, PRIMARY KEY(`category_type`, `category_id`))",
        "CREATE TABLE IF NOT EXISTS `product_v2_meta` (`product_id` TEXT NOT NULL, `cost` INTEGER, `track_inventory` INTEGER NOT NULL, `is_active` INTEGER NOT NULL, `updated_at_millis` INTEGER NOT NULL, `deleted_at_millis` INTEGER, PRIMARY KEY(`product_id`), FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_product_v2_meta_product_id` ON `product_v2_meta` (`product_id`)",
        "CREATE TABLE IF NOT EXISTS `bundle_v2_meta` (`bundle_id` TEXT NOT NULL, `is_active` INTEGER NOT NULL, `updated_at_millis` INTEGER NOT NULL, `deleted_at_millis` INTEGER, PRIMARY KEY(`bundle_id`), FOREIGN KEY(`bundle_id`) REFERENCES `bundles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_bundle_v2_meta_bundle_id` ON `bundle_v2_meta` (`bundle_id`)",
        "CREATE TABLE IF NOT EXISTS `events` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `code` TEXT NOT NULL, `event_type` TEXT NOT NULL, `start_at_millis` INTEGER NOT NULL, `end_at_millis` INTEGER NOT NULL, `actual_open_at_millis` INTEGER, `actual_close_at_millis` INTEGER, `timezone` TEXT NOT NULL, `location` TEXT NOT NULL, `status` TEXT NOT NULL, `updated_at_millis` INTEGER NOT NULL, `deleted_at_millis` INTEGER, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_events_code` ON `events` (`code`)",
        "CREATE INDEX IF NOT EXISTS `index_events_status` ON `events` (`status`)",
        "CREATE TABLE IF NOT EXISTS `inventory_levels` (`product_id` TEXT NOT NULL, `location_key` TEXT NOT NULL, `location_type` TEXT NOT NULL, `event_id` TEXT, `quantity` INTEGER NOT NULL, `updated_at_millis` INTEGER NOT NULL, PRIMARY KEY(`product_id`, `location_key`), FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_inventory_levels_product_id` ON `inventory_levels` (`product_id`)",
        "CREATE INDEX IF NOT EXISTS `index_inventory_levels_event_id` ON `inventory_levels` (`event_id`)",
        "CREATE TABLE IF NOT EXISTS `inventory_movements` (`id` TEXT NOT NULL, `product_id` TEXT NOT NULL, `event_id` TEXT, `from_location_key` TEXT, `to_location_key` TEXT, `movement_type` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `related_transaction_id` TEXT, `occurred_at_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_inventory_movements_product_id` ON `inventory_movements` (`product_id`)",
        "CREATE INDEX IF NOT EXISTS `index_inventory_movements_event_id` ON `inventory_movements` (`event_id`)",
        "CREATE INDEX IF NOT EXISTS `index_inventory_movements_related_transaction_id` ON `inventory_movements` (`related_transaction_id`)",
        "CREATE TABLE IF NOT EXISTS `sale_v2_meta` (`sale_id` TEXT NOT NULL, `event_id` TEXT, `device_id` TEXT, `receipt_number` TEXT NOT NULL, `discount_type` TEXT, `discount_value` INTEGER, `discount_amount` INTEGER NOT NULL, `net_adjustment` INTEGER NOT NULL, PRIMARY KEY(`sale_id`), FOREIGN KEY(`sale_id`) REFERENCES `sales`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_sale_v2_meta_sale_id` ON `sale_v2_meta` (`sale_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sale_v2_meta_receipt_number` ON `sale_v2_meta` (`receipt_number`)",
        "CREATE INDEX IF NOT EXISTS `index_sale_v2_meta_event_id` ON `sale_v2_meta` (`event_id`)",
        "CREATE TABLE IF NOT EXISTS `sale_line_snapshots` (`sale_id` TEXT NOT NULL, `line_index` INTEGER NOT NULL, `unit_cost_snapshot` INTEGER, `original_amount` INTEGER NOT NULL, `allocated_discount` INTEGER NOT NULL, `allocated_adjustment` INTEGER NOT NULL, `final_amount` INTEGER NOT NULL, PRIMARY KEY(`sale_id`, `line_index`), FOREIGN KEY(`sale_id`, `line_index`) REFERENCES `sale_lines`(`sale_id`, `line_index`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_sale_line_snapshots_sale_id_line_index` ON `sale_line_snapshots` (`sale_id`, `line_index`)",
        "CREATE TABLE IF NOT EXISTS `bundle_component_allocations` (`sale_id` TEXT NOT NULL, `line_index` INTEGER NOT NULL, `allocation_index` INTEGER NOT NULL, `product_id` TEXT NOT NULL, `product_name_snapshot` TEXT NOT NULL, `unit_cost_snapshot` INTEGER, `quantity` INTEGER NOT NULL, `allocated_revenue` INTEGER NOT NULL, PRIMARY KEY(`sale_id`, `line_index`, `allocation_index`))",
        "CREATE INDEX IF NOT EXISTS `index_bundle_component_allocations_sale_id_line_index` ON `bundle_component_allocations` (`sale_id`, `line_index`)",
        "CREATE INDEX IF NOT EXISTS `index_bundle_component_allocations_product_id` ON `bundle_component_allocations` (`product_id`)",
        "CREATE TABLE IF NOT EXISTS `reversal_v2_meta` (`reversal_id` TEXT NOT NULL, `event_id` TEXT, `device_id` TEXT, `payment_method` TEXT NOT NULL, PRIMARY KEY(`reversal_id`), FOREIGN KEY(`reversal_id`) REFERENCES `reversals`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_reversal_v2_meta_reversal_id` ON `reversal_v2_meta` (`reversal_id`)",
        "CREATE INDEX IF NOT EXISTS `index_reversal_v2_meta_event_id` ON `reversal_v2_meta` (`event_id`)",
        "CREATE TABLE IF NOT EXISTS `sync_outbox` (`operation_id` TEXT NOT NULL, `category` TEXT NOT NULL, `entity_type` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `operation_type` TEXT NOT NULL, `payload_json` TEXT NOT NULL, `payload_hash` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_error_code` TEXT, `last_error_message` TEXT, `next_attempt_at_millis` INTEGER, `created_at_millis` INTEGER NOT NULL, `updated_at_millis` INTEGER NOT NULL, `synced_at_millis` INTEGER, PRIMARY KEY(`operation_id`))",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_status` ON `sync_outbox` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_category` ON `sync_outbox` (`category`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_created_at_millis` ON `sync_outbox` (`created_at_millis`)",
        "CREATE TABLE IF NOT EXISTS `cloud_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS `device_state` (`slot` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `short_code` TEXT NOT NULL, `name` TEXT NOT NULL, `status` TEXT NOT NULL, `cloud_epoch` INTEGER NOT NULL, `registered_at_millis` INTEGER NOT NULL, `last_seen_at_millis` INTEGER NOT NULL, `retired_at_millis` INTEGER, PRIMARY KEY(`slot`))",
    )
}
