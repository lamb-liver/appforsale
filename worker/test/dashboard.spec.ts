import { createExecutionContext, env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { sha256 } from "../src/auth";
import { resetPosDb } from "./db";

const userId = "11000000-0000-4000-8000-000000000070";
const eventId = "70000000-0000-4000-8000-000000000070";
const productA = "50000000-0000-4000-8000-000000000070";
const productB = "50000000-0000-4000-8000-000000000071";
const bundleId = "60000000-0000-4000-8000-000000000070";
const dashboardToken = "dashboard-test-session-token";

beforeEach(async () => {
  await resetPosDb(env.POS_DB);
  await env.POS_DB.batch([
    env.POS_DB.prepare("INSERT INTO users (id,google_sub,cloud_epoch,created_at_utc) VALUES (?,'google-report',1,'2026-08-24T00:00:00Z')").bind(userId),
    env.POS_DB.prepare("INSERT INTO dashboard_sessions (token_hash,user_id,created_at_utc,expires_at_utc) VALUES (?,?,?,?)")
      .bind(await sha256(dashboardToken), userId, "2026-08-24T00:00:00Z", "2099-01-01T00:00:00Z"),
    env.POS_DB.prepare(
      `INSERT INTO events (user_id,id,name,code,event_type,start_at_utc,end_at_utc,timezone,location,status,updated_at_utc,payload_json)
       VALUES (?,?, '台北市集','TPE26','MARKET','2026-08-24T00:00:00Z','2026-08-25T00:00:00Z','Asia/Taipei','台北','CLOSED','2026-08-25T00:00:00Z','{}')`,
    ).bind(userId, eventId),
    product(userId, productA, "徽章", 30),
    product(userId, productB, "貼紙", null),
    env.POS_DB.prepare(
      `INSERT INTO inventory_movements
       (user_id,id,product_id,event_id,from_location_key,to_location_key,movement_type,quantity,occurred_at_utc,payload_json)
       VALUES (?,?,?,?,NULL,?,'ADJUSTMENT',20,'2026-08-24T00:30:00Z','{}')`,
    ).bind(userId, "80000000-0000-4000-8000-000000000070", productA, eventId, `EVENT:${eventId}`),
    env.POS_DB.prepare(
      `INSERT INTO inventory_movements
       (user_id,id,product_id,event_id,from_location_key,to_location_key,movement_type,quantity,occurred_at_utc,payload_json)
       VALUES (?,?,?,?,?,NULL,'SALE',7,'2026-08-24T02:00:00Z','{}')`,
    ).bind(userId, "80000000-0000-4000-8000-000000000071", productA, eventId, `EVENT:${eventId}`),
    env.POS_DB.prepare(
      `INSERT INTO inventory_movements
       (user_id,id,product_id,event_id,from_location_key,to_location_key,movement_type,quantity,occurred_at_utc,payload_json)
       VALUES (?,?,?,?,?,NULL,'DAMAGE',1,'2026-08-24T03:00:00Z','{}')`,
    ).bind(userId, "80000000-0000-4000-8000-000000000072", productA, eventId, `EVENT:${eventId}`),
  ]);
  for (let index = 0; index < 5; index += 1) await insertSingleSale(index);
  await insertBundleSale();
  await insertVoidedSale();
});

describe("read-only dashboard reports", () => {
  it("reconciles event analytics, excludes voids, and preserves unknown costs", async () => {
    const list = await api("/v2/reports/events");
    expect(list.status).toBe(200);
    expect(await list.json()).toMatchObject({ events: [{ id: eventId, transactionCount: 6, revenue: 1000 }] });

    const response = await api(`/v2/reports/events/${eventId}`);
    expect(response.status).toBe(200);
    const report = await response.json() as any;
    expect(report.summary).toMatchObject({ transactionCount: 6, revenue: 1000, costComplete: false, grossProfit: null });
    expect(report.products).toEqual(expect.arrayContaining([
      expect.objectContaining({ productId: productA, quantity: 7, revenue: 700, cost: 210, grossProfit: 490 }),
      expect.objectContaining({ productId: productB, quantity: 5, revenue: 250, cost: null, grossProfit: null }),
    ]));
    expect(report.coPurchases).toEqual([expect.objectContaining({ firstId: productA, secondId: productB, saleCount: 5 })]);
    expect(report.bundles).toEqual([expect.objectContaining({ bundleId, quantity: 1, revenue: 200 })]);
    expect(report.inventory).toEqual([expect.objectContaining({ productId: productA, supplied: 20, sold: 7, damaged: 1, ending: 12 })]);
    expect(report.trends[0].date).toBe("2026-08-24");
  });

  it("requires a server-side session and serves a locked-down responsive shell", async () => {
    const unauthorized = await api("/v2/reports/events", false);
    expect(unauthorized.status).toBe(401);
    const page = await api("/dashboard", false);
    expect(page.status).toBe(200);
    expect(page.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
    const html = await page.text();
    expect(html).toContain("活動分析");
    expect(html).toContain("交易查詢");
    const script = await (await api("/dashboard/dashboard.js", false)).text();
    expect(() => new Function(script)).not.toThrow();
    expect(script).toContain("paymentLabels[x.method]||x.method");
    expect(script).toContain("requestSequence!==transactionRequestSequence");
  });

  it("cursor-pages transaction history and returns snapshot detail including void status", async () => {
    const firstResponse = await api(`/v2/reports/events/${eventId}/transactions?limit=2`);
    expect(firstResponse.status).toBe(200);
    const first = await firstResponse.json() as {
      transactions: Array<{ id: string; voided: boolean; itemCount: number }>;
      nextCursor: string | null;
    };
    expect(first.transactions).toHaveLength(2);
    expect(first.transactions[0]).toMatchObject({ id: "90000000-0000-4000-8000-000000000090", voided: true, itemCount: 99 });
    expect(first.nextCursor).not.toBeNull();

    const secondResponse = await api(
      `/v2/reports/events/${eventId}/transactions?limit=2&cursor=${encodeURIComponent(first.nextCursor!)}`,
    );
    const second = await secondResponse.json() as { transactions: Array<{ id: string }> };
    expect(second.transactions).toHaveLength(2);
    expect(second.transactions.map((row) => row.id)).not.toContain(first.transactions[0]!.id);

    const detailResponse = await api(`/v2/reports/events/${eventId}/transactions/90000000-0000-4000-8000-000000000080`);
    expect(detailResponse.status).toBe(200);
    expect(await detailResponse.json()).toMatchObject({
      eventId,
      transaction: {
        receiptNumber: "90000000-0000-4000-8000-000000000080",
        paymentMethod: "CASH",
        finalTotal: 200,
        void: null,
        lines: [{ displayName: "徽章雙入組", itemType: "BUNDLE", quantity: 1 }],
        bundleComponents: [{ productNameSnapshot: "徽章", quantity: 2, allocatedRevenue: 200 }],
      },
    });
  });

  it("filters transaction history and exports the same event rows as CSV", async () => {
    const filtered = await api(`/v2/reports/events/${eventId}/transactions?paymentMethod=OTHER&status=ACTIVE`);
    expect(filtered.status).toBe(200);
    const body = await filtered.json() as { transactions: Array<{ paymentMethod: string; voided: boolean }> };
    expect(body.transactions).toHaveLength(2);
    expect(body.transactions.every((row) => row.paymentMethod === "OTHER" && !row.voided)).toBe(true);

    const csv = await api(`/v2/reports/events/${eventId}/transactions.csv?paymentMethod=OTHER&status=ACTIVE`);
    expect(csv.status).toBe(200);
    expect(csv.headers.get("content-type")).toContain("text/csv");
    expect(csv.headers.get("content-disposition")).toContain("stallpos-TPE26-transactions.csv");
    const text = await csv.text();
    expect(text).toContain("receipt_number,occurred_at_utc,status,payment_method");
    expect(text.match(/,OTHER,/g)).toHaveLength(2);
    expect(text).not.toContain("VOIDED");
  });

  it("rejects malformed transaction cursors and isolates event ownership", async () => {
    expect((await api(`/v2/reports/events/${eventId}/transactions?cursor=broken`)).status).toBe(400);
    expect((await api("/v2/reports/events/70000000-0000-4000-8000-000000000099/transactions")).status).toBe(404);
  });

  it("uses report indexes for event sales and inventory lookups", async () => {
    const salesPlan = await env.POS_DB.prepare(
      "EXPLAIN QUERY PLAN SELECT * FROM sales WHERE user_id=? AND event_id=? ORDER BY occurred_at_utc",
    ).bind(userId, eventId).all<{ detail: string }>();
    const inventoryPlan = await env.POS_DB.prepare(
      "EXPLAIN QUERY PLAN SELECT * FROM inventory_levels WHERE user_id=? AND event_id=?",
    ).bind(userId, eventId).all<{ detail: string }>();
    expect(salesPlan.results.map((row) => row.detail).join(" ")).toContain("sales_user_event_time_idx");
    expect(inventoryPlan.results.map((row) => row.detail).join(" ")).toContain("inventory_levels_user_event_idx");
  });
});

function product(user: string, id: string, name: string, cost: number | null) {
  return env.POS_DB.prepare(
    `INSERT INTO products (user_id,id,name,selling_price,cost,category_id,track_inventory,is_active,updated_at_utc,payload_json)
     VALUES (?,?,?,100,?,NULL,1,1,'2026-08-24T00:00:00Z','{}')`,
  ).bind(user, id, name, cost);
}

async function insertSingleSale(index: number) {
  const id = `90000000-0000-4000-8000-${String(70 + index).padStart(12, "0")}`;
  const occurred = `2026-08-24T0${index + 1}:00:00Z`;
  await env.POS_DB.batch([
    sale(id, 150, 10, occurred, index < 3 ? "CASH" : "OTHER"),
    line(id, 0, "PRODUCT", productA, "徽章", 1, 100, 100, 30),
    line(id, 1, "PRODUCT", productB, "貼紙", 1, 50, 50, null),
  ]);
}

async function insertBundleSale() {
  const id = "90000000-0000-4000-8000-000000000080";
  await env.POS_DB.batch([
    sale(id, 200, 0, "2026-08-24T08:00:00Z", "CASH"),
    line(id, 0, "BUNDLE", bundleId, "徽章雙入組", 1, 200, 200, null),
    env.POS_DB.prepare(
      `INSERT INTO bundle_component_allocations
       (user_id,sale_id,line_index,allocation_index,product_id,product_name_snapshot,unit_cost_snapshot,quantity,allocated_revenue)
       VALUES (?,?,0,0,?,'徽章',30,2,200)`,
    ).bind(userId, id, productA),
  ]);
}

async function insertVoidedSale() {
  const id = "90000000-0000-4000-8000-000000000090";
  await env.POS_DB.batch([
    sale(id, 9999, 0, "2026-08-24T09:00:00Z", "CASH"),
    line(id, 0, "PRODUCT", productA, "徽章", 99, 101, 9999, 30),
    env.POS_DB.prepare(
      `INSERT INTO voids (user_id,id,sale_id,device_id,event_id,occurred_at_utc,reason,payment_method,payload_json)
       VALUES (?,?,?,'device',?,'2026-08-24T09:01:00Z','USER_VOID','CASH','{}')`,
    ).bind(userId, "91000000-0000-4000-8000-000000000090", id, eventId),
  ]);
}

function sale(id: string, finalTotal: number, tip: number, occurred: string, payment: string) {
  return env.POS_DB.prepare(
    `INSERT INTO sales
     (user_id,id,device_id,event_id,receipt_number,occurred_at_utc,subtotal,discount_amount,net_adjustment,final_total,tip_amount,payment_method,payload_json)
     VALUES (?,?,'device',?,?,?, ?,0,0,?,?,?,'{}')`,
  ).bind(userId, id, eventId, id, occurred, finalTotal, finalTotal, tip, payment);
}

function line(saleId: string, index: number, type: string, ref: string, name: string, quantity: number,
  unitPrice: number, amount: number, cost: number | null) {
  return env.POS_DB.prepare(
    `INSERT INTO sale_lines
     (user_id,sale_id,line_index,item_type,item_ref_id,display_name,quantity,unit_price,original_amount,
      allocated_discount,allocated_adjustment,final_amount,unit_cost_snapshot)
     VALUES (?,?,?,?,?,?,?,?,?,0,0,?,?)`,
  ).bind(userId, saleId, index, type, ref, name, quantity, unitPrice, amount, amount, cost);
}

async function api(path: string, authenticated = true): Promise<Response> {
  const headers = authenticated ? { cookie: `stallpos_dashboard=${dashboardToken}` } : undefined;
  return worker.fetch(new Request(`https://stallpos.test${path}`, { headers }), env, createExecutionContext());
}
