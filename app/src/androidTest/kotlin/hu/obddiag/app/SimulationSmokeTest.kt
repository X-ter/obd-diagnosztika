package hu.obddiag.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.obddiag.app.transport.AdapterTarget
import hu.obddiag.core.connection.ConnectionState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Végponttól végpontig tartó füstteszt a SZIMULÁLT adapterrel: kapcsolódás, minden fő képernyő
 * megnyitása, hibakód-olvasás, élő adatok, műszerfal, haladó funkciók. Minden lépésről képernyőkép készül
 * (a CI feltölti őket), így a megjelenés is ellenőrizhető.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SimulationSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (ctx.applicationContext as ObdApp).container

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val bmp = rule.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
        val dir = File(ctx.filesDir, "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun present(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Vár, amíg a szöveg megjelenik; ha nincs a képernyőn, a görgethető listákat a szöveghez görgeti. */
    private fun waitText(text: String, timeout: Long = 15_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (present(text)) return
            val n = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
            for (i in 0 until n) {
                runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText(text, substring = true)) }
                if (present(text)) return
            }
            Thread.sleep(300)
        }
        throw AssertionError("A szöveg nem jelent meg $timeout ms alatt: \"$text\"")
    }

    private fun clickText(text: String, last: Boolean = false) {
        waitText(text)
        val nodes = rule.onAllNodesWithText(text, substring = false)
        (if (last) nodes.onLast() else nodes.onFirst()).performClick()
    }

    @Test
    fun fullSimulatedSession() {
        try {
            session()
        } catch (t: Throwable) {
            runCatching { shot("99_failure") }
            throw t
        }
    }

    private fun session() {
        shot("01_home_disconnected")
        container.connect(AdapterTarget.Simulation)
        rule.waitUntil(20_000) { container.connection.state.value is ConnectionState.Connected }
        waitText("SZIMULÁCIÓS MÓD")
        Thread.sleep(5_500) // feszültség- és MIL-frissítés
        shot("02_home_connected")

        rule.onNodeWithTag("tab_dtc").performClick()
        clickText("Olvasás")
        waitText("P0301")
        waitText("P0171")
        shot("03_dtc")
        clickText("Freeze Frame betöltése")
        waitText("Kiváltó hibakód", 20_000)
        shot("04_freeze_frame")

        rule.onNodeWithTag("tab_live").performClick()
        waitText("Motorfordulatszám")
        Thread.sleep(2_000)
        shot("05_live_cards")
        clickText("Lista")
        Thread.sleep(1_000)
        shot("06_live_list")
        clickText("Grafikon")
        Thread.sleep(4_000)
        shot("07_live_chart")

        rule.onNodeWithTag("tab_dash").performClick()
        Thread.sleep(3_000)
        shot("08_dashboard")

        rule.onNodeWithTag("tab_more").performClick()
        shot("09_more")
        clickText("Haladó diagnosztika")
        clickText("Monitorállapot lekérdezése")
        waitText("Gyújtáskimaradás")
        clickText("ECU-adatok lekérdezése")
        waitText("SMLT0BD2026000001")
        shot("10_advanced")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        clickText("Adatnaplózás")
        clickText("Rögzítés indítása")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Rögzítés indítása")).fetchSemanticsNodes().size >= 3 }
        clickText("Rögzítés indítása", last = true)
        Thread.sleep(4_000)
        shot("11_logging_running")
        clickText("Rögzítés leállítása")
        Thread.sleep(1_500)
        shot("12_logging_list")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        clickText("Szakértői terminál")
        clickText("Megértettem")
        clickText("ATI")
        waitText("ELM327 v1.5")
        clickText("03")
        waitText("43 02")
        shot("13_terminal")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        clickText("Adapterkezelés")
        Thread.sleep(800)
        shot("14_adapter")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        clickText("Névjegy és funkcióállapot")
        shot("15_about")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        rule.onNodeWithTag("tab_home").performClick()
        Thread.sleep(1_000)
        shot("16_home_after")
        assertTrue(container.connection.state.value is ConnectionState.Connected)
    }
}
