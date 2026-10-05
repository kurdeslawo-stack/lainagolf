package pl.laina.golf;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownConfigTest {

    @Test
    void oldMapWithoutCooldownLoadsWithDisabledExitDefaults() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                maps:
                  old_map:
                    maxTime: 180
                """);
        YamlConfiguration bundledDefaults = new YamlConfiguration();
        bundledDefaults.loadFromString("""
                maps:
                  old_map:
                    cooldown:
                      seconds: 3600
                      start: ENTRY
                """);
        config.setDefaults(bundledDefaults);
        ConfigurationSection map = config.getConfigurationSection("maps.old_map");

        assertTrue(CooldownConfig.addMissingDefaults(map));
        CooldownConfig.Settings settings = CooldownConfig.read(map);

        assertEquals(0L, settings.seconds());
        assertEquals(CooldownStart.EXIT, settings.start());
        assertEquals(0L, map.getLong("cooldown.seconds"));
        assertEquals("EXIT", map.getString("cooldown.start"));
    }

    @Test
    void existingCooldownConfigurationIsNotOverwritten() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                maps:
                  configured_map:
                    cooldown:
                      seconds: 7200
                      start: ENTRY
                """);
        ConfigurationSection map = config.getConfigurationSection("maps.configured_map");

        assertFalse(CooldownConfig.addMissingDefaults(map));
        CooldownConfig.Settings settings = CooldownConfig.read(map);

        assertEquals(7200L, settings.seconds());
        assertEquals(CooldownStart.ENTRY, settings.start());
        assertEquals(7200L, map.getLong("cooldown.seconds"));
        assertEquals("ENTRY", map.getString("cooldown.start"));
    }

    @Test
    void onlyMissingCooldownFieldsAreAdded() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                maps:
                  seconds_only:
                    cooldown:
                      seconds: 45
                  start_only:
                    cooldown:
                      start: ENTRY
                """);
        ConfigurationSection secondsOnly = config.getConfigurationSection("maps.seconds_only");
        ConfigurationSection startOnly = config.getConfigurationSection("maps.start_only");

        assertTrue(CooldownConfig.addMissingDefaults(secondsOnly));
        assertEquals(45L, secondsOnly.getLong("cooldown.seconds"));
        assertEquals("EXIT", secondsOnly.getString("cooldown.start"));

        assertTrue(CooldownConfig.addMissingDefaults(startOnly));
        assertEquals(0L, startOnly.getLong("cooldown.seconds"));
        assertEquals("ENTRY", startOnly.getString("cooldown.start"));
    }
}
