"""Runs the plugin's REAL SQL statements (parsed out of Sql.java) against SQLite and checks the economy invariants.
Usage: python3 sql_tests.py path/to/Sql.java
"""
import re, sqlite3, sys, os, tempfile, threading, uuid, time

src = open(sys.argv[1] if len(sys.argv) > 1 else "Sql.java").read()
SQL = dict(re.findall(r'public static final String (\w+) = "((?:[^"\\]|\\.)*)";', src))
declared = src.count("public static final String")
assert len(SQL) == declared, f"parsed {len(SQL)} of {declared} statements"

passed = failed = 0
def check(name, cond, detail=""):
    global passed, failed
    if cond: passed += 1; print(f"  PASS  {name}")
    else: failed += 1; print(f"  FAIL  {name} {detail}")

def new_db(path=None):
    path = path or tempfile.mktemp(suffix=".db")
    c = sqlite3.connect(path, isolation_level=None)
    for k in ("CREATE_ACCOUNTS","CREATE_TX","CREATE_TX_INDEX","CREATE_SETTINGS","CREATE_ADMIN_LOG","CREATE_HOMES","CREATE_KITS","CREATE_MARKET","CREATE_META"): c.execute(SQL[k])
    return c, path
def add(c, name, dollars=0, bounty=0):
    u = str(uuid.uuid4()); c.execute(SQL["INSERT_ACCOUNT"], (u, name, dollars))
    if bounty: c.execute(SQL["BOUNTY_SET"], (bounty, u))
    return u
def bal(c, u): return c.execute("SELECT dollars FROM accounts WHERE uuid=?", (u,)).fetchone()[0]
def bnty(c, u): return c.execute("SELECT bounty FROM accounts WHERE uuid=?", (u,)).fetchone()[0]

def move(c, u, delta, kind="TEST", extra=None):
    """Mirror of EconomyManager.commit for one change: conditional update + history row + extra, all-or-nothing."""
    c.execute("BEGIN")
    try:
        n = c.execute(SQL["CREDIT"], (delta, u)).rowcount if delta >= 0 else c.execute(SQL["DEBIT"], (-delta, u, -delta)).rowcount
        if n != 1: c.execute("ROLLBACK"); return False
        c.execute(SQL["TX_INSERT"], (int(time.time()*1000), u, None, "USD", delta, kind))
        if extra and not extra(c): c.execute("ROLLBACK"); return False
        c.execute("COMMIT"); return True
    except Exception:
        c.execute("ROLLBACK"); return False

print("== accounts / balances")
c, path = new_db()
a = add(c, "alice", 150)
check("starting balance stored", bal(c, a) == 150)
check("INSERT OR IGNORE does not duplicate/reset an account", (c.execute(SQL["INSERT_ACCOUNT"], (a, "alice", 999)).rowcount == 0) and bal(c, a) == 150)
check("debit more than balance is rejected (never negative)", move(c, a, -151) is False and bal(c, a) == 150)
check("debit exactly the balance works", move(c, a, -150) is True and bal(c, a) == 0)
check("credit works", move(c, a, 40) is True and bal(c, a) == 40)
try: c.execute("UPDATE accounts SET dollars=-5 WHERE uuid=?", (a,)); neg = True
except sqlite3.IntegrityError: neg = False
check("CHECK constraint blocks a negative balance even for a buggy direct write", neg is False and bal(c, a) == 40)
check("each COMMITTED move left one history row; the rejected debit left none", c.execute("SELECT COUNT(*) FROM tx WHERE uuid=?", (a,)).fetchone()[0] == 2)

