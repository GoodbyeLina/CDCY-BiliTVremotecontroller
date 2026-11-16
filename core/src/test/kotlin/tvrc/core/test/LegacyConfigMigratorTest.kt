package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.config.AppConfig
import tvrc.core.config.LegacyConfigMigrator

class LegacyConfigMigratorTest {

    private val legacy = java.lang.String.join("\n",
        "{",
        "  \"source\": \"live\",",
        "  \"rooms\": [5017134, 5064126, 4374913],",
        "  \"list_mid\": 23191782,",
        "  \"list_id\": 1424266,",
        "  \"pick_count\": 5,",
        "  \"switch_seconds\": 30,",
        "  \"status_seconds\": 10,",
        "  \"port\": 8099,",
        "  \"control_port\": 8098,",
        "  \"setup_seen\": false,",
        "  \"tv\": \"192.168.10.139:5555\",",
        "  \"public_host\": \"192.168.10.241\",",
        "  \"mpv_pkg\": \"is.xyz.mpv\",",
        "  \"mpv_activity\": \"is.xyz.mpv/.MPVActivity\"",
        "}"
    )

    @Test
    fun mapsFieldsFromSecondGeneration() {
        val r = LegacyConfigMigrator.migrateText(legacy)
        assertEquals(listOf(5017134L, 5064126L, 4374913L), r.config.rooms)
        assertEquals(23191782L, r.config.listMid)
        assertEquals(1424266L, r.config.listId)
        assertEquals("192.168.10.139", r.config.tvHost)
        assertEquals(5555, r.config.tvPort)
        assertEquals("192.168.10.241", r.config.publicHost)
        assertEquals("is.xyz.mpv", r.config.mpvPkg)
        assertEquals(30, r.config.switchSeconds, "现场 30 秒必须照搬，不被默认 1800 覆盖")
    }

    @Test
    fun portIsNormalizedTo8100AndControlPortKept() {
        val r = LegacyConfigMigrator.migrateText(legacy)
        assertEquals(8100, r.config.port)
        assertTrue(r.notes.any { it.contains("8099") })
        assertEquals(true, r.config.extra.containsKey("control_port"))
    }

    @Test
    fun setupSeenMapsToWizardCompleted() {
        assertTrue(!LegacyConfigMigrator.migrateText(legacy).config.wizardCompleted)
        val seen = legacy.replace("\"setup_seen\": false", "\"setup_seen\": true")
        assertTrue(LegacyConfigMigrator.migrateText(seen).config.wizardCompleted)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val r = LegacyConfigMigrator.migrateText("{\"source\": \"video\", \"bvid\": \"BV1xx411c7mD\", \"tv\": \"10.0.0.9:5555\"}")
        assertEquals(8100, r.config.port)
        assertEquals(1800, r.config.switchSeconds)
        assertEquals(5, r.config.pickCount)
        assertEquals(10, r.config.statusSeconds)
        assertEquals("10.0.0.9", r.config.tvHost)
    }

    @Test
    fun invalidValuesAreCoercedWithNotes() {
        val bad = legacy
            .replace("\"switch_seconds\": 30", "\"switch_seconds\": 5")
            .replace("\"pick_count\": 5", "\"pick_count\": 999")
            .replace("\"status_seconds\": 10", "\"status_seconds\": 1")
        val r = LegacyConfigMigrator.migrateText(bad)
        assertEquals(AppConfig.MIN_SWITCH_SECONDS, r.config.switchSeconds)
        assertEquals(AppConfig.MAX_PICK_COUNT, r.config.pickCount)
        assertEquals(AppConfig.MIN_STATUS_SECONDS, r.config.statusSeconds)
        assertEquals(3, r.notes.count { it.contains("超范围") })
    }

    @Test
    fun migrationIsIdempotent() {
        val once = LegacyConfigMigrator.migrateText(legacy)
        val twice = LegacyConfigMigrator.migrate(once.config)
        assertEquals(once.config, twice.config)
        assertTrue(twice.notes.none { it.contains("→") }, "二次迁移不应再有变更 note：" + twice.notes)
    }
}
