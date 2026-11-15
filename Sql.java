package dev.knightsmp.mods.storage;

/** Every SQL statement used by the economy, in one place (also exercised by the SQL test suite). */
public final class Sql {
    private Sql() {}

    public static final String CREATE_ACCOUNTS = "CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, iron INTEGER NOT NULL DEFAULT 0, diamonds INTEGER NOT NULL DEFAULT 0, bounty INTEGER NOT NULL DEFAULT 0 CHECK(bounty>=0), dollars INTEGER NOT NULL DEFAULT 0 CHECK(dollars>=0), migrated INTEGER NOT NULL DEFAULT 0, kills INTEGER NOT NULL DEFAULT 0 CHECK(kills>=0), deaths INTEGER NOT NULL DEFAULT 0 CHECK(deaths>=0), streak INTEGER NOT NULL DEFAULT 0, best_streak INTEGER NOT NULL DEFAULT 0, playtime INTEGER NOT NULL DEFAULT 0 CHECK(playtime>=0), anim_off INTEGER NOT NULL DEFAULT 0)";
    public static final String ADD_DOLLARS = "ALTER TABLE accounts ADD COLUMN dollars INTEGER NOT NULL DEFAULT 0 CHECK(dollars>=0)";
    public static final String ADD_MIGRATED = "ALTER TABLE accounts ADD COLUMN migrated INTEGER NOT NULL DEFAULT 0";
    public static final String ADD_KILLS = "ALTER TABLE accounts ADD COLUMN kills INTEGER NOT NULL DEFAULT 0 CHECK(kills>=0)";
    public static final String ADD_DEATHS = "ALTER TABLE accounts ADD COLUMN deaths INTEGER NOT NULL DEFAULT 0 CHECK(deaths>=0)";
    public static final String ADD_STREAK = "ALTER TABLE accounts ADD COLUMN streak INTEGER NOT NULL DEFAULT 0";
    public static final String ADD_BEST_STREAK = "ALTER TABLE accounts ADD COLUMN best_streak INTEGER NOT NULL DEFAULT 0";
    public static final String ADD_PLAYTIME = "ALTER TABLE accounts ADD COLUMN playtime INTEGER NOT NULL DEFAULT 0 CHECK(playtime>=0)";
    public static final String ADD_ANIM_OFF = "ALTER TABLE accounts ADD COLUMN anim_off INTEGER NOT NULL DEFAULT 0";
    public static final String CREATE_HOMES = "CREATE TABLE IF NOT EXISTS homes (uuid TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL, y REAL, z REAL, yaw REAL, pitch REAL)";
    public static final String CREATE_TX = "CREATE TABLE IF NOT EXISTS tx (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, uuid TEXT NOT NULL, other TEXT, currency TEXT NOT NULL, delta INTEGER NOT NULL, type TEXT NOT NULL)";
    public static final String CREATE_TX_INDEX = "CREATE INDEX IF NOT EXISTS idx_tx_uuid ON tx(uuid, id)";
    public static final String CREATE_KITS = "CREATE TABLE IF NOT EXISTS kit_cooldowns (uuid TEXT NOT NULL, kit TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(uuid, kit))";
    public static final String CREATE_MARKET = "CREATE TABLE IF NOT EXISTS market_state (material TEXT PRIMARY KEY, up INTEGER NOT NULL, multiplier REAL NOT NULL, price INTEGER NOT NULL, stock INTEGER NOT NULL)";
    public static final String CREATE_META = "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)";

