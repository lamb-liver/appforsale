import { json, readJsonObject } from "./http";
import { sha256 } from "./auth";
import type { AuthContext, JsonObject, SyncBatchRequest, SyncOperation } from "./types";
import { locationKey, validateSyncBatch } from "./validation";

export async function handleSync(request: Request, env: Env, auth: AuthContext): Promise<Response> {
  const body = await readJsonObject(request);
  const validationError = validateSyncBatch(body);
  const requestId = typeof body.requestId === "string" ? body.requestId : crypto.randomUUID();
  if (validationError) {
    return json({ requestId, code: "INVALID_DATA", message: validationError }, 400, requestId);
  }
  const batch = body as unknown as SyncBatchRequest;
  if (batch.deviceId !== auth.deviceId) {
    return blockedBatch(batch, "DEVICE_RETIRED", "Session is not valid for this device.");
  }
  if (batch.cloudEpoch !== auth.cloudEpoch) {
    return blockedBatch(batch, "CLOUD_EPOCH_REVOKED", "Cloud epoch is no longer active.");
  }

  const results = [];
  for (const operation of batch.operations) {
    const payloadHash = await sha256(canonicalJson(operation));
    const processed = await env.POS_DB.prepare(
      "SELECT payload_hash FROM processed_operations WHERE user_id = ? AND operation_id = ?",
    ).bind(auth.userId, operation.operationId).first<{ payload_hash: string }>();
    if (processed) {
      results.push(processed.payload_hash === payloadHash
        ? syncResult(operation.operationId, "ACK")
        : syncResult(operation.operationId, "BLOCKED", "SERVER_CONFLICT", "Operation ID was reused with different data."));
      continue;
    }

    const nowUtc = new Date().toISOString();
    const statements = statementsForOperation(env.POS_DB, auth.userId, operation);
    statements.push(
      env.POS_DB.prepare(
        `INSERT INTO processed_operations
         (user_id, operation_id, payload_hash, entity_type, entity_id, processed_at_utc)
         VALUES (?, ?, ?, ?, ?, ?)`,
      ).bind(auth.userId, operation.operationId, payloadHash, operation.entityType, operation.entityId, nowUtc),
      env.POS_DB.prepare(
        `INSERT INTO audit_logs
         (id, user_id, device_id, request_id, operation_id, action, entity_type, entity_id, occurred_at_utc)
         VALUES (?, ?, ?, ?, ?, 'SYNC_APPLY', ?, ?, ?)`,
      ).bind(crypto.randomUUID(), auth.userId, auth.deviceId, batch.requestId, operation.operationId, operation.entityType, operation.entityId, nowUtc),
    );
    try {
      const applied = await env.POS_DB.batch(statements);
      if (applied.some((result) => !result.success)) throw new Error("D1 batch failed");
      results.push(syncResult(operation.operationId, "ACK"));
    } catch {
      results.push(syncResult(operation.operationId, "BLOCKED", "INVALID_DATA", "Operation could not be applied."));
    }
  }
  return json({ requestId: batch.requestId, results }, 200, batch.requestId);
}

export async function handleBootstrap(
  request: Request,
  env: Env,
  auth: AuthContext,
  requestId: string,
): Promise<Response> {
  const group = new URL(request.url).searchParams.get("group");
  const tableByGroup = {
    PRODUCTS: ["categories", "products"],
    BUNDLES: ["bundles"],
    EVENTS: ["events"],
    INVENTORY: ["inventory_movements"],
    TRANSACTIONS: ["sales", "voids"],
  } as const;
  if (!group || !(group in tableByGroup)) {
    return json({ requestId, code: "INVALID_DATA", message: "Bootstrap group is invalid." }, 400, requestId);
  }
  const data: unknown[] = [];
  for (const table of tableByGroup[group as keyof typeof tableByGroup]) {
    const result = await env.POS_DB.prepare(
      `SELECT payload_json FROM ${table} WHERE user_id = ? ORDER BY rowid LIMIT 500`,
    ).bind(auth.userId).all<{ payload_json: string }>();
    for (const row of result.results) data.push(JSON.parse(row.payload_json));
  }
  return json({ requestId, cloudEpoch: auth.cloudEpoch, group, nextCursor: null, data }, 200, requestId);
}

