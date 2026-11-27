"""Runs the SQL of WardrobeStorage (extracted from the Java source, so it cannot drift) against real SQLite.
Usage: python3 dev-checks/sql_check.py src/main/java/com/veyrion/wardrobe/storage/WardrobeStorage.java src/main/resources/schema.sql
Python's sqlite3 stands in for the JDBC driver (not available here); the SQL text is the same."""
import os, re, sqlite3, subprocess, sys, tempfile

java, schema = sys.argv[1], sys.argv[2]
src = open(java, encoding="utf-8").read()
SQL = {m.group(1): " ".join(m.group(2).split()) for m in re.finditer(r'static final String (SQL_\w+) = """\n(.*?)""";', src, re.S)}
expected = {"SQL_LOAD_OUTFITS","SQL_LOAD_ACTIVE","SQL_NAME_EXISTS","SQL_INSERT_OUTFIT","SQL_LAST_ID","SQL_RENAME","SQL_DELETE","SQL_CLEAR_ACTIVE","SQL_SET_ACTIVE","SQL_SET_SKIN_REF","SQL_SKIN_LOAD_ALL","SQL_SKIN_UPSERT","SQL_SKIN_TOUCH","SQL_SKIN_DELETE","SQL_SKIN_DELETE_OLD"}
checks = failures = 0
def eq(what, exp, act):
    global checks, failures
    checks += 1
    if exp != act:
        failures += 1
        print(f"FAIL {what}: expected {exp!r} but was {act!r}")

eq("all statements extracted", expected, set(SQL))

def open_db(path):
    c = sqlite3.connect(path, isolation_level=None)
    for p in ("busy_timeout = 5000", "foreign_keys = ON", "journal_mode = WAL", "synchronous = FULL"):
        c.execute("PRAGMA " + p)
    c.execute("BEGIN")
    for part in open(schema, encoding="utf-8").read().split(";"):
        if part.strip():
            c.execute(part.strip())
    c.execute("COMMIT")
    return c

d = tempfile.mkdtemp()
path = os.path.join(d, "wardrobe.db")
db = open_db(path)
open_db(path).close()  # schema is idempotent (second start)
eq("schema version", "1", db.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()[0])
eq("quick_check", "ok", db.execute("PRAGMA quick_check").fetchone()[0])
eq("journal mode", "wal", db.execute("PRAGMA journal_mode").fetchone()[0])

A, B = "00000000-0000-0000-0000-00000000000a", "00000000-0000-0000-0000-00000000000b"
def insert(owner, name, limit, url="https://x.io/a.png", model="WIDE", ref=None):
    if db.execute(SQL["SQL_NAME_EXISTS"], (owner, name)).fetchone():
        return "NAME_TAKEN"
    cur = db.execute(SQL["SQL_INSERT_OUTFIT"], (owner, name, url, model, ref, 1000, owner, limit))
    if cur.rowcount == 0:
        return "LIMIT_REACHED"
    return db.execute(SQL["SQL_LAST_ID"]).fetchone()[0]

a1 = insert(A, "Knight", 2)
a2 = insert(A, "Mage", 2)
eq("ids are numbers", True, isinstance(a1, int) and isinstance(a2, int) and a2 > a1)
eq("limit reached", "LIMIT_REACHED", insert(A, "Third", 2))
eq("limit counts per owner", True, isinstance(insert(B, "Knight", 2), int))
eq("name taken, same case", "NAME_TAKEN", insert(A, "Knight", 2))
eq("name taken, other case", "NAME_TAKEN", insert(A, "kNiGhT", 2))
try:
    db.execute("INSERT INTO outfits (owner,name,url,model,created_at) VALUES (?,?,?,?,?)", (A, "KNIGHT", "u", "WIDE", 1))
    eq("raw duplicate blocked by UNIQUE", "blocked", "allowed")
except sqlite3.IntegrityError as e:
    eq("raw duplicate blocked by UNIQUE", True, "UNIQUE" in str(e))
try:
    db.execute("INSERT INTO outfits (owner,name,url,model,created_at) VALUES (?,?,?,?,?)", (A, "Bad", "u", "HUGE", 1))
    eq("bad model blocked", "blocked", "allowed")
