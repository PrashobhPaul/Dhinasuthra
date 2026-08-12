package com.dhinasuthra.app.export

import android.content.Context
import android.net.Uri
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-owned data (product.md §50): export everything meaningful as JSON or CSV
 * via the Storage Access Framework — no broad storage permission (§14), no cloud.
 */
class DataExporter(private val context: Context) {

    suspend fun exportJson(uri: Uri) {
        val db = DhinaSuthraApp.get(context).container.db
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("places", JSONArray().apply {
            db.placeDao().all().forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("category", p.category.name)
                    put("lat", p.lat); put("lon", p.lon); put("radiusM", p.radiusM)
                    put("visitCount", p.visitCount); put("visitDays", p.visitDays)
                    put("confirmed", p.confirmed); put("confidence", p.confidence)
                })
            }
        })
        val today = TimeUtils.epochDay()
        root.put("routineEvents", JSONArray().apply {
            db.routineEventDao().betweenDays(today - 365, today).forEach { e ->
                put(JSONObject().apply {
                    put("epochDay", e.epochDay); put("type", e.eventType.name)
                    put("timestamp", e.timestamp); put("durationMin", e.durationMin ?: JSONObject.NULL)
                    put("confidence", e.confidence); put("source", e.source.name)
                })
            }
        })
        root.put("patterns", JSONArray().apply {
            db.routinePatternDao().all().forEach { p ->
                put(JSONObject().apply {
                    put("eventType", p.eventType.name); put("dayType", p.dayType.name)
                    put("median", p.medianMin); put("p10", p.p10); put("p25", p.p25)
                    put("p75", p.p75); put("p90", p.p90)
                    put("observations", p.observationCount); put("confidence", p.confidence)
                })
            }
        })
        root.put("dailySummaries", JSONArray().apply {
            db.dailySummaryDao().betweenDays(today - 365, today).forEach { s ->
                put(JSONObject().apply {
                    put("epochDay", s.epochDay); put("homeMin", s.homeMin); put("workMin", s.workMin)
                    put("travelMin", s.travelMin); put("sleepMin", s.sleepMin)
                    put("socialMin", s.socialMin); put("otherMin", s.otherMin)
                    put("routineMatch", s.routineMatch ?: JSONObject.NULL)
                })
            }
        })
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(root.toString(2).toByteArray())
        }
    }

    suspend fun exportCsv(uri: Uri) {
        val db = DhinaSuthraApp.get(context).container.db
        val today = TimeUtils.epochDay()
        val sb = StringBuilder("date,eventType,time,durationMin,confidence,source\n")
        db.routineEventDao().betweenDays(today - 365, today).forEach { e ->
            sb.append(TimeUtils.localDate(e.epochDay)).append(',')
                .append(e.eventType.name).append(',')
                .append(TimeUtils.formatMinuteOfDay(TimeUtils.minuteOfDay(java.time.Instant.ofEpochMilli(e.timestamp)))).append(',')
                .append(e.durationMin ?: "").append(',')
                .append("%.2f".format(e.confidence)).append(',')
                .append(e.source.name).append('\n')
        }
        context.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
    }

    /** Delete everything (product.md §71): DB tables + settings, sensing torn down by caller. */
    suspend fun deleteAllData() {
        val app = DhinaSuthraApp.get(context)
        val db = app.container.db
        db.clearAllTables()
        app.container.contextEngine.reset()
        app.container.settings.simulatedDataLoaded = false
    }
}