function statementsForOperation(db: D1Database, userId: string, operation: SyncOperation): D1PreparedStatement[] {
  const payload = operation.payload;
  const payloadJson = JSON.stringify(payload);
  switch (operation.entityType) {
    case "CATEGORY":
      return [db.prepare(
        `INSERT INTO categories (user_id, id, category_type, name, sort_order, updated_at_utc, deleted_at_utc, payload_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, id) DO UPDATE SET category_type=excluded.category_type, name=excluded.name,
         sort_order=excluded.sort_order, updated_at_utc=excluded.updated_at_utc,
         deleted_at_utc=excluded.deleted_at_utc, payload_json=excluded.payload_json`,
      ).bind(userId, payload.id, payload.categoryType, payload.name, payload.sortOrder, payload.updatedAtUtc, payload.deletedAtUtc, payloadJson)];
    case "PRODUCT":
      return [db.prepare(
        `INSERT INTO products (user_id, id, name, selling_price, cost, category_id, track_inventory, is_active, updated_at_utc, deleted_at_utc, payload_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, id) DO UPDATE SET name=excluded.name, selling_price=excluded.selling_price,
         cost=excluded.cost, category_id=excluded.category_id, track_inventory=excluded.track_inventory,
         is_active=excluded.is_active, updated_at_utc=excluded.updated_at_utc,
         deleted_at_utc=excluded.deleted_at_utc, payload_json=excluded.payload_json`,
      ).bind(userId, payload.id, payload.name, payload.sellingPrice, payload.cost, payload.categoryId,
        boolInt(payload.trackInventory), boolInt(payload.isActive), payload.updatedAtUtc, payload.deletedAtUtc, payloadJson)];
    case "BUNDLE":
      return [db.prepare(
        `INSERT INTO bundles (user_id, id, name, selling_price, category_id, is_active, components_json, updated_at_utc, deleted_at_utc, payload_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, id) DO UPDATE SET name=excluded.name, selling_price=excluded.selling_price,
         category_id=excluded.category_id, is_active=excluded.is_active, components_json=excluded.components_json,
         updated_at_utc=excluded.updated_at_utc, deleted_at_utc=excluded.deleted_at_utc, payload_json=excluded.payload_json`,
      ).bind(userId, payload.id, payload.name, payload.sellingPrice, payload.categoryId, boolInt(payload.isActive),
        JSON.stringify(payload.components), payload.updatedAtUtc, payload.deletedAtUtc, payloadJson)];
    case "EVENT":
      return [db.prepare(
        `INSERT INTO events (user_id, id, name, code, event_type, start_at_utc, end_at_utc, actual_open_at_utc,
         actual_close_at_utc, timezone, location, status, updated_at_utc, payload_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, id) DO UPDATE SET name=excluded.name, code=excluded.code, event_type=excluded.event_type,
         start_at_utc=excluded.start_at_utc, end_at_utc=excluded.end_at_utc, actual_open_at_utc=excluded.actual_open_at_utc,
         actual_close_at_utc=excluded.actual_close_at_utc, timezone=excluded.timezone, location=excluded.location,
         status=excluded.status, updated_at_utc=excluded.updated_at_utc, payload_json=excluded.payload_json`,
      ).bind(userId, payload.id, payload.name, payload.code, payload.eventType, payload.startAtUtc, payload.endAtUtc,
        payload.actualOpenAtUtc, payload.actualCloseAtUtc, payload.timezone, payload.location, payload.status, payload.updatedAtUtc, payloadJson)];
    case "INVENTORY_MOVEMENT":
      return [inventoryStatement(db, userId, payload)];
    case "SALE":
      return saleStatements(db, userId, payload, payloadJson);
    case "VOID":
      return voidStatements(db, userId, payload, payloadJson);
  }
}

