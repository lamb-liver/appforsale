import { HttpError, json } from "./http";
import { isUuid } from "./validation";

const TRANSACTION_PAGE_SIZE = 50;
const TRANSACTION_PAGE_MAX = 100;
const CSV_PAGE_SIZE = 200;
const PAYMENT_METHODS = new Set(["CASH", "LINE_PAY", "JKOPAY", "OTHER"]);

export async function handleEventReports(env: Env, userId: string, requestId: string): Promise<Response> {
  const rows = await env.POS_DB.prepare(
    `SELECT e.id,e.name,e.code,e.status,e.timezone,e.start_at_utc,e.end_at_utc,
      COUNT(s.id) AS transaction_count,
      COALESCE(SUM(s.final_total+s.tip_amount),0) AS revenue
     FROM events e LEFT JOIN sales s ON s.user_id=e.user_id AND s.event_id=e.id
       AND NOT EXISTS (SELECT 1 FROM voids v WHERE v.user_id=s.user_id AND v.sale_id=s.id)
     WHERE e.user_id=? GROUP BY e.id ORDER BY e.start_at_utc DESC`,
  ).bind(userId).all<EventListRow>();
  return json({ requestId, events: rows.results.map((row) => ({
    id: row.id, name: row.name, code: row.code, status: row.status, timezone: row.timezone,
    startAtUtc: row.start_at_utc, endAtUtc: row.end_at_utc,
    transactionCount: row.transaction_count, revenue: row.revenue,
  })) }, 200, requestId);
}