print("== SELL (the reported bug): payment, stock and rollback")
c, path = new_db(); s = add(c, "seller", 0)
c.execute(SQL["MARKET_STOCK_RETURN"].replace("stock=stock+?", "stock=stock+?"), (0, "CARROT"))  # no row yet: no-op
c.execute("INSERT INTO market_state(material,up,multiplier,price,stock) VALUES('CARROT',1,1.0,3,100)")
bundles, price = 48, 3           # 12 stacks of 64 carrots = 768 = 48 bundles of 16
ok = move(c, s, bundles*price, "SELL", lambda cc: cc.execute(SQL["MARKET_STOCK_TAKE"], (bundles, "CARROT", bundles)).rowcount == 1)
check("12 stacks of carrots (48 bundles) pays $144", ok and bal(c, s) == 144)
check("market stock reduced by exactly 48", c.execute("SELECT stock FROM market_state WHERE material='CARROT'").fetchone()[0] == 52)
ok = move(c, s, 60*price, "SELL", lambda cc: cc.execute(SQL["MARKET_STOCK_TAKE"], (60, "CARROT", 60)).rowcount == 1)
check("selling more than the remaining stock fails ENTIRELY (no payment)", ok is False and bal(c, s) == 144)
check("...and the stock is unchanged after that failed sale", c.execute("SELECT stock FROM market_state WHERE material='CARROT'").fetchone()[0] == 52)
ok = move(c, s, -144, "SELL_REVERSE", lambda cc: cc.execute(SQL["MARKET_STOCK_RETURN"], (48, "CARROT")).rowcount == 1)
check("sale reversal returns the money AND the stock", ok and bal(c, s) == 0 and c.execute("SELECT stock FROM market_state WHERE material='CARROT'").fetchone()[0] == 100)

print("== SHOP / KIT purchases")
c, path = new_db(); b = add(c, "buyer", 500)
check("purchase deducts exactly the price", move(c, b, -173, "SHOP") and bal(c, b) == 327)
check("cannot buy with insufficient funds, balance untouched", move(c, b, -328, "SHOP") is False and bal(c, b) == 327)
kit_ok = move(c, b, -102, "KIT", lambda cc: cc.execute(SQL["KIT_STAMP"], (b, "beginner", 1)).rowcount == 1)
check("kit charge + cooldown stamp commit together", kit_ok and c.execute("SELECT COUNT(*) FROM kit_cooldowns").fetchone()[0] == 1)
move(c, b, 102, "KIT_REFUND", lambda cc: cc.execute(SQL["KIT_UNSTAMP"], (b, "beginner")).rowcount == 1)
check("kit refund returns money and removes the stamp", bal(c, b) == 327 and c.execute("SELECT COUNT(*) FROM kit_cooldowns").fetchone()[0] == 0)
fail = move(c, b, -50, "KIT", lambda cc: False)
check("a failing extra step rolls the charge back", fail is False and bal(c, b) == 327)

print("== /pay atomicity")
c, path = new_db(); x = add(c, "x", 100); y = add(c, "y", 0)
def pay(c, f, t, n):
    c.execute("BEGIN")
    if c.execute(SQL["DEBIT"], (n, f, n)).rowcount != 1: c.execute("ROLLBACK"); return False
    if c.execute(SQL["CREDIT"], (n, t)).rowcount != 1: c.execute("ROLLBACK"); return False
    c.execute("COMMIT"); return True
check("pay moves exactly the amount, total money conserved", pay(c, x, y, 60) and bal(c, x) == 40 and bal(c, y) == 60)
check("overdraft pay rejected, nothing moves", pay(c, x, y, 41) is False and bal(c, x) == 40 and bal(c, y) == 60)
ghost = str(uuid.uuid4())
check("pay to a non-existent account rolls the debit back", pay(c, x, ghost, 10) is False and bal(c, x) == 40)

print("== bounty claim runs exactly once")
c, path = new_db(); k = add(c, "killer", 0); v = add(c, "victim", 0, bounty=300)
def claim(vb):
    return move(c, k, vb, "BOUNTY", lambda cc: cc.execute(SQL["BOUNTY_CLAIM"], (v, vb)).rowcount == 1 and cc.execute(SQL["BOUNTY_ADD"], (1000000, 50, k)).rowcount == 1)
