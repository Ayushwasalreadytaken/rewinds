package com.xyron.rewind;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RewindPlugin - rewinds players (position, health, food) and block changes
 * (break, place, explosions, fire, buckets) back by a given amount of time.
 * Works on Spigot/Paper 1.13 up to the latest version, Java 8+.
 */
public class RewindPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static class Snap {
        final long time; final Location loc; final double health; final int food; final float sat;
        Snap(Player p) {
            time = System.currentTimeMillis();
            loc = p.getLocation().clone();
            health = p.getHealth();
            food = p.getFoodLevel();
            sat = p.getSaturation();
        }
    }

    private static class Change {
        final long time; final BlockState state;
        Change(BlockState s) { time = System.currentTimeMillis(); state = s; }
    }

    private final Map<UUID, Deque<Snap>> snaps = new HashMap<UUID, Deque<Snap>>();
    private final Deque<Change> changes = new ArrayDeque<Change>();
    private int maxSeconds;
    private int maxChanges;

    @Override
    public void onEnable() {
        getConfig().addDefault("max-seconds", 300);
        getConfig().addDefault("max-block-changes", 200000);
        getConfig().options().copyDefaults(true);
        saveConfig();
        maxSeconds = Math.max(5, getConfig().getInt("max-seconds"));
        maxChanges = Math.max(1000, getConfig().getInt("max-block-changes"));

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("rewind").setExecutor(this);
        getCommand("rewind").setTabCompleter(this);

        // snapshot every player once per second and drop old history
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { tick(); }
        }, 20L, 20L);
    }

    private void msg(CommandSender s, String m) {
        s.sendMessage(ChatColor.AQUA + "[Rewind] " + ChatColor.WHITE + m);
    }

    // ---------- recording ----------

    private void tick() {
        long now = System.currentTimeMillis();
        long keep = (maxSeconds + 5L) * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            Deque<Snap> d = snaps.get(p.getUniqueId());
            if (d == null) { d = new ArrayDeque<Snap>(); snaps.put(p.getUniqueId(), d); }
            d.addLast(new Snap(p));
            while (!d.isEmpty() && now - d.peekFirst().time > keep) d.pollFirst();
        }
        while (!changes.isEmpty() && now - changes.peekFirst().time > keep) changes.pollFirst();
        while (changes.size() > maxChanges) changes.pollFirst();
    }

    private void record(BlockState s) {
        if (s != null) changes.addLast(new Change(s));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { record(e.getBlock().getState()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { record(e.getBlockReplacedState()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { record(e.getBlock().getState()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) record(b.getState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) record(b.getState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        record(e.getBlockClicked().getRelative(e.getBlockFace()).getState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { record(e.getBlockClicked().getState()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { snaps.remove(e.getPlayer().getUniqueId()); }

    // ---------- rewinding ----------

    private int rewindBlocks(long target) {
        int n = 0;
        while (!changes.isEmpty() && changes.peekLast().time >= target) {
            Change c = changes.pollLast();
            c.state.update(true, false);
            n++;
        }
        return n;
    }

    private boolean rewindPlayer(Player p, long target) {
        Deque<Snap> d = snaps.get(p.getUniqueId());
        if (d == null || d.isEmpty()) return false;
        Snap pick = d.peekFirst(); // oldest, used if history is shorter than asked
        for (Snap s : d) { if (s.time <= target) pick = s; else break; }
        while (!d.isEmpty() && d.peekLast().time > pick.time) d.pollLast();

        if (p.isDead()) return false;
        p.teleport(pick.loc);
        p.setFallDistance(0f);
        p.setFireTicks(0);
        double max = p.getMaxHealth();
        p.setHealth(Math.max(1.0, Math.min(pick.health, max)));
        p.setFoodLevel(pick.food);
        p.setSaturation(pick.sat);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        return true;
    }

    // ---------- command ----------

    private static final Pattern TIME = Pattern.compile("^(\\d+)([smh]?)$", Pattern.CASE_INSENSITIVE);

    private long parseSeconds(String in) {
        Matcher m = TIME.matcher(in);
        if (!m.matches()) return -1;
        long v = Long.parseLong(m.group(1));
        String u = m.group(2).toLowerCase();
        if (u.equals("m")) v *= 60; else if (u.equals("h")) v *= 3600;
        return v;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("rewind.use")) { msg(sender, "You don't have permission."); return true; }
        if (args.length == 0) {
            msg(sender, "/rewind <time> - rewind yourself (e.g. 10s, 2m)");
            msg(sender, "/rewind <time> <@a|players...> - rewind players");
            msg(sender, "/rewind <time> world - undo block changes");
            msg(sender, "/rewind <time> all - everyone + blocks");
            msg(sender, "Max rewind: " + maxSeconds + "s");
            return true;
        }
        long secs = parseSeconds(args[0]);
        if (secs <= 0) { msg(sender, "Bad time. Use e.g. 10, 30s, 2m."); return true; }
        if (secs > maxSeconds) { msg(sender, "Max is " + maxSeconds + " seconds (change max-seconds in config.yml)."); return true; }
        long target = System.currentTimeMillis() - secs * 1000L;

        List<Player> players = new ArrayList<Player>();
        boolean blocks = false;

        if (args.length == 1) {
            if (!(sender instanceof Player)) { msg(sender, "Console: use /rewind <time> all"); return true; }
            players.add((Player) sender);
        } else {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a.equalsIgnoreCase("all")) { players.addAll(Bukkit.getOnlinePlayers()); blocks = true; }
                else if (a.equalsIgnoreCase("world") || a.equalsIgnoreCase("blocks")) blocks = true;
                else if (a.startsWith("@")) {
                    List<Player> f = select(sender, a);
                    if (f == null) { msg(sender, "Bad selector: " + a); return true; }
                    players.addAll(f);
                } else {
                    Player t = Bukkit.getPlayerExact(a);
                    if (t == null) { msg(sender, "Player '" + a + "' is not online."); return true; }
                    players.add(t);
                }
            }
        }

        // blocks first, then players, so players are not teleported into restored blocks
        int blockCount = blocks ? rewindBlocks(target) : 0;
        Set<UUID> done = new HashSet<UUID>();
        int ok = 0;
        for (Player p : players) {
            if (!done.add(p.getUniqueId())) continue;
            if (rewindPlayer(p, target)) {
                ok++;
                p.sendMessage(ChatColor.AQUA + "[Rewind] " + ChatColor.WHITE + "Rewound " + args[0] + " back in time.");
            }
        }
        String res = "Rewound " + args[0] + ": " + ok + " player(s)";
        if (blocks) res += ", " + blockCount + " block change(s) undone";
        msg(sender, res + ".");
        return true;
    }

    private List<Player> select(CommandSender sender, String sel) {
        List<Player> out = new ArrayList<Player>();
        try {
            for (Entity e : Bukkit.selectEntities(sender, sel)) if (e instanceof Player) out.add((Player) e);
            return out;
        } catch (Throwable t) {
            String s = sel.toLowerCase();
            if (s.equals("@a")) { out.addAll(Bukkit.getOnlinePlayers()); return out; }
            if (s.equals("@s") && sender instanceof Player) { out.add((Player) sender); return out; }
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (!sender.hasPermission("rewind.use")) return out;
        if (args.length == 1) out.addAll(Arrays.asList("10s", "30s", "1m", "5m"));
        else {
            out.addAll(Arrays.asList("all", "world", "@a", "@s"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args[args.length - 1].toLowerCase();
        Iterator<String> it = out.iterator();
        while (it.hasNext()) if (!it.next().toLowerCase().startsWith(last)) it.remove();
        return out;
    }
}
