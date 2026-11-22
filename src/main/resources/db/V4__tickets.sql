CREATE TABLE ticket_sets(
  id INTEGER PRIMARY KEY,
  subject_id INTEGER NOT NULL REFERENCES subjects(id),
  subject_name TEXT NOT NULL,
  name TEXT NOT NULL,
  algorithm TEXT NOT NULL,
  created_at TEXT NOT NULL,
  ticket_count INTEGER NOT NULL,
  repeat_limit INTEGER NOT NULL,
  target REAL NOT NULL,
  deviation REAL,
  seed INTEGER NOT NULL,
  budget INTEGER NOT NULL,
  required TEXT NOT NULL,
  excluded TEXT NOT NULL,
  topic_quotas TEXT NOT NULL,
  set_quotas INTEGER NOT NULL,
  type_quotas TEXT NOT NULL,
  weights TEXT NOT NULL,
  initial TEXT NOT NULL,
  selected TEXT NOT NULL,
  organization TEXT,
  period TEXT,
  signature TEXT,
  approval TEXT,
  approved_at TEXT,
  version INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX idx_ticket_set_subject ON ticket_sets(subject_id);
CREATE TABLE ticket_slots(
  set_id INTEGER NOT NULL REFERENCES ticket_sets(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  topic_id INTEGER,
  type TEXT,
  min_difficulty INTEGER NOT NULL,
  max_difficulty INTEGER NOT NULL,
  PRIMARY KEY(set_id,position)
);
CREATE TABLE ticket_bank_snapshot(
  set_id INTEGER NOT NULL REFERENCES ticket_sets(id) ON DELETE CASCADE,
  question_id INTEGER NOT NULL,
  revision INTEGER NOT NULL,
  topic_id INTEGER NOT NULL,
  topic_name TEXT NOT NULL,
  type TEXT NOT NULL,
  difficulty INTEGER NOT NULL,
  points_scaled INTEGER NOT NULL,
  rank REAL NOT NULL,
  text TEXT NOT NULL,
  PRIMARY KEY(set_id,question_id),
  FOREIGN KEY(question_id,revision) REFERENCES question_revisions(question_id,revision_no)
);
