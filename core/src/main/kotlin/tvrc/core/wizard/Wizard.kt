package tvrc.core.wizard

import tvrc.core.config.AppConfig
import tvrc.core.config.ConfigStore
import tvrc.core.config.ConfigValidator
import tvrc.core.config.JsonConfigRepository

/** 五步向导：电视 → 片源 → 参数 → 自检 → 完成。 */
enum class WizardStep(val index: Int, val title: String) {
    TV(0, "连接电视"),
    SOURCE(1, "选择片源"),
    PARAMS(2, "播放参数"),
    SELFTEST(3, "自检"),
    DONE(4, "完成");

    companion object {
        val ordered: List<WizardStep> = listOf(TV, SOURCE, PARAMS, SELFTEST, DONE)
    }
}

/** 向导里正在编辑的草稿（就是 AppConfig 的可编辑子集）。 */
data class WizardDraft(
    val tvHost: String = "",
    val tvPort: Int = 5555,
    val source: String = "live",
    val rooms: List<Long> = emptyList(),
    val listMid: Long? = null,
    val listId: Long? = null,
    val pickCount: Int = 5,
    val switchSeconds: Int = 1800,
    val idleTimeoutMinutes: Int = 0
) {
    fun toConfig(port: Int = 8100): AppConfig = AppConfig(
        source = source, rooms = rooms, listMid = listMid, listId = listId,
        pickCount = pickCount, switchSeconds = switchSeconds, port = port,
        tvHost = tvHost, tvPort = tvPort, idleTimeoutMinutes = idleTimeoutMinutes,
        wizardCompleted = false
    )

    companion object {
        fun fromConfig(c: AppConfig): WizardDraft = WizardDraft(
            tvHost = c.tvHost, tvPort = c.tvPort, source = c.source, rooms = c.rooms,
            listMid = c.listMid, listId = c.listId, pickCount = c.pickCount,
            switchSeconds = c.switchSeconds, idleTimeoutMinutes = c.idleTimeoutMinutes
        )
    }
}

/** 自检项。required=true 的项不过就不许进下一步。 */
data class SelfTestItem(val name: String, val required: Boolean, val ok: Boolean, val detail: String = "")

data class WizardState(
    val step: WizardStep = WizardStep.TV,
    val draft: WizardDraft = WizardDraft(),
    val errors: List<String> = emptyList(),
    val selfTests: List<SelfTestItem> = defaultSelfTests(),
    val finished: Boolean = false
) {
    /** 0–100，按已完成步骤算（DONE 之前最多 80）。 */
    val percent: Int get() = if (finished) 100 else step.index * 20

    val canFinish: Boolean get() = selfTests.filter { it.required }.all { it.ok }

    companion object {
        fun defaultSelfTests(): List<SelfTestItem> = listOf(
            SelfTestItem("adb 能连上电视", required = true, ok = false),
            SelfTestItem("电视装了 mpv", required = true, ok = false),
            SelfTestItem("手机能访问 B站", required = true, ok = false),
            SelfTestItem("代理端口可用", required = false, ok = false)
        )
    }
}

sealed interface WizardEvent {
    data class Field(val name: String, val value: String) : WizardEvent
    data class TestResult(val name: String, val ok: Boolean, val detail: String = "") : WizardEvent
    data object Next : WizardEvent
    data object Back : WizardEvent
    data object Finish : WizardEvent
}

/**
 * 向导状态机（纯函数，不碰 UI/网络）。
 * 约定：Next 在当前步有错时**原地不动**并把 errors 交给 UI 显示，绝不跳到下一步。
 */
object WizardMachine {

    fun start(draft: WizardDraft = WizardDraft()): WizardState = WizardState(draft = draft)

    fun onEvent(state: WizardState, event: WizardEvent): WizardState = when (event) {
        is WizardEvent.Field -> applyField(state, event.name, event.value)
        is WizardEvent.TestResult -> applyTest(state, event)
        WizardEvent.Next -> next(state)
        WizardEvent.Back -> back(state)
        WizardEvent.Finish -> finish(state)
    }

    private fun applyField(state: WizardState, name: String, value: String): WizardState {
        val d = state.draft
        val nd = when (name) {
            "tv" -> {
                val hp = JsonConfigRepository.parseHostPort(value, 5555)
                d.copy(tvHost = hp.first, tvPort = hp.second)
            }
            "source" -> d.copy(source = value.trim())
            "rooms" -> d.copy(rooms = value.split(',', ' ', '，')
                .mapNotNull { it.trim().toLongOrNull() }.distinct())
            "list_mid" -> d.copy(listMid = value.trim().toLongOrNull())
            "list_id" -> d.copy(listId = value.trim().toLongOrNull())
            "pick_count" -> d.copy(pickCount = value.trim().toIntOrNull() ?: d.pickCount)
            "switch_seconds" -> d.copy(switchSeconds = value.trim().toIntOrNull() ?: d.switchSeconds)
            "idle_timeout_minutes" -> d.copy(idleTimeoutMinutes = value.trim().toIntOrNull() ?: d.idleTimeoutMinutes)
            else -> d
        }
        return state.copy(draft = nd, errors = emptyList())
    }

