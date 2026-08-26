package com.lambliver.stallpos.domain

/**
 * Cross-record consistency check. Unit tests own formula correctness;
 * this audit only asks whether the records currently in the snapshot contradict
 * each other. Callers (CI, migration fixtures, 檢查資料) share this function.
 */
data class IntegrityFinding(
    val code: String,
    val message: String,
)

data class IntegrityReport(
    val findings: List<IntegrityFinding>,
) {
    val ok: Boolean get() = findings.isEmpty()

    fun userMessage(): String = if (ok) {
        "資料正常"
    } else {
        buildString {
            append("資料檢查發現 ").append(findings.size).append(" 個問題\n")
            findings.forEach { append("\n• ").append(it.message) }
            append("\n\n代碼：")
            findings.forEach { append("\n").append(it.code) }
        }
    }
}

data class OutboxAuditRow(
    val operationId: String,
    val status: String,
    val errorCode: String? = null,
)

data class IntegritySnapshot(
    val products: List<Product> = emptyList(),
    val categories: List<Category> = emptyList(),
    val bundleCategories: List<BundleCategory> = emptyList(),
    val bundles: List<Bundle> = emptyList(),
    val sales: List<SaleRecord> = emptyList(),
    val reversals: List<SaleReversal> = emptyList(),
    val events: List<MarketEvent> = emptyList(),
    val inventoryLevels: List<InventoryLevel> = emptyList(),
    val inventoryMovements: List<InventoryMovement> = emptyList(),
    val outbox: List<OutboxAuditRow> = emptyList(),
    val lastCheckout: LastCheckout? = null,
    val reportedRevenue: Long? = null,
    val reportedTxCount: Long? = null,
)

object IntegrityAudit {
    private val blockedCodes = setOf(
        "DEVICE_RETIRED",
        "CLOUD_EPOCH_REVOKED",
        "ACCOUNT_DELETED",
        "INVALID_DATA",
        "SERVER_CONFLICT",
    )

    fun audit(snapshot: IntegritySnapshot): IntegrityReport {
        val findings = mutableListOf<IntegrityFinding>()
        uniqueIds(snapshot, findings)
        foreignKeys(snapshot, findings)
        money(snapshot, findings)
        salesAndVoids(snapshot, findings)
        bundles(snapshot, findings)
        inventory(snapshot, findings)
        events(snapshot, findings)
        outbox(snapshot, findings)
        aggregates(snapshot, findings)
        return IntegrityReport(findings)
    }

    private fun uniqueIds(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        fun check(ids: List<String>, label: String) {
            if (ids.any { it.isBlank() }) {
                out += IntegrityFinding("ID-001", "有${label}少了編號")
            }
            if (ids.filter { it.isNotBlank() }.groupingBy { it }.eachCount().any { it.value > 1 }) {
                out += IntegrityFinding("ID-002", "有兩筆${label}編號重複")
            }
        }
        check(snapshot.products.map { it.id }, "商品")
        check(snapshot.categories.map { it.id }, "分類")
        check(snapshot.bundleCategories.map { it.id }, "套組分類")
        check(snapshot.bundles.map { it.id }, "套組")
        check(snapshot.reversals.map { it.id }, "作廢紀錄")
        check(snapshot.events.map { it.id }, "活動")
        check(snapshot.inventoryMovements.map { it.id }, "庫存異動")
        check(snapshot.outbox.map { it.operationId }, "上傳")
        if (snapshot.sales.any { it.id.isBlank() }) {
            out += IntegrityFinding("ID-001", "有交易少了單號")
        }
        val duplicateSaleIds = snapshot.sales.map { it.id }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().any { it.value > 1 }
        val duplicateReceipts = snapshot.sales.mapNotNull { it.receiptNumber }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().any { it.value > 1 }
        if (duplicateSaleIds || duplicateReceipts) {
            out += IntegrityFinding("SALE-002", "有兩筆交易單號重複")
        }
    }

