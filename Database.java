package dev.knightsmp.mods.storage;

import dev.knightsmp.mods.KnightPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    private File file;

    public Database(KnightPlugin plugin) { this.plugin = plugin; }

    public void open() throws Exception {
        plugin.getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC"); // bundled with Paper
        file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "knightsmp.db"));
        boolean existed = file.exists();
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL"); // durability matters more than speed for money
            s.execute(Sql.CREATE_ACCOUNTS);
            s.execute(Sql.CREATE_TX);
            s.execute(Sql.CREATE_TX_INDEX);
            s.execute(Sql.CREATE_HOMES);
            s.execute(Sql.CREATE_KITS);
            s.execute(Sql.CREATE_MARKET);
            s.execute(Sql.CREATE_META);
        }
        upgrade(existed);
    }

    /** Safe to run on every start: adds any missing columns to an old database once, after taking a backup. */
    private void upgrade(boolean existed) throws Exception {
        String[][] needed = {{"dollars", Sql.ADD_DOLLARS}, {"migrated", Sql.ADD_MIGRATED}, {"kills", Sql.ADD_KILLS}, {"deaths", Sql.ADD_DEATHS},
                {"streak", Sql.ADD_STREAK}, {"best_streak", Sql.ADD_BEST_STREAK}, {"playtime", Sql.ADD_PLAYTIME}, {"anim_off", Sql.ADD_ANIM_OFF}};
        java.util.List<String[]> missing = new java.util.ArrayList<>();
        for (String[] n : needed) if (!hasColumn(n[0])) missing.add(n);
        if (missing.isEmpty()) return;
        try {
            if (existed) backup(conn, "pre-upgrade");
            try (Statement s = conn.createStatement()) { for (String[] n : missing) s.execute(n[1]); }
            plugin.getLogger().info("Database upgraded: added columns " + missing.stream().map(n -> n[0]).toList()
                    + ". Existing balances, bounties, homes and old Iron/Diamond values were kept untouched.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "DATABASE UPGRADE FAILED - your data was not modified. Restore from the .bak file if one was created.", e);
            throw e;
        }
    }

    private boolean hasColumn(String column) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(accounts)")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) return true;
        }
        return false;
    }

    /** Copies the database file to <name>.<label>-<timestamp>.bak (after flushing the WAL). */
    public void backup(Connection c, String label) throws Exception {
        try (Statement s = c.createStatement()) { s.execute("PRAGMA wal_checkpoint(FULL)"); }
        File bak = new File(file.getParentFile(), file.getName() + "." + label + "-" + System.currentTimeMillis() + ".bak");
        Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
        plugin.getLogger().info("Database backup written: " + bak.getName());
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

    /** Runs one statement and returns the number of rows it changed. */
    public static int run(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = bind(c, sql, params)) { return ps.executeUpdate(); }
    }

    /** Flushes queued writes, then closes. */
    public void close() {
        exec.shutdown();
        try { if (!exec.awaitTermination(20, TimeUnit.SECONDS)) plugin.getLogger().warning("Database queue did not drain in time."); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}