    private fun applyTest(state: WizardState, e: WizardEvent.TestResult): WizardState {
        val list = state.selfTests.map {
            if (it.name == e.name) it.copy(ok = e.ok, detail = e.detail) else it
        }
        return state.copy(selfTests = list, errors = emptyList())
    }

    private fun next(state: WizardState): WizardState {
        if (state.finished) return state
        val errs = validate(state.step, state)
        if (errs.isNotEmpty()) return state.copy(errors = errs)
        val nextStep = WizardStep.ordered.getOrNull(state.step.index + 1) ?: WizardStep.DONE
        return state.copy(step = nextStep, errors = emptyList())
    }

    private fun back(state: WizardState): WizardState {
        if (state.finished) return state
        val prev = WizardStep.ordered.getOrNull(state.step.index - 1) ?: WizardStep.TV
        return state.copy(step = prev, errors = emptyList())
    }

    private fun finish(state: WizardState): WizardState {
        if (state.step != WizardStep.SELFTEST && state.step != WizardStep.DONE) {
            return state.copy(errors = listOf("请先完成前面的步骤"))
        }
        if (!state.canFinish) {
            val missing = state.selfTests.filter { it.required && !it.ok }.joinToString("、") { it.name }
            return state.copy(errors = listOf("自检未通过：" + missing))
        }
        return state.copy(step = WizardStep.DONE, finished = true, errors = emptyList())
    }

    /** 每步的校验规则，与 ConfigValidator 同源（不重复实现区间）。 */
    fun validate(step: WizardStep, state: WizardState): List<String> = when (step) {
        WizardStep.TV -> {
            val e = mutableListOf<String>()
            if (state.draft.tvHost.isBlank()) e += "请填写电视 IP（电视设置 → 关于 → 状态信息里能看到）"
            if (state.draft.tvPort !in 1..65535) e += "端口不合法"
            e
        }
        WizardStep.SOURCE -> {
            val chk = ConfigValidator.check(state.draft.toConfig())
            chk.errors.filter { it.contains("source") || it.contains("rooms") || it.contains("list_") }
        }
        WizardStep.PARAMS -> {
            val chk = ConfigValidator.check(state.draft.toConfig())
            chk.errors.filter {
                it.contains("switch_seconds") || it.contains("pick_count") || it.contains("idle_timeout")
            }
        }
        WizardStep.SELFTEST -> if (state.canFinish) emptyList() else listOf("请先跑完必检项")
        WizardStep.DONE -> emptyList()
    }
}

/** 中途退出恢复：草稿单独存一个文件，不污染正式 config。 */
class WizardDraftStore(private val store: ConfigStore, private val path: String) {

    fun save(state: WizardState) {
        val d = state.draft
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"step\": ").append(state.step.index).append(",\n")
        sb.append("  \"tv\": \"").append(d.tvHost).append(":").append(d.tvPort).append("\",\n")
        sb.append("  \"source\": \"").append(d.source).append("\",\n")
        sb.append("  \"rooms\": [").append(d.rooms.joinToString(",")).append("],\n")
        sb.append("  \"list_mid\": ").append(d.listMid ?: 0).append(",\n")
        sb.append("  \"list_id\": ").append(d.listId ?: 0).append(",\n")
        sb.append("  \"pick_count\": ").append(d.pickCount).append(",\n")
        sb.append("  \"switch_seconds\": ").append(d.switchSeconds).append(",\n")
        sb.append("  \"idle_timeout_minutes\": ").append(d.idleTimeoutMinutes).append("\n")
        sb.append("}\n")
        store.writeText(path, sb.toString())
    }

    fun load(): WizardState? {
        val text = store.readText(path) ?: return null
        val cfgRepo = JsonConfigRepository(store, path)
        // 草稿结构与 config 兼容（tv/source/rooms/...），直接借它的解析，再补 step。
        val loaded = cfgRepo.load()
        val cfg = (loaded as? tvrc.core.config.LoadResult.Ok)?.config ?: return null
        val stepIndex = Regex("\"step\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val step = WizardStep.ordered.getOrNull(stepIndex) ?: WizardStep.TV
        return WizardState(step = step, draft = WizardDraft.fromConfig(cfg))
    }
}
