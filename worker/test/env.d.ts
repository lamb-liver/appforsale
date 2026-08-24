interface Env {
  TEST_POS_MIGRATIONS: D1Migration[];
  TEST_DELETION_MIGRATIONS: D1Migration[];
}

declare namespace Cloudflare {
  interface Env {
    TEST_POS_MIGRATIONS: D1Migration[];
    TEST_DELETION_MIGRATIONS: D1Migration[];
  }
}
