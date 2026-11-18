package com.mylivestreamer.app

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import org.json.JSONObject
import java.util.Locale

fun t(key: String): String = I18n.t(key)

/**
 * English and Bangla are built in. Any other language is translated from English on the
 * phone with Google ML Kit (free, model downloaded once) and then cached.
 */
object I18n {
    private var current: Map<String, String> = emptyMap()

    val en: Map<String, String> = linkedMapOf(
        "language" to "Language",
        "dests" to "Where to stream",
        "add_dest" to "+ Add platform",
        "remove" to "Remove",
        "use_it" to "Use",
        "server_url" to "Server URL",
        "stream_key" to "Stream key",
        "paste" to "Paste key",
        "get_key" to "Get key",
        "quality" to "Quality",
        "resolution" to "Resolution",
        "fps" to "Frame rate",
        "max_bitrate" to "Max bitrate (Mbps)",
        "auto_adapt" to "Auto-lower quality when internet is weak",
        "orientation" to "Stream orientation",
        "portrait" to "Portrait",
        "landscape" to "Landscape",
        "quality_note" to "All platforms get the same quality. 4K works on YouTube only; others usually cap at 1080p.",
        "audio" to "Audio",
        "audio_mic" to "Microphone",
        "audio_internal" to "Phone sound (Android 10+)",
        "audio_both" to "Both",
        "controls" to "Controls and overlays",
        "panel" to "Floating control panel (also visible in the stream)",
        "cam_overlay" to "Front camera overlay",
        "cam_pos" to "Camera position",
        "cam_size" to "Camera size",
        "cam_rot" to "Camera rotation fix",
        "small" to "Small",
        "medium" to "Medium",
        "large" to "Large",
        "text_overlay" to "Text overlay",
        "text_hint" to "Text shown on the stream (empty = none)",
        "logo" to "Logo",
        "pick_logo" to "Choose logo",
        "clear_logo" to "Remove logo",
        "pos_tl" to "Top left",
        "pos_tr" to "Top right",
        "pos_bl" to "Bottom left",
        "pos_br" to "Bottom right",
        "chat" to "Live chat on screen",
        "chat_hint" to "Chat page link or YouTube video ID",
        "chat_top" to "Chat at top (default: bottom)",
        "stats" to "Show live stats",
        "start" to "START LIVE",
        "stop" to "STOP",
        "idle" to "Ready",
        "need_dest" to "Add at least one platform with a URL and key",
        "need_mic" to "Microphone permission is needed",
        "overlay_denied" to "Floating controls turned off (permission not given)",
        "translating" to "Translating...",
        "translate_fail" to "Translation failed. Check internet and Google Play services."
    )