    private fun foreignKeys(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        val productIds = snapshot.products.map { it.id }.toSet()
        val categoryIds = snapshot.categories.map { it.id }.toSet()
        val saleIds = snapshot.sales.map { it.id }.toSet()
        val eventIds = snapshot.events.map { it.id }.toSet()
        val voided = snapshot.reversals.mapTo(HashSet()) { it.saleId }
        snapshot.products.filter { it.categoryId.isNotBlank() && it.categoryId !in categoryIds }
            .forEach { out += IntegrityFinding("FK-001", "商品「${it.name}」的分類找不到了") }
        snapshot.bundles.filter { it.categoryId.isNotBlank() && it.categoryId !in snapshot.bundleCategories.map { c -> c.id }.toSet() }
            .forEach { out += IntegrityFinding("FK-001", "套組「${it.name}」的分類找不到了") }
        snapshot.bundles.forEach { bundle ->
            bundle.components.filter { it.productId !in productIds }.forEach {
                out += IntegrityFinding("FK-001", "套組「${bundle.name}」用到了不存在的商品")
            }
        }
        snapshot.reversals.filter { it.saleId !in saleIds }.forEach {
            out += IntegrityFinding("VOID-001", "作廢紀錄對不到原交易")
        }
        snapshot.lastCheckout?.let { last ->
            if (last.saleId.isNotBlank() && (last.saleId !in saleIds || last.saleId in voided)) {
                out += IntegrityFinding("SALE-005", "上一筆可作廢的交易找不到了")
            }
        }
        snapshot.inventoryLevels.filter { it.productId !in productIds }.forEach {
            out += IntegrityFinding("FK-001", "庫存對到不存在的商品")
        }
        snapshot.inventoryMovements.filter { it.productId !in productIds }.forEach {
            out += IntegrityFinding("FK-001", "庫存異動對到不存在的商品")
        }
        if (snapshot.inventoryLevels.mapNotNull { it.location.eventId }.any { it !in eventIds } ||
            snapshot.sales.mapNotNull { it.eventId }.any { it !in eventIds }
        ) {
            out += IntegrityFinding("EVT-002", "庫存或交易對到不存在的活動")
        }
    }

    private fun money(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        snapshot.products.filter { it.price < 0 }.forEach {
            out += IntegrityFinding("AMT-001", "商品「${it.name}」售價不可為負數")
        }
        snapshot.products.filter { it.cost != null && it.cost!! < 0 }.forEach {
            out += IntegrityFinding("AMT-001", "商品「${it.name}」成本不可為負數")
        }
        snapshot.sales.forEach { sale ->
            if (sale.total < 0 || sale.subtotal < 0 || sale.tipAmount < 0) {
                out += IntegrityFinding("AMT-001", "結帳金額不可為負數")
            }
            if (sale.subtotal != sale.total) {
                out += IntegrityFinding("AMT-002", "這筆交易的金額對不上")
            }
            if (sale.discount != 0L) {
                out += IntegrityFinding("AMT-003", "這筆交易的折扣紀錄異常")
            }
        }
    }

    private fun salesAndVoids(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        snapshot.reversals.groupingBy { it.saleId }.eachCount().filter { it.value > 1 }.forEach {
            out += IntegrityFinding("VOID-002", "同一筆交易作廢了兩次")
        }
        snapshot.sales.forEach { sale ->
            val lines = sale.checkoutLines
            if (lines.isNotEmpty()) {
                val lineSum = lines.sumOf { it.lineSubtotal } + sale.netAdjustment
                val expected = lineSum.coerceAtLeast(0L)
                if (expected != sale.total) {
                    out += IntegrityFinding("SALE-003", "明細加起來跟結帳金額不一樣")
                }
            }
            if (sale.lineFinancialSnapshots.isNotEmpty()) {
                val snapSum = sale.lineFinancialSnapshots.sumOf { it.finalAmount }
                if (snapSum != sale.total) {
                    out += IntegrityFinding("SALE-004", "明細金額跟結帳金額不一樣")
                }
            }
        }
    }

