CREATE TABLE verdicts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  mod_id TEXT NOT NULL,
  version TEXT NOT NULL DEFAULT '',
  sha1 TEXT,
  reason TEXT NOT NULL,
  verdict TEXT NOT NULL,
  method TEXT NOT NULL,
  detail TEXT,
  by TEXT NOT NULL,
  tool TEXT,
  created INTEGER NOT NULL
);
CREATE INDEX verdicts_key ON verdicts(mod_id, version, reason);