check("first claim pays the bounty, resets victim, raises killer bounty", claim(300) and bal(c, k) == 300 and bnty(c, v) == 0 and bnty(c, k) == 50)
check("SECOND claim of the same bounty pays nothing (duplicate event / race)", claim(300) is False and bal(c, k) == 300)
check("BOUNTY_ADD respects the max-bounty cap", c.execute(SQL["BOUNTY_ADD"], (60, 50, k)).rowcount == 1 and bnty(c, k) == 60)

print("== concurrency: two simultaneous spends of the same money")
c, path = new_db(); u = add(c, "racer", 100); c.close()
results = []
def spend():
    cn = sqlite3.connect(path, isolation_level=None, timeout=10)   # each worker has its own connection
    results.append(move(cn, u, -80)); cn.close()
ts = [threading.Thread(target=spend) for _ in range(2)]
[t.start() for t in ts]; [t.join() for t in ts]
chk = sqlite3.connect(path, isolation_level=None)
check("only ONE of two simultaneous $80 purchases succeeds with $100", results.count(True) == 1 and results.count(False) == 1 and bal(chk, u) == 20, str(results))

print("== persistence across restart")
c, path = new_db(); p = add(c, "persist", 150); move(c, p, 77); c.close()
c2 = sqlite3.connect(path, isolation_level=None)
check("balance survives close/reopen", bal(c2, p) == 227)

print("== migration of an OLD database (Iron/Diamond schema)")
old = tempfile.mktemp(suffix=".db"); oc = sqlite3.connect(old, isolation_level=None)
oc.execute("CREATE TABLE accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, iron INTEGER NOT NULL DEFAULT 0 CHECK(iron>=0), diamonds INTEGER NOT NULL DEFAULT 0 CHECK(diamonds>=0), bounty INTEGER NOT NULL DEFAULT 0 CHECK(bounty>=0))")
o1, o2, o3 = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
oc.execute("INSERT INTO accounts VALUES(?,?,?,?,?)", (o1, "rich", 100, 5, 40)); oc.execute("INSERT INTO accounts VALUES(?,?,?,?,?)", (o2, "iron-only", 30, 0, 0)); oc.execute("INSERT INTO accounts VALUES(?,?,?,?,?)", (o3, "empty", 0, 0, 7))
def cols(cn): return {r[1] for r in cn.execute("PRAGMA table_info(accounts)")}
def upgrade(cn):
    if "dollars" not in cols(cn): cn.execute(SQL["ADD_DOLLARS"])
    if "migrated" not in cols(cn): cn.execute(SQL["ADD_MIGRATED"])
    for col, key in (("kills", "ADD_KILLS"), ("deaths", "ADD_DEATHS"), ("streak", "ADD_STREAK"), ("best_streak", "ADD_BEST_STREAK"), ("playtime", "ADD_PLAYTIME"), ("anim_off", "ADD_ANIM_OFF")):
        if col not in cols(cn): cn.execute(SQL[key])
upgrade(oc); upgrade(oc)
check("upgrade adds dollars/migrated/kills/deaths/streaks/playtime/anim_off and is safe to run twice", {"dollars", "migrated", "kills", "deaths", "streak", "best_streak", "playtime", "anim_off"} <= cols(oc))
check("upgrade keeps every old row, balance and bounty", oc.execute("SELECT iron,diamonds,bounty FROM accounts WHERE uuid=?", (o1,)).fetchone() == (100, 5, 40) and oc.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] == 3)
check("new accounts on an upgraded DB work (CHECK on added column)", oc.execute(SQL["INSERT_ACCOUNT"], (str(uuid.uuid4()), "new", 150)).rowcount == 1)
n, ti, td = oc.execute(SQL["MIG_PREVIEW"]).fetchone()
check("preview counts only accounts that still hold Iron/Diamonds", (n, ti, td) == (2, 130, 5))
IRON, DIA = 8, 60
def migrate(cn):
    cn.execute("BEGIN"); done = 0
    for u, i, d in cn.execute(SQL["MIG_SELECT"]).fetchall():
        if cn.execute(SQL["MIG_APPLY"], (i*IRON + d*DIA, u)).rowcount == 1: done += 1
    cn.execute("COMMIT"); return done