except sqlite3.IntegrityError:
    eq("bad model blocked", True, True)

rows = db.execute(SQL["SQL_LOAD_OUTFITS"], (A,)).fetchall()
eq("load order and fields", [("Knight", "WIDE", None), ("Mage", "WIDE", None)], [(r[1], r[3], r[4]) for r in rows])
eq("B's outfits are separate", 1, len(db.execute(SQL["SQL_LOAD_OUTFITS"], (B,)).fetchall()))
eq("no active row yet", None, db.execute(SQL["SQL_LOAD_ACTIVE"], (A,)).fetchone())

# rename: owner check, uniqueness
eq("rename own", 1, db.execute(SQL["SQL_RENAME"], ("Paladin", a1, A)).rowcount)
eq("rename someone else's", 0, db.execute(SQL["SQL_RENAME"], ("Stolen", a1, B)).rowcount)
eq("name unchanged after foreign rename", "Paladin", db.execute("SELECT name FROM outfits WHERE id=?", (a1,)).fetchone()[0])
try:
    db.execute(SQL["SQL_RENAME"], ("mage", a1, A))
    eq("rename to taken name blocked", "blocked", "allowed")
except sqlite3.IntegrityError as e:
    eq("rename to taken name blocked", True, "UNIQUE" in str(e))
eq("rename to own name, other case", 1, db.execute(SQL["SQL_RENAME"], ("PALADIN", a1, A)).rowcount)

# skin ref: owner check
eq("skin ref own", 1, db.execute(SQL["SQL_SET_SKIN_REF"], ("ref1", a1, A)).rowcount)
eq("skin ref foreign", 0, db.execute(SQL["SQL_SET_SKIN_REF"], ("hack", a1, B)).rowcount)
eq("skin ref stored", "ref1", db.execute("SELECT sr_identifier FROM outfits WHERE id=?", (a1,)).fetchone()[0])

# active outfit
eq("set active own (insert)", 1, db.execute(SQL["SQL_SET_ACTIVE"], (A, a1, a1, A)).rowcount)
eq("active loaded", a1, db.execute(SQL["SQL_LOAD_ACTIVE"], (A,)).fetchone()[0])
eq("set active other own (upsert)", 1, db.execute(SQL["SQL_SET_ACTIVE"], (A, a2, a2, A)).rowcount)
eq("active switched", a2, db.execute(SQL["SQL_LOAD_ACTIVE"], (A,)).fetchone()[0])
eq("B cannot activate A's outfit", 0, db.execute(SQL["SQL_SET_ACTIVE"], (B, a1, a1, B)).rowcount)
eq("B has no players row", None, db.execute(SQL["SQL_LOAD_ACTIVE"], (B,)).fetchone())
eq("A's active untouched by B's attempt", a2, db.execute(SQL["SQL_LOAD_ACTIVE"], (A,)).fetchone()[0])
eq("nonexistent outfit", 0, db.execute(SQL["SQL_SET_ACTIVE"], (A, 99999, 99999, A)).rowcount)

# delete: owner check, active cleared by ON DELETE SET NULL
eq("delete foreign", 0, db.execute(SQL["SQL_DELETE"], (a2, B)).rowcount)
eq("delete own active outfit", 1, db.execute(SQL["SQL_DELETE"], (a2, A)).rowcount)
row = db.execute(SQL["SQL_LOAD_ACTIVE"], (A,)).fetchone()
eq("active cleared by delete", None, row[0])
eq("room after delete", True, isinstance(insert(A, "Again", 2), int))
eq("clear active (no row yet)", 1, db.execute(SQL["SQL_CLEAR_ACTIVE"], (B,)).rowcount)
eq("clear active (existing row)", 1, db.execute(SQL["SQL_CLEAR_ACTIVE"], (A,)).rowcount)

# mirrors: trust rows vanish with the mirror
db.execute("INSERT INTO mirrors (furniture_uuid, owner, world, x, y, z, created_at) VALUES ('m1', ?, 'world', 1, 2, 3, 1)", (A,))
db.execute("INSERT INTO mirror_trust (furniture_uuid, player) VALUES ('m1', ?)", (B,))
db.execute("DELETE FROM mirrors WHERE furniture_uuid='m1'")
eq("trust deleted with mirror", 0, db.execute("SELECT COUNT(*) FROM mirror_trust").fetchone()[0])