function inventoryStatement(db: D1Database, userId: string, payload: JsonObject): D1PreparedStatement {
  return db.prepare(
    `INSERT INTO inventory_movements
     (user_id, id, product_id, event_id, from_location_key, to_location_key, movement_type,
      quantity, related_transaction_id, occurred_at_utc, payload_json)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(userId, payload.id, payload.productId, payload.eventId,
    locationKey(payload.fromLocation as JsonObject | null), locationKey(payload.toLocation as JsonObject | null),
    payload.type, payload.quantity, payload.relatedTransactionId, payload.occurredAtUtc, JSON.stringify(payload));
}

function saleStatements(db: D1Database, userId: string, payload: JsonObject, payloadJson: string): D1PreparedStatement[] {
  const statements = [db.prepare(
    `INSERT INTO sales
     (user_id, id, device_id, event_id, receipt_number, occurred_at_utc, subtotal, discount_amount,
      net_adjustment, final_total, tip_amount, payment_method, payload_json)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(userId, payload.id, payload.deviceId, payload.eventId, payload.receiptNumber, payload.occurredAtUtc,
    payload.subtotal, payload.discountAmount, payload.netAdjustment, payload.finalTotal, payload.tipAmount,
    payload.paymentMethod, payloadJson)];
  for (const line of payload.lines as JsonObject[]) {
    statements.push(db.prepare(
      `INSERT INTO sale_lines
       (user_id, sale_id, line_index, item_type, item_ref_id, display_name, quantity, unit_price,
        original_amount, allocated_discount, allocated_adjustment, final_amount)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(userId, payload.id, line.lineIndex, line.itemType, line.itemRefId, line.displayName, line.quantity,
      line.unitPrice, line.originalAmount, line.allocatedDiscount, line.allocatedAdjustment, line.finalAmount));
  }
  (payload.componentAllocations as JsonObject[]).forEach((allocation, index) => {
    statements.push(db.prepare(
      `INSERT INTO bundle_component_allocations
       (user_id, sale_id, line_index, allocation_index, product_id, product_name_snapshot,
        unit_cost_snapshot, quantity, allocated_revenue)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(userId, payload.id, allocation.lineIndex, index, allocation.productId, allocation.productNameSnapshot,
      allocation.unitCostSnapshot, allocation.quantity, allocation.allocatedRevenue));
  });
  for (const movement of payload.inventoryMovements as JsonObject[]) statements.push(inventoryStatement(db, userId, movement));
  return statements;
}

function voidStatements(db: D1Database, userId: string, payload: JsonObject, payloadJson: string): D1PreparedStatement[] {
  const statements = [db.prepare(
    `INSERT INTO voids (user_id, id, sale_id, device_id, event_id, occurred_at_utc, reason, payment_method, payload_json)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(userId, payload.id, payload.saleId, payload.deviceId, payload.eventId, payload.occurredAtUtc,
    payload.reason, payload.paymentMethod, payloadJson)];
  for (const movement of payload.inventoryMovements as JsonObject[]) statements.push(inventoryStatement(db, userId, movement));
  return statements;
}

function boolInt(value: unknown): number {
  return value === true ? 1 : 0;
}

function syncResult(operationId: string, status: "ACK" | "BLOCKED", code: string | null = null, message: string | null = null) {
  return { operationId, status, code, message };
}

function blockedBatch(batch: SyncBatchRequest, code: string, message: string): Response {
  return json({
    requestId: batch.requestId,
    results: batch.operations.map((operation) => syncResult(operation.operationId, "BLOCKED", code, message)),
  }, 200, batch.requestId);
}

export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    const object = value as Record<string, unknown>;
    return `{${Object.keys(object).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(object[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}
