package com.totecam.universal

import org.json.JSONObject
import java.io.Serializable
import java.util.UUID

data class CameraEntry(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Camera",
    var type: String = TYPE_RTSP,
    var url: String = "",
    var username: String = "",
    var password: String = "",
    var note: String = ""
) : Serializable {

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", type); put("url", url)
        put("username", username); put("password", password); put("note", note)
    }

    /** URL with credentials embedded for players that read userinfo from the URI. */
    fun effectiveUrl(): String {
        if (type == TYPE_RTSP && username.isNotEmpty() && !url.contains("@")) {
            val u = android.net.Uri.encode(username)
            val p = android.net.Uri.encode(password)
            return url.replaceFirst("://", "://$u:$p@")
        }
        return url
    }

    companion object {
        const val TYPE_RTSP = "RTSP"
        const val TYPE_MJPEG = "MJPEG"
        const val TYPE_USB = "USB"
        const val TYPE_DEVICE = "DEVICE"

        fun fromJson(o: JSONObject) = CameraEntry(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Camera"),
            type = o.optString("type", TYPE_RTSP),
            url = o.optString("url", ""),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            note = o.optString("note", "")
        )
    }
}

object CameraStore {
    private const val PREFS = "totecam"
    private const val KEY = "cameras"

    fun load(ctx: android.content.Context): List<CameraEntry> {
        val s = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = org.json.JSONArray(s)
        val out = mutableListOf<CameraEntry>()
        for (i in 0 until arr.length()) {
            try { out.add(CameraEntry.fromJson(arr.getJSONObject(i))) } catch (e: Exception) {}
        }
        return out
    }

    fun save(ctx: android.content.Context, entry: CameraEntry) {
        val all = load(ctx).toMutableList()
        val idx = all.indexOfFirst { it.id == entry.id }
        if (idx >= 0) all[idx] = entry else all.add(entry)
        persist(ctx, all)
    }

    fun remove(ctx: android.content.Context, entry: CameraEntry) {
        persist(ctx, load(ctx).filterNot { it.id == entry.id })
    }

    fun byId(ctx: android.content.Context, id: String): CameraEntry? = load(ctx).firstOrNull { it.id == id }

    private fun persist(ctx: android.content.Context, list: List<CameraEntry>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
