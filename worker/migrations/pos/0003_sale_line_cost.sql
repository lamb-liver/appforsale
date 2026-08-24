ALTER TABLE sale_lines ADD COLUMN unit_cost_snapshot INTEGER CHECK (unit_cost_snapshot IS NULL OR unit_cost_snapshot >= 0);
