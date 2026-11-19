CREATE TABLE blocked_ip (
  ip TEXT PRIMARY KEY,
  reason TEXT,
  created INTEGER NOT NULL,
  expires INTEGER
);
CREATE INDEX runs_ip ON runs(ip);
CREATE INDEX reports_ip ON reports(ip);