export async function handleEventReport(env: Env, userId: string, eventId: string, requestId: string): Promise<Response> {
  if (!isUuid(eventId)) return json({ requestId, code: "INVALID_DATA", message: "Event ID is invalid." }, 400, requestId);
  const event = await env.POS_DB.prepare(
    "SELECT id,name,code,status,timezone,start_at_utc,end_at_utc FROM events WHERE user_id=? AND id=?",
  ).bind(userId, eventId).first<EventListRow>();
  if (!event) return json({ requestId, code: "NOT_FOUND", message: "Event was not found." }, 404, requestId);

  const activeSale = `s.user_id=? AND s.event_id=? AND NOT EXISTS
    (SELECT 1 FROM voids v WHERE v.user_id=s.user_id AND v.sale_id=s.id)`;
  const [summary, productRows, saleRows, pairRows, bundleRows, inventoryRows] = await Promise.all([
    env.POS_DB.prepare(
      `SELECT COUNT(*) AS transaction_count,COALESCE(SUM(s.final_total+s.tip_amount),0) AS revenue,
       COALESCE(SUM(s.tip_amount),0) AS tips,COALESCE(AVG(s.final_total+s.tip_amount),0) AS average_order_value
       FROM sales s WHERE ${activeSale}`,
    ).bind(userId, eventId).first<SummaryRow>(),
    env.POS_DB.prepare(
      `SELECT product_id,MIN(name) AS name,SUM(quantity) AS quantity,SUM(revenue) AS revenue,
       SUM(known_cost) AS known_cost,SUM(missing_cost_units) AS missing_cost_units
       FROM (
         SELECT l.item_ref_id AS product_id,l.display_name AS name,l.quantity,l.final_amount AS revenue,
          CASE WHEN l.unit_cost_snapshot IS NULL THEN 0 ELSE l.unit_cost_snapshot*l.quantity END AS known_cost,
          CASE WHEN l.unit_cost_snapshot IS NULL THEN l.quantity ELSE 0 END AS missing_cost_units
         FROM sale_lines l JOIN sales s ON s.user_id=l.user_id AND s.id=l.sale_id
         WHERE ${activeSale} AND l.item_type='PRODUCT'
         UNION ALL
         SELECT a.product_id,a.product_name_snapshot,a.quantity,a.allocated_revenue,
          CASE WHEN a.unit_cost_snapshot IS NULL THEN 0 ELSE a.unit_cost_snapshot*a.quantity END,
          CASE WHEN a.unit_cost_snapshot IS NULL THEN a.quantity ELSE 0 END
         FROM bundle_component_allocations a JOIN sales s ON s.user_id=a.user_id AND s.id=a.sale_id
         WHERE ${activeSale}
       ) GROUP BY product_id ORDER BY quantity DESC,revenue DESC,product_id`,
    ).bind(userId, eventId, userId, eventId).all<ProductRow>(),
    env.POS_DB.prepare(
      `SELECT s.occurred_at_utc,s.final_total+s.tip_amount AS revenue,s.payment_method
       FROM sales s WHERE ${activeSale} ORDER BY s.occurred_at_utc`,
    ).bind(userId, eventId).all<SaleTimeRow>(),
    env.POS_DB.prepare(
      `SELECT a.item_ref_id AS first_id,MIN(a.display_name) AS first_name,
       b.item_ref_id AS second_id,MIN(b.display_name) AS second_name,COUNT(*) AS sale_count
       FROM sale_lines a JOIN sale_lines b ON b.user_id=a.user_id AND b.sale_id=a.sale_id
        AND b.item_type='PRODUCT' AND a.item_ref_id<b.item_ref_id
       JOIN sales s ON s.user_id=a.user_id AND s.id=a.sale_id
       WHERE ${activeSale} AND a.item_type='PRODUCT'
       GROUP BY a.item_ref_id,b.item_ref_id HAVING COUNT(*)>=5
       ORDER BY sale_count DESC,first_id,second_id`,
    ).bind(userId, eventId).all<PairRow>(),
    env.POS_DB.prepare(
      `SELECT l.item_ref_id AS bundle_id,MIN(l.display_name) AS name,SUM(l.quantity) AS quantity,
       SUM(l.final_amount) AS revenue
       FROM sale_lines l JOIN sales s ON s.user_id=l.user_id AND s.id=l.sale_id
       WHERE ${activeSale} AND l.item_type='BUNDLE'
       GROUP BY l.item_ref_id ORDER BY quantity DESC,revenue DESC,bundle_id`,
    ).bind(userId, eventId).all<BundleRow>(),
    env.POS_DB.prepare(
      `SELECT im.product_id,COALESCE(p.name,im.product_id) AS name,
       SUM(CASE WHEN im.to_location_key='EVENT:'||? AND im.movement_type IN ('ALLOCATE_TO_EVENT','ADJUSTMENT') THEN im.quantity ELSE 0 END) AS supplied,
       SUM(CASE WHEN im.from_location_key='EVENT:'||? AND im.movement_type='SALE' THEN im.quantity ELSE 0 END) AS sold,
       SUM(CASE WHEN im.from_location_key='EVENT:'||? AND im.movement_type='DAMAGE' THEN im.quantity ELSE 0 END) AS damaged,
       SUM(CASE WHEN im.from_location_key='EVENT:'||? AND im.movement_type IN ('RETURN_FROM_EVENT','ADJUSTMENT') THEN im.quantity ELSE 0 END) AS returned,
       COALESCE(MAX(il.quantity),0) AS ending
       FROM inventory_movements im LEFT JOIN products p ON p.user_id=im.user_id AND p.id=im.product_id
       LEFT JOIN inventory_levels il ON il.user_id=im.user_id AND il.product_id=im.product_id AND il.event_id=?
       WHERE im.user_id=? AND im.event_id=? GROUP BY im.product_id ORDER BY sold DESC,name`,
    ).bind(eventId, eventId, eventId, eventId, eventId, userId, eventId).all<InventoryRow>(),
  ]);

  const products = productRows.results.map((row) => ({
    productId: row.product_id, name: row.name, quantity: row.quantity, revenue: row.revenue,
    cost: row.missing_cost_units === 0 ? row.known_cost : null,
    grossProfit: row.missing_cost_units === 0 ? row.revenue - row.known_cost : null,
    missingCostUnits: row.missing_cost_units,
  }));
  const buckets = timeBuckets(saleRows.results, event.timezone);
  return json({
    requestId,
    event: { id: event.id, name: event.name, code: event.code, status: event.status, timezone: event.timezone,
      startAtUtc: event.start_at_utc, endAtUtc: event.end_at_utc },
    summary: {
      transactionCount: summary?.transaction_count ?? 0,
      revenue: summary?.revenue ?? 0,
      tips: summary?.tips ?? 0,
      averageOrderValue: Math.round(summary?.average_order_value ?? 0),
      grossProfit: products.every((row) => row.cost !== null) ? products.reduce((sum, row) => sum + (row.grossProfit ?? 0), 0) : null,
      costComplete: products.every((row) => row.cost !== null),
    },
    products,
    trends: buckets.trends,
    hourly: buckets.hourly,
    payments: buckets.payments,
    coPurchases: pairRows.results.map((row) => ({ firstId: row.first_id, firstName: row.first_name,
      secondId: row.second_id, secondName: row.second_name, saleCount: row.sale_count })),
    bundles: bundleRows.results.map((row) => ({ bundleId: row.bundle_id, name: row.name, quantity: row.quantity, revenue: row.revenue })),
    inventory: inventoryRows.results.map((row) => ({ productId: row.product_id, name: row.name, supplied: row.supplied,
      sold: row.sold, damaged: row.damaged, returned: row.returned, ending: row.ending,
      sellThroughRate: row.supplied > 0 ? row.sold / row.supplied : null })),
  }, 200, requestId);
}

