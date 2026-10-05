package pl.laina.golf;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

final class CooldownConfig {

    static final long DEFAULT_SECONDS = 0L;
    static final CooldownStart DEFAULT_START = CooldownStart.EXIT;

    private CooldownConfig() {
    }

    static boolean addMissingDefaults(ConfigurationSection mapConfig) {
        boolean changed = false;

        if (!mapConfig.contains("cooldown.seconds", true)) {
            mapConfig.set("cooldown.seconds", DEFAULT_SECONDS);
            changed = true;
        }

        if (!mapConfig.contains("cooldown.start", true)) {
            mapConfig.set("cooldown.start", DEFAULT_START.name());
            changed = true;
        }

        return changed;
    }

    static Settings read(ConfigurationSection mapConfig) {
        if (!mapConfig.isInt("cooldown.seconds") && !mapConfig.isLong("cooldown.seconds")) {
            throw new IllegalArgumentException("cooldown.seconds musi byc liczba calkowita >= 0.");
        }

        long seconds = mapConfig.getLong("cooldown.seconds", DEFAULT_SECONDS);
        if (seconds < 0L) {
            throw new IllegalArgumentException("cooldown.seconds musi byc >= 0.");
        }

        String startName = Objects.requireNonNullElse(
                mapConfig.getString("cooldown.start"),
                DEFAULT_START.name()
        ).trim().toUpperCase(Locale.ROOT);

        try {
            return new Settings(seconds, CooldownStart.valueOf(startName));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cooldown.start musi miec wartosc ENTRY albo EXIT.");
        }
    }

    record Settings(long seconds, CooldownStart start) {
    }
}
