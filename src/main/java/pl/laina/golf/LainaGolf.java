package pl.laina.golf;

import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.FluidCollisionMode;
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
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
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
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class LainaGolf extends JavaPlugin implements Listener {
    private static final long GAME_TICK_PERIOD = 2L;
    private static final double BALL_STOP_MOVEMENT_SQUARED = 0.0004;
    private static final int BALL_STOP_CONFIRM_TICKS = 6;
    private static final double PLAYER_FREEZE_EPSILON_SQUARED = 1.0E-6;
    private static final double GROUND_SNAP_MAX_DISTANCE = 6.0;

    private final Map<String, GolfMap> maps = new HashMap<>();
    private final Map<UUID, GolfSession> activeSessions = new HashMap<>();
    private final Map<UUID, GolfSession> sessionsByBall = new HashMap<>();

    private Location lobbyLocation;
    private NamespacedKey golfBallKey;
    private NamespacedKey golfFeedItemKey;
    private File scoresFile;
    private YamlConfiguration scoresConfig;
    private Scoreboard rankingScoreboard;

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

        loadScores();
        setupRankingObjectives();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("minigolf"), "Brak komendy minigolf w plugin.yml").setExecutor(this::onCommand);
        Bukkit.getScheduler().runTaskTimer(this, this::tickGames, 20L, GAME_TICK_PERIOD);
        Bukkit.getScheduler().runTaskTimer(this, this::tickShotControl, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::protectBusyMaps, 20L, 20L);
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

        saveScores();
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

    private void loadScores() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Nie udalo sie utworzyc folderu danych pluginu.");
        }

        scoresFile = new File(getDataFolder(), "scores.yml");
        scoresConfig = YamlConfiguration.loadConfiguration(scoresFile);
    }

    private void saveScores() {
        if (scoresFile == null || scoresConfig == null) {
            return;
        }

        try {
            scoresConfig.save(scoresFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Nie udalo sie zapisac scores.yml.", ex);
        }
    }

    private void setupRankingObjectives() {
        rankingScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (GolfMap map : maps.values()) {
            unregisterLegacyObjective("lg_s_" + map.scoreId);
            unregisterLegacyObjective("lg_t_" + map.scoreId);

            map.strokesObjective = getOrCreateObjective(
                    "GolfHits_" + map.name,
                    map.name + " - Uderzenia"
            );
            map.timeObjective = getOrCreateObjective(
                    "GolfTime_" + map.name,
                    map.name + " - Czas"
            );

            clearObjective(map.strokesObjective);
            clearObjective(map.timeObjective);
            populateRankingObjectives(map);
        }
    }

    private void unregisterLegacyObjective(String name) {
        Objective objective = rankingScoreboard.getObjective(name);
        if (objective != null) {
            objective.unregister();
        }
    }

    private Objective getOrCreateObjective(String name, String displayName) {
        Objective objective = rankingScoreboard.getObjective(name);
        if (objective != null) {
            objective.displayName(Component.text(displayName));
            return objective;
        }

        return rankingScoreboard.registerNewObjective(name, Criteria.DUMMY, Component.text(displayName));
    }

    private void clearObjective(Objective objective) {
        for (String entry : new HashSet<>(rankingScoreboard.getEntries())) {
            Score score = objective.getScore(entry);
            if (score.isScoreSet()) {
                score.resetScore();
            }
        }
    }

    private void populateRankingObjectives(GolfMap map) {
        ConfigurationSection players = scoresConfig.getConfigurationSection("maps." + map.scoreId + ".players");
        if (players == null) {
            return;
        }

        for (String playerId : players.getKeys(false)) {
            String base = "maps." + map.scoreId + ".players." + playerId;
            String name = scoresConfig.getString(base + ".name");
            if (name == null || name.isBlank()) {
                continue;
            }

            int bestStrokes = scoresConfig.getInt(base + ".bestStrokes", -1);
            long bestTimeMillis = scoresConfig.getLong(base + ".bestTimeMillis", -1L);

            if (bestStrokes > 0) {
                updateRankingScore(map.strokesObjective, playerId, name, bestStrokes, Integer.toString(bestStrokes));
            }

            if (bestTimeMillis >= 0L) {
                updateRankingScore(
                        map.timeObjective,
                        playerId,
                        name,
                        clampRankingMetric(bestTimeMillis),
                        formatElapsedMillis(bestTimeMillis)
                );
            }
        }
    }

    private int clampRankingMetric(long value) {
        return (int) Math.max(0L, Math.min((long) Integer.MAX_VALUE - 1L, value));
    }

    private void updateRankingScore(Objective objective, String entryId, String playerName, int metric, String displayValue) {
        int rankingValue = Integer.MAX_VALUE - Math.max(0, metric);
        Score score = objective.getScore(entryId);
        score.setScore(rankingValue);
        score.customName(Component.text(playerName));
        score.numberFormat(NumberFormat.fixed(Component.text(displayValue)));
    }

    private void recordResult(GolfSession session, long elapsedMillis) {
        GolfMap map = session.map;
        Player player = session.player;
        String playerId = player.getUniqueId().toString();
        String base = "maps." + map.scoreId + ".players." + playerId;
        String previousName = scoresConfig.getString(base + ".name");
        int previousStrokes = scoresConfig.getInt(base + ".bestStrokes", Integer.MAX_VALUE);
        long previousTime = scoresConfig.getLong(base + ".bestTimeMillis", Long.MAX_VALUE);
        boolean nameChanged = previousName == null || !previousName.equals(player.getName());
        boolean strokesImproved = session.strokes < previousStrokes;
        boolean timeImproved = elapsedMillis < previousTime;

        scoresConfig.set("maps." + map.scoreId + ".name", map.name);
        scoresConfig.set(base + ".name", player.getName());

        if (strokesImproved) {
            scoresConfig.set(base + ".bestStrokes", session.strokes);
            previousStrokes = session.strokes;
        }

        if (timeImproved) {
            scoresConfig.set(base + ".bestTimeMillis", elapsedMillis);
            previousTime = elapsedMillis;
        }

        if (previousStrokes != Integer.MAX_VALUE) {
            updateRankingScore(
                    map.strokesObjective,
                    playerId,
                    player.getName(),
                    previousStrokes,
                    Integer.toString(previousStrokes)
            );
        }

        if (previousTime != Long.MAX_VALUE) {
            updateRankingScore(
                    map.timeObjective,
                    playerId,
                    player.getName(),
                    clampRankingMetric(previousTime),
                    formatElapsedMillis(previousTime)
            );
        }

        if (strokesImproved || timeImproved || nameChanged) {
            saveScores();
        }

        player.sendMessage(
                ChatColor.YELLOW + "Wynik: " + ChatColor.WHITE + session.strokes + " uderzen"
                        + ChatColor.GRAY + " | " + ChatColor.WHITE + formatElapsedMillis(elapsedMillis)
        );

        if (strokesImproved) {
            player.sendMessage(ChatColor.GREEN + "Nowy rekord uderzen na tej planszy!");
        }

        if (timeImproved) {
            player.sendMessage(ChatColor.GREEN + "Nowy rekord czasu na tej planszy!");
        }
    }

    private String formatElapsedMillis(long millis) {
        long safeMillis = Math.max(0L, millis);
        long minutes = safeMillis / 60_000L;
        long seconds = (safeMillis % 60_000L) / 1_000L;
        long milliseconds = safeMillis % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, milliseconds);
    }

    private static String stableMapId(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("lainagolf:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        return uuid.toString().replace("-", "").substring(0, 12);
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
                resetToCheckpoint(session);
                continue;
            }

            Location currentBallCenter = getBallCenter(ball);

            while (session.nextCheckpointIndex < session.map.checkpoints.size()) {
                GolfCheckpoint checkpoint = session.map.checkpoints.get(session.nextCheckpointIndex);
                if (!checkpoint.region.contains(currentBallCenter)
                        && !checkpoint.region.intersectsMovement(session.lastBallLocation, currentBallCenter)) {
                    break;
                }

                session.nextCheckpointIndex++;
                session.ballRespawnLocation = checkpoint.ballRespawn.clone();
                player.sendMessage(
                        ChatColor.AQUA + "Checkpoint " + session.nextCheckpointIndex + "/" + session.map.checkpoints.size() + " zaliczony."
                );
                updateBossBar(session, now);
            }

            boolean checkpointRequirementMet = !session.map.checkpointsWinCondition
                    || session.nextCheckpointIndex >= session.map.checkpoints.size();
            boolean finishReached = checkpointRequirementMet
                    && (session.map.finishRegion.contains(currentBallCenter)
                    || session.map.finishRegion.intersectsMovement(session.lastBallLocation, currentBallCenter));

            if (finishReached) {
                finishSession(session, true, true, true);
                continue;
            }

            session.lastBallLocation = currentBallCenter.clone();
        }

    }

    private static Location getBallCenter(SulfurCube ball) {
        Vector center = ball.getBoundingBox().getCenter();
        return new Location(ball.getWorld(), center.getX(), center.getY(), center.getZ());
    }

    private void tickShotControl() {
        for (GolfSession session : activeSessions.values()) {
            if (!session.shotInProgress || session.ending) {
                continue;
            }

            Player player = session.player;
            SulfurCube ball = session.ball;
            if (!player.isOnline() || !ball.isValid() || ball.isDead()) {
                continue;
            }

            freezePlayer(session);

            Location currentBallLocation = ball.getLocation();
            if (session.lastFlightLocation != null
                    && session.lastFlightLocation.getWorld() != null
                    && currentBallLocation.getWorld() != null
                    && session.lastFlightLocation.getWorld().equals(currentBallLocation.getWorld())
                    && currentBallLocation.distanceSquared(session.lastFlightLocation) <= BALL_STOP_MOVEMENT_SQUARED) {
                session.stillTicks++;
            } else {
                session.stillTicks = 0;
            }

            session.lastFlightLocation = currentBallLocation.clone();

            if (session.stillTicks >= BALL_STOP_CONFIRM_TICKS) {
                stopShot(session);
            }
        }
    }

    private void freezePlayer(GolfSession session) {
        Location lock = session.frozenPlayerLocation;
        if (lock == null) {
            return;
        }

        Player player = session.player;
        Location current = player.getLocation();

        if (current.getWorld() != null
                && lock.getWorld() != null
                && current.getWorld().equals(lock.getWorld())
                && current.distanceSquared(lock) <= PLAYER_FREEZE_EPSILON_SQUARED) {
            if (player.getVelocity().lengthSquared() > 0.0) {
                player.setVelocity(new Vector(0, 0, 0));
            }
            return;
        }

        Location target = lock.clone();
        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());
        player.setVelocity(new Vector(0, 0, 0));
        player.teleport(target);
    }

    private boolean beginShot(GolfSession session) {
        if (!session.shotInProgress) {
            Player player = session.player;
            Location groundedLocation = findGroundedShotLocation(player);
            if (groundedLocation == null) {
                player.sendMessage(ChatColor.RED + "Nie znaleziono podloza pod graczem. Uderzenie anulowane.");
                return false;
            }

            player.setVelocity(new Vector(0, 0, 0));
            player.setFallDistance(0.0F);
            if (!player.teleport(groundedLocation)) {
                player.sendMessage(ChatColor.RED + "Nie udalo sie ustawic gracza na ziemi. Uderzenie anulowane.");
                return false;
            }
            player.setVelocity(new Vector(0, 0, 0));
            player.setFallDistance(0.0F);

            session.frozenPlayerLocation = player.getLocation().clone();
            session.ball.setVelocity(new Vector(0, 0, 0));
            session.ball.setAI(true);
            session.ball.setWander(false);
            session.shotInProgress = true;
        }

        session.stillTicks = 0;
        session.lastFlightLocation = session.ball.getLocation().clone();
        return true;
    }

    private Location findGroundedShotLocation(Player player) {
        Location current = player.getLocation();
        if (player.isOnGround()) {
            return current.clone();
        }

        World world = current.getWorld();
        if (world == null) {
            return null;
        }

        Location rayStart = current.clone().add(0.0, 0.2, 0.0);
        RayTraceResult hit = world.rayTraceBlocks(
                rayStart,
                new Vector(0.0, -1.0, 0.0),
                GROUND_SNAP_MAX_DISTANCE,
                FluidCollisionMode.NEVER,
                true
        );

        if (hit == null) {
            return null;
        }

        Location grounded = current.clone();
        grounded.setY(hit.getHitPosition().getY());
        return grounded;
    }

    private boolean startCountedShot(GolfSession session, Player player) {
        if (session.ending) {
            return false;
        }

        if (session.strokes >= session.map.maxStrokes) {
            return registerStroke(session, player);
        }

        if (!beginShot(session)) {
            return false;
        }

        if (!registerStroke(session, player)) {
            stopShot(session);
            return false;
        }

        return true;
    }

    private void stopShot(GolfSession session) {
        session.ball.setVelocity(new Vector(0, 0, 0));
        session.ball.setAI(false);
        session.ball.setWander(false);
        session.shotInProgress = false;
        session.stillTicks = 0;
        session.lastFlightLocation = session.ball.getLocation().clone();
        session.frozenPlayerLocation = null;
    }

    private boolean registerStroke(GolfSession session, Player player) {
        if (session.ending) {
            return false;
        }

        if (session.strokes >= session.map.maxStrokes) {
            session.ending = true;
            player.sendMessage(ChatColor.RED + "Wykorzystales limit " + session.map.maxStrokes + " uderzen.");
            Bukkit.getScheduler().runTask(this, () -> finishSession(session, false, true, false));
            return false;
        }

        session.strokes++;
        updateBossBar(session, System.nanoTime());
        player.sendMessage(ChatColor.GREEN + "Uderzenie " + session.strokes + "/" + session.map.maxStrokes);
        return true;
    }

    private Player resolveProjectileOwner(Projectile projectile) {
        if (projectile.getShooter() instanceof Player player) {
            return player;
        }

        UUID ownerId = projectile.getOwnerUniqueId();
        return ownerId == null ? null : Bukkit.getPlayer(ownerId);
    }

    private void updateBossBar(GolfSession session, long now) {
        long remainingNanos = Math.max(0L, session.deadlineNanos - now);
        double progress = session.durationNanos <= 0L
                ? 0.0
                : (double) remainingNanos / (double) session.durationNanos;
        progress = Math.max(0.0, Math.min(1.0, progress));

        long remainingSeconds = (long) Math.ceil(remainingNanos / 1_000_000_000.0);
        session.bossBar.setProgress(progress);
        String checkpointText = session.map.checkpoints.isEmpty()
                ? ""
                : " | CP: " + session.nextCheckpointIndex + "/" + session.map.checkpoints.size();
        session.bossBar.setTitle(
                "MINIGOLF" + checkpointText
                        + " | Czas: " + formatClock(remainingSeconds)
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

    private void resetToCheckpoint(GolfSession session) {
        Location ballRespawn = session.ballRespawnLocation.clone();
        session.ball.teleport(ballRespawn);
        session.ball.setVelocity(new Vector(0, 0, 0));
        session.ball.setAI(false);
        session.ball.setWander(false);
        session.lastBallLocation = getBallCenter(session.ball);
        session.lastFlightLocation = ballRespawn.clone();
        session.frozenPlayerLocation = null;
        session.shotInProgress = false;
        session.stillTicks = 0;
        String message = session.nextCheckpointIndex == 0
                ? "Pilka wypadla poza plansze. Powrot na start."
                : "Pilka wypadla poza plansze. Powrot do checkpointu " + session.nextCheckpointIndex + ".";
        session.player.sendMessage(ChatColor.YELLOW + message);
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

        if (session.ending || session.shotInProgress) {
            event.setCancelled(true);
            return;
        }

        if (!startCountedShot(session, attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity hitEntity = event.getHitEntity();
        if (hitEntity == null) {
            return;
        }

        GolfSession session = sessionsByBall.get(hitEntity.getUniqueId());
        if (session == null || session.ending) {
            return;
        }

        Projectile projectile = event.getEntity();
        Player owner = resolveProjectileOwner(projectile);
        if (owner == null || !owner.getUniqueId().equals(session.player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (!session.countedProjectiles.add(projectile.getUniqueId())) {
            return;
        }

        if (!startCountedShot(session, owner)) {
            event.setCancelled(true);
        }
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
        if (session == null || session.ending) {
            return;
        }

        Entity source = event.getPushedBy();
        Player owner;

        if (source instanceof Player player) {
            owner = player;
        } else if (source instanceof Projectile projectile) {
            owner = resolveProjectileOwner(projectile);
        } else {
            event.setCancelled(true);
            return;
        }

        if (owner == null || !owner.getUniqueId().equals(session.player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (source instanceof Projectile projectile
                && session.countedProjectiles.add(projectile.getUniqueId())) {
            if (!startCountedShot(session, owner)) {
                event.setCancelled(true);
            }
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
        GolfSession activeSession = activeSessions.get(playerId);
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                && activeSession != null
                && activeSession.shotInProgress
                && activeSession.frozenPlayerLocation != null
                && to.getWorld() != null
                && activeSession.frozenPlayerLocation.getWorld() != null
                && to.getWorld().equals(activeSession.frozenPlayerLocation.getWorld())
                && to.distanceSquared(activeSession.frozenPlayerLocation) <= PLAYER_FREEZE_EPSILON_SQUARED) {
            return;
        }

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
        session.shotInProgress = false;
        session.frozenPlayerLocation = null;
        activeSessions.remove(session.player.getUniqueId(), session);
        sessionsByBall.remove(session.ball.getUniqueId(), session);
        session.bossBar.removeAll();
        Player player = session.player;

        if (win) {
            long elapsedMillis = Math.max(0L, (System.nanoTime() - session.startNanos) / 1_000_000L);
            recordResult(session, elapsedMillis);

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
                session.ball.setVelocity(new Vector(0, 0, 0));
                session.ball.setAI(false);
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
        private final long startNanos;
        private final long deadlineNanos;
        private final BossBar bossBar;
        private final Set<UUID> countedProjectiles = new HashSet<>();
        private Location lastBallLocation;
        private Location lastFlightLocation;
        private Location frozenPlayerLocation;
        private Location ballRespawnLocation;
        private int strokes = 0;
        private int stillTicks = 0;
        private int nextCheckpointIndex = 0;
        private boolean ending = false;
        private boolean shotInProgress = false;

        private GolfSession(Player player, SulfurCube ball, GolfMap map, GameMode previousGameMode) {
            this.player = player;
            this.ball = ball;
            this.map = map;
            this.previousGameMode = previousGameMode;
            this.lastBallLocation = getBallCenter(ball);
            this.lastFlightLocation = ball.getLocation().clone();
            this.ballRespawnLocation = map.ballSpawn.clone();
            this.durationNanos = (long) Math.ceil(map.maxTime * 1.0E9);
            this.startNanos = System.nanoTime();
            this.deadlineNanos = startNanos + durationNanos;
            this.bossBar = Bukkit.createBossBar("MINIGOLF", BarColor.GREEN, BarStyle.SOLID);
        }
    }

    private static final class GolfRegion {
        private static final double BORDER_EPSILON = 1.0E-7;

        private final World world;
        private final Location corner1;
        private final Location corner2;
        private final BoundingBox box;

        private GolfRegion(Location corner1, Location corner2) {
            this(corner1, corner2, true);
        }

        private GolfRegion(Location corner1, Location corner2, boolean wholeBlocks) {
            if (!corner1.getWorld().equals(corner2.getWorld())) {
                throw new IllegalArgumentException("Rogi regionu musza byc w tym samym swiecie.");
            }

            this.world = corner1.getWorld();
            this.corner1 = corner1.clone();
            this.corner2 = corner2.clone();

            if (wholeBlocks) {
                double minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
                double minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
                double minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
                double maxX = Math.max(corner1.getBlockX(), corner2.getBlockX()) + 1.0;
                double maxY = Math.max(corner1.getBlockY(), corner2.getBlockY()) + 1.0;
                double maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ()) + 1.0;
                this.box = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ).expand(BORDER_EPSILON);
            } else {
                this.box = BoundingBox.of(corner1.toVector(), corner2.toVector()).expand(BORDER_EPSILON);
            }
        }

        private static GolfRegion around(Location center, double radius) {
            Location first = center.clone().add(-radius, -radius, -radius);
            Location second = center.clone().add(radius, radius, radius);
            return new GolfRegion(first, second, false);
        }

        private boolean contains(Location location) {
            return location.getWorld() != null
                    && location.getWorld().equals(world)
                    && box.contains(location.getX(), location.getY(), location.getZ());
        }

        private boolean intersectsMovement(Location from, Location to) {
            if (from == null
                    || from.getWorld() == null
                    || to.getWorld() == null
                    || !from.getWorld().equals(world)
                    || !to.getWorld().equals(world)) {
                return false;
            }

            if (contains(from) || contains(to)) {
                return true;
            }

            Vector direction = to.toVector().subtract(from.toVector());
            double distance = direction.length();
            if (distance <= 1.0E-9) {
                return false;
            }

            direction.multiply(1.0 / distance);
            return box.rayTrace(from.toVector(), direction, distance) != null;
        }
    }

    private static final class GolfCheckpoint {
        private final GolfRegion region;
        private final Location ballRespawn;

        private GolfCheckpoint(GolfRegion region, Location ballRespawn) {
            this.region = region;
            this.ballRespawn = ballRespawn;
        }
    }

    private final class GolfMap {
        private final String name;
        private final String scoreId;
        private final String winCommand;
        private final Location corner1;
        private final Location corner2;
        private final Location playerSpawn;
        private final Location ballSpawn;
        private final GolfRegion finishRegion;
        private final List<GolfCheckpoint> checkpoints;
        private final boolean checkpointsWinCondition;
        private final double maxTime;
        private final int maxStrokes;
        private final Material blockMaterial;
        private boolean isBusy;
        private UUID busyPlayerId;
        private SulfurCube ballEntity;
        private Objective strokesObjective;
        private Objective timeObjective;

        private GolfMap(String name, ConfigurationSection cfg) {
            isBusy = false;

            if (name.isBlank()) {
                throw new IllegalArgumentException("Nazwa mapy nie moze byc pusta.");
            }

            this.name = name;
            this.scoreId = stableMapId(name);
            corner1 = requireLocation(cfg, "corner1");
            corner2 = requireLocation(cfg, "corner2");
            playerSpawn = requireLocation(cfg, "playerSpawn");
            ballSpawn = requireLocation(cfg, "ballSpawn");
            finishRegion = loadFinishRegion(cfg);
            checkpoints = loadCheckpoints(cfg);
            checkpointsWinCondition = cfg.getBoolean("checkpointsWinCondition", true);
            ensureSameWorld();

            if (!isInside(playerSpawn)) {
                throw new IllegalArgumentException("playerSpawn lezy poza granicami mapy.");
            }

            if (!isInside(ballSpawn)) {
                throw new IllegalArgumentException("ballSpawn lezy poza granicami mapy.");
            }

            if (!isInside(finishRegion.corner1) || !isInside(finishRegion.corner2)) {
                throw new IllegalArgumentException("Region mety lezy poza granicami mapy.");
            }

            for (int i = 0; i < checkpoints.size(); i++) {
                GolfCheckpoint checkpoint = checkpoints.get(i);
                if (!isInside(checkpoint.region.corner1) || !isInside(checkpoint.region.corner2)) {
                    throw new IllegalArgumentException("Region checkpointu " + (i + 1) + " lezy poza granicami mapy.");
                }
                if (!isInside(checkpoint.ballRespawn)) {
                    throw new IllegalArgumentException("ballRespawn checkpointu " + (i + 1) + " lezy poza granicami mapy.");
                }
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

        private GolfRegion loadFinishRegion(ConfigurationSection cfg) {
            ConfigurationSection finishCfg = cfg.getConfigurationSection("finish");
            if (finishCfg != null) {
                return new GolfRegion(
                        requireLocation(finishCfg, "corner1"),
                        requireLocation(finishCfg, "corner2")
                );
            }

            Location legacyFinish = LainaGolf.this.parseLocation(cfg.getConfigurationSection("finishLoc"));
            if (legacyFinish == null) {
                throw new IllegalArgumentException("Brak regionu mety 'finish.corner1' i 'finish.corner2'.");
            }

            getLogger().warning("Mapa '" + name + "' uzywa starego finishLoc. Ustaw region finish z corner1 i corner2.");
            return GolfRegion.around(legacyFinish, Math.sqrt(0.5));
        }

        private List<GolfCheckpoint> loadCheckpoints(ConfigurationSection cfg) {
            ConfigurationSection section = cfg.getConfigurationSection("checkpoints");
            if (section == null || section.getKeys(false).isEmpty()) {
                return List.of();
            }

            ArrayList<Integer> ids = new ArrayList<>();
            for (String key : section.getKeys(false)) {
                try {
                    int id = Integer.parseInt(key);
                    if (id <= 0) {
                        throw new NumberFormatException();
                    }
                    ids.add(id);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Checkpoint '" + key + "' musi miec numer 1, 2, 3...");
                }
            }

            ids.sort(Integer::compareTo);
            for (int i = 0; i < ids.size(); i++) {
                if (ids.get(i) != i + 1) {
                    throw new IllegalArgumentException("Checkpointy musza miec kolejne numery od 1 bez przerw.");
                }
            }

            ArrayList<GolfCheckpoint> loaded = new ArrayList<>();
            for (int id : ids) {
                ConfigurationSection checkpointCfg = section.getConfigurationSection(Integer.toString(id));
                if (checkpointCfg == null) {
                    throw new IllegalArgumentException("Checkpoint " + id + " nie jest poprawna sekcja YAML.");
                }

                GolfRegion region = new GolfRegion(
                        requireLocation(checkpointCfg, "corner1"),
                        requireLocation(checkpointCfg, "corner2")
                );
                Location checkpointBallSpawn = requireLocation(checkpointCfg, "ballRespawn");
                loaded.add(new GolfCheckpoint(region, checkpointBallSpawn));
            }

            return List.copyOf(loaded);
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
                    || !finishRegion.world.equals(world)) {
                throw new IllegalArgumentException("Wszystkie lokacje danej mapy musza byc w tym samym swiecie.");
            }

            for (GolfCheckpoint checkpoint : checkpoints) {
                if (!checkpoint.region.world.equals(world)
                        || !checkpoint.ballRespawn.getWorld().equals(world)) {
                    throw new IllegalArgumentException("Wszystkie checkpointy musza byc w tym samym swiecie co mapa.");
                }
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