export async function handleEventTransactions(
  request: Request,
  env: Env,
  userId: string,
  eventId: string,
  requestId: string,
): Promise<Response> {
  const event = await eventForUser(env.POS_DB, userId, eventId);
  const query = transactionQuery(new URL(request.url));
  const page = await queryEventTransactions(env.POS_DB, userId, event.id, query, query.cursor, query.limit);
  return json({
    requestId,
    eventId: event.id,
    transactions: page.rows.map(transactionSummary),
    nextCursor: page.nextCursor,
  }, 200, requestId);
}

export async function handleEventTransactionDetail(
  env: Env,
  userId: string,
  eventId: string,
  saleId: string,
  requestId: string,
): Promise<Response> {
  const event = await eventForUser(env.POS_DB, userId, eventId);
  if (!isUuid(saleId)) throw new HttpError(400, "INVALID_DATA", "Transaction ID is invalid.");
  const [sale, lines, allocations] = await Promise.all([
    env.POS_DB.prepare(
      `SELECT s.*,v.id AS void_id,v.occurred_at_utc AS void_occurred_at_utc,v.reason AS void_reason,
       v.payment_method AS void_payment_method
       FROM sales s LEFT JOIN voids v ON v.user_id=s.user_id AND v.sale_id=s.id
       WHERE s.user_id=? AND s.event_id=? AND s.id=?`,
    ).bind(userId, event.id, saleId).first<TransactionDetailRow>(),
    env.POS_DB.prepare(
      `SELECT line_index,item_type,item_ref_id,display_name,quantity,unit_price,unit_cost_snapshot,
       original_amount,allocated_discount,allocated_adjustment,final_amount
       FROM sale_lines WHERE user_id=? AND sale_id=? ORDER BY line_index`,
    ).bind(userId, saleId).all<TransactionLineRow>(),
    env.POS_DB.prepare(
      `SELECT line_index,allocation_index,product_id,product_name_snapshot,unit_cost_snapshot,quantity,allocated_revenue
       FROM bundle_component_allocations WHERE user_id=? AND sale_id=? ORDER BY line_index,allocation_index`,
    ).bind(userId, saleId).all<BundleAllocationRow>(),
  ]);
  if (!sale) throw new HttpError(404, "NOT_FOUND", "Transaction was not found.");
  return json({
    requestId,
    eventId: event.id,
    transaction: {
      id: sale.id,
      receiptNumber: sale.receipt_number,
      occurredAtUtc: sale.occurred_at_utc,
      deviceId: sale.device_id,
      paymentMethod: sale.payment_method,
      subtotal: sale.subtotal,
      discountAmount: sale.discount_amount,
      netAdjustment: sale.net_adjustment,
      finalTotal: sale.final_total,
      tipAmount: sale.tip_amount,
      revenue: sale.final_total + sale.tip_amount,
      void: sale.void_id === null ? null : {
        id: sale.void_id,
        occurredAtUtc: sale.void_occurred_at_utc,
        reason: sale.void_reason,
        paymentMethod: sale.void_payment_method,
      },
      lines: lines.results.map((row) => ({
        lineIndex: row.line_index,
        itemType: row.item_type,
        itemRefId: row.item_ref_id,
        displayName: row.display_name,
        quantity: row.quantity,
        unitPrice: row.unit_price,
        unitCostSnapshot: row.unit_cost_snapshot,
        originalAmount: row.original_amount,
        allocatedDiscount: row.allocated_discount,
        allocatedAdjustment: row.allocated_adjustment,
        finalAmount: row.final_amount,
      })),
      bundleComponents: allocations.results.map((row) => ({
        lineIndex: row.line_index,
        productId: row.product_id,
        productNameSnapshot: row.product_name_snapshot,
        unitCostSnapshot: row.unit_cost_snapshot,
        quantity: row.quantity,
        allocatedRevenue: row.allocated_revenue,
      })),
    },
  }, 200, requestId);
}