check("migration converts both accounts with the configured rates", migrate(oc) == 2 and bal(oc, o1) == 100*8+5*60 and bal(oc, o2) == 240)
check("running the migration AGAIN changes nothing (no double credit)", migrate(oc) == 0 and bal(oc, o1) == 1100)
check("old Iron/Diamond columns and bounty are preserved after migration", oc.execute("SELECT iron,diamonds,bounty FROM accounts WHERE uuid=?", (o1,)).fetchone() == (100, 5, 40))

print("== starting balance is given exactly once")
c, path = new_db(); START = 150
def join(cn, uid, name):                       # mirrors EconomyManager.loadBlocking
    cn.execute(SQL["INSERT_ACCOUNT"], (uid, name, START)); cn.execute(SQL["UPDATE_NAME"], (name, uid))
    return cn.execute(SQL["SELECT_ACCOUNT"], (uid,)).fetchone()
nu = str(uuid.uuid4())
check("a new player starts with $150", join(c, nu, "newbie")[1] == 150)
move(c, nu, -100); check("...and spends some", bal(c, nu) == 50)
check("joining again does NOT give another $150", join(c, nu, "newbie")[1] == 50 and bal(c, nu) == 50)
c.close(); c = sqlite3.connect(path, isolation_level=None)
check("a server restart + rejoin does NOT give another $150", join(c, nu, "newbie")[1] == 50)
check("a rename keeps the balance", join(c, nu, "renamed")[0] == "renamed" and bal(c, nu) == 50)
check("accounts that existed before the Dollar update (upgraded) are NOT given $150", oc.execute("SELECT dollars FROM accounts WHERE uuid=?", (o3,)).fetchone()[0] == 0 and (oc.execute(SQL["INSERT_ACCOUNT"], (o3, "empty", START)).rowcount == 0) and oc.execute("SELECT dollars FROM accounts WHERE uuid=?", (o3,)).fetchone()[0] == 0)

print("== kills counter")
c, path = new_db(); kk = add(c, "killer", 0)
def kills(cn, u): return cn.execute("SELECT kills FROM accounts WHERE uuid=?", (u,)).fetchone()[0]
check("new accounts start with 0 kills", kills(c, kk) == 0)
check("a valid kill adds exactly 1 (inside the same transaction as the reward)", move(c, kk, 50, "BOUNTY", lambda cc: cc.execute(SQL["KILLS_ADD"], (kk,)).rowcount == 1) and kills(c, kk) == 1)
check("a rolled-back kill transaction does not count", move(c, kk, 50, "BOUNTY", lambda cc: cc.execute(SQL["KILLS_ADD"], (kk,)).rowcount == 1 and False) is False and kills(c, kk) == 1 and bal(c, kk) == 50)
check("kills survive restart", (c.close() or True) and kills(sqlite3.connect(path, isolation_level=None), kk) == 1)
check("upgraded old accounts get kills = 0 and keep their data", oc.execute("SELECT kills, iron, bounty FROM accounts WHERE uuid=?", (o1,)).fetchone() == (0, 100, 40))

