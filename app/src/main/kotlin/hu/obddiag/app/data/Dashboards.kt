package hu.obddiag.app.data

import hu.obddiag.data.DashboardEntity
import hu.obddiag.data.ObdDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class GaugeType { ANALOG, DIGITAL, TEMPERATURE, PRESSURE, ROUND, BAR, NUMBER, SPARKLINE }

/** A widget szélessége a rácsban: 1 oszlop, 2 oszlop vagy teljes sor. */
enum class WidgetSize { SMALL, WIDE, FULL }

data class DashboardWidget(
    val id: String = UUID.randomUUID().toString(),
    val type: GaugeType,
    val signalId: String,
    val size: WidgetSize = WidgetSize.SMALL,
    val warnLow: Double? = null,
    val warnHigh: Double? = null,
    /** Egyedi skála; null esetén a PID-definíció tartománya. */
    val min: Double? = null,
    val max: Double? = null
)

data class Dashboard(val id: Long, val name: String, val widgets: List<DashboardWidget>, val sortOrder: Int)

object DashboardCodec {
    fun encode(widgets: List<DashboardWidget>): String = JSONArray().apply {
        widgets.forEach { w ->
            put(JSONObject().apply {
                put("id", w.id); put("type", w.type.name); put("signal", w.signalId); put("size", w.size.name)
                w.warnLow?.let { put("warnLow", it) }; w.warnHigh?.let { put("warnHigh", it) }
                w.min?.let { put("min", it) }; w.max?.let { put("max", it) }
            })
        }
    }.toString()

    fun decode(json: String): List<DashboardWidget> = try {
        val a = JSONArray(json)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            fun optD(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
            DashboardWidget(
                id = o.optString("id", UUID.randomUUID().toString()),
                type = runCatching { GaugeType.valueOf(o.getString("type")) }.getOrDefault(GaugeType.NUMBER),
                signalId = o.getString("signal"),
                size = runCatching { WidgetSize.valueOf(o.optString("size")) }.getOrDefault(WidgetSize.SMALL),
                warnLow = optD("warnLow"), warnHigh = optD("warnHigh"), min = optD("min"), max = optD("max")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

class DashboardRepository(private val db: ObdDatabase) {
    val dashboards: Flow<List<Dashboard>> = db.dashboards().observeAll().map { list ->
        list.map { Dashboard(it.id, it.name, DashboardCodec.decode(it.layoutJson), it.sortOrder) }
    }

    suspend fun ensureDefaults() {
        if (db.dashboards().count() > 0) return
        db.dashboards().upsert(DashboardEntity(name = "Motor", sortOrder = 0, layoutJson = DashboardCodec.encode(listOf(
            DashboardWidget(type = GaugeType.ANALOG, signalId = "01.0C", size = WidgetSize.WIDE, warnHigh = 6000.0),
            DashboardWidget(type = GaugeType.DIGITAL, signalId = "01.0D", size = WidgetSize.WIDE),
            DashboardWidget(type = GaugeType.TEMPERATURE, signalId = "01.05", warnHigh = 105.0),
            DashboardWidget(type = GaugeType.ROUND, signalId = "01.04"),
            DashboardWidget(type = GaugeType.BAR, signalId = "01.11", size = WidgetSize.WIDE),
            DashboardWidget(type = GaugeType.NUMBER, signalId = "01.42", warnLow = 12.0, warnHigh = 15.0),
            DashboardWidget(type = GaugeType.PRESSURE, signalId = "01.0B"),
            DashboardWidget(type = GaugeType.SPARKLINE, signalId = "01.0C", size = WidgetSize.FULL),
        ))))
        db.dashboards().upsert(DashboardEntity(name = "Keverék", sortOrder = 1, layoutJson = DashboardCodec.encode(listOf(
            DashboardWidget(type = GaugeType.ROUND, signalId = "01.06", min = -25.0, max = 25.0, warnLow = -15.0, warnHigh = 15.0),
            DashboardWidget(type = GaugeType.ROUND, signalId = "01.07", min = -25.0, max = 25.0, warnLow = -15.0, warnHigh = 15.0),
            DashboardWidget(type = GaugeType.SPARKLINE, signalId = "01.15.V", size = WidgetSize.FULL),
            DashboardWidget(type = GaugeType.NUMBER, signalId = "01.10"),
            DashboardWidget(type = GaugeType.TEMPERATURE, signalId = "01.0F"),
        ))))
    }

    suspend fun save(d: Dashboard): Long {
        val id = db.dashboards().upsert(DashboardEntity(id = d.id, name = d.name, layoutJson = DashboardCodec.encode(d.widgets), sortOrder = d.sortOrder))
        return if (id == -1L) d.id else id
    }

    suspend fun create(name: String, sortOrder: Int): Long =
        db.dashboards().upsert(DashboardEntity(name = name, layoutJson = "[]", sortOrder = sortOrder))

    suspend fun delete(id: Long) = db.dashboards().delete(id)
}
