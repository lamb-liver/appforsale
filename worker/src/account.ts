export interface DeletionTombstone {
  google_sub: string;
  former_user_id: string | null;
  scope: "CLOUD" | "ACCOUNT";
  deletion_epoch: number;
  requested_at_utc: string;
}

export async function latestTombstone(db: D1Database, googleSub: string): Promise<DeletionTombstone | null> {
  return db.prepare(
    `SELECT google_sub, former_user_id, scope, deletion_epoch, requested_at_utc
     FROM deletion_tombstones WHERE google_sub = ?
     ORDER BY deletion_epoch DESC, requested_at_utc DESC LIMIT 1`,
  ).bind(googleSub).first<DeletionTombstone>();
}

export function clearUserStatements(db: D1Database, userId: string, deleteUser: boolean): D1PreparedStatement[] {
  const tables = [
    "audit_logs", "processed_operations", "voids", "bundle_component_allocations", "sale_lines", "sales",
    "inventory_movements", "inventory_levels", "events", "bundles", "products", "categories",
    "dashboard_sessions", "device_transfers", "refresh_credentials", "sessions", "devices",
  ];
  const statements = tables.map((table) => db.prepare(`DELETE FROM ${table} WHERE user_id = ?`).bind(userId));
  if (deleteUser) statements.push(db.prepare("DELETE FROM users WHERE id = ?").bind(userId));
  return statements;
}

export function strictlyAfter(iso: string): string {
  return new Date(Math.max(Date.now(), new Date(iso).getTime() + 1)).toISOString();
}