# skin cache (Stage 2)
K1, K2 = "WIDE|https://x.io/a.png", "SLIM|https://x.io/a.png"
def up(key, url, model, ref, created, used):
    db.execute(SQL["SQL_SKIN_UPSERT"], (key, url, model, ref, created, used))
up(K1, "https://x.io/a.png", "WIDE", "ref-1", 100, 100)
up(K2, "https://x.io/a.png", "SLIM", "ref-2", 200, 200)
rows = db.execute(SQL["SQL_SKIN_LOAD_ALL"]).fetchall()
eq("cache: same URL, two models = two rows", 2, len(rows))
eq("cache: load order is least recently used first", [K1, K2], [r[0] for r in rows])
up(K1, "https://x.io/a.png", "WIDE", "ref-1b", 999, 300)
row = db.execute("SELECT sr_identifier, created_at, last_used_at FROM skin_cache WHERE cache_key=?", (K1,)).fetchone()
eq("cache: upsert replaces ref and last use, keeps created_at", ("ref-1b", 100, 300), row)
eq("cache: still two rows after upsert", 2, db.execute("SELECT COUNT(*) FROM skin_cache").fetchone()[0])
eq("cache: touch", 1, db.execute(SQL["SQL_SKIN_TOUCH"], (400, K2)).rowcount)
eq("cache: touched value", 400, db.execute("SELECT last_used_at FROM skin_cache WHERE cache_key=?", (K2,)).fetchone()[0])
eq("cache: touch unknown key", 0, db.execute(SQL["SQL_SKIN_TOUCH"], (1, "WIDE|nope")).rowcount)
try:
    db.execute(SQL["SQL_SKIN_UPSERT"], ("k", "u", "HUGE", "r", 1, 1))
    eq("cache: bad model blocked", "blocked", "allowed")
except sqlite3.IntegrityError:
    eq("cache: bad model blocked", True, True)
eq("cache: delete old (K1 used at 300 < 350)", 1, db.execute(SQL["SQL_SKIN_DELETE_OLD"], (350,)).rowcount)
eq("cache: delete one", 1, db.execute(SQL["SQL_SKIN_DELETE"], (K2,)).rowcount)
eq("cache: empty", 0, db.execute("SELECT COUNT(*) FROM skin_cache").fetchone()[0])
eq("cache: delete unknown", 0, db.execute(SQL["SQL_SKIN_DELETE"], (K2,)).rowcount)

# a Stage 1 database (no skin_cache table) gets the table when Stage 2 starts, and keeps its outfits
db.execute("DROP TABLE skin_cache")
db.close()
db = open_db(path)
eq("migration: table created", 1, db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name='skin_cache'").fetchone()[0])
eq("migration: outfits kept", True, db.execute("SELECT COUNT(*) FROM outfits").fetchone()[0] >= 1)
eq("migration: schema version unchanged", "1", db.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()[0])

# crash safety: a process dies in the middle of a transaction
db.close()
child = f'''
import sqlite3, os
c = sqlite3.connect({path!r}, isolation_level=None)
c.execute("PRAGMA journal_mode = WAL"); c.execute("PRAGMA synchronous = FULL")
c.execute("INSERT INTO outfits (owner,name,url,model,created_at) VALUES ('{A}','Committed','u','WIDE',1)")
c.execute("BEGIN")
c.execute("INSERT INTO outfits (owner,name,url,model,created_at) VALUES ('{A}','HalfWritten','u','WIDE',1)")
os._exit(9)
'''
subprocess.run([sys.executable, "-c", child])
db = sqlite3.connect(path)
eq("after crash: file ok", "ok", db.execute("PRAGMA quick_check").fetchone()[0])
names = [r[0] for r in db.execute("SELECT name FROM outfits WHERE owner=?", (A,))]
eq("after crash: committed row kept", True, "Committed" in names)
eq("after crash: half-written row gone", False, "HalfWritten" in names)

print(f"{checks} checks, {failures} failures")
print("ALL CHECKS PASSED" if failures == 0 else "SOME CHECKS FAILED")
sys.exit(1 if failures else 0)