print("== /stat data: deaths, streaks, playtime, totals, ranks, popup preference")
c, path = new_db(); st = add(c, "stats", 150)
def row(cn, u): return cn.execute("SELECT kills,deaths,streak,best_streak,playtime,anim_off FROM accounts WHERE uuid=?", (u,)).fetchone()
check("a new account starts every statistic at zero", row(c, st) == (0, 0, 0, 0, 0, 0))
for _ in range(3): c.execute(SQL["KILLS_ADD"], (st,))
check("3 kills -> kills 3, streak 3, best streak 3", row(c, st)[:4] == (3, 0, 3, 3))
c.execute(SQL["DEATH_ADD"], (st,))
check("a death adds 1 death and ends the streak but keeps the best streak", row(c, st)[:4] == (3, 1, 0, 3))
c.execute(SQL["KILLS_ADD"], (st,)); c.execute(SQL["KILLS_ADD"], (st,))
check("a shorter streak after a death does not lower the best streak", row(c, st)[:4] == (5, 1, 2, 3))
for _ in range(2): c.execute(SQL["KILLS_ADD"], (st,))
check("a new longer streak raises the best streak", row(c, st)[2:4] == (4, 4))
c.execute(SQL["PLAYTIME_ADD"], (300, st)); c.execute(SQL["PLAYTIME_ADD"], (125, st))
check("playtime increments add up (300 s + 125 s = 425 s)", row(c, st)[4] == 425)
try: c.execute(SQL["PLAYTIME_ADD"], (-9999, st)); neg = True
except sqlite3.IntegrityError: neg = False
check("playtime can never go negative (CHECK constraint)", neg is False and row(c, st)[4] == 425)
c.execute(SQL["ANIM_SET"], (1, st)); check("the /payanim off preference is saved", row(c, st)[5] == 1)
c.close(); c = sqlite3.connect(path, isolation_level=None)
check("kills, deaths, streaks, playtime and the popup preference all survive a restart", row(c, st) == (7, 1, 4, 4, 425, 1))
r2 = add(c, "rich", 900); c.execute(SQL["PLAYTIME_ADD"], (1, r2)); c.execute(SQL["BOUNTY_SET"], (0, r2))
move(c, st, 90, "SELL"); move(c, st, -40, "SHOP"); move(c, st, -15, "KIT"); move(c, st, 200, "PAY_RECEIVED"); move(c, st, -30, "PAY_SENT"); move(c, st, 60, "BOUNTY")
tot = {t: (a, n) for t, a, n in c.execute(SQL["STAT_TOTALS"], (st,))}
check("totals come from the saved transaction history (earned selling, spent, payments, bounties)", tot["SELL"] == (90, 1) and tot["SHOP"] == (-40, 1) and tot["KIT"] == (-15, 1) and tot["PAY_RECEIVED"] == (200, 1) and tot["PAY_SENT"] == (-30, 1) and tot["BOUNTY"] == (60, 1))
check("richest rank is computed from real balances (rich $900 is #1, stats is behind)", c.execute(SQL["RANK_MONEY"], (r2,)).fetchone()[0] == 1 and c.execute(SQL["RANK_MONEY"], (st,)).fetchone()[0] == 2)
check("kills rank is computed from real kills", c.execute(SQL["RANK_KILLS"], (st,)).fetchone()[0] == 1 and c.execute(SQL["RANK_KILLS"], (r2,)).fetchone()[0] == 2)
check("an old-database account upgraded for 2.0 has all statistics at 0", oc.execute("SELECT deaths,streak,best_streak,playtime,anim_off FROM accounts WHERE uuid=?", (o1,)).fetchone() == (0, 0, 0, 0, 0))

print("== /settings persistence and isolation")
c, path = new_db(); pa = add(c, "playerA", 150); pb = add(c, "playerB", 150)
def sets(cn, u): return dict(cn.execute(SQL["SETTING_SELECT"], (u,)).fetchall())
check("a new player has no stored settings (defaults apply)", sets(c, pa) == {})
c.execute(SQL["SETTING_SET"], (pa, "mob-spawn", "closed"))
check("player A closes Mob Spawn: it is saved for A only", sets(c, pa) == {"mob-spawn": "closed"} and sets(c, pb) == {})
c.execute(SQL["SETTING_SET"], (pa, "mob-spawn", "open")); c.execute(SQL["SETTING_SET"], (pa, "mob-spawn", "closed"))
check("changing a setting repeatedly keeps exactly one row per setting (no duplicates)", c.execute("SELECT COUNT(*) FROM player_settings WHERE uuid=?", (pa,)).fetchone()[0] == 1)
c.execute(SQL["SETTING_SET"], (pa, "future-setting", "open"))
check("new settings need no schema change (key/value rows)", sets(c, pa) == {"mob-spawn": "closed", "future-setting": "open"})
c.execute(SQL["SETTING_SET"], (pb, "mob-spawn", "open"))
check("A and B stay independent", sets(c, pa)["mob-spawn"] == "closed" and sets(c, pb)["mob-spawn"] == "open")
c.close(); c = sqlite3.connect(path, isolation_level=None)
check("settings survive a restart", sets(c, pa)["mob-spawn"] == "closed" and sets(c, pb)["mob-spawn"] == "open")

