package com.mylivestreamer.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** One streaming destination (one platform). */
data class Dest(var preset: String, var url: String, var key: String, var enabled: Boolean) {
    /** Full RTMP address = server URL + "/" + stream key. */
    fun endpoint(): String {
        val u = url.trim()
        val k = key.trim()
        return if (k.isEmpty()) u else u.trimEnd('/') + "/" + k
    }
}

object Presets {
    val names = listOf("YouTube", "Facebook", "Instagram", "TikTok", "Twitch", "Custom")

    fun serverUrl(p: String): String = when (p) {
        "YouTube" -> "rtmp://a.rtmp.youtube.com/live2"
        "Facebook" -> "rtmps://live-api-s.facebook.com:443/rtmp/"
        "Twitch" -> "rtmp://live.twitch.tv/app"
        else -> ""
    }

    /** Page where the user gets the stream key (opened in the normal browser, so login works). */
    fun keyPage(p: String): String = when (p) {
        "YouTube" -> "https://studio.youtube.com"
        "Facebook" -> "https://www.facebook.com/live/producer"
        "Instagram" -> "https://www.instagram.com/"
        "TikTok" -> "https://www.tiktok.com/live"
        "Twitch" -> "https://dashboard.twitch.tv/settings/stream"
        else -> ""
    }
}

data class Quality(val label: String, val w: Int, val h: Int, val mbps30: Float, val mbps60: Float)

object Qualities {
    // w x h are given in landscape; the app rotates them for portrait streams.
    val list = listOf(
        Quality("480p", 854, 480, 1.5f, 2.5f),
        Quality("720p", 1280, 720, 3.0f, 4.5f),
        Quality("1080p", 1920, 1080, 5.0f, 8.0f),
        Quality("1440p", 2560, 1440, 9.0f, 14.0f),
        Quality("4K", 3840, 2160, 16.0f, 25.0f)
    )
}

@Suppress("UNCHECKED_CAST")
private class P<T>(
    private val sp: SharedPreferences,
    private val key: String,
    private val def: T
) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = when (def) {
        is Int -> sp.getInt(key, def) as T
        is Boolean -> sp.getBoolean(key, def) as T
        is Float -> sp.getFloat(key, def) as T
        else -> (sp.getString(key, def as String) ?: def) as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val e = sp.edit()
        when (value) {
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            is Float -> e.putFloat(key, value)
            else -> e.putString(key, value as String)
        }
        e.apply()
    }
}

/** All settings live in the app's private storage on the phone. Nothing is sent anywhere. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("mls", Context.MODE_PRIVATE)

    var lang by P(sp, "lang", "en")
    var resIdx by P(sp, "resIdx", 2)          // index in Qualities.list
    var fps60 by P(sp, "fps60", false)
    var mbps by P(sp, "mbps", 5.0f)           // max video bitrate, Mbps
    var autoAdapt by P(sp, "autoAdapt", true)
    var portrait by P(sp, "portrait", true)
    var audioMode by P(sp, "audioMode", 0)    // 0 mic, 1 internal, 2 both
    var panel by P(sp, "panel", true)         // floating controls
    var cam by P(sp, "cam", false)            // camera overlay on at start
    var camPos by P(sp, "camPos", 3)          // 0 TL 1 TR 2 BL 3 BR
    var camSize by P(sp, "camSize", 1)        // 0 S 1 M 2 L
    var camRot by P(sp, "camRot", 1)          // 0,90,180,270 index
    var overlayText by P(sp, "overlayText", "")
    var textPos by P(sp, "textPos", 0)
    var logoPath by P(sp, "logoPath", "")
    var logoPos by P(sp, "logoPos", 1)
    var chatUrl by P(sp, "chatUrl", "")
    var chatTop by P(sp, "chatTop", false)
    var stats by P(sp, "stats", true)

    var dests: List<Dest>
        get() {
            val out = mutableListOf<Dest>()
            runCatching {
                val arr = JSONArray(sp.getString("dests", "[]"))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += Dest(
                        o.optString("preset", "Custom"), o.optString("url", ""),
                        o.optString("key", ""), o.optBoolean("enabled", true)
                    )
                }
            }
            if (out.isEmpty()) out += Dest("YouTube", Presets.serverUrl("YouTube"), "", true)
            return out
        }
        set(v) {
            val arr = JSONArray()
            v.forEach {
                arr.put(
                    JSONObject().put("preset", it.preset).put("url", it.url)
                        .put("key", it.key).put("enabled", it.enabled)
                )
            }
            sp.edit().putString("dests", arr.toString()).apply()
        }
}
