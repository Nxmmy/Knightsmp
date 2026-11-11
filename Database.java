package dev.knightsmp.mods.storage;

import dev.knightsmp.mods.KnightPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** SQLite access. One connection, one worker thread: all queries are serialized and never run on the server thread. */
public final class Database {
    @FunctionalInterface public interface SqlFunction<T> { T apply(Connection c) throws Exception; }

    private final KnightPlugin plugin;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "KnightSMP-DB"));
    private Connection conn;

    public Database(KnightPlugin plugin) { this.plugin = plugin; }

    public void open() throws Exception {
        plugin.getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC"); // bundled with Paper
        conn = DriverManager.getConnection("jdbc:sqlite:" + new File(plugin.getDataFolder(), "knightsmp.db").getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "iron INTEGER NOT NULL DEFAULT 0 CHECK(iron>=0), diamonds INTEGER NOT NULL DEFAULT 0 CHECK(diamonds>=0), "
                    + "bounty INTEGER NOT NULL DEFAULT 0 CHECK(bounty>=0))");
            s.execute("CREATE TABLE IF NOT EXISTS tx (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, uuid TEXT NOT NULL, "
                    + "other TEXT, currency TEXT NOT NULL, delta INTEGER NOT NULL, type TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_uuid ON tx(uuid, id)");
            s.execute("CREATE TABLE IF NOT EXISTS homes (uuid TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL, y REAL, z REAL, yaw REAL, pitch REAL)");
            s.execute("CREATE TABLE IF NOT EXISTS kit_cooldowns (uuid TEXT NOT NULL, kit TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(uuid, kit))");
            s.execute("CREATE TABLE IF NOT EXISTS market_state (material TEXT PRIMARY KEY, up INTEGER NOT NULL, multiplier REAL NOT NULL, price INTEGER NOT NULL, stock INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        }
    }

    public <T> CompletableFuture<T> call(SqlFunction<T> f) {
        CompletableFuture<T> fut = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try { fut.complete(f.apply(conn)); }
                catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Database error", t);
                    fut.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) { fut.completeExceptionally(e); }
        return fut;
    }

    public void update(String sql, Object... params) {
        call(c -> { try (PreparedStatement ps = bind(c, sql, params)) { return ps.executeUpdate(); } });
    }

    public static PreparedStatement bind(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        return ps;
    }

    /** Flushes queued writes, then closes. */
    public void close() {
        exec.shutdown();
        try { if (!exec.awaitTermination(20, TimeUnit.SECONDS)) plugin.getLogger().warning("Database queue did not drain in time."); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}