export async function handleEventTransactionsCsv(
  request: Request,
  env: Env,
  userId: string,
  eventId: string,
  requestId: string,
  ctx: ExecutionContext,
): Promise<Response> {
  const event = await eventForUser(env.POS_DB, userId, eventId);
  const query = transactionQuery(new URL(request.url), false);
  const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
  ctx.waitUntil(writeTransactionsCsv(writable, env.POS_DB, userId, event.id, query).catch((error) => {
    console.error(JSON.stringify({ event: "csv_export_failed", requestId, error: error instanceof Error ? error.message : String(error) }));
  }));
  return new Response(readable, { headers: {
    "content-type": "text/csv; charset=utf-8",
    "content-disposition": `attachment; filename="stallpos-${event.code}-transactions.csv"`,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "x-request-id": requestId,
  } });
}

async function eventForUser(db: D1Database, userId: string, eventId: string): Promise<{ id: string; code: string }> {
  if (!isUuid(eventId)) throw new HttpError(400, "INVALID_DATA", "Event ID is invalid.");
  const event = await db.prepare("SELECT id,code FROM events WHERE user_id=? AND id=?")
    .bind(userId, eventId).first<{ id: string; code: string }>();
  if (!event) throw new HttpError(404, "NOT_FOUND", "Event was not found.");
  return event;
}

function transactionQuery(url: URL, includeCursor = true): TransactionQuery {
  const paymentMethod = url.searchParams.get("paymentMethod");
  const status = url.searchParams.get("status");
  if (paymentMethod !== null && !PAYMENT_METHODS.has(paymentMethod)) {
    throw new HttpError(400, "INVALID_DATA", "Payment method is invalid.");
  }
  if (status !== null && status !== "ACTIVE" && status !== "VOIDED") {
    throw new HttpError(400, "INVALID_DATA", "Transaction status is invalid.");
  }
  const rawLimit = url.searchParams.get("limit");
  const limit = rawLimit === null ? TRANSACTION_PAGE_SIZE : Number(rawLimit);
  if (!Number.isInteger(limit) || limit < 1 || limit > TRANSACTION_PAGE_MAX) {
    throw new HttpError(400, "INVALID_DATA", `Limit must be between 1 and ${TRANSACTION_PAGE_MAX}.`);
  }
  const cursor = includeCursor ? decodeTransactionCursor(url.searchParams.get("cursor")) : null;
  return { paymentMethod, status, limit, cursor };
}

