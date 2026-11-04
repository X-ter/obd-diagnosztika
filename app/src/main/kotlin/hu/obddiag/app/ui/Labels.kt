package hu.obddiag.app.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import hu.obddiag.core.pid.PidCategory
import hu.obddiag.core.pid.StandardPids
import java.util.concurrent.ConcurrentHashMap

/**
 * Fordítási kulcsok feloldása a strings.xml-ből (pid_*, state_*, mon_*, cat_*, mid_*).
 * A kulcsokat a tiszta Kotlin OBD-mag adja, így a mag nyelvfüggetlen marad; új nyelvhez
 * elég egy újabb values-xx/strings.xml.
 */
object Labels {
    private val cache = ConcurrentHashMap<String, String>()

    @SuppressLint("DiscouragedApi")
    fun get(context: Context, name: String, fallback: String = name): String {
        val locale = context.resources.configuration.locales[0]?.toLanguageTag() ?: ""
        return cache.getOrPut("$locale|$name") {
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            if (id != 0) context.getString(id) else fallback
        }
    }

    fun signalName(context: Context, signalId: String): String {
        if (signalId.startsWith("mfr.")) {
            val reg = hu.obddiag.core.mfr.MfrSignals.registry?.signal(signalId) ?: return signalId
            return hu.obddiag.core.mfr.MfrRegistry.labelOf(reg.item, reg.param).get(hu.obddiag.core.mfr.MfrSignals.language)
        }
        val key = StandardPids.signal(signalId)?.key ?: StandardPids.state(signalId)?.key
        return if (key != null) get(context, "pid_$key", signalId) else signalId
    }

    fun stateValue(context: Context, code: String): String = when {
        code.startsWith("txt:") -> code.removePrefix("txt:")
        code == "bool:1" -> get(context, "state_bool_on", "1")
        code == "bool:0" -> get(context, "state_bool_off", "0")
        else -> get(context, "state_$code", code)
    }
    fun monitor(context: Context, key: String): String = get(context, "mon_$key", key)
    fun category(context: Context, c: PidCategory): String = get(context, "cat_${c.name.lowercase()}", c.name)
    fun mid(context: Context, key: String): String = get(context, key, key)
}

@Composable
@ReadOnlyComposable
fun signalName(signalId: String): String = Labels.signalName(LocalContext.current, signalId)

@Composable
@ReadOnlyComposable
fun stateLabel(code: String): String = Labels.stateValue(LocalContext.current, code)
