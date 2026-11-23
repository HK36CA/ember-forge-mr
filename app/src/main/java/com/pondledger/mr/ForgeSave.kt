package com.pondledger.mr

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ForgeSave(private val prefs: SharedPreferences) {
    fun write(g: ForgeGame) {
        val json = JSONObject().apply {
            put("version", 1)
            put("days", g.days); put("rng", g.rng.toString()); put("phase", g.phase.name)
            put("day", g.day); put("blanks", g.blanks); put("tribute", g.tribute); put("anger", g.anger)
            put("grade", g.grade); put("value", g.value); put("charge", g.charge); put("frenzy", g.frenzyMs)
            put("dents", g.dents); put("bonus", g.bonus); put("strikes", g.strikes); put("total", g.totalStrikes)
            put("relics", JSONArray(g.relics)); put("shelf", JSONArray(g.shelf)); put("best", g.best)
            put("lifetime", g.lifetime); put("last", g.last); put("hp", g.dragonHp); put("shields", g.shields)
            put("rune", g.rune); put("endless", g.endless); put("ore", g.ore)
            put("tickets", JSONArray(g.tickets)); put("lastTrade", g.lastTradeDay)
            put("spotLane", g.spotLane); put("precisionHits", g.precisionHits)
            put("bladePrecisionHits", g.bladePrecisionHits)
            put("commissionPaidDay", g.commissionPaidDay)
            put("commissionsCompleted", g.commissionsCompleted)
            put("commissionEarnings", g.commissionEarnings)
        }
        prefs.edit().putString("contract", json.toString()).apply()
    }
    fun read(): ForgeGame? = runCatching {
        val j = JSONObject(prefs.getString("contract", null) ?: return null)
        fun ints(key: String) = j.getJSONArray(key).let { a -> List(a.length()) { a.getInt(it) } }
        require(j.getInt("version") == 1)
        val inventory = ints("relics")
        val shelf = ints("shelf")
        require(inventory.size == Relic.entries.size && shelf.size == 3 && shelf.all { it in Relic.entries.indices })
        ForgeGame(days = j.getInt("days"), rng = j.getString("rng").toLong(), phase = Phase.valueOf(j.getString("phase")),
            day = j.getInt("day"), blanks = j.getInt("blanks"), tribute = j.getLong("tribute"), anger = j.getInt("anger"),
            grade = j.getInt("grade"), value = j.getLong("value"), charge = j.getInt("charge"), frenzyMs = j.getInt("frenzy"),
            dents = j.getInt("dents"), bonus = j.getInt("bonus"), strikes = j.getInt("strikes"), totalStrikes = j.getInt("total"),
            relics = inventory, shelf = shelf, best = j.getLong("best"), lifetime = j.getLong("lifetime"),
            last = if (j.getString("phase") == Phase.MATERIAL.name) "料架已就绪。请选择一块原料。" else j.getString("last"),
            dragonHp = j.getInt("hp"), shields = j.getInt("shields"), rune = j.getInt("rune"), endless = j.getBoolean("endless"),
            ore = j.getInt("ore"), tickets = if (j.has("tickets")) ints("tickets") else listOf(0,0,0),
            lastTradeDay = j.optInt("lastTrade"), spotLane = j.optInt("spotLane", 2).coerceIn(0, 4),
            precisionHits = j.optInt("precisionHits"),
            bladePrecisionHits = j.optInt("bladePrecisionHits").coerceAtLeast(0),
            commissionPaidDay = j.optInt("commissionPaidDay").coerceIn(0, j.getInt("day")),
            commissionsCompleted = j.optInt("commissionsCompleted").coerceAtLeast(0),
            commissionEarnings = j.optLong("commissionEarnings").coerceIn(0L, ForgeGame.LIMIT))
    }.getOrNull()
}
