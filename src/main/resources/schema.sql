CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS outfits (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    owner         TEXT    NOT NULL,
    name          TEXT    NOT NULL COLLATE NOCASE,
    url           TEXT    NOT NULL,
    model         TEXT    NOT NULL CHECK (model IN ('WIDE', 'SLIM')),
    sr_identifier TEXT,
    created_at    INTEGER NOT NULL,
    UNIQUE (owner, name)
);

CREATE INDEX IF NOT EXISTS idx_outfits_owner ON outfits (owner);

CREATE TABLE IF NOT EXISTS players (
    uuid             TEXT PRIMARY KEY,
    active_outfit_id INTEGER REFERENCES outfits (id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS mirrors (
    furniture_uuid TEXT    PRIMARY KEY,
    owner          TEXT    NOT NULL,
    world          TEXT    NOT NULL,
    x              REAL    NOT NULL,
    y              REAL    NOT NULL,
    z              REAL    NOT NULL,
    is_public      INTEGER NOT NULL DEFAULT 0 CHECK (is_public IN (0, 1)),
    created_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS mirror_trust (
    furniture_uuid TEXT NOT NULL REFERENCES mirrors (furniture_uuid) ON DELETE CASCADE,
    player         TEXT NOT NULL,
    PRIMARY KEY (furniture_uuid, player)
);

-- Stage 2: skins SkinsRestorer already processed, so a restart does not send them to MineSkin again.
CREATE TABLE IF NOT EXISTS skin_cache (
    cache_key     TEXT    PRIMARY KEY,
    url           TEXT    NOT NULL,
    model         TEXT    NOT NULL CHECK (model IN ('WIDE', 'SLIM')),
    sr_identifier TEXT    NOT NULL,
    created_at    INTEGER NOT NULL,
    last_used_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_skin_cache_used ON skin_cache (last_used_at);

INSERT OR IGNORE INTO meta (key, value) VALUES ('schema_version', '1')
