package com.zen.bmwlink

import android.content.Context
import kotlin.math.abs

/**
 * Picks well-known values out of the raw CarData readings by keyword.
 * BMW's descriptor names vary by model/year, so matching is deliberately loose;
 * everything is also visible in the "All data" tab.
 */
class Metrics(private val all: Map<String, Reading>) {
    private fun find(pred: (String) -> Boolean): Reading? =
        all.values.firstOrNull { pred(it.key.lowercase()) }

    private fun findAll(pred: (String) -> Boolean): List<Reading> =
        all.values.filter { pred(it.key.lowercase()) }

    val odometer = find { "travelleddistance" in it || "odometer" in it || "mileage" in it }
    val fuel = find { "fuel" in it && ("level" in it || "remaining" in it) && "range" !in it }
    val range = find { "range" in it && "electric" !in it }
    val latitude = find { "latitude" in it }
    val longitude = find { "longitude" in it }
    val battery12v = find { "battery" in it && "voltage" in it }
    val locks = findAll { "lock" in it }
    val roof = findAll { "roof" in it || "convertible" in it }
    val openItems = all.values.filter {
        val k = it.key.lowercase()
        val v = it.value.lowercase()
        ("door" in k || "window" in k || "trunk" in k || "hood" in k) &&
            "lock" !in k && v in setOf("open", "opened", "true", "ajar")
    }
}

/** Reading value as miles, converting from km when needed. */
fun Reading.miles(): Double? {
    val n = value.toDoubleOrNull() ?: return null
    return if (unit?.lowercase()?.startsWith("mi") == true) n else n * 0.621371
}

data class Trip(val start: Long, val end: Long, val miles: Double)

/** Approximate trip log built from odometer changes (the car only reports when something changes). */
class TripLog(ctx: Context) {
    private val p = ctx.getSharedPreferences("triplog", Context.MODE_PRIVATE)
    private val entries = mutableListOf<Pair<Long, Double>>()

    init {
        p.getString("e", "")?.split(';')?.forEach {
            val parts = it.split(',')
            if (parts.size == 2) {
                val t = parts[0].toLongOrNull()
                val m = parts[1].toDoubleOrNull()
                if (t != null && m != null) entries.add(t to m)
            }
        }
    }

    fun add(miles: Double) {
        if (entries.isNotEmpty() && abs(entries.last().second - miles) < 0.05) return
        entries.add(System.currentTimeMillis() to miles)
        while (entries.size > 3000) entries.removeAt(0)
        p.edit().putString("e", entries.joinToString(";") { "${it.first},${it.second}" }).apply()
    }

    fun trips(): List<Trip> {
        val out = mutableListOf<Trip>()
        var cluster = mutableListOf<Pair<Long, Double>>()
        fun flush() {
            if (cluster.size >= 2) {
                val d = cluster.last().second - cluster.first().second
                if (d > 0.1) out.add(Trip(cluster.first().first, cluster.last().first, d))
            }
            cluster = mutableListOf()
        }
        for (e in entries.sortedBy { it.first }) {
            if (cluster.isNotEmpty() && e.first - cluster.last().first > 20 * 60_000L) flush()
            cluster.add(e)
        }
        flush()
        return out.reversed()
    }
}
