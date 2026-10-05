package pl.laina.golf;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void entryStartsOnlyOnEntry() throws Exception {
        UUID player = UUID.randomUUID();
        CooldownService service = serviceAt(NOW);

        assertFalse(service.start(player, "winter_1", 120, CooldownStart.ENTRY, CooldownStart.EXIT));
        assertEquals(0, service.remainingSeconds(player, "winter_1"));

        assertTrue(service.start(player, "winter_1", 120, CooldownStart.ENTRY, CooldownStart.ENTRY));
        assertEquals(120, service.remainingSeconds(player, "winter_1"));
    }

    @Test
    void exitStartsOnlyOnExit() throws Exception {
        UUID player = UUID.randomUUID();
        CooldownService service = serviceAt(NOW);

        assertFalse(service.start(player, "winter_1", 120, CooldownStart.EXIT, CooldownStart.ENTRY));
        assertEquals(0, service.remainingSeconds(player, "winter_1"));

        assertTrue(service.start(player, "winter_1", 120, CooldownStart.EXIT, CooldownStart.EXIT));
        assertEquals(120, service.remainingSeconds(player, "winter_1"));
    }

    @Test
    void mapsHaveIndependentCooldowns() throws Exception {
        UUID player = UUID.randomUUID();
        CooldownService service = serviceAt(NOW);

        service.start(player, "winter_1", 1_800, CooldownStart.ENTRY, CooldownStart.ENTRY);

        assertEquals(1_800, service.remainingSeconds(player, "winter_1"));
        assertEquals(0, service.remainingSeconds(player, "winter_2"));
    }

    @Test
    void playersHaveIndependentCooldowns() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CooldownService service = serviceAt(NOW);

        service.start(first, "winter_1", 1_800, CooldownStart.ENTRY, CooldownStart.ENTRY);

        assertEquals(1_800, service.remainingSeconds(first, "winter_1"));
        assertEquals(0, service.remainingSeconds(second, "winter_1"));
    }

    @Test
    void cooldownSurvivesRestart() throws Exception {
        UUID player = UUID.randomUUID();
        serviceAt(NOW).start(player, "winter_1", 3_600, CooldownStart.ENTRY, CooldownStart.ENTRY);

        CooldownService restarted = serviceAt(NOW.plusSeconds(45));
        assertEquals(3_555, restarted.remainingSeconds(player, "winter_1"));
    }

    @Test
    void disconnectCannotBypassExitCooldown() throws Exception {
        UUID player = UUID.randomUUID();
        CooldownService running = serviceAt(NOW);

        assertTrue(running.start(player, "winter_1", 600, CooldownStart.EXIT, CooldownStart.EXIT));

        CooldownService afterDisconnectAndRestart = serviceAt(NOW.plusSeconds(1));
        assertEquals(599, afterDisconnectAndRestart.remainingSeconds(player, "winter_1"));
    }

    @Test
    void zeroSecondsDisablesCooldown() throws Exception {
        UUID player = UUID.randomUUID();
        CooldownService service = serviceAt(NOW);

        service.start(player, "winter_1", 600, CooldownStart.ENTRY, CooldownStart.ENTRY);
        assertFalse(service.start(player, "winter_1", 0, CooldownStart.ENTRY, CooldownStart.ENTRY));
        assertEquals(0, service.remainingSeconds(player, "winter_1"));
        assertEquals(0, serviceAt(NOW.plusSeconds(1)).remainingSeconds(player, "winter_1"));
    }

    @Test
    void remainingTimeIsReadable() {
        assertEquals("12m 34s", CooldownService.formatRemaining(754));
        assertEquals("1h 2m 3s", CooldownService.formatRemaining(3_723));
    }

    private CooldownService serviceAt(Instant instant) throws Exception {
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
        return new CooldownService(tempDir.resolve("cooldowns.properties"), clock);
    }
}
