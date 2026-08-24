import { json } from "./http";
import { isUuid } from "./validation";

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
