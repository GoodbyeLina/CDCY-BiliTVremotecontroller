package tvrc.core.tools

import tvrc.core.config.FsConfigStore
import tvrc.core.config.JsonConfigRepository
import tvrc.core.config.LoadResult

/**
 * 配置自检命令行：给 tools/verify-on-tv.sh 用，也能直接验证配置文件。
 * 退出码：0 合法、1 不合法、2 文件缺失。
 */
fun main(args: Array<String>) {
    val migrateIdx = args.indexOf("--migrate")
    if (migrateIdx >= 0) {
        val legacy = args.getOrNull(migrateIdx + 1)
        if (legacy == null) {
            println("MIGRATE_INVALID 缺 --migrate 的路径")
            kotlin.system.exitProcess(1)
        }
        val text = java.io.File(legacy).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        if (text == null) {
            println("MIGRATE_MISSING " + legacy)
            kotlin.system.exitProcess(2)
        }
        val r = tvrc.core.config.LegacyConfigMigrator.migrateText(text)
        val chk = tvrc.core.config.ConfigValidator.check(r.config)
        println("MIGRATED " + legacy + " -> port=" + r.config.port + " rooms=" + r.config.rooms.size +
            " switch=" + r.config.switchSeconds + "s tv=" + r.config.tvAddress)
        for (n in r.notes) println("  note: " + n)
        if (chk.ok) {
            println("MIGRATE_OK")
            kotlin.system.exitProcess(0)
        }
        println("MIGRATE_NEEDS_INPUT")
        for (e in chk.errors) println("  - " + e)
        kotlin.system.exitProcess(1)
    }
    val path = args.firstOrNull { !it.startsWith("-") } ?: defaultConfigPath()
    val repo = JsonConfigRepository(FsConfigStore(), path)
    when (val r = repo.load()) {
        is LoadResult.Missing -> {
            println("CONFIG_MISSING " + path)
            kotlin.system.exitProcess(2)
        }
        is LoadResult.Invalid -> {
            println("CONFIG_INVALID " + path + " -> " + r.reason)
            kotlin.system.exitProcess(1)
        }
        is LoadResult.Ok -> {
            if (r.check.ok) {
                println("CONFIG_OK " + path)
                println("  source=" + r.config.source + " rooms=" + r.config.rooms.size +
                    " tv=" + r.config.tvAddress + " port=" + r.config.port +
                    " switch=" + r.config.switchSeconds + "s pick=" + r.config.pickCount)
                kotlin.system.exitProcess(0)
            } else {
                println("CONFIG_INVALID " + path)
                for (e in r.check.errors) println("  - " + e)
                kotlin.system.exitProcess(1)
            }
        }
    }
}

private fun defaultConfigPath(): String {
    val home = System.getProperty("user.home") ?: "."
    return home + "/.tvrc/config.json"
}