print("== /reset: money and bounty only")
c, path = new_db(); TO = 0
ra, rb, rc = add(c, "alice", 500, bounty=300), add(c, "bob", 80, bounty=50), add(c, "carol", 0, bounty=0)
for u in (ra, rb, rc):
    c.execute(SQL["KILLS_ADD"], (u,)); c.execute(SQL["DEATH_ADD"], (u,)); c.execute(SQL["PLAYTIME_ADD"], (999, u))
    c.execute(SQL["SETTING_SET"], (u, "mob-spawn", "closed")); c.execute(SQL["HOME_SET"], (u, "world", 1.0, 2.0, 3.0, 0.0, 0.0)); c.execute(SQL["KIT_STAMP"], (u, "beginner", 1))
def snapshot(u): return (c.execute("SELECT kills,deaths,streak,best_streak,playtime,anim_off FROM accounts WHERE uuid=?", (u,)).fetchone(), sets(c, u), c.execute(SQL["HOME_SELECT"], (u,)).fetchone(), c.execute("SELECT kit FROM kit_cooldowns WHERE uuid=?", (u,)).fetchall())
before = {u: snapshot(u) for u in (ra, rb, rc)}
def do_reset(cn, target=None, to=TO, admin=("admin-uuid", "Admin")):
    cn.execute("BEGIN")
    if target is None:
        cn.execute(SQL["RESET_TX_ALL"], (1, to, to)); n = cn.execute(SQL["RESET_ALL"], (to,)).rowcount
    else:
        cn.execute(SQL["RESET_TX_ONE"], (1, to, target, to)); n = cn.execute(SQL["RESET_ONE"], (to, target)).rowcount
        if n != 1: cn.execute("ROLLBACK"); return n
    cn.execute(SQL["ADMIN_LOG_INSERT"], (1, admin[0], admin[1], "RESET_MONEY_BOUNTY", "EVERYONE" if target is None else target, "money->%d" % to))
    cn.execute("COMMIT"); return n
