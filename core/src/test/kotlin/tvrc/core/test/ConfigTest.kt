package tvrc.core.test

import tvrc.core.config.*
import tvrc.core.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTest {

    private class MemStore(var text: String? = null) : ConfigStore {
        val writes = mutableListOf<String>()
        override fun readText(path: String): String? = text
        override fun writeText(path: String, t: String) {
            writes += t
            text = t
        }
    }

    private val sample = java.lang.String.join("\n",
        "{",
        "  \"source\": \"live\",",
        "  \"rooms\": [5017134, 5064126],",
        "  \"pick_count\": 5,",
        "  \"switch_seconds\": 30,",
        "  \"status_seconds\": 10,",
        "  \"port\": 8099,",
        "  \"control_port\": 8098,",
        "  \"setup_seen\": true,",
        "  \"tv\": \"192.168.10.139:5555\",",
        "  \"public_host\": \"192.168.10.241\",",
        "  \"mpv_pkg\": \"is.xyz.mpv\",",
        "  \"mpv_activity\": \"is.xyz.mpv/.MPVActivity\"",
        "}"
    )

    @Test
    fun loadsSecondGenerationConfig() {
        val repo = JsonConfigRepository(MemStore(sample), "/x/config.json")
        val r = repo.load()
        assertTrue(r is LoadResult.Ok, "应解析成功")
        val c = (r as LoadResult.Ok).config
        assertEquals(listOf(5017134L, 5064126L), c.rooms)
        assertEquals(30, c.switchSeconds)
        assertEquals(8099, c.port)
        assertEquals("192.168.10.139", c.tvHost)
        assertEquals(5555, c.tvPort)
        assertTrue(c.wizardCompleted, "setup_seen=true 应映射为 wizardCompleted")
        assertTrue(c.extra.containsKey("control_port"))
    }

    @Test
    fun secondGenerationConfigIsRejectedOnPort8099() {
        val r = JsonConfigRepository(MemStore(sample), "/x/config.json").load() as LoadResult.Ok
        assertFalse(r.check.ok)
        assertTrue(r.check.errors.any { it.contains("8099") }, "应提示 8099：@${r.check.errors}")
    }

    @Test
    fun missingFileReported() {
        assertTrue(JsonConfigRepository(MemStore(null), "/x/none.json").load() is LoadResult.Missing)
    }

    @Test
    fun brokenJsonReported() {
        assertTrue(JsonConfigRepository(MemStore("{ not json"), "/x/bad.json").load() is LoadResult.Invalid)
    }

    @Test
    fun saveRoundTripsAndKeepsUnknownKeys() {
        val store = MemStore(sample)
        val repo = JsonConfigRepository(store, "/x/config.json")
        val ok = AppConfig(
            source = "live", rooms = listOf(5017134L), switchSeconds = 900, port = 8100,
            tvHost = "192.168.10.139", tvPort = 5555, wizardCompleted = true,
            extra = mapOf("control_port" to Json.parse("8098"))
        )
        assertEquals(emptyList<String>(), repo.save(ok))
        val again = (repo.load() as LoadResult.Ok).config
        assertEquals(900, again.switchSeconds)
        assertEquals(8100, again.port)
        assertTrue(again.extra.containsKey("control_port"), "未知键必须保留")
        assertTrue((repo.load() as LoadResult.Ok).check.ok)
    }

    @Test
    fun invalidConfigIsNotWritten() {
        val store = MemStore(sample)
        val repo = JsonConfigRepository(store, "/x/config.json")
        val errs = repo.save(AppConfig(switchSeconds = 5, rooms = listOf(1L), tvHost = "h"))
        assertTrue(errs.any { it.contains("switch_seconds") })
        assertTrue(store.writes.isEmpty(), "非法配置不应落盘")
    }

    @Test
    fun deviceSettingsCanBeSavedBeforeImportingContent() {
        val store = MemStore()
        val repo = JsonConfigRepository(store, "/x/config.json", ConfigValidator::checkDeviceSettings)
        val config = AppConfig(tvHost = "192.168.10.139")
        assertEquals(emptyList<String>(), repo.save(config))
        val reloaded = JsonConfigRepository(store, "/x/config.json", ConfigValidator::checkDeviceSettings)
        val (loaded, errors) = reloaded.loadOrDefaults()
        assertEquals(config, loaded)
        assertTrue(errors.isEmpty())
        assertTrue(loaded.rooms.isEmpty(), "设备保存不应伪造或填充播放内容")
    }

    @Test
    fun deviceSettingsDoNotRequireLegacySeriesSelection() {
        val config = AppConfig(source = "series", tvHost = "192.168.10.139")
        assertTrue(ConfigValidator.checkDeviceSettings(config).ok)
        assertFalse(ConfigValidator.check(config).ok)
    }

    @Test
    fun playbackValidationStillRequiresLiveContent() {
        val store = MemStore()
        val config = AppConfig(tvHost = "192.168.10.139")
        assertFalse(ConfigValidator.check(config).ok)
        assertTrue(JsonConfigRepository(store, "/x/config.json").save(config).isNotEmpty())
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun invalidDeviceSettingsDoNotOverwriteSavedConfig() {
        val store = MemStore()
        val repo = JsonConfigRepository(store, "/x/config.json", ConfigValidator::checkDeviceSettings)
        val valid = AppConfig(tvHost = "192.168.10.139")
        assertTrue(repo.save(valid).isEmpty())
        val savedText = store.text
        listOf(
            valid.copy(tvHost = ""),
            valid.copy(tvPort = 0),
            valid.copy(tvPort = 65536),
            valid.copy(port = 8099),
            valid.copy(port = 80),
            valid.copy(switchSeconds = 0),
            valid.copy(statusSeconds = 0)
        ).forEach { invalid ->
            assertTrue(repo.save(invalid).isNotEmpty(), "应拒绝：$invalid")
            assertEquals(savedText, store.text)
        }
        assertEquals(1, store.writes.size)
    }

    @Test
    fun customAdbPortSurvivesSaveAndReload() {
        val store = MemStore()
        val repo = JsonConfigRepository(store, "/x/config.json", ConfigValidator::checkDeviceSettings)
        val config = AppConfig(tvHost = "192.168.10.139", tvPort = 5556)
        assertTrue(repo.save(config).isEmpty())
        assertEquals(config, (repo.load() as LoadResult.Ok).config)
    }

    @Test
    fun brokenFileFallsBackToDefaults() {
        val (cfg, notes) = JsonConfigRepository(MemStore("{ oops"), "/x/bad.json").loadOrDefaults()
        assertEquals(AppConfig(), cfg)
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("配置无法解析"), notes[0])
    }

    @Test
    fun hostPortParsing() {
        assertEquals("1.2.3.4" to 5555, JsonConfigRepository.parseHostPort("1.2.3.4", 5555))
        assertEquals("1.2.3.4" to 5037, JsonConfigRepository.parseHostPort("1.2.3.4:5037", 5555))
        assertEquals("" to 5555, JsonConfigRepository.parseHostPort("  ", 5555))
        assertEquals("tv.local" to 5555, JsonConfigRepository.parseHostPort("tv.local:abc", 5555))
    }
}
