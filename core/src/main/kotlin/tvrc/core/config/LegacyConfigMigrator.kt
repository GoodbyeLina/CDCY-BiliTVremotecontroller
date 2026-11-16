package tvrc.core.config

/**
 * 二代（Termux 版 config.json）→ 三代单 JSON 配置的迁移器。
 *
 * 规则（对齐 K 文档 §11 迁移约定）：
 * 1. 字段名：switch_seconds / pick_count / list_mid / list_id / public_host / mpv_pkg / mpv_activity 同名直搬
 * 2. setup_seen → wizard_completed
 * 3. control_port **忽略**（三代没有 web 控制台），但作为未知键保留在 extra 里不丢
 * 4. port 统一到 8100（二代是 8099；照搬会与二代抢端口）
 * 5. 非法值**收敛**而不是拒绝：switch_seconds / pick_count / status_seconds 夹到合法区间并记录 note
 * 6. 幂等：对已迁移配置再跑一次不产生任何 note，配置逐字段相等
 */
data class MigrationResult(val config: AppConfig, val notes: List<String>)

object LegacyConfigMigrator {

    private class MemStore(var text: String) : ConfigStore {
        override fun readText(path: String): String? = text
        override fun writeText(path: String, text: String) { this.text = text }
    }

    fun migrateText(json: String): MigrationResult {
        val repo = JsonConfigRepository(MemStore(json), "mem://legacy-config.json")
        val loaded = repo.load()
        val base = when (loaded) {
            is LoadResult.Ok -> loaded.config
            is LoadResult.Missing -> AppConfig()
            is LoadResult.Invalid -> return MigrationResult(
                AppConfig(),
                listOf("原配置不是合法 JSON（" + loaded.reason + "），已全部用默认值，请重新走向导")
            )
        }
        return migrate(base)
    }

    fun migrate(c: AppConfig): MigrationResult {
        val notes = mutableListOf<String>()
        var port = c.port
        if (port == 8099) {
            notes += "port 8099 → 8100（二代端口，避让）"
            port = 8100
        } else if (port !in 1024..65535) {
            notes += "port " + port + " 不合法 → 8100"
            port = 8100
        }
        val sw = coerce(c.switchSeconds, AppConfig.MIN_SWITCH_SECONDS, AppConfig.MAX_SWITCH_SECONDS, "switch_seconds", notes)
        val pick = coerce(c.pickCount, AppConfig.MIN_PICK_COUNT, AppConfig.MAX_PICK_COUNT, "pick_count", notes)
        val st = coerce(c.statusSeconds, AppConfig.MIN_STATUS_SECONDS, AppConfig.MAX_STATUS_SECONDS, "status_seconds", notes)
        if (c.tvHost.isBlank()) notes += "tv 地址为空，需向导补（迁移无法猜测）"
        if (c.source == "live" && c.rooms.isEmpty()) notes += "rooms 为空，需向导补"
        val out = c.copy(port = port, switchSeconds = sw, pickCount = pick, statusSeconds = st)
        val chk = ConfigValidator.check(out)
        if (!chk.ok) notes += "迁移后仍有待补项：" + chk.errors.joinToString("；")
        return MigrationResult(out, notes)
    }

    private fun coerce(v: Int, min: Int, max: Int, name: String, notes: MutableList<String>): Int {
        if (v in min..max) return v
        val fixed = v.coerceIn(min, max)
        notes += name + " " + v + " 超范围 → " + fixed
        return fixed
    }
}
