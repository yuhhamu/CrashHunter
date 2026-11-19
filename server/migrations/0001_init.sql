CREATE TABLE reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created INTEGER NOT NULL,
  install TEXT NOT NULL,
  ip TEXT NOT NULL,
  app TEXT NOT NULL,
  mc TEXT NOT NULL,
  loader TEXT NOT NULL,
  body TEXT NOT NULL
);
CREATE INDEX reports_install ON reports(install);
CREATE INDEX reports_env ON reports(mc, loader);

CREATE TABLE items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id INTEGER NOT NULL,
  install TEXT NOT NULL,
  created INTEGER NOT NULL,
  mc TEXT NOT NULL,
  loader TEXT NOT NULL,
  kind TEXT NOT NULL,
  data TEXT NOT NULL
);
CREATE INDEX items_env ON items(mc, loader);
CREATE INDEX items_install ON items(install);

CREATE TABLE blocked (
  install TEXT PRIMARY KEY,
  reason TEXT,
  created INTEGER NOT NULL
);

CREATE TABLE rate (
  bucket TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  expires INTEGER NOT NULL
);

CREATE TABLE dirty (
  mc TEXT NOT NULL,
  loader TEXT NOT NULL,
  PRIMARY KEY (mc, loader)
);
