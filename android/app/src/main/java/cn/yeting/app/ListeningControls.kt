package cn.yeting.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.NumberPicker
import android.widget.SeekBar
import java.util.Locale
import kotlin.math.floor

internal object PlaybackSpeed {
    fun normalize(value:Float):Float = (floor(value.coerceIn(.5f,2f)*10+.5001f)/10).toFloat()
    fun label(value:Float)=String.format(Locale.ROOT,"%.1f×",value)
}

/** Stable decorative heights; they do not represent measured audio amplitude. */
internal object ProgressTicks {
    fun heights(key:String,count:Int):IntArray {
        val random=java.util.Random(key.hashCode().toLong())
        return IntArray(count){when(random.nextInt(100)){
            in 0..4->34+random.nextInt(12)
            in 5..9->6+random.nextInt(5)
            else->18+random.nextInt(8)
        }}
    }
}

/** Preserve NumberPicker's native value changes, with one app-controlled haptic path. */
internal open class ListeningNumberPicker(context:Context):NumberPicker(context) {
    // The superclass may dispatch setValue() before this field's initializer runs.
    private val feedback:TouchFeedback.Ticks?=TouchFeedback.Ticks()
    private var sendingFeedback=false
    init {feedback?.reset(value);setOnValueChangedListener(null)}

    override fun setValue(value:Int){super.setValue(value);feedback?.reset(this.value)}
    override fun setOnValueChangedListener(listener:OnValueChangeListener?) {
        super.setOnValueChangedListener {picker,oldValue,newValue->
            if(oldValue!=newValue) {
                sendingFeedback=true
                try {feedback?.tick(this,newValue)}
                finally {sendingFeedback=false}
            }
            listener?.onValueChange(picker,oldValue,newValue)
        }
    }
    override fun performHapticFeedback(feedbackConstant:Int):Boolean = sendingFeedback && super.performHapticFeedback(feedbackConstant)
    override fun performHapticFeedback(feedbackConstant:Int,flags:Int):Boolean = sendingFeedback && super.performHapticFeedback(feedbackConstant,flags)
}

internal class ListeningProgress(context:Context):SeekBar(context) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    var entryKey=""
        set(value){if(field!=value){field=value;rebuildTicks();invalidate()}}
    private var heights=intArrayOf()
    private fun rebuildTicks(){heights=ProgressTicks.heights(entryKey,((width-paddingLeft-paddingRight)/context.dp(8)).coerceAtLeast(2)+1)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);rebuildTicks()}
    init {
        max=1000;setPadding(context.dp(12),0,context.dp(12),0)
        thumb=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        splitTrack=false;contentDescription="收听进度"
    }
    override fun onDraw(canvas:Canvas) {
        val palette=Palette(context.nightDark());val inset=paddingLeft.toFloat()
        val end=width-paddingRight.toFloat();val top=context.dp(5).toFloat();val bottom=height-context.dp(5).toFloat()
        val position=inset+(end-inset)*progress/max.toFloat()
        ink.color=palette.soft;canvas.drawRoundRect(inset,top,end,bottom,context.dp(16).toFloat(),context.dp(16).toFloat(),ink)
        canvas.save();canvas.clipRect(inset,top,position,bottom)
        ink.color=palette.accent;ink.alpha=35
        canvas.drawRoundRect(inset,top,end,bottom,context.dp(16).toFloat(),context.dp(16).toFloat(),ink);canvas.restore()
        val count=heights.size-1
        val base=bottom-context.dp(10)
        ink.strokeWidth=context.dp(3).toFloat();ink.strokeCap=Paint.Cap.ROUND
        for(i in 0..count) {
            val x=inset+context.dp(10)+(end-inset-context.dp(20))*i/count
            ink.color=if(x<=position)palette.accent else palette.muted;ink.alpha=if(x<=position)230 else 90
            canvas.drawLine(x,base-context.dp(heights[i]),x,base,ink)
        }
        ink.color=palette.text;ink.alpha=255;ink.strokeWidth=context.dp(3).toFloat()
        val marker=position.coerceIn(inset+context.dp(3),end-context.dp(3))
        canvas.drawLine(marker,top+context.dp(8),marker,bottom-context.dp(8),ink)
    }
}

/** SeekBar retains keyboard and accessibility actions; its integer progress snaps to ticks. */
internal open class ListeningRuler(context:Context,steps:Int,private val label:(Int)->String,private val major:(Int)->Boolean):SeekBar(context) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val feedback=TouchFeedback.Ticks()
    private var sendingFeedback=false
    var active=true
        set(value){field=value;invalidate()}
    var selectionVisible=true
        set(value){field=value;invalidate()}
    init {
        max=steps;setPadding(context.dp(16),0,context.dp(16),0)
        thumb=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);splitTrack=false
    }
    override fun onDraw(canvas:Canvas) {
        val palette=Palette(context.nightDark());val left=paddingLeft.toFloat();val right=width-paddingRight.toFloat()
        val base=height-context.dp(12).toFloat()
        ink.strokeCap=Paint.Cap.ROUND;ink.strokeWidth=context.dp(2).toFloat();ink.textSize=12*resources.displayMetrics.scaledDensity
        ink.textAlign=Paint.Align.CENTER
        for(i in 0..max) {
            val x=left+(right-left)*i/max;val chosen=i==progress && active && selectionVisible
            ink.color=if(chosen)palette.accent else palette.muted;ink.alpha=if(chosen)255 else if(active)130 else 75
            val tick=if(major(i))context.dp(23)else context.dp(13)
            canvas.drawLine(x,base-tick,x,base,ink)
            if(major(i)) {
                val value=label(i);val half=ink.measureText(value)/2
                canvas.drawText(value,x.coerceIn(half+context.dp(2),width-half-context.dp(2)),base-context.dp(34),ink)
            }
        }
        ink.alpha=255
    }
    override fun setOnSeekBarChangeListener(listener:OnSeekBarChangeListener?) {
        super.setOnSeekBarChangeListener(object:OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar){feedback.reset(bar.progress);listener?.onStartTrackingTouch(bar)}
            override fun onStopTrackingTouch(bar:SeekBar){listener?.onStopTrackingTouch(bar)}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){
                if(!fromUser)feedback.reset(value)
                listener?.onProgressChanged(bar,value,fromUser)
            }
        })
    }
    // Only the explicit path below may emit feedback; suppress native/OEM duplicates.
    override fun performHapticFeedback(feedbackConstant:Int):Boolean = sendingFeedback && super.performHapticFeedback(feedbackConstant)
    override fun performHapticFeedback(feedbackConstant:Int,flags:Int):Boolean = sendingFeedback && super.performHapticFeedback(feedbackConstant,flags)
    fun tickFeedback(){
        sendingFeedback=true
        try {feedback.tick(this,progress,progress==0 || progress==max || major(progress))}
        finally {sendingFeedback=false}
    }
}