    private val bn: Map<String, String> = linkedMapOf(
        "language" to "ভাষা",
        "dests" to "কোথায় স্ট্রিম করবেন",
        "add_dest" to "+ প্ল্যাটফর্ম যোগ করুন",
        "remove" to "মুছুন",
        "use_it" to "ব্যবহার করুন",
        "server_url" to "সার্ভার ইউআরএল",
        "stream_key" to "স্ট্রিম কী",
        "paste" to "কী পেস্ট করুন",
        "get_key" to "কী সংগ্রহ করুন",
        "quality" to "মান",
        "resolution" to "রেজোলিউশন",
        "fps" to "ফ্রেম রেট",
        "max_bitrate" to "সর্বোচ্চ বিটরেট (Mbps)",
        "auto_adapt" to "ইন্টারনেট দুর্বল হলে মান স্বয়ংক্রিয়ভাবে কমান",
        "orientation" to "স্ট্রিমের দিক",
        "portrait" to "পোর্ট্রেট (খাড়া)",
        "landscape" to "ল্যান্ডস্কেপ (আড়াআড়ি)",
        "quality_note" to "সব প্ল্যাটফর্মে একই মান যাবে। ৪কে শুধু ইউটিউবে চলে; অন্যগুলো সাধারণত ১০৮০পি পর্যন্ত।",
        "audio" to "অডিও",
        "audio_mic" to "মাইক্রোফোন",
        "audio_internal" to "ফোনের শব্দ (অ্যান্ড্রয়েড ১০+)",
        "audio_both" to "দুটোই",
        "controls" to "কন্ট্রোল ও ওভারলে",
        "panel" to "ভাসমান কন্ট্রোল প্যানেল (স্ট্রিমেও দেখা যাবে)",
        "cam_overlay" to "সামনের ক্যামেরা ওভারলে",
        "cam_pos" to "ক্যামেরার অবস্থান",
        "cam_size" to "ক্যামেরার আকার",
        "cam_rot" to "ক্যামেরা ঘোরানো (ঠিক করতে)",
        "small" to "ছোট",
        "medium" to "মাঝারি",
        "large" to "বড়",
        "text_overlay" to "লেখা ওভারলে",
        "text_hint" to "স্ট্রিমে দেখানোর লেখা (ফাঁকা = কিছু না)",
        "logo" to "লোগো",
        "pick_logo" to "লোগো বাছুন",
        "clear_logo" to "লোগো সরান",
        "pos_tl" to "উপরে বামে",
        "pos_tr" to "উপরে ডানে",
        "pos_bl" to "নিচে বামে",
        "pos_br" to "নিচে ডানে",
        "chat" to "স্ক্রিনে লাইভ চ্যাট",
        "chat_hint" to "চ্যাট পেজের লিংক বা ইউটিউব ভিডিও আইডি",
        "chat_top" to "চ্যাট উপরে (ডিফল্ট: নিচে)",
        "stats" to "লাইভ পরিসংখ্যান দেখান",
        "start" to "লাইভ শুরু করুন",
        "stop" to "বন্ধ করুন",
        "idle" to "প্রস্তুত",
        "need_dest" to "অন্তত একটি প্ল্যাটফর্মের ইউআরএল ও কী দিন",
        "need_mic" to "মাইক্রোফোনের অনুমতি দরকার",
        "overlay_denied" to "ভাসমান কন্ট্রোল বন্ধ (অনুমতি দেওয়া হয়নি)",
        "translating" to "অনুবাদ হচ্ছে...",
        "translate_fail" to "অনুবাদ ব্যর্থ। ইন্টারনেট ও গুগল প্লে সার্ভিস দেখুন।"
    )

    fun t(key: String): String = current[key] ?: en[key] ?: key

    /** Language codes shown in the picker: English, Bangla, then every ML Kit language. */
    fun codes(): List<String> {
        val others = TranslateLanguage.getAllLanguages().filter { it != "en" && it != "bn" }
            .sortedBy { name(it) }
        return listOf("en", "bn") + others
    }

    fun name(code: String): String {
        val loc = Locale.forLanguageTag(code)
        val n = loc.getDisplayLanguage(loc)
        return if (n.isNullOrBlank()) code else n.replaceFirstChar { it.uppercase(loc) }
    }

    /** Load a language that is already available without internet. */
    fun loadOffline(ctx: Context, code: String) {
        current = when (code) {
            "en" -> en
            "bn" -> bn
            else -> cached(ctx, code) ?: en
        }
    }

    private fun cached(ctx: Context, code: String): Map<String, String>? {
        val s = ctx.getSharedPreferences("mls_tr", Context.MODE_PRIVATE).getString("tr_$code", null)
            ?: return null
        return runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrNull()
    }

    /** Switch language; translates and caches first if needed. */
    fun select(ctx: Context, code: String, onDone: () -> Unit, onFail: () -> Unit) {
        if (code == "en" || code == "bn" || cached(ctx, code) != null) {
            loadOffline(ctx, code)
            onDone()
            return
        }
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(code)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val keys = en.keys.toList()
                val out = HashMap<String, String>()
                var i = 0
                fun next() {
                    if (i >= keys.size) {
                        val o = JSONObject()
                        out.forEach { (k, v) -> o.put(k, v) }
                        ctx.getSharedPreferences("mls_tr", Context.MODE_PRIVATE)
                            .edit().putString("tr_$code", o.toString()).apply()
                        translator.close()
                        loadOffline(ctx, code)
                        onDone()
                        return
                    }
                    val k = keys[i]
                    val src = en.getValue(k)
                    translator.translate(src)
                        .addOnSuccessListener { out[k] = it; i++; next() }
                        .addOnFailureListener { out[k] = src; i++; next() }
                }
                next()
            }
            .addOnFailureListener {
                translator.close()
                onFail()
            }
    }
}