check("reset ONE player (alice): only alice's money and bounty change", do_reset(c, ra) == 1 and bal(c, ra) == 0 and bnty(c, ra) == 0 and bal(c, rb) == 80 and bnty(c, rb) == 50)
check("...and nobody else's money/bounty was touched (bob keeps $80 / bounty 50)", bal(c, rb) == 80 and bnty(c, rb) == 50)
check("kills, deaths, streaks, playtime, settings, homes and kit cooldowns are unchanged for the reset player AND everyone else", all(snapshot(u) == before[u] for u in (ra, rb, rc)))
check("a history row records the money that was removed (-500) for alice only", c.execute("SELECT delta,type FROM tx WHERE uuid=? AND type='ADMIN_RESET'", (ra,)).fetchall() == [(-500, "ADMIN_RESET")] and c.execute("SELECT COUNT(*) FROM tx WHERE uuid=? AND type='ADMIN_RESET'", (rb,)).fetchone()[0] == 0)
check("resetting an unknown/offline-never-seen account changes nothing and reports 0 rows", do_reset(c, str(uuid.uuid4())) == 0 and bal(c, rb) == 80)
check("reset EVERYONE: all balances and bounties go to 0 (offline players included - they are just database rows)", do_reset(c) == 3 and all(bal(c, u) == 0 and bnty(c, u) == 0 for u in (ra, rb, rc)))
check("...history rows only for accounts that actually lost money (bob yes, alice/carol already 0 -> none new)", c.execute("SELECT COUNT(*) FROM tx WHERE type='ADMIN_RESET'", ()).fetchone()[0] == 2)
check("EVERY other column is still untouched after the global reset", all(snapshot(u) == before[u] for u in (ra, rb, rc)))
check("the audit log names the admin and the target for both resets", c.execute("SELECT admin_name,action,target FROM admin_log ORDER BY id").fetchall() == [("Admin", "RESET_MONEY_BOUNTY", ra), ("Admin", "RESET_MONEY_BOUNTY", "EVERYONE")])
check("running the global reset twice is harmless (idempotent) and never creates a negative balance", do_reset(c) == 3 and all(bal(c, u) == 0 for u in (ra, rb, rc)))
check("reset can set a chosen amount instead of 0 (config reset.money-to) and records the right delta", (move(c, ra, 40, "TEST") and do_reset(c, ra, to=10) == 1 and bal(c, ra) == 10 and c.execute("SELECT delta FROM tx WHERE uuid=? AND type='ADMIN_RESET' ORDER BY id DESC LIMIT 1", (ra,)).fetchone()[0] == -30))
check("the reset player list orders names case-insensitively and includes everyone known", [r[1] for r in c.execute(SQL["RESET_LIST"], (100,))] == ["alice", "bob", "carol"])

print("== homes persist")
c, path = new_db(); hu = add(c, "homer", 150)
check("no home at first", c.execute(SQL["HOME_SELECT"], (hu,)).fetchone() is None)
c.execute(SQL["HOME_SET"], (hu, "world", 10.5, 64.0, -3.25, 90.0, 12.5))
c.execute(SQL["HOME_SET"], (hu, "world_nether", 1.0, 70.0, 2.0, 0.0, 0.0))
check("setting a home twice keeps ONE row (replaces)", c.execute("SELECT COUNT(*) FROM homes WHERE uuid=?", (hu,)).fetchone()[0] == 1)
c.close(); c = sqlite3.connect(path, isolation_level=None)
check("home survives restart with exact coordinates", c.execute(SQL["HOME_SELECT"], (hu,)).fetchone() == ("world_nether", 1.0, 70.0, 2.0, 0.0, 0.0))
c.execute(SQL["HOME_DELETE"], (hu,))
check("deleting removes it; deleting again is harmless", c.execute(SQL["HOME_SELECT"], (hu,)).fetchone() is None and c.execute(SQL["HOME_DELETE"], (hu,)).rowcount == 0)
ob = sqlite3.connect(tempfile.mktemp(suffix=".db"), isolation_level=None); ob.execute("CREATE TABLE homes (uuid TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL, y REAL, z REAL, yaw REAL, pitch REAL)")
ob.execute("INSERT INTO homes VALUES('u1','world',1,2,3,4,5)"); ob.execute(SQL["CREATE_HOMES"])
check("an existing homes table from the old plugin is kept as-is (CREATE IF NOT EXISTS)", ob.execute(SQL["HOME_SELECT"], ("u1",)).fetchone() == ("world", 1.0, 2.0, 3.0, 4.0, 5.0))

print("== leaderboard / baltop ordering")
c, path = new_db()
for nme, bo, do in (("a", 10, 5), ("b", 500, 1), ("c", 50, 900)): add(c, nme, do, bo)
check("LEADERBOARD orders by bounty desc", [r[1] for r in c.execute(SQL["LEADERBOARD"], (0, 10))] == ["b", "c", "a"])
check("BALTOP orders by dollars desc", [r[1] for r in c.execute(SQL["BALTOP"], (10,))] == ["c", "a", "b"])

print(f"\n{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
