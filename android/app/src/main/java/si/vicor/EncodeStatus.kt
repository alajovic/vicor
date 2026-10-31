package si.vicor

import android.net.Uri
import android.os.Handler
import android.os.Looper

sealed interface EncodeState {
    data object Idle : EncodeState
    data class Running(val name: String, val percent: Int) : EncodeState
    data class Done(val name: String, val output: Uri, val oldSize: Long, val newSize: Long) : EncodeState
    data class Failed(val name: String, val message: String) : EncodeState
}

/** Process-wide encode state, shared between [EncodeService] and [MainActivity]. Main thread only. */
object EncodeStatus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(EncodeState) -> Unit>()

    var state: EncodeState = EncodeState.Idle
        private set

    fun set(newState: EncodeState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { set(newState) }
            return
        }
        state = newState
        listeners.toList().forEach { it(newState) }
    }

    fun observe(listener: (EncodeState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun remove(listener: (EncodeState) -> Unit) {
        listeners -= listener
    }
}
