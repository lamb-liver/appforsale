const POS_TABLES = [
  "inactivity_notifications",
  "dashboard_sessions",
  "audit_logs",
  "processed_operations",
  "voids",
  "bundle_component_allocations",
  "sale_lines",
  "sales",
  "inventory_movements",
  "inventory_levels",
  "events",
  "bundles",
  "products",
  "categories",
  "device_transfers",
  "refresh_credentials",
  "sessions",
  "devices",
  "users",
] as const;

export async function resetPosDb(db: D1Database): Promise<void> {
  await db.batch(POS_TABLES.map((table) => db.prepare(`DELETE FROM ${table}`)));
}