async function queryEventTransactions(
  db: D1Database,
  userId: string,
  eventId: string,
  query: TransactionQuery,
  cursor: TransactionCursor | null,
  limit: number,
): Promise<{ rows: TransactionListRow[]; nextCursor: string | null }> {
  const clauses = ["s.user_id=?", "s.event_id=?"];
  const values: unknown[] = [userId, eventId];
  if (query.paymentMethod !== null) {
    clauses.push("s.payment_method=?");
    values.push(query.paymentMethod);
  }
  if (query.status === "ACTIVE") clauses.push("v.id IS NULL");
  if (query.status === "VOIDED") clauses.push("v.id IS NOT NULL");
  if (cursor !== null) {
    clauses.push("(s.occurred_at_utc<? OR (s.occurred_at_utc=? AND s.id<?))");
    values.push(cursor.occurredAtUtc, cursor.occurredAtUtc, cursor.id);
  }
  values.push(limit + 1);
  const result = await db.prepare(
    `SELECT s.id,s.receipt_number,s.occurred_at_utc,s.payment_method,s.subtotal,s.discount_amount,
     s.net_adjustment,s.final_total,s.tip_amount,CASE WHEN v.id IS NULL THEN 0 ELSE 1 END AS voided,
     COALESCE(SUM(l.quantity),0) AS item_count,
     COALESCE(GROUP_CONCAT(REPLACE(l.display_name,';','；')||' x'||l.quantity,'; '),'') AS items
     FROM sales s LEFT JOIN voids v ON v.user_id=s.user_id AND v.sale_id=s.id
     LEFT JOIN sale_lines l ON l.user_id=s.user_id AND l.sale_id=s.id
     WHERE ${clauses.join(" AND ")} GROUP BY s.user_id,s.id
     ORDER BY s.occurred_at_utc DESC,s.id DESC LIMIT ?`,
  ).bind(...values).all<TransactionListRow>();
  const rows = result.results.slice(0, limit);
  const last = rows.at(-1);
  return {
    rows,
    nextCursor: result.results.length > limit && last ? encodeTransactionCursor(last.occurred_at_utc, last.id) : null,
  };
}

function transactionSummary(row: TransactionListRow) {
  return {
    id: row.id,
    receiptNumber: row.receipt_number,
    occurredAtUtc: row.occurred_at_utc,
    paymentMethod: row.payment_method,
    finalTotal: row.final_total,
    tipAmount: row.tip_amount,
    revenue: row.final_total + row.tip_amount,
    voided: row.voided === 1,
    itemCount: row.item_count,
  };
}

