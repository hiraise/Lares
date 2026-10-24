-- Shopping module (M2-02, architecture/shopping.md § Server › Tables): shopping lists and their items.

-- A shopping list (SHP-001). Every member with shopping.view sees all lists of the household (Q-018).
CREATE TABLE shopping_list (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  name text NOT NULL,
  created_by uuid NOT NULL REFERENCES member(id),
  -- JSON array of tag ids (like task.tag_ids).
  tag_ids text NOT NULL DEFAULT '[]',
  group_by_category boolean NOT NULL DEFAULT true,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX shopping_list_household_seq ON shopping_list (household_id, seq);

-- "This product in this list" (SHP-002, ADR-0021): kept after it is bought and cleared, as the list's memory.
CREATE TABLE shopping_item (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  list_id uuid NOT NULL REFERENCES shopping_list(id) ON DELETE CASCADE,
  name text NOT NULL,
  -- Wire names: ShoppingItemStatus.key, ProductCategory.key.
  status text NOT NULL,
  category text NOT NULL,
  quantity text NULL,
  note text NULL,
  -- JSON array of tag ids.
  tag_ids text NOT NULL DEFAULT '[]',
  added_by uuid NOT NULL REFERENCES member(id),
  added_at timestamptz NOT NULL,
  bought_by uuid NULL REFERENCES member(id),
  bought_at timestamptz NULL,
  bought_count int NOT NULL DEFAULT 0,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX shopping_item_household_seq ON shopping_item (household_id, seq);
CREATE INDEX shopping_item_list ON shopping_item (list_id);
