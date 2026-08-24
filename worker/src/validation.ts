import { Validator, type Schema } from "@cfworker/json-schema";
import syncBatchSchema from "../../contracts/v2/sync-batch.schema.json";
import type { JsonObject, SyncBatchRequest, SyncOperation } from "./types";

const validator = new Validator(syncBatchSchema as Schema, "2020-12", false);
const categoryOrder = { MASTER: 0, INVENTORY: 1, TRANSACTION: 2 } as const;
const entityContract = {
  CATEGORY: ["MASTER", "UPSERT", "DELETE"],
  PRODUCT: ["MASTER", "UPSERT", "DELETE"],
  BUNDLE: ["MASTER", "UPSERT", "DELETE"],
  EVENT: ["MASTER", "UPSERT", "DELETE"],
  INVENTORY_MOVEMENT: ["INVENTORY", "APPEND"],
  SALE: ["TRANSACTION", "APPEND"],
  VOID: ["TRANSACTION", "APPEND"],
} as const;

export function validateSyncBatch(value: JsonObject): string | null {
  const result = validator.validate(value);
  if (!result.valid) return "Request does not match the StallPOS v2 contract.";
  const batch = value as unknown as SyncBatchRequest;
  let previous = -1;
  for (const operation of batch.operations) {
    const current = categoryOrder[operation.category];
    if (current < previous) return "Operations must be ordered MASTER, INVENTORY, TRANSACTION.";
    previous = current;
    const expected = entityContract[operation.entityType];
    if (expected[0] !== operation.category || !expected.slice(1).includes(operation.operationType as never)) {
      return `Operation ${operation.operationId} has an invalid category or operation type.`;
    }
    if (operation.payload.id !== operation.entityId) {
      return `Operation ${operation.operationId} entityId does not match payload.id.`;
    }
    const semanticError = validatePayload(operation);
    if (semanticError) return `Operation ${operation.operationId}: ${semanticError}`;
  }
  return null;
}

function validatePayload(operation: SyncOperation): string | null {
  if (operation.entityType === "INVENTORY_MOVEMENT") return validateMovement(operation.payload);
  if (operation.entityType === "SALE") {
    const payload = operation.payload;
    const subtotal = payload.subtotal as number;
    const discount = payload.discountAmount as number;
    const adjustment = payload.netAdjustment as number;
    const finalTotal = payload.finalTotal as number;
    if (discount > 0 && adjustment !== 0) return "discountAmount and netAdjustment are mutually exclusive.";
    if (finalTotal !== subtotal - discount + adjustment) return "finalTotal does not reconcile.";
    const lines = payload.lines as JsonObject[];
    if (lines.some((line) => line.finalAmount !== (line.originalAmount as number) - (line.allocatedDiscount as number) + (line.allocatedAdjustment as number))) {
      return "sale line amounts do not reconcile.";
    }
    if (lines.length > 0 && lines.reduce((sum, line) => sum + (line.finalAmount as number), 0) !== finalTotal) {
      return "sale lines do not sum to finalTotal.";
    }
    if (lines.length === 0 && (subtotal !== 0 || discount !== 0)) {
      return "custom-amount-only sale has invalid item amounts.";
    }
    for (const movement of payload.inventoryMovements as JsonObject[]) {
      const error = validateMovement(movement);
      if (error) return error;
      if (movement.relatedTransactionId !== payload.id || movement.type !== "SALE") {
        return "sale inventory movement has the wrong transaction or type.";
      }
    }
  }
  if (operation.entityType === "VOID") {
    const payload = operation.payload;
    for (const movement of payload.inventoryMovements as JsonObject[]) {
      const error = validateMovement(movement);
      if (error) return error;
      if (movement.relatedTransactionId !== payload.saleId || movement.type !== "VOID") {
        return "void inventory movement has the wrong transaction or type.";
      }
    }
  }
  return null;
}

function validateMovement(payload: JsonObject): string | null {
  const from = payload.fromLocation as JsonObject | null;
  const to = payload.toLocation as JsonObject | null;
  if (!from && !to) return "inventory movement needs a source or destination.";
  if (from && to && locationKey(from) === locationKey(to)) return "inventory source and destination must differ.";
  for (const location of [from, to]) {
    if (!location) continue;
    if (location.type === "GENERAL" && location.eventId !== null) return "GENERAL location cannot have eventId.";
    if (location.type === "EVENT" && location.eventId !== payload.eventId) return "EVENT location must match movement eventId.";
  }
  return null;
}

export function locationKey(location: JsonObject | null): string | null {
  if (!location) return null;
  return location.type === "GENERAL" ? "GENERAL" : `EVENT:${String(location.eventId)}`;
}

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
}
