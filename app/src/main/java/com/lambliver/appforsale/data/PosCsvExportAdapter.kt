package com.lambliver.appforsale.data

import com.lambliver.appforsale.domain.*

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.StringWriter

/**
 * SAF 寫入銷售 CSV（與結帳邏輯無關的 I／O + 格式化）。
 */
class PosCsvExportAdapter {

    suspend fun writeSalesCsv(
        resolver: ContentResolver,
        uri: Uri,
        totalSales: Long,
        txCount: Long,
        todaySales: Long,
        todayKey: String,
        salesLog: List<SaleRecord>,
        products: List<Product>,
        bundles: List<Bundle>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openOutputStream(uri)?.use { os ->
                os.write("\uFEFF".toByteArray(Charsets.UTF_8))
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { writer ->
                    writePosSalesCsvTo(
                        writer,
                        totalSales,
                        txCount,
                        todaySales,
                        todayKey,
                        salesLog,
                        products,
                        bundles,
                    )
                }
            } ?: error("無法開啟輸出串流")
        }
    }
}

/**
 * 逐行寫入，避免 `buildString { log.forEach }` 產生單一巨型字串與多次中間分配。
 * 單元測試可透過 [buildPosSalesCsv] 仍取得完整字串。
 */
internal fun writePosSalesCsvTo(
    writer: BufferedWriter,
    ts: Long,
    count: Long,
    tdy: Long,
    todayKey: String,
    log: List<SaleRecord>,
    prods: List<Product>,
    bundles: List<Bundle>,
) {
    val pm = prods.associateBy { it.id }
    val bm = bundles.associateBy { it.id }
    fun escape(s: String) = "\"${s.replace("\"", "\"\"")}\""
    writer.write("資料夾,欄位,數值/明細")
    writer.newLine()
    writer.write("報表,累積總額,$ts")
    writer.newLine()
    writer.write("報表,累積筆數,$count")
    writer.newLine()
    writer.write("報表,今日營收($todayKey),$tdy")
    writer.newLine()
    writer.write("報表,CSV明細,全部SaleRecord")
    writer.newLine()
    writer.newLine()
    writer.write("時間戳,日期,付款方式,小計,折扣,總額,小費,購買明細")
    writer.newLine()
    log.forEach { r ->
        val details = formatSaleRecordDetailsForCsv(r, pm, bm)
        val payLabel = when (r.paymentMethod) {
            PaymentMethod.CASH    -> "現金"
            PaymentMethod.DIGITAL -> "行動支付"
        }
        writer.write("${r.tsMillis},${r.dateKey},${escape(payLabel)},${r.subtotal},${r.discount},${r.total},${r.tipAmount},${escape(details)}")
        writer.newLine()
    }
}

internal fun buildPosSalesCsv(
    ts: Long,
    count: Long,
    tdy: Long,
    todayKey: String,
    log: List<SaleRecord>,
    prods: List<Product>,
    bundles: List<Bundle>,
): String {
    val sw = StringWriter()
    BufferedWriter(sw).use { writer ->
        writePosSalesCsvTo(writer, ts, count, tdy, todayKey, log, prods, bundles)
    }
    return sw.toString()
}

internal fun formatSaleRecordDetailsForCsv(r: SaleRecord, pm: Map<String, Product>, bm: Map<String, Bundle>): String {
    val buf = StringBuilder()
    if (r.checkoutLines.isNotEmpty()) {
        buf.append(
            r.checkoutLines.joinToString(";") { line ->
                when (line) {
                    is SaleCheckoutLine.Product ->
                        "${pm[line.productId]?.name ?: "已刪除商品"}x${line.qty}"
                    is SaleCheckoutLine.Bundle ->
                        "${bm[line.bundleId]?.name ?: "套組"}x${line.qty}"
                }
            },
        )
    } else {
        val legacyP = r.cartSnapshot.entries.joinToString(";") {
            "${pm[it.key]?.name ?: "已刪除商品"}x${it.value}"
        }
        buf.append(legacyP)
        if (r.bundleCartSnapshot.isNotEmpty()) {
            if (legacyP.isNotEmpty()) buf.append(";")
            buf.append(
                r.bundleCartSnapshot.entries.joinToString(";") {
                    "${bm[it.key]?.name ?: "套組"}x${it.value}"
                },
            )
        }
    }
    if (r.stockDeductions.isNotEmpty()) {
        if (buf.isNotEmpty()) buf.append(" ")
        buf.append(
            "|展開:" + r.stockDeductions.entries.joinToString(";") {
                "${pm[it.key]?.name ?: it.key}x${it.value}"
            },
        )
    }
    return buf.toString()
}
