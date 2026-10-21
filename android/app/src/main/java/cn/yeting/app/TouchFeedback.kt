package cn.yeting.app

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/** User-driven View feedback only; the platform retains control of system haptic settings. */
internal object TouchFeedback {
    private const val TICK_INTERVAL_MS=35L

    internal fun shouldTick(previous:Int?,value:Int,lastTickMs:Long?,nowMs:Long,anchor:Boolean=false):Boolean =
        previous!=value && (anchor || lastTickMs==null || nowMs-lastTickMs>=TICK_INTERVAL_MS)

    /** Keep one instance per ruler; reset to its current value at gesture start or programmatic refresh. */
    class Ticks {
        private var previous:Int?=null
        private var lastTickMs:Long?=null

        fun reset(value:Int?=null){previous=value;lastTickMs=null}

        internal fun advance(value:Int,nowMs:Long,anchor:Boolean=false):Boolean {
            val accepted=shouldTick(previous,value,lastTickMs,nowMs,anchor)
            // Remember even suppressed changes: holding a value must never produce a delayed tick.
            previous=value
            if(accepted)lastTickMs=nowMs
            return accepted
        }

        fun tick(view:View,value:Int,anchor:Boolean=false):Boolean {
            if(!advance(value,SystemClock.uptimeMillis(),anchor))return false
            return feedback(view,if(anchor)HapticFeedbackConstantsCompat.SEGMENT_TICK else HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK)
        }
    }

    // ItemTouchHelper supplies the pickup feedback; only add the successful drop ourselves.
    /** Call once after a successful move, never for cancellation or merely crossing a row. */
    fun drop(view:View):Boolean = feedback(view,HapticFeedbackConstantsCompat.GESTURE_END)
    /** The caller owns the once-per-gesture threshold latch, including recrossings. */
    fun threshold(view:View):Boolean = feedback(view,HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE)

    private fun feedback(view:View,kind:Int):Boolean {
        if(Looper.myLooper()!=Looper.getMainLooper() || !view.isEnabled || !view.isHapticFeedbackEnabled ||
            !view.isAttachedToWindow || !view.isShown || !view.hasWindowFocus())return false
        if(!view.context.getSharedPreferences("night",Context.MODE_PRIVATE).getBoolean("haptics",true))return false
        return ViewCompat.performHapticFeedback(view,kind)
    }
}