    private fun bundles(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        snapshot.bundles.forEach { bundle ->
            if (bundle.components.isEmpty()) {
                out += IntegrityFinding("BND-001", "套組「${bundle.name}」沒有內容")
            }
            bundle.components.forEach { component ->
                if (component.qty < 1L) {
                    out += IntegrityFinding("BND-001", "套組「${bundle.name}」的內容每項至少要 1")
                }
            }
        }
        snapshot.sales.forEach { sale ->
            sale.checkoutLines.forEachIndexed { index, line ->
                if (line !is SaleCheckoutLine.Bundle) return@forEachIndexed
                val allocations = sale.bundleComponentAllocations.filter { it.lineIndex == index }
                if (allocations.isEmpty()) return@forEachIndexed
                val allocated = allocations.sumOf { it.allocatedRevenue }
                val expected = sale.lineFinancialSnapshots.find { it.lineIndex == index }?.finalAmount
                    ?: line.lineSubtotal
                if (allocated != expected) {
                    out += IntegrityFinding("BND-002", "套組拆出來的金額跟售價不一樣")
                }
            }
        }
    }

    private fun inventory(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        val tracked = snapshot.products.filter { it.stock != null }.associateBy { it.id }
        snapshot.inventoryLevels.filter { it.productId in tracked && it.quantity < 0 }.forEach { level ->
            val name = tracked[level.productId]?.name ?: level.productId
            out += IntegrityFinding("INV-001", "商品「$name」庫存是負的")
        }
        val expected = mutableMapOf<Pair<String, String>, Long>()
        snapshot.inventoryMovements.forEach { movement ->
            if (movement.productId !in tracked) return@forEach
            if (movement.quantity <= 0L) {
                out += IntegrityFinding("INV-004", "庫存異動數量必須大於 0")
                return@forEach
            }
            movement.from?.let { from ->
                val key = movement.productId to from.key
                expected[key] = (expected[key] ?: 0L) - movement.quantity
            }
            movement.to?.let { to ->
                val key = movement.productId to to.key
                expected[key] = (expected[key] ?: 0L) + movement.quantity
            }
        }
        val actual = snapshot.inventoryLevels
            .filter { it.productId in tracked }
            .associate { (it.productId to it.location.key) to it.quantity }
        val keys = expected.keys + actual.keys
        keys.forEach { key ->
            val got = actual[key] ?: 0L
            val want = expected[key] ?: 0L
            if (got != want) {
                val name = tracked[key.first]?.name ?: key.first
                out += IntegrityFinding("INV-003", "商品「$name」的庫存異動跟現有庫存對不上")
            }
        }
    }

    private fun events(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        val active = snapshot.events.filter { it.deletedAtMillis == null && it.status == MarketEventStatus.ACTIVE }
        if (active.size > 1) {
            out += IntegrityFinding("EVT-001", "同時開了兩個活動")
        }
        snapshot.events.map { it.code }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().filter { it.value > 1 }
            .forEach { _ ->
                out += IntegrityFinding("EVT-003", "有兩個活動使用相同代碼")
            }
    }

    private fun outbox(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        snapshot.outbox.forEach { row ->
            if (row.status !in setOf("PENDING", "SYNCED", "BLOCKED")) {
                out += IntegrityFinding("OUT-001", "上傳狀態不對")
            }
            if (row.status == "BLOCKED" && row.errorCode !in blockedCodes) {
                out += IntegrityFinding("OUT-002", "上傳失敗但沒寫原因")
            }
        }
    }

    private fun aggregates(snapshot: IntegritySnapshot, out: MutableList<IntegrityFinding>) {
        val effective = activeSales(snapshot.sales, snapshot.reversals)
        val revenue = effective.sumOf { it.total + it.tipAmount }
        snapshot.reportedRevenue?.let { reported ->
            if (reported != revenue) {
                out += IntegrityFinding("AGG-001", "營收數字跟交易加總不一樣")
            }
        }
        snapshot.reportedTxCount?.let { reported ->
            if (reported != effective.size.toLong()) {
                out += IntegrityFinding("AGG-002", "交易筆數對不上")
            }
        }
    }
}
