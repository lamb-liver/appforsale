export type JsonObject = Record<string, unknown>;

export interface SyncOperation {
  operationId: string;
  category: "MASTER" | "INVENTORY" | "TRANSACTION";
  entityType: "CATEGORY" | "PRODUCT" | "BUNDLE" | "EVENT" | "INVENTORY_MOVEMENT" | "SALE" | "VOID";
  entityId: string;
  operationType: "UPSERT" | "DELETE" | "APPEND";
  payload: JsonObject;
}

export interface SyncBatchRequest {
  requestId: string;
  deviceId: string;
  cloudEpoch: number;
  operations: SyncOperation[];
}

export interface AuthContext {
  userId: string;
  deviceId: string;
  cloudEpoch: number;
  googleSub: string;
}

export interface GoogleClaims {
  sub: string;
  iss: string;
  aud: string;
  exp: number;
  email?: string;
  email_verified?: boolean;
}
