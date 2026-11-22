CREATE TABLE remembered_login (
  id INTEGER PRIMARY KEY CHECK(id=1),
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  password_version INTEGER NOT NULL,
  token_hash TEXT NOT NULL CHECK(length(token_hash)=64),
  expires_at INTEGER NOT NULL
);
