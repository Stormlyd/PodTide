package eu.darken.capod.reaction.core.headgesture

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class HeadGestureConfig(
    val enabled: Boolean = false,
    val calls: Boolean = true,
    val media: Boolean = false,
    val nod: GestureAction = GestureAction.PLAY_PAUSE,
    val shake: GestureAction = GestureAction.NEXT,
    val threshold: Int = 1_000,
)

@Singleton
class HeadGestureSettings @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("podbridge.gestures", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    fun read(id: String): HeadGestureConfig {
        fun action(key: String, fallback: GestureAction) = runCatching {
            GestureAction.valueOf(preferences.getString("$id.$key", fallback.name)!!)
        }.getOrDefault(fallback).takeUnless { it == GestureAction.ANSWER || it == GestureAction.DECLINE } ?: fallback
        return HeadGestureConfig(
            enabled = preferences.getBoolean("$id.enabled", false),
            calls = preferences.getBoolean("$id.calls", true),
            media = preferences.getBoolean("$id.media", false),
            nod = action("nod", GestureAction.PLAY_PAUSE),
            shake = action("shake", GestureAction.NEXT),
            threshold = preferences.getInt("$id.threshold", 1_000).coerceIn(600, 3_000),
        )
    }

    fun update(id: String, config: HeadGestureConfig) {
        preferences.edit().putBoolean("$id.enabled", config.enabled).putBoolean("$id.calls", config.calls)
            .putBoolean("$id.media", config.media).putString("$id.nod", config.nod.name)
            .putString("$id.shake", config.shake.name).putInt("$id.threshold", config.threshold.coerceIn(600, 3_000)).apply()
        _changes.value++
    }
}
