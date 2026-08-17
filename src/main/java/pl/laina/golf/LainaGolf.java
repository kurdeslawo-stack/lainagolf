package pl.laina.golf;

import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class LainaGolf
extends JavaPlugin
implements Listener {
    private static final long GAME_TICK_PERIOD = 2L;
    private static final long PREPARE_TICK_PERIOD = 2L;
    private static final int BALL_PREPARE_TIMEOUT_TICKS = 100;
    private static final double FINISH_DISTANCE_SQUARED = 0.5;
    private static final double MAX_SEGMENT_CHECK_DISTANCE_SQUARED = 16.0;
    private final Map<String, GolfMap> maps = new HashMap<String, GolfMap>();
    private final Map<UUID, GolfSession> activeSessions = new HashMap<UUID, GolfSession>();
    private final Map<UUID, GolfSession> sessionsByBall = new HashMap<UUID, GolfSession>();
    private final Map<UUID, PendingPreparation> pendingPreparations = new HashMap<UUID, PendingPreparation>();
    private final Map<UUID, PendingPreparation> pendingByBall = new HashMap<UUID, PendingPreparation>();
    private Location lobbyLocation;
    private NamespacedKey golfBallKey;
    private NamespacedKey golfFeedItemKey;

    public void onEnable() {
        this.golfBallKey = new NamespacedKey((Plugin)this, "golf_ball");
        this.golfFeedItemKey = new NamespacedKey((Plugin)this, "golf_feed_item");
        this.saveDefaultConfig();
        if (!this.loadConfig()) {
            this.getLogger().severe("LainaGolf zostaje wylaczony przez bledna konfiguracje.");
            Bukkit.getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        this.getServer().getPluginManager().registerEvents((Listener)this, (Plugin)this);
        Objects.requireNonNull(this.getCommand("minigolf"), "Brak komendy minigolf w plugin.yml").setExecutor(this::onCommand);
        Bukkit.getScheduler().runTaskTimer((Plugin)this, this::tickGames, 20L, 2L);
        this.getLogger().info("LainaGolf 3.0 - gotowy.");
    }

    public void onDisable() {
        for (PendingPreparation preparation : new ArrayList<PendingPreparation>(this.pendingPreparations.values())) {
            this.abortPreparation(preparation);
        }
        for (GolfSession session : new ArrayList<GolfSession>(this.activeSessions.values())) {
            this.finishSession(session, false, true, false);
        }
        this.pendingPreparations.clear();
        this.pendingByBall.clear();
        this.activeSessions.clear();
        this.sessionsByBall.clear();
        for (GolfMap map : this.maps.values()) {
            map.release();
        }
    }

    private boolean loadConfig() {
        ConfigurationSection mapsSection;
        this.reloadConfig();
        this.maps.clear();
        boolean valid = true;
        this.lobbyLocation = this.parseLocation(this.getConfig().getConfigurationSection("lobby"));
        if (this.lobbyLocation == null) {
            this.getLogger().severe("Brak poprawnej lokalizacji lobby albo swiat lobby nie jest zaladowany.");
            valid = false;
        }
        if ((mapsSection = this.getConfig().getConfigurationSection("maps")) == null || mapsSection.getKeys(false).isEmpty()) {
            this.getLogger().severe("Brak zdefiniowanych map w sekcji 'maps'.");
            return false;
        }
        for (String key : mapsSection.getKeys(false)) {
            ConfigurationSection cfg = mapsSection.getConfigurationSection(key);
            if (cfg == null) {
                this.getLogger().severe("Mapa '" + key + "' nie jest poprawna sekcja YAML.");
                valid = false;
                continue;
            }
            try {
                String lookupKey = key.toLowerCase(Locale.ROOT);
                if (this.maps.containsKey(lookupKey)) {
                    throw new IllegalArgumentException("Nazwa mapy duplikuje inna nazwe po pominieciu wielkosci liter.");
                }
                this.maps.put(lookupKey, new GolfMap(key, cfg));
            }
            catch (IllegalArgumentException ex) {
                this.getLogger().log(Level.SEVERE, "Nie mozna zaladowac mapy '" + key + "': " + ex.getMessage());
                valid = false;
            }
        }
        if (this.maps.isEmpty()) {
            valid = false;
        }
        ArrayList<GolfMap> loadedMaps = new ArrayList<GolfMap>(this.maps.values());
        for (int i = 0; i < loadedMaps.size(); ++i) {
            GolfMap first = (GolfMap)loadedMaps.get(i);
            if (this.lobbyLocation != null && first.isInside(this.lobbyLocation)) {
                this.getLogger().severe("Lobby znajduje sie wewnatrz mapy '" + first.name + "'. To powodowaloby petle teleportowania.");
                valid = false;
            }
            for (int j = i + 1; j < loadedMaps.size(); ++j) {
                GolfMap second = (GolfMap)loadedMaps.get(j);
                if (!first.overlaps(second)) continue;
                this.getLogger().severe("Mapy '" + first.name + "' i '" + second.name + "' nachodza na siebie. Zajete plansze konfliktowalyby ze soba.");
                valid = false;
            }
        }
        return valid;
    }

    private Location parseLocation(ConfigurationSection cfg) {
        if (cfg == null) {
            return null;
        }
        String worldName = cfg.getString("world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        if (!(cfg.contains("x") && cfg.contains("y") && cfg.contains("z"))) {
            return null;
        }
        World world = Bukkit.getWorld((String)worldName);
        if (world == null) {
            return null;
        }
        double x = cfg.getDouble("x");
        double y = cfg.getDouble("y");
        double z = cfg.getDouble("z");
        if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
            return null;
        }
        return new Location(world, x, y, z);
    }

    private void tickGames() {
        long now = System.nanoTime();
        for (GolfSession session : new ArrayList<GolfSession>(this.activeSessions.values())) {
            boolean finishReached;
            if (session.ending) continue;
            Player player = session.player;
            if (!player.isOnline()) {
                this.finishSession(session, false, false, false);
                continue;
            }
            if (now >= session.deadlineNanos) {
                player.sendMessage(String.valueOf(ChatColor.RED) + "Czas minal. Koniec gry.");
                this.finishSession(session, false, true, false);
                continue;
            }
            if (!session.map.isInside(player.getLocation())) {
                player.sendMessage(String.valueOf(ChatColor.RED) + "Wyszedles poza plansze. Koniec gry.");
                this.finishSession(session, false, true, false);
                continue;
            }
            SulfurCube ball = session.ball;
            if (!ball.isValid() || ball.isDead()) {
                player.sendMessage(String.valueOf(ChatColor.RED) + "Pilka przestala istniec. Koniec gry.");
                this.finishSession(session, false, true, false);
                continue;
            }
            Location currentBallLocation = ball.getLocation();
            if (!session.map.isInside(currentBallLocation)) {
                this.resetToStart(session);
                continue;
            }
            boolean bl = finishReached = session.map.isAtFinish(currentBallLocation) || session.map.segmentHitsFinish(session.lastBallLocation, currentBallLocation);
            if (finishReached) {
                this.finishSession(session, true, true, true);
                continue;
            }
            session.lastBallLocation = currentBallLocation.clone();
        }
        this.protectBusyMaps();
    }

    private void protectBusyMaps() {
        for (GolfMap map : this.maps.values()) {
            if (!map.isBusy || map.busyPlayerId == null) continue;
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.getUniqueId().equals(map.busyPlayerId) || !map.isInside(other.getLocation())) continue;
                if (this.lobbyLocation != null) {
                    other.teleport(this.lobbyLocation);
                }
                other.sendMessage(String.valueOf(ChatColor.RED) + "Ta plansza minigolfa jest obecnie zajeta.");
            }
        }
    }

    private void resetToStart(GolfSession session) {
        session.ball.teleport(session.map.ballSpawn);
        session.ball.setVelocity(new Vector(0, 0, 0));
        session.player.teleport(session.map.playerSpawn);
        session.lastBallLocation = session.map.ballSpawn.clone();
        session.player.sendMessage(String.valueOf(ChatColor.YELLOW) + "Pilka wypadla poza plansze. Powrot na start.");
    }

    @EventHandler(priority=EventPriority.NORMAL, ignoreCancelled=true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        UUID attackedId = event.getAttacked().getUniqueId();
        PendingPreparation preparation = this.pendingByBall.get(attackedId);
        if (preparation != null) {
            event.setCancelled(true);
            return;
        }
        GolfSession session = this.sessionsByBall.get(attackedId);
        if (session == null) {
            return;
        }
        Player attacker = event.getPlayer();
        if (!attacker.getUniqueId().equals(session.player.getUniqueId())) {
            event.setCancelled(true);
            attacker.sendMessage(String.valueOf(ChatColor.RED) + "To nie jest twoja pilka.");
            return;
        }
        if (session.ending) {
            event.setCancelled(true);
            return;
        }
        if (session.strokes >= session.map.maxStrokes) {
            event.setCancelled(true);
            session.ending = true;
            attacker.sendMessage(String.valueOf(ChatColor.RED) + "Wykorzystales limit " + session.map.maxStrokes + " uderzen.");
            Bukkit.getScheduler().runTask((Plugin)this, () -> this.finishSession(session, false, true, false));
            return;
        }
        ++session.strokes;
        attacker.sendMessage(String.valueOf(ChatColor.GREEN) + "Uderzenie " + session.strokes + "/" + session.map.maxStrokes);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBallPushed(EntityPushedByEntityAttackEvent event) {
        Player p;
        GolfSession session = this.sessionsByBall.get(event.getEntity().getUniqueId());
        if (session == null) {
            return;
        }
        Entity entity = event.getPushedBy();
        if (entity instanceof Player && !(p = (Player)entity).getUniqueId().equals(session.player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBallSheared(PlayerShearEntityEvent event) {
        if (this.isManagedBall(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBallBucketed(PlayerBucketEntityEvent event) {
        if (this.isManagedBall(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        for (GolfMap map : this.maps.values()) {
            if (!map.isBusy || map.busyPlayerId == null || playerId.equals(map.busyPlayerId) || !map.isInside(to)) continue;
            event.setCancelled(true);
            event.getPlayer().sendMessage(String.valueOf(ChatColor.RED) + "Ta plansza minigolfa jest obecnie zajeta.");
            return;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        GolfSession session;
        UUID playerId = event.getPlayer().getUniqueId();
        PendingPreparation preparation = this.pendingPreparations.get(playerId);
        if (preparation != null) {
            this.abortPreparation(preparation);
        }
        if ((session = this.activeSessions.get(playerId)) != null) {
            try {
                event.getPlayer().setGameMode(session.previousGameMode);
                if (this.lobbyLocation != null) {
                    event.getPlayer().teleport(this.lobbyLocation);
                }
            }
            catch (Exception ex) {
                this.getLogger().log(Level.WARNING, "Nie udalo sie przywrocic gracza " + event.getPlayer().getName() + " podczas wyjscia.", ex);
            }
            this.finishSession(session, false, false, false);
        }
    }

    private boolean isManagedBall(UUID entityId) {
        return this.sessionsByBall.containsKey(entityId) || this.pendingByBall.containsKey(entityId);
    }

    private void prepareAndStartLevel(Player player, GolfMap map) {
        map.cleanup();
        map.isBusy = true;
        map.busyPlayerId = player.getUniqueId();
        SulfurCube ball = null;
        Item feedItem = null;
        try {
            BukkitTask task;
            ball = (SulfurCube)map.ballSpawn.getWorld().spawnEntity(map.ballSpawn, EntityType.SULFUR_CUBE);
            ball.setAdult();
            ball.setAgeLock(true);
            ball.setPersistent(true);
            ball.setAI(true);
            ball.setWander(true);
            ball.setVelocity(new Vector(0, 0, 0));
            ball.getPersistentDataContainer().set(this.golfBallKey, PersistentDataType.BYTE, (Object)1);
            feedItem = map.ballSpawn.getWorld().dropItem(map.ballSpawn.clone().add(0.0, 0.15, 0.0), new ItemStack(map.blockMaterial, 1));
            feedItem.setVelocity(new Vector(0, 0, 0));
            feedItem.setCanPlayerPickup(false);
            feedItem.setWillAge(false);
            feedItem.setUnlimitedLifetime(true);
            feedItem.getPersistentDataContainer().set(this.golfFeedItemKey, PersistentDataType.BYTE, (Object)1);
            PendingPreparation preparation = new PendingPreparation(player, map, ball, feedItem);
            this.pendingPreparations.put(player.getUniqueId(), preparation);
            this.pendingByBall.put(ball.getUniqueId(), preparation);
            preparation.task = task = Bukkit.getScheduler().runTaskTimer((Plugin)this, () -> this.tickPreparation(preparation), 2L, 2L);
        }
        catch (Exception ex) {
            if (feedItem != null && feedItem.isValid()) {
                feedItem.remove();
            }
            if (ball != null && ball.isValid()) {
                ball.remove();
            }
            map.release();
            this.getLogger().log(Level.SEVERE, "Nie udalo sie przygotowac mapy '" + map.name + "'.", ex);
            player.sendMessage(String.valueOf(ChatColor.RED) + "Nie udalo sie przygotowac planszy minigolfa.");
        }
    }

    private void tickPreparation(PendingPreparation preparation) {
        boolean cubeAbsorbedBlock;
        if (this.pendingPreparations.get(preparation.player.getUniqueId()) != preparation) {
            this.cancelTask(preparation);
            return;
        }
        preparation.waitedTicks = (int)((long)preparation.waitedTicks + 2L);
        Player player = preparation.player;
        SulfurCube ball = preparation.ball;
        Item feedItem = preparation.feedItem;
        if (!player.isOnline()) {
            this.abortPreparation(preparation);
            return;
        }
        if (!ball.isValid() || ball.isDead()) {
            player.sendMessage(String.valueOf(ChatColor.RED) + "Nie udalo sie utworzyc pilki minigolfa.");
            this.abortPreparation(preparation);
            return;
        }
        boolean itemConsumed = !feedItem.isValid() || feedItem.isDead();
        boolean bl = cubeAbsorbedBlock = !ball.hasAI();
        if (itemConsumed && cubeAbsorbedBlock) {
            this.pendingPreparations.remove(player.getUniqueId(), preparation);
            this.pendingByBall.remove(ball.getUniqueId(), preparation);
            this.cancelTask(preparation);
            ball.teleport(preparation.map.ballSpawn);
            ball.setVelocity(new Vector(0, 0, 0));
            this.startSession(player, preparation.map, ball);
            return;
        }
        if (preparation.waitedTicks >= 100) {
            player.sendMessage(String.valueOf(ChatColor.RED) + "Sulfur Cube nie pochlonal bloku '" + preparation.map.blockMaterial.name() + "'.");
            this.abortPreparation(preparation);
        }
    }

    private void abortPreparation(PendingPreparation preparation) {
        this.pendingPreparations.remove(preparation.player.getUniqueId(), preparation);
        this.pendingByBall.remove(preparation.ball.getUniqueId(), preparation);
        this.cancelTask(preparation);
        if (preparation.feedItem.isValid()) {
            preparation.feedItem.remove();
        }
        if (preparation.ball.isValid()) {
            preparation.ball.remove();
        }
        preparation.map.release();
    }

    private void cancelTask(PendingPreparation preparation) {
        if (preparation.task != null && !preparation.task.isCancelled()) {
            preparation.task.cancel();
        }
    }

    private void startSession(Player player, GolfMap map, SulfurCube ball) {
        if (!player.isOnline()) {
            if (ball.isValid()) {
                ball.remove();
            }
            map.release();
            return;
        }
        GameMode previousGameMode = player.getGameMode();
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(map.playerSpawn);
        GolfSession session = new GolfSession(player, ball, map, previousGameMode);
        this.activeSessions.put(player.getUniqueId(), session);
        this.sessionsByBall.put(ball.getUniqueId(), session);
        map.ballEntity = ball;
        player.sendMessage(String.valueOf(ChatColor.GREEN) + "Zaczynasz " + map.name + "! Masz " + map.maxStrokes + " uderzen i " + this.formatSeconds(map.maxTime) + " sekund.");
    }

    private String formatSeconds(double seconds) {
        if (seconds == Math.rint(seconds)) {
            return Integer.toString((int)seconds);
        }
        return Double.toString(seconds);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void finishSession(GolfSession session, boolean win, boolean teleportToLobby, boolean notifyPlayer) {
        if (this.activeSessions.get(session.player.getUniqueId()) != session) {
            return;
        }
        session.ending = true;
        this.activeSessions.remove(session.player.getUniqueId(), session);
        this.sessionsByBall.remove(session.ball.getUniqueId(), session);
        Player player = session.player;
        if (win) {
            String consoleCommand = session.map.winCommand.replace("{PLAYER}", player.getName()).replace("{LEVEL}", session.map.name);
            if (consoleCommand.startsWith("/")) {
                consoleCommand = consoleCommand.substring(1);
            }
            try {
                boolean accepted = Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)consoleCommand);
                if (!accepted) {
                    this.getLogger().warning("Komenda nagrody mapy '" + session.map.name + "' zwrocila false: " + consoleCommand);
                }
            }
            catch (Exception ex) {
                this.getLogger().log(Level.SEVERE, "Blad przy wykonywaniu komendy nagrody mapy '" + session.map.name + "'.", ex);
            }
            if (notifyPlayer && player.isOnline()) {
                player.sendMessage(String.valueOf(ChatColor.GOLD) + "Plansza ukonczona!");
            }
        } else if (notifyPlayer && player.isOnline()) {
            player.sendMessage(String.valueOf(ChatColor.RED) + "Koniec gry.");
        }
        try {
            if (session.ball.isValid()) {
                session.ball.remove();
            }
            if (player.isOnline()) {
                player.setGameMode(session.previousGameMode);
                if (teleportToLobby && this.lobbyLocation != null) {
                    player.teleport(this.lobbyLocation);
                }
            }
        }
        finally {
            session.map.ballEntity = null;
            session.map.release();
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player;
        boolean allowed;
        boolean bl = allowed = sender instanceof ConsoleCommandSender || sender instanceof Player && (player = (Player)sender).isOp();
        if (!allowed) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Ta komenda jest tylko dla konsoli lub opa.");
            return true;
        }
        if (args.length != 3 || !args[0].equalsIgnoreCase("join")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uzycie: /minigolf join <mapa> <gracz>");
            return true;
        }
        GolfMap map = this.maps.get(args[1].toLowerCase(Locale.ROOT));
        if (map == null) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Mapa '" + args[1] + "' nie istnieje.");
            return true;
        }
        Player target = Bukkit.getPlayerExact((String)args[2]);
        if (target == null) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Gracz '" + args[2] + "' nie jest online.");
            return true;
        }
        if (this.activeSessions.containsKey(target.getUniqueId()) || this.pendingPreparations.containsKey(target.getUniqueId())) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Ten gracz juz gra albo dolacza do minigolfa.");
            return true;
        }
        if (map.isBusy) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Mapa jest juz zajeta.");
            return true;
        }
        this.prepareAndStartLevel(target, map);
        if (map.isBusy) {
            sender.sendMessage(String.valueOf(ChatColor.GREEN) + "Przygotowywanie mapy " + map.name + " dla gracza " + target.getName() + ".");
        }
        return true;
    }

    private static final class PendingPreparation {
        private final Player player;
        private final GolfMap map;
        private final SulfurCube ball;
        private final Item feedItem;
        private BukkitTask task;
        private int waitedTicks = 0;

        private PendingPreparation(Player player, GolfMap map, SulfurCube ball, Item feedItem) {
            this.player = player;
            this.map = map;
            this.ball = ball;
            this.feedItem = feedItem;
        }
    }

    private static final class GolfSession {
        private final Player player;
        private final SulfurCube ball;
        private final GolfMap map;
        private final GameMode previousGameMode;
        private final long deadlineNanos;
        private Location lastBallLocation;
        private int strokes = 0;
        private boolean ending = false;

        private GolfSession(Player player, SulfurCube ball, GolfMap map, GameMode previousGameMode) {
            this.player = player;
            this.ball = ball;
            this.map = map;
            this.previousGameMode = previousGameMode;
            this.lastBallLocation = ball.getLocation().clone();
            long durationNanos = (long)Math.ceil(map.maxTime * 1.0E9);
            this.deadlineNanos = System.nanoTime() + durationNanos;
        }
    }

    private final class GolfMap {
        private final String name;
        private final String winCommand;
        private final Location corner1;
        private final Location corner2;
        private final Location playerSpawn;
        private final Location ballSpawn;
        private final Location finishLoc;
        private final double maxTime;
        private final int maxStrokes;
        private final Material blockMaterial;
        private boolean isBusy;
        private UUID busyPlayerId;
        private SulfurCube ballEntity;
        private GolfMap(String name, ConfigurationSection cfg) {
            this.isBusy = false;
            if (name.isBlank()) {
                throw new IllegalArgumentException("Nazwa mapy nie moze byc pusta.");
            }
            this.name = name;
            this.corner1 = this.requireLocation(cfg, "corner1");
            this.corner2 = this.requireLocation(cfg, "corner2");
            this.playerSpawn = this.requireLocation(cfg, "playerSpawn");
            this.ballSpawn = this.requireLocation(cfg, "ballSpawn");
            this.finishLoc = this.requireLocation(cfg, "finishLoc");
            this.ensureSameWorld();
            if (!this.isInside(this.playerSpawn)) {
                throw new IllegalArgumentException("playerSpawn lezy poza granicami mapy.");
            }
            if (!this.isInside(this.ballSpawn)) {
                throw new IllegalArgumentException("ballSpawn lezy poza granicami mapy.");
            }
            if (!this.isInside(this.finishLoc)) {
                throw new IllegalArgumentException("finishLoc lezy poza granicami mapy.");
            }
            this.winCommand = Objects.requireNonNullElse(cfg.getString("winCommand"), "").trim();
            if (this.winCommand.isBlank()) {
                throw new IllegalArgumentException("winCommand nie moze byc puste.");
            }
            this.maxTime = cfg.getDouble("maxTime", -1.0);
            this.maxStrokes = cfg.getInt("maxStrokes", -1);
            if (!Double.isFinite(this.maxTime) || this.maxTime <= 0.0) {
                throw new IllegalArgumentException("maxTime musi byc liczba > 0.");
            }
            if (this.maxStrokes <= 0) {
                throw new IllegalArgumentException("maxStrokes musi byc > 0.");
            }
            String blockName = Objects.requireNonNullElse(cfg.getString("block"), "").trim();
            Material material = Material.matchMaterial((String)blockName);
            if (material == null || !material.isBlock() || !material.isItem() || material.isAir()) {
                throw new IllegalArgumentException("Nieprawidlowy blok dla Sulfur Cube: '" + blockName + "'.");
            }
            if (!Tag.ITEMS_SULFUR_CUBE_SWALLOWABLE.isTagged((Keyed)material)) {
                throw new IllegalArgumentException("Blok '" + material.name() + "' nie nalezy do vanilla tagu sulfur_cube_swallowable.");
            }
            this.blockMaterial = material;
        }

        private Location requireLocation(ConfigurationSection cfg, String key) {
            Location location = LainaGolf.this.parseLocation(cfg.getConfigurationSection(key));
            if (location == null) {
                throw new IllegalArgumentException("Lokacja '" + key + "' jest nieprawidlowa albo jej swiat nie jest zaladowany.");
            }
            return location;
        }

        private void ensureSameWorld() {
            World world = this.corner1.getWorld();
            if (!(this.corner2.getWorld().equals((Object)world) && this.playerSpawn.getWorld().equals((Object)world) && this.ballSpawn.getWorld().equals((Object)world) && this.finishLoc.getWorld().equals((Object)world))) {
                throw new IllegalArgumentException("Wszystkie lokacje danej mapy musza byc w tym samym swiecie.");
            }
        }

        private boolean isInside(Location location) {
            if (location.getWorld() == null || !location.getWorld().equals((Object)this.corner1.getWorld())) {
                return false;
            }
            return location.getX() >= this.minX() && location.getX() <= this.maxX() && location.getY() >= this.minY() && location.getY() <= this.maxY() && location.getZ() >= this.minZ() && location.getZ() <= this.maxZ();
        }

        private boolean isAtFinish(Location ballLocation) {
            return ballLocation.getWorld() != null && ballLocation.getWorld().equals((Object)this.finishLoc.getWorld()) && ballLocation.distanceSquared(this.finishLoc) < 0.5;
        }

        private boolean segmentHitsFinish(Location from, Location to) {
            if (from == null || from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals((Object)to.getWorld()) || !to.getWorld().equals((Object)this.finishLoc.getWorld())) {
                return false;
            }
            if (from.distanceSquared(to) > 16.0) {
                return false;
            }
            Vector a = from.toVector();
            Vector b = to.toVector();
            Vector p = this.finishLoc.toVector();
            Vector ab = b.clone().subtract(a);
            double lengthSquared = ab.lengthSquared();
            if (lengthSquared < 1.0E-9) {
                return false;
            }
            double t = p.clone().subtract(a).dot(ab) / lengthSquared;
            t = Math.max(0.0, Math.min(1.0, t));
            Vector closest = a.clone().add(ab.multiply(t));
            return closest.distanceSquared(p) < 0.5;
        }

        private boolean overlaps(GolfMap other) {
            if (!this.corner1.getWorld().equals((Object)other.corner1.getWorld())) {
                return false;
            }
            return Math.max(this.minX(), other.minX()) < Math.min(this.maxX(), other.maxX()) && Math.max(this.minY(), other.minY()) < Math.min(this.maxY(), other.maxY()) && Math.max(this.minZ(), other.minZ()) < Math.min(this.maxZ(), other.maxZ());
        }

        private double minX() {
            return Math.min(this.corner1.getX(), this.corner2.getX());
        }

        private double maxX() {
            return Math.max(this.corner1.getX(), this.corner2.getX());
        }

        private double minY() {
            return Math.min(this.corner1.getY(), this.corner2.getY());
        }

        private double maxY() {
            return Math.max(this.corner1.getY(), this.corner2.getY());
        }

        private double minZ() {
            return Math.min(this.corner1.getZ(), this.corner2.getZ());
        }

        private double maxZ() {
            return Math.max(this.corner1.getZ(), this.corner2.getZ());
        }

        private void cleanup() {
            Location middle = new Location(this.corner1.getWorld(), (this.minX() + this.maxX()) / 2.0, (this.minY() + this.maxY()) / 2.0, (this.minZ() + this.maxZ()) / 2.0);
            double radiusX = (this.maxX() - this.minX()) / 2.0 + 2.0;
            double radiusY = (this.maxY() - this.minY()) / 2.0 + 2.0;
            double radiusZ = (this.maxZ() - this.minZ()) / 2.0 + 2.0;
            for (Entity entity : this.corner1.getWorld().getNearbyEntities(middle, radiusX, radiusY, radiusZ)) {
                Item item;
                if (!this.isInside(entity.getLocation())) continue;
                if (entity instanceof Mob) {
                    entity.remove();
                    continue;
                }
                if (!(entity instanceof Item) || !(item = (Item)entity).getPersistentDataContainer().has(LainaGolf.this.golfFeedItemKey, PersistentDataType.BYTE)) continue;
                item.remove();
            }
            this.ballEntity = null;
        }

        private void release() {
            this.isBusy = false;
            this.busyPlayerId = null;
            this.ballEntity = null;
        }
    }
}
