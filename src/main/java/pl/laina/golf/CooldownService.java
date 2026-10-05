package pl.laina.golf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class CooldownService {

    private final Path storageFile;
    private final Clock clock;
    private final Map<CooldownKey, Long> expiresAt = new HashMap<>();

    CooldownService(Path storageFile, Clock clock) throws IOException {
        this.storageFile = storageFile;
        this.clock = clock;
        load();
    }

    long remainingSeconds(UUID playerId, String mapName) {
        CooldownKey key = new CooldownKey(playerId, normalizeMapName(mapName));
        Long expiry = expiresAt.get(key);
        if (expiry == null) {
            return 0L;
        }

        long remaining = expiry - clock.instant().getEpochSecond();
        if (remaining <= 0L) {
            expiresAt.remove(key);
            return 0L;
        }

        return remaining;
    }

    boolean start(
            UUID playerId,
            String mapName,
            long seconds,
            CooldownStart configuredStart,
            CooldownStart event
    ) throws IOException {
        if (configuredStart != event) {
            return false;
        }

        CooldownKey key = new CooldownKey(playerId, normalizeMapName(mapName));
        if (seconds == 0L) {
            boolean changed = expiresAt.remove(key) != null;
            if (changed) {
                save();
            }
            return false;
        }

        long now = clock.instant().getEpochSecond();
        long expiry = seconds > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + seconds;
        expiresAt.put(key, expiry);
        save();
        return true;
    }

    void clear(UUID playerId, String mapName) throws IOException {
        CooldownKey key = new CooldownKey(playerId, normalizeMapName(mapName));
        if (expiresAt.remove(key) != null) {
            save();
        }
    }

    private void load() throws IOException {
        expiresAt.clear();
        if (!Files.exists(storageFile)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(storageFile)) {
            properties.load(input);
        }

        long now = clock.instant().getEpochSecond();
        for (String encodedKey : properties.stringPropertyNames()) {
            try {
                int separator = encodedKey.lastIndexOf('.');
                if (separator <= 0 || separator == encodedKey.length() - 1) {
                    continue;
                }

                String mapName = new String(
                        Base64.getUrlDecoder().decode(encodedKey.substring(0, separator)),
                        StandardCharsets.UTF_8
                );
                UUID playerId = UUID.fromString(encodedKey.substring(separator + 1));
                long expiry = Long.parseLong(properties.getProperty(encodedKey));

                if (expiry > now) {
                    expiresAt.put(new CooldownKey(playerId, normalizeMapName(mapName)), expiry);
                }
            } catch (IllegalArgumentException ignored) {
                // Pomijamy pojedynczy uszkodzony wpis zamiast blokowac caly plugin.
            }
        }
    }

    private void save() throws IOException {
        Path parent = storageFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Properties properties = new Properties();
        for (Map.Entry<CooldownKey, Long> entry : expiresAt.entrySet()) {
            String encodedMap = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(entry.getKey().mapName().getBytes(StandardCharsets.UTF_8));
            properties.setProperty(
                    encodedMap + "." + entry.getKey().playerId(),
                    Long.toString(entry.getValue())
            );
        }

        Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "LainaGolf cooldowns");
        }

        try {
            Files.move(
                    temporary,
                    storageFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String formatRemaining(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder result = new StringBuilder();
        if (days > 0L) {
            result.append(days).append("d ");
        }
        if (hours > 0L || days > 0L) {
            result.append(hours).append("h ");
        }
        if (minutes > 0L || hours > 0L || days > 0L) {
            result.append(minutes).append("m ");
        }
        result.append(seconds).append('s');
        return result.toString();
    }

    private static String normalizeMapName(String mapName) {
        return mapName.toLowerCase(Locale.ROOT);
    }

    private record CooldownKey(UUID playerId, String mapName) {
    }
}