    public static final String INSERT_ACCOUNT = "INSERT OR IGNORE INTO accounts(uuid,name,dollars) VALUES(?,?,?)";
    public static final String UPDATE_NAME = "UPDATE accounts SET name=? WHERE uuid=?";
    public static final String SELECT_ACCOUNT = "SELECT name,dollars,bounty,kills,deaths,streak,best_streak,playtime,anim_off FROM accounts WHERE uuid=?";
    public static final String HOME_SELECT = "SELECT world,x,y,z,yaw,pitch FROM homes WHERE uuid=?";
    public static final String HOME_SET = "INSERT OR REPLACE INTO homes(uuid,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?)";
    public static final String HOME_DELETE = "DELETE FROM homes WHERE uuid=?";
    public static final String KILLS_ADD = "UPDATE accounts SET kills=kills+1, streak=streak+1, best_streak=MAX(best_streak, streak+1) WHERE uuid=?";
    public static final String DEATH_ADD = "UPDATE accounts SET deaths=deaths+1, streak=0 WHERE uuid=?";
    public static final String PLAYTIME_ADD = "UPDATE accounts SET playtime=playtime+? WHERE uuid=?";
    public static final String ANIM_SET = "UPDATE accounts SET anim_off=? WHERE uuid=?";
    public static final String STAT_TOTALS = "SELECT type, COALESCE(SUM(delta),0), COUNT(*) FROM tx WHERE uuid=? AND currency='USD' GROUP BY type";
    public static final String RANK_MONEY = "SELECT COUNT(*)+1 FROM accounts WHERE dollars > (SELECT dollars FROM accounts WHERE uuid=?)";
    public static final String RANK_KILLS = "SELECT COUNT(*)+1 FROM accounts WHERE kills > (SELECT kills FROM accounts WHERE uuid=?)";
    public static final String SELECT_KITS = "SELECT kit,ts FROM kit_cooldowns WHERE uuid=?";

    public static final String CREDIT = "UPDATE accounts SET dollars=dollars+? WHERE uuid=?";
    public static final String DEBIT = "UPDATE accounts SET dollars=dollars-? WHERE uuid=? AND dollars>=?";
    public static final String SET_BALANCE = "UPDATE accounts SET dollars=? WHERE uuid=?";
    public static final String TX_INSERT = "INSERT INTO tx(ts,uuid,other,currency,delta,type) VALUES(?,?,?,?,?,?)";
    public static final String SELECT_TX = "SELECT ts,other,currency,delta,type FROM tx WHERE uuid=? ORDER BY id DESC LIMIT ?";

    public static final String MARKET_STOCK_TAKE = "UPDATE market_state SET stock=stock-? WHERE material=? AND stock>=?";
    public static final String MARKET_STOCK_RETURN = "UPDATE market_state SET stock=stock+? WHERE material=?";

    public static final String BOUNTY_CLAIM = "UPDATE accounts SET bounty=0 WHERE uuid=? AND bounty=?";
    public static final String BOUNTY_ADD = "UPDATE accounts SET bounty=MIN(?, bounty+?) WHERE uuid=?";
    public static final String BOUNTY_SET = "UPDATE accounts SET bounty=? WHERE uuid=?";
    public static final String LEADERBOARD = "SELECT uuid,name,bounty FROM accounts WHERE bounty>=? ORDER BY bounty DESC, rowid ASC LIMIT ?";
    public static final String BALTOP = "SELECT uuid,name,dollars FROM accounts ORDER BY dollars DESC, rowid ASC LIMIT ?";

    public static final String KIT_STAMP = "INSERT OR REPLACE INTO kit_cooldowns(uuid,kit,ts) VALUES(?,?,?)";
    public static final String KIT_UNSTAMP = "DELETE FROM kit_cooldowns WHERE uuid=? AND kit=?";

    public static final String MIG_PREVIEW = "SELECT COUNT(*), COALESCE(SUM(iron),0), COALESCE(SUM(diamonds),0) FROM accounts WHERE migrated=0 AND (iron>0 OR diamonds>0)";
    public static final String MIG_SELECT = "SELECT uuid,iron,diamonds FROM accounts WHERE migrated=0 AND (iron>0 OR diamonds>0)";
    public static final String MIG_APPLY = "UPDATE accounts SET dollars=dollars+?, migrated=1 WHERE uuid=? AND migrated=0";
}
