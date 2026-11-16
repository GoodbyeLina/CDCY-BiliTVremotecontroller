package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.config.ConfigStore
import tvrc.core.wizard.WizardDraftStore
import tvrc.core.wizard.WizardEvent
import tvrc.core.wizard.WizardMachine
import tvrc.core.wizard.WizardStep

class WizardTest {

    private class MemStore(var text: String? = null) : ConfigStore {
        override fun readText(path: String): String? = text
        override fun writeText(path: String, t: String) { text = t }
    }

    private fun readyToFinish(): tvrc.core.wizard.WizardState {
        var s = WizardMachine.start()
        s = WizardMachine.onEvent(s, WizardEvent.Field("tv", "192.168.10.139:5555"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        s = WizardMachine.onEvent(s, WizardEvent.Field("rooms", "5017134,5064126"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        s = WizardMachine.onEvent(s, WizardEvent.Field("switch_seconds", "60"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        for (name in listOf("adb 能连上电视", "电视装了 mpv", "手机能访问 B站")) {
            s = WizardMachine.onEvent(s, WizardEvent.TestResult(name, true, "ok"))
        }
        return s
    }

    @Test
    fun startsAtFirstStep() {
        val s = WizardMachine.start()
        assertEquals(WizardStep.TV, s.step)
        assertEquals(0, s.percent)
        assertFalse(s.finished)
    }

    @Test
    fun nextIsBlockedWhenTvMissing() {
        val s = WizardMachine.onEvent(WizardMachine.start(), WizardEvent.Next)
        assertEquals(WizardStep.TV, s.step, "没有电视地址不该进入下一步")
        assertTrue(s.errors.any { it.contains("电视 IP") }, s.errors.toString())
    }

    @Test
    fun tvStepAdvancesAndCountsProgress() {
        var s = WizardMachine.onEvent(WizardMachine.start(), WizardEvent.Field("tv", "10.0.0.9:5037"))
        assertEquals("10.0.0.9", s.draft.tvHost)
        assertEquals(5037, s.draft.tvPort)
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        assertEquals(WizardStep.SOURCE, s.step)
        assertEquals(20, s.percent)
        assertEquals(emptyList<String>(), s.errors)
    }

    @Test
    fun sourceStepNeedsRoomsForLive() {
        var s = WizardMachine.start()
        s = WizardMachine.onEvent(s, WizardEvent.Field("tv", "10.0.0.9"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        assertEquals(WizardStep.SOURCE, s.step)
        assertTrue(s.errors.any { it.contains("rooms") }, s.errors.toString())
        s = WizardMachine.onEvent(s, WizardEvent.Field("rooms", "5017134, 5064126,5017134"))
        assertEquals(listOf(5017134L, 5064126L), s.draft.rooms, "重复房间号应去重")
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        assertEquals(WizardStep.PARAMS, s.step)
    }

    @Test
    fun paramsStepRejectsOutOfRangeSwitch() {
        var s = WizardMachine.start()
        s = WizardMachine.onEvent(s, WizardEvent.Field("tv", "10.0.0.9"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        s = WizardMachine.onEvent(s, WizardEvent.Field("rooms", "5017134"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        s = WizardMachine.onEvent(s, WizardEvent.Field("switch_seconds", "5"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        assertEquals(WizardStep.PARAMS, s.step)
        assertTrue(s.errors.any { it.contains("switch_seconds") }, s.errors.toString())
    }

    @Test
    fun finishRequiresRequiredSelfTests() {
        // 必检项全过 → 可以完成；非必检的「代理端口可用」没过也不拦。
        val ok = readyToFinish()
        assertEquals(WizardStep.SELFTEST, ok.step)
        assertTrue(ok.selfTests.any { it.name == "代理端口可用" && !it.ok }, "该用例前提是端口自检未跑")
        val done = WizardMachine.onEvent(ok, WizardEvent.Finish)
        assertTrue(done.finished)
        assertEquals(WizardStep.DONE, done.step)
        assertEquals(100, done.percent)

        // 必检项 adb 失败 → 拦下并给出原因。
        var blocked = WizardMachine.onEvent(readyToFinish(), WizardEvent.TestResult("adb 能连上电视", false, "unauthorized"))
        blocked = WizardMachine.onEvent(blocked, WizardEvent.Finish)
        assertTrue(blocked.errors.any { it.contains("adb") }, blocked.errors.toString())
        assertFalse(blocked.finished)
        assertEquals(WizardStep.SELFTEST, blocked.step)
    }

    @Test
    fun backGoesOneStepAndKeepsDraft() {
        var s = readyToFinish()
        s = WizardMachine.onEvent(s, WizardEvent.Back)
        assertEquals(WizardStep.PARAMS, s.step)
        s = WizardMachine.onEvent(s, WizardEvent.Back)
        assertEquals(WizardStep.SOURCE, s.step)
        assertEquals(listOf(5017134L, 5064126L), s.draft.rooms)
    }

    @Test
    fun draftSurvivesRestart() {
        val store = MemStore()
        var s = WizardMachine.start()
        s = WizardMachine.onEvent(s, WizardEvent.Field("tv", "192.168.10.139"))
        s = WizardMachine.onEvent(s, WizardEvent.Field("rooms", "5017134"))
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        s = WizardMachine.onEvent(s, WizardEvent.Next)
        WizardDraftStore(store, "/x/.wizard-draft.json").save(s)

        val resumed = WizardDraftStore(store, "/x/.wizard-draft.json").load()
        assertNotNull(resumed)
        assertEquals(WizardStep.PARAMS, resumed!!.step)
        assertEquals("192.168.10.139", resumed.draft.tvHost)
        assertEquals(listOf(5017134L), resumed.draft.rooms)
    }
}