function encodeTransactionCursor(occurredAtUtc: string, id: string): string {
  return btoa(JSON.stringify([occurredAtUtc, id])).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function decodeTransactionCursor(value: string | null): TransactionCursor | null {
  if (value === null) return null;
  if (value.length > 512 || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new HttpError(400, "INVALID_DATA", "Transaction cursor is invalid.");
  }
  try {
    const padded = value.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
    const decoded: unknown = JSON.parse(atob(padded));
    if (!Array.isArray(decoded) || decoded.length !== 2 || typeof decoded[0] !== "string" || !isUuid(decoded[1])) throw new Error();
    if (!Number.isFinite(Date.parse(decoded[0]))) throw new Error();
    return { occurredAtUtc: decoded[0], id: decoded[1] };
  } catch {
    throw new HttpError(400, "INVALID_DATA", "Transaction cursor is invalid.");
  }
}

async function writeTransactionsCsv(
  writable: WritableStream<Uint8Array>,
  db: D1Database,
  userId: string,
  eventId: string,
  query: TransactionQuery,
): Promise<void> {
  const writer = writable.getWriter();
  const encoder = new TextEncoder();
  try {
    await writer.write(encoder.encode("\uFEFFreceipt_number,occurred_at_utc,status,payment_method,subtotal,discount_amount,net_adjustment,final_total,tip_amount,revenue,items\r\n"));
    let cursor: TransactionCursor | null = null;
    do {
      const page = await queryEventTransactions(db, userId, eventId, query, cursor, CSV_PAGE_SIZE);
      for (const row of page.rows) {
        const values = [row.receipt_number, row.occurred_at_utc, row.voided === 1 ? "VOIDED" : "ACTIVE", row.payment_method,
          row.subtotal, row.discount_amount, row.net_adjustment, row.final_total, row.tip_amount,
          row.final_total + row.tip_amount, row.items];
        await writer.write(encoder.encode(`${values.map(csvCell).join(",")}\r\n`));
      }
      cursor = page.nextCursor === null ? null : decodeTransactionCursor(page.nextCursor);
    } while (cursor !== null);
    await writer.close();
  } catch (error) {
    await writer.abort(error);
    throw error;
  }
}

function csvCell(value: unknown): string {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function timeBuckets(rows: SaleTimeRow[], timezone: string) {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", hourCycle: "h23",
  });
  const daily = new Map<string, number>();
  const hourly = new Map<string, number>();
  const payments = new Map<string, { transactionCount: number; revenue: number }>();
  for (const row of rows) {
    const parts = Object.fromEntries(formatter.formatToParts(new Date(row.occurred_at_utc)).map((part) => [part.type, part.value]));
    const day = `${parts.year}-${parts.month}-${parts.day}`;
    daily.set(day, (daily.get(day) ?? 0) + row.revenue);
    hourly.set(parts.hour!, (hourly.get(parts.hour!) ?? 0) + row.revenue);
    const payment = payments.get(row.payment_method) ?? { transactionCount: 0, revenue: 0 };
    payment.transactionCount += 1;
    payment.revenue += row.revenue;
    payments.set(row.payment_method, payment);
  }
  return {
    trends: [...daily].map(([date, revenue]) => ({ date, revenue })),
    hourly: [...hourly].sort(([a], [b]) => a.localeCompare(b)).map(([hour, revenue]) => ({ hour: Number(hour), revenue })),
    payments: [...payments].map(([method, value]) => ({ method, ...value })),
  };
}

interface EventListRow { id: string; name: string; code: string; status: string; timezone: string; start_at_utc: string; end_at_utc: string; transaction_count: number; revenue: number }
interface SummaryRow { transaction_count: number; revenue: number; tips: number; average_order_value: number }
interface ProductRow { product_id: string; name: string; quantity: number; revenue: number; known_cost: number; missing_cost_units: number }
interface SaleTimeRow { occurred_at_utc: string; revenue: number; payment_method: string }
interface PairRow { first_id: string; first_name: string; second_id: string; second_name: string; sale_count: number }
interface BundleRow { bundle_id: string; name: string; quantity: number; revenue: number }
interface InventoryRow { product_id: string; name: string; supplied: number; sold: number; damaged: number; returned: number; ending: number }
interface TransactionQuery { paymentMethod: string | null; status: string | null; limit: number; cursor: TransactionCursor | null }
interface TransactionCursor { occurredAtUtc: string; id: string }
interface TransactionListRow { id: string; receipt_number: string; occurred_at_utc: string; payment_method: string; subtotal: number; discount_amount: number; net_adjustment: number; final_total: number; tip_amount: number; voided: number; item_count: number; items: string }
interface TransactionDetailRow { id: string; receipt_number: string; occurred_at_utc: string; device_id: string; payment_method: string; subtotal: number; discount_amount: number; net_adjustment: number; final_total: number; tip_amount: number; void_id: string | null; void_occurred_at_utc: string | null; void_reason: string | null; void_payment_method: string | null }
interface TransactionLineRow { line_index: number; item_type: string; item_ref_id: string; display_name: string; quantity: number; unit_price: number; unit_cost_snapshot: number | null; original_amount: number; allocated_discount: number; allocated_adjustment: number; final_amount: number }
interface BundleAllocationRow { line_index: number; allocation_index: number; product_id: string; product_name_snapshot: string; unit_cost_snapshot: number | null; quantity: number; allocated_revenue: number }
