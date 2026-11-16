package tvrc.core

/**
 * 版本与契约常量。改这里等于改对外承诺，必须同时更新 HANDOFF.md 的「契约冻结」章节。
 */
object TvrcVersion {
    /** 应用版本（三代原生 App）。 */
    const val APP = "3.0.0-alpha.1"

    /** 配置 schema 版本，与 AppConfig.schemaVersion 一致；升版本必须带迁移。 */
    const val CONFIG_SCHEMA = 1

    /** core 对外契约版本：TvController / AdbClient / Scheduler 三个接口的语义版本。 */
    const val CORE_API = 1

    /** 契约冻结日期（ISO）。 */
    const val FROZEN_AT = "2026-11-13"

    /** 回归闸门：低于这个条数的测试结果不算通过（P4-3 验收口径）。 */
    const val MIN_TEST_COUNT = 170
}
