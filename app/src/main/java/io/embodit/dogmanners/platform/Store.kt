package io.embodit.dogmanners.platform

import android.content.Context

/** SharedPreferences (design persistence): progress, language, sound, stand / sit, signal tag, disclaimer seen. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("dog_manners", Context.MODE_PRIVATE)

    var completed: Set<Int>
        get() = p.getStringSet("completed", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("completed", v.map { it.toString() }.toSet()).apply()

    var introsSeen: Set<Int>
        get() = p.getStringSet("intros", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("intros", v.map { it.toString() }.toSet()).apply()

    var lang: String
        get() = p.getString("lang", "SYSTEM")!!
        set(v) = p.edit().putString("lang", v).apply()

    var soundOn: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    var seated: Boolean
        get() = p.getBoolean("seated", false)
        set(v) = p.edit().putBoolean("seated", v).apply()

    var tagOn: Boolean
        get() = p.getBoolean("tag", true)
        set(v) = p.edit().putBoolean("tag", v).apply()

    var disclaimerSeen: Boolean
        get() = p.getBoolean("disclaimer", false)
        set(v) = p.edit().putBoolean("disclaimer", v).apply()

    var eyeAsked: Boolean
        get() = p.getBoolean("eyeAsked", false)
        set(v) = p.edit().putBoolean("eyeAsked", v).apply()

    fun clearProgress() {
        p.edit().remove("completed").remove("intros").apply()
    }

    fun clearAll() {
        p.edit().clear().apply()
    }
}
