CREATE TABLE runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created INTEGER NOT NULL,
  install TEXT NOT NULL,
  ip TEXT NOT NULL,
  app TEXT NOT NULL,
  kind TEXT NOT NULL,
  mc TEXT NOT NULL,
  loader TEXT NOT NULL,
  status TEXT NOT NULL,
  body TEXT NOT NULL
);
CREATE INDEX runs_install ON runs(install);
CREATE INDEX runs_kind ON runs(kind, created);
