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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class LainaGolf extends JavaPlugin implements Listener {
    private static final long GAME_TICK_PERIOD = 2L;
    private static final double FINISH_DISTANCE_SQUARED = 0.5;
    private static final double MAX_SEGMENT_CHECK_DISTANCE_SQUARED = 16.0;

    private final Map<String, GolfMap> maps = new HashMap<>();
    private final Map<UUID, GolfSession> activeSessions = new HashMap<>();
    private final Map<UUID, GolfSession> sessionsByBall = new HashMap<>();

    private Location lobbyLocation;
    private NamespacedKey golfBallKey;
    private NamespacedKey golfFeedItemKey;

    @Override
    public void onEnable() {
        golfBallKey = new NamespacedKey(this, "golf_ball");
        golfFeedItemKey = new NamespacedKey(this, "golf_feed_item");
        saveDefaultConfig();

        if (!loadConfig()) {
            getLogger().severe("LainaGolf zostaje wylaczony przez bledna konfiguracje.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("minigolf"), "Brak komendy minigolf w plugin.yml").setExecutor(this::onCommand);
        Bukkit.getScheduler().runTaskTimer(this, this::tickGames, 20L, GAME_TICK_PERIOD);
        getLogger().info("LainaGolf 3.0 - gotowy.");
    }

    @Override
    public void onDisable() {
        for (GolfSession session : new ArrayList<>(activeSessions.values())) {
            finishSession(session, false, true, false);
        }

        activeSessions.clear();
        sessionsByBall.clear();

        for (GolfMap map : maps.values()) {
            map.release();
        }
    }

    private boolean loadConfig() {
        reloadConfig();
        maps.clear();
        boolean valid = true;

        lobbyLocation = parseLocation(getConfig().getConfigurationSection("lobby"));
        if (lobbyLocation == null) {
            getLogger().severe("Brak poprawnej lokalizacji lobby albo swiat lobby nie jest zaladowany.");
            valid = false;
        }

        ConfigurationSection mapsSection = getConfig().getConfigurationSection("maps");
        if (mapsSection == null || mapsSection.getKeys(false).isEmpty()) {
            getLogger().severe("Brak zdefiniowanych map w sekcji 'maps'.");
            return false;
        }

        for (String key : mapsSection.getKeys(false)) {
            ConfigurationSection cfg = mapsSection.getConfigurationSection(key);
            if (cfg == null) {
                getLogger().severe("Mapa '" + key + "' nie jest poprawna sekcja YAML.");
                valid = false;
                continue;
            }

            try {
                String lookupKey = key.toLowerCase(Locale.ROOT);
                if (maps.containsKey(lookupKey)) {
                    throw new IllegalArgumentException("Nazwa mapy duplikuje inna nazwe po pominieciu wielkosci liter.");
                }
                maps.put(lookupKey, new GolfMap(key, cfg));
            } catch (IllegalArgumentException ex) {
                getLogger().log(Level.SEVERE, "Nie mozna zaladowac mapy '" + key + "': " + ex.getMessage());
                valid = false;
            }
        }

        if (maps.isEmpty()) {
            valid = false;
        }

        ArrayList<GolfMap> loadedMaps = new ArrayList<>(maps.values());
        for (int i = 0; i < loadedMaps.size(); i++) {
            GolfMap first = loadedMaps.get(i);

            if (lobbyLocation != null && first.isInside(lobbyLocation)) {
                getLogger().severe("Lobby znajduje sie wewnatrz mapy '" + first.name + "'. To powodowaloby petle teleportowania.");
                valid = false;
            }

            for (int j = i + 1; j < loadedMaps.size(); j++) {
                GolfMap second = loadedMaps.get(j);
                if (!first.overlaps(second)) {
                    continue;
                }
                getLogger().severe("Mapy '" + first.name + "' i '" + second.name + "' nachodza na siebie. Zajete plansze konfliktowalyby ze soba.");
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

        if (!cfg.contains("x") || !cfg.contains("y") || !cfg.contains("z")) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        double x = cfg.getDouble("x");
        double y = cfg.getDouble("y");
        double z = cfg.getDouble("z");
        double yaw = cfg.getDouble("yaw", 0.0);
        double pitch = cfg.getDouble("pitch", 0.0);

        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch)
                || pitch < -90.0 || pitch > 90.0) {
            return null;
        }

        return new Location(world, x, y, z, (float) yaw, (float) pitch);
    }

    private void tickGames() {
        long now = System.nanoTime();

        for (GolfSession session : new ArrayList<>(activeSessions.values())) {
            if (session.ending) {
                continue;
            }

            Player player = session.player;
            if (!player.isOnline()) {
                finishSession(session, false, false, false);
                continue;
            }

            updateBossBar(session, now);

            if (now >= session.deadlineNanos) {
                player.sendMessage(ChatColor.RED + "Czas minal. Koniec gry.");
                finishSession(session, false, true, false);
                continue;
            }

            if (!session.map.isInside(player.getLocation())) {
                player.sendMessage(ChatColor.RED + "Wyszedles poza plansze. Koniec gry.");
                finishSession(session, false, true, false);
                continue;
            }

            SulfurCube ball = session.ball;
            if (!ball.isValid() || ball.isDead()) {
                player.sendMessage(ChatColor.RED + "Pilka przestala istniec. Koniec gry.");
                finishSession(session, false, true, false);
                continue;
            }

            Location currentBallLocation = ball.getLocation();
            if (!session.map.isInside(currentBallLocation)) {
                resetToStart(session);
                continue;
            }

            boolean finishReached = session.map.isAtFinish(currentBallLocation)
                    || session.map.segmentHitsFinish(session.lastBallLocation, currentBallLocation);

            if (finishReached) {
                finishSession(session, true, true, true);
                continue;
            }

            session.lastBallLocation = currentBallLocation.clone();
        }

        protectBusyMaps();
    }

    private void updateBossBar(GolfSession session, long now) {
        long remainingNanos = Math.max(0L, session.deadlineNanos - now);
        double progress = session.durationNanos <= 0L
                ? 0.0
                : (double) remainingNanos / (double) session.durationNanos;
        progress = Math.max(0.0, Math.min(1.0, progress));

        long remainingSeconds = (long) Math.ceil(remainingNanos / 1_000_000_000.0);
        session.bossBar.setProgress(progress);
        session.bossBar.setTitle(
                "MINIGOLF | Czas: " + formatClock(remainingSeconds)
                        + " | Uderzenia: " + session.strokes + "/" + session.map.maxStrokes
        );

        if (progress <= 0.15) {
            session.bossBar.setColor(BarColor.RED);
        } else if (progress <= 0.35) {
            session.bossBar.setColor(BarColor.YELLOW);
        } else {
            session.bossBar.setColor(BarColor.GREEN);
        }
    }

    private String formatClock(long totalSeconds) {
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private void protectBusyMaps() {
        for (GolfMap map : maps.values()) {
            if (!map.isBusy || map.busyPlayerId == null) {
                continue;
            }

            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.getUniqueId().equals(map.busyPlayerId) || !map.isInside(other.getLocation())) {
                    continue;
                }

                if (lobbyLocation != null) {
                    other.teleport(lobbyLocation);
                }

                other.sendMessage(ChatColor.RED + "Ta plansza minigolfa jest obecnie zajeta.");
            }
        }
    }

    private void resetToStart(GolfSession session) {
        session.ball.teleport(session.map.ballSpawn);
        session.ball.setVelocity(new Vector(0, 0, 0));
        session.player.teleport(session.map.playerSpawn);
        session.lastBallLocation = session.map.ballSpawn.clone();
        session.player.sendMessage(ChatColor.YELLOW + "Pilka wypadla poza plansze. Powrot na start.");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        GolfSession session = sessionsByBall.get(event.getAttacked().getUniqueId());
        if (session == null) {
            return;
        }

        Player attacker = event.getPlayer();
        if (!attacker.getUniqueId().equals(session.player.getUniqueId())) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "To nie jest twoja pilka.");
            return;
        }

        if (session.ending) {
            event.setCancelled(true);
            return;
        }

        if (session.strokes >= session.map.maxStrokes) {
            event.setCancelled(true);
            session.ending = true;
            attacker.sendMessage(ChatColor.RED + "Wykorzystales limit " + session.map.maxStrokes + " uderzen.");
            Bukkit.getScheduler().runTask(this, () -> finishSession(session, false, true, false));
            return;
        }

        session.strokes++;
        updateBossBar(session, System.nanoTime());
        attacker.sendMessage(ChatColor.GREEN + "Uderzenie " + session.strokes + "/" + session.map.maxStrokes);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBallInteract(PlayerInteractEntityEvent event) {
        if (isManagedBall(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBallInteractAt(PlayerInteractAtEntityEvent event) {
        if (isManagedBall(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBallPushed(EntityPushedByEntityAttackEvent event) {
        GolfSession session = sessionsByBall.get(event.getEntity().getUniqueId());
        if (session == null) {
            return;
        }

        Entity entity = event.getPushedBy();
        if (entity instanceof Player player && !player.getUniqueId().equals(session.player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBallSheared(PlayerShearEntityEvent event) {
        if (isManagedBall(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBallBucketed(PlayerBucketEntityEvent event) {
        if (isManagedBall(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        for (GolfMap map : maps.values()) {
            if (!map.isBusy || map.busyPlayerId == null || playerId.equals(map.busyPlayerId) || !map.isInside(to)) {
                continue;
            }

            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Ta plansza minigolfa jest obecnie zajeta.");
            return;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        GolfSession session = activeSessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }

        try {
            event.getPlayer().setGameMode(session.previousGameMode);
            if (lobbyLocation != null) {
                event.getPlayer().teleport(lobbyLocation);
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Nie udalo sie przywrocic gracza " + event.getPlayer().getName() + " podczas wyjscia.", ex);
        }

        finishSession(session, false, false, false);
    }

    private boolean isManagedBall(UUID entityId) {
        return sessionsByBall.containsKey(entityId);
    }

    private void prepareAndStartLevel(Player player, GolfMap map) {
        map.cleanup();
        map.isBusy = true;
        map.busyPlayerId = player.getUniqueId();
        SulfurCube ball = null;

        try {
            ball = (SulfurCube) map.ballSpawn.getWorld().spawnEntity(map.ballSpawn, EntityType.SULFUR_CUBE);
            ball.setAdult();
            ball.setAgeLock(true);
            ball.setPersistent(true);
            ball.setCollidable(false);
            ball.getEquipment().setItem(EquipmentSlot.BODY, new ItemStack(map.blockMaterial, 1), true);
            ball.getEquipment().setDropChance(EquipmentSlot.BODY, 0.0F);
            ball.setAI(false);
            ball.setWander(false);
            ball.setVelocity(new Vector(0, 0, 0));
            ball.getPersistentDataContainer().set(golfBallKey, PersistentDataType.BYTE, (byte) 1);
            startSession(player, map, ball);
        } catch (Exception ex) {
            if (ball != null && ball.isValid()) {
                ball.remove();
            }

            map.release();
            getLogger().log(Level.SEVERE, "Nie udalo sie przygotowac mapy '" + map.name + "'.", ex);
            player.sendMessage(ChatColor.RED + "Nie udalo sie przygotowac planszy minigolfa.");
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
        activeSessions.put(player.getUniqueId(), session);
        sessionsByBall.put(ball.getUniqueId(), session);
        map.ballEntity = ball;
        session.bossBar.addPlayer(player);
        updateBossBar(session, System.nanoTime());

        player.sendMessage(ChatColor.GREEN + "Zaczynasz " + map.name + "! Masz " + map.maxStrokes + " uderzen i " + formatSeconds(map.maxTime) + " sekund.");
    }

    private String formatSeconds(double seconds) {
        if (seconds == Math.rint(seconds)) {
            return Integer.toString((int) seconds);
        }
        return Double.toString(seconds);
    }

    private void finishSession(GolfSession session, boolean win, boolean teleportToLobby, boolean notifyPlayer) {
        if (activeSessions.get(session.player.getUniqueId()) != session) {
            return;
        }

        session.ending = true;
        activeSessions.remove(session.player.getUniqueId(), session);
        sessionsByBall.remove(session.ball.getUniqueId(), session);
        session.bossBar.removeAll();
        Player player = session.player;

        if (win) {
            String consoleCommand = session.map.winCommand
                    .replace("{PLAYER}", player.getName())
                    .replace("{LEVEL}", session.map.name);

            if (consoleCommand.startsWith("/")) {
                consoleCommand = consoleCommand.substring(1);
            }

            try {
                boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCommand);
                if (!accepted) {
                    getLogger().warning("Komenda nagrody mapy '" + session.map.name + "' zwrocila false: " + consoleCommand);
                }
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "Blad przy wykonywaniu komendy nagrody mapy '" + session.map.name + "'.", ex);
            }

            if (notifyPlayer && player.isOnline()) {
                player.sendMessage(ChatColor.GOLD + "Plansza ukonczona!");
            }
        } else if (notifyPlayer && player.isOnline()) {
            player.sendMessage(ChatColor.RED + "Koniec gry.");
        }

        try {
            if (session.ball.isValid()) {
                session.ball.remove();
            }

            if (player.isOnline()) {
                player.setGameMode(session.previousGameMode);
                if (teleportToLobby && lobbyLocation != null) {
                    player.teleport(lobbyLocation);
                }
            }
        } finally {
            session.map.ballEntity = null;
            session.map.release();
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean allowed = sender instanceof ConsoleCommandSender || sender instanceof Player player && player.isOp();

        if (!allowed) {
            sender.sendMessage(ChatColor.RED + "Ta komenda jest tylko dla konsoli lub opa.");
            return true;
        }

        if (args.length != 3 || !args[0].equalsIgnoreCase("join")) {
            sender.sendMessage(ChatColor.RED + "Uzycie: /minigolf join <mapa> <gracz>");
            return true;
        }

        GolfMap map = maps.get(args[1].toLowerCase(Locale.ROOT));
        if (map == null) {
            sender.sendMessage(ChatColor.RED + "Mapa '" + args[1] + "' nie istnieje.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Gracz '" + args[2] + "' nie jest online.");
            return true;
        }

        if (activeSessions.containsKey(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Ten gracz juz gra w minigolfa.");
            return true;
        }

        if (map.isBusy) {
            String busyMessage = ChatColor.RED + "Ta plansza minigolfa jest obecnie zajeta.";
            sender.sendMessage(busyMessage);
            if (!sender.equals(target)) {
                target.sendMessage(busyMessage);
            }
            return true;
        }

        prepareAndStartLevel(target, map);

        if (activeSessions.containsKey(target.getUniqueId())) {
            sender.sendMessage(ChatColor.GREEN + "Uruchomiono mape " + map.name + " dla gracza " + target.getName() + ".");
        }

        return true;
    }

    private static final class GolfSession {
        private final Player player;
        private final SulfurCube ball;
        private final GolfMap map;
        private final GameMode previousGameMode;
        private final long durationNanos;
        private final long deadlineNanos;
        private final BossBar bossBar;
        private Location lastBallLocation;
        private int strokes = 0;
        private boolean ending = false;

        private GolfSession(Player player, SulfurCube ball, GolfMap map, GameMode previousGameMode) {
            this.player = player;
            this.ball = ball;
            this.map = map;
            this.previousGameMode = previousGameMode;
            this.lastBallLocation = ball.getLocation().clone();
            this.durationNanos = (long) Math.ceil(map.maxTime * 1.0E9);
            this.deadlineNanos = System.nanoTime() + durationNanos;
            this.bossBar = Bukkit.createBossBar("MINIGOLF", BarColor.GREEN, BarStyle.SOLID);
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
            isBusy = false;

            if (name.isBlank()) {
                throw new IllegalArgumentException("Nazwa mapy nie moze byc pusta.");
            }

            this.name = name;
            corner1 = requireLocation(cfg, "corner1");
            corner2 = requireLocation(cfg, "corner2");
            playerSpawn = requireLocation(cfg, "playerSpawn");
            ballSpawn = requireLocation(cfg, "ballSpawn");
            finishLoc = requireLocation(cfg, "finishLoc");
            ensureSameWorld();

            if (!isInside(playerSpawn)) {
                throw new IllegalArgumentException("playerSpawn lezy poza granicami mapy.");
            }

            if (!isInside(ballSpawn)) {
                throw new IllegalArgumentException("ballSpawn lezy poza granicami mapy.");
            }

            if (!isInside(finishLoc)) {
                throw new IllegalArgumentException("finishLoc lezy poza granicami mapy.");
            }

            winCommand = Objects.requireNonNullElse(cfg.getString("winCommand"), "").trim();
            if (winCommand.isBlank()) {
                throw new IllegalArgumentException("winCommand nie moze byc puste.");
            }

            maxTime = cfg.getDouble("maxTime", -1.0);
            maxStrokes = cfg.getInt("maxStrokes", -1);

            if (!Double.isFinite(maxTime) || maxTime <= 0.0) {
                throw new IllegalArgumentException("maxTime musi byc liczba > 0.");
            }

            if (maxStrokes <= 0) {
                throw new IllegalArgumentException("maxStrokes musi byc > 0.");
            }

            String blockName = Objects.requireNonNullElse(cfg.getString("block"), "").trim();
            Material material = Material.matchMaterial(blockName);

            if (material == null || !material.isBlock() || !material.isItem() || material.isAir()) {
                throw new IllegalArgumentException("Nieprawidlowy blok dla Sulfur Cube: '" + blockName + "'.");
            }

            if (!Tag.ITEMS_SULFUR_CUBE_SWALLOWABLE.isTagged(material)) {
                throw new IllegalArgumentException("Blok '" + material.name() + "' nie nalezy do vanilla tagu sulfur_cube_swallowable.");
            }

            blockMaterial = material;
        }

        private Location requireLocation(ConfigurationSection cfg, String key) {
            Location location = LainaGolf.this.parseLocation(cfg.getConfigurationSection(key));
            if (location == null) {
                throw new IllegalArgumentException("Lokacja '" + key + "' jest nieprawidlowa albo jej swiat nie jest zaladowany.");
            }
            return location;
        }

        private void ensureSameWorld() {
            World world = corner1.getWorld();
            if (!corner2.getWorld().equals(world)
                    || !playerSpawn.getWorld().equals(world)
                    || !ballSpawn.getWorld().equals(world)
                    || !finishLoc.getWorld().equals(world)) {
                throw new IllegalArgumentException("Wszystkie lokacje danej mapy musza byc w tym samym swiecie.");
            }
        }

        private boolean isInside(Location location) {
            if (location.getWorld() == null || !location.getWorld().equals(corner1.getWorld())) {
                return false;
            }

            return location.getX() >= minX() && location.getX() <= maxX()
                    && location.getY() >= minY() && location.getY() <= maxY()
                    && location.getZ() >= minZ() && location.getZ() <= maxZ();
        }

        private boolean isAtFinish(Location ballLocation) {
            return ballLocation.getWorld() != null
                    && ballLocation.getWorld().equals(finishLoc.getWorld())
                    && ballLocation.distanceSquared(finishLoc) < FINISH_DISTANCE_SQUARED;
        }

        private boolean segmentHitsFinish(Location from, Location to) {
            if (from == null
                    || from.getWorld() == null
                    || to.getWorld() == null
                    || !from.getWorld().equals(to.getWorld())
                    || !to.getWorld().equals(finishLoc.getWorld())) {
                return false;
            }

            if (from.distanceSquared(to) > MAX_SEGMENT_CHECK_DISTANCE_SQUARED) {
                return false;
            }

            Vector a = from.toVector();
            Vector b = to.toVector();
            Vector p = finishLoc.toVector();
            Vector ab = b.clone().subtract(a);
            double lengthSquared = ab.lengthSquared();

            if (lengthSquared < 1.0E-9) {
                return false;
            }

            double t = p.clone().subtract(a).dot(ab) / lengthSquared;
            t = Math.max(0.0, Math.min(1.0, t));
            Vector closest = a.clone().add(ab.multiply(t));
            return closest.distanceSquared(p) < FINISH_DISTANCE_SQUARED;
        }

        private boolean overlaps(GolfMap other) {
            if (!corner1.getWorld().equals(other.corner1.getWorld())) {
                return false;
            }

            return Math.max(minX(), other.minX()) < Math.min(maxX(), other.maxX())
                    && Math.max(minY(), other.minY()) < Math.min(maxY(), other.maxY())
                    && Math.max(minZ(), other.minZ()) < Math.min(maxZ(), other.maxZ());
        }

        private double minX() {
            return Math.min(corner1.getX(), corner2.getX());
        }

        private double maxX() {
            return Math.max(corner1.getX(), corner2.getX());
        }

        private double minY() {
            return Math.min(corner1.getY(), corner2.getY());
        }

        private double maxY() {
            return Math.max(corner1.getY(), corner2.getY());
        }

        private double minZ() {
            return Math.min(corner1.getZ(), corner2.getZ());
        }

        private double maxZ() {
            return Math.max(corner1.getZ(), corner2.getZ());
        }

        private void cleanup() {
            Location middle = new Location(
                    corner1.getWorld(),
                    (minX() + maxX()) / 2.0,
                    (minY() + maxY()) / 2.0,
                    (minZ() + maxZ()) / 2.0
            );

            double radiusX = (maxX() - minX()) / 2.0 + 2.0;
            double radiusY = (maxY() - minY()) / 2.0 + 2.0;
            double radiusZ = (maxZ() - minZ()) / 2.0 + 2.0;

            for (Entity entity : corner1.getWorld().getNearbyEntities(middle, radiusX, radiusY, radiusZ)) {
                if (!isInside(entity.getLocation())) {
                    continue;
                }

                if (entity instanceof Mob) {
                    entity.remove();
                    continue;
                }

                if (entity instanceof Item item
                        && item.getPersistentDataContainer().has(golfFeedItemKey, PersistentDataType.BYTE)) {
                    item.remove();
                }
            }

            ballEntity = null;
        }

        private void release() {
            isBusy = false;
            busyPlayerId = null;
            ballEntity = null;
        }
    }
}
