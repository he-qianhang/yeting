package cn.yeting.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Switch
import androidx.core.graphics.ColorUtils

class Palette(val dark:Boolean) {
    val bg=Color.parseColor(if(dark) "#161816" else "#F7F6F2")
    val surface=Color.parseColor(if(dark) "#222522" else "#FFFFFF")
    val text=Color.parseColor(if(dark) "#EEF0E9" else "#252B27")
    val muted=Color.parseColor(if(dark) "#A5AFA6" else "#657067")
    val line=Color.parseColor(if(dark) "#343B34" else "#E5E7DF")
    val soft=Color.parseColor(if(dark) "#2C332D" else "#EEEEE7")
    val art=Color.parseColor(if(dark) "#30342B" else "#EDE8DC")
    val accent=Color.parseColor("#F2C29E")
    val onAccent=Color.parseColor("#3B2D23")
    val error=Color.parseColor(if(dark) "#F0A99C" else "#A23424")
    fun values()=listOf(bg,surface,text,muted,line,soft,art,accent,onAccent,error)
}
fun Context.dp(value:Int)=(resources.displayMetrics.density*value).toInt()
fun Context.nightDark()=when(NightApp.store.prefs.getString("theme","system")){"dark"->true;"light"->false;else->resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}
internal class ThemeShape:GradientDrawable() {var outline:Int?=null}
fun shape(color:Int,radius:Float=16f,stroke:Int?=null,context:Context):GradientDrawable = ThemeShape().apply {
    setColor(color);cornerRadius=context.dp(radius.toInt()).toFloat()
    outline=stroke;if(stroke!=null)setStroke(context.dp(1),stroke)
}
fun View.ripple(color:Int,radius:Int=14) {
    val content=shape(color,radius.toFloat(),context=context)
    background=RippleDrawable(ColorStateList.valueOf(0x22aaaaaa),content,null)
}
class Glyph(context:Context,var symbol:String,var ink:Int):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var mark:android.graphics.drawable.Drawable?=null
    private var markSymbol=""
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val brand=symbol in listOf("brand","抖音","B站","YouTube")
        val size=context.dp(if(width>=context.dp(70) && (symbol=="wave" || brand))64 else if(brand)28 else if(width>=context.dp(70))30 else 22).toFloat();canvas.save();canvas.translate((width-size)/2,(height-size)/2);canvas.scale(size/24,size/24)
        if(brand){
            if(markSymbol!=symbol){mark=context.getDrawable(when(symbol){"抖音"->R.drawable.ic_platform_douyin;"B站"->R.drawable.ic_platform_bilibili;"YouTube"->R.drawable.ic_platform_youtube;else->R.drawable.ic_brand})?.mutate();markSymbol=symbol}
            mark?.apply{setTint(ink);setBounds(0,0,24,24);draw(canvas)};canvas.restore();return
        }
        paint.color=ink;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.7f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        fun line(x:Float,y:Float,a:Float,b:Float)=canvas.drawLine(x,y,a,b,paint)
        fun path(vararg points:Float) {val p=Path();p.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)p.lineTo(points[i],points[i+1]);canvas.drawPath(p,paint)}
        when(symbol) {
            "plus"->{line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
            "close"->{line(6f,6f,18f,18f);line(6f,18f,18f,6f)}
            "down"->path(6f,9f,12f,15f,18f,9f)
            "source"->{path(7f,6f,18f,6f,18f,17f);line(18f,6f,6f,18f)}
            "play"->{val p=Path();p.moveTo(9f,5f);p.lineTo(19f,12f);p.lineTo(9f,19f);p.close();canvas.drawPath(p,paint)}
            "pause"->{line(9f,5f,9f,19f);line(16f,5f,16f,19f)}
            "wave"->{listOf(8f,14f,22f,16f,10f).forEachIndexed{i,h->line(4f+i*4,12-h/2,4f+i*4,12+h/2)}}
            "moon"->{val p=Path();p.moveTo(18f,15f);p.cubicTo(9f,19f,5f,11f,9f,5f);p.cubicTo(-2f,10f,6f,25f,18f,15f);canvas.drawPath(p,paint)}
            "sun"->{canvas.drawCircle(12f,12f,4f,paint);for(i in 0..7){val a=i*Math.PI/4;line((12+7*kotlin.math.cos(a)).toFloat(),(12+7*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat())}}
            "queue"->{line(4f,6f,19f,6f);line(4f,12f,15f,12f);line(4f,18f,11f,18f);line(18f,13f,18f,20f);canvas.drawCircle(16f,20f,2f,paint)}
            "more"->{paint.style=Paint.Style.FILL;for(y in listOf(5f,12f,19f))canvas.drawCircle(12f,y,1.5f,paint)}
            "settings"->{for(y in listOf(6f,12f,18f))line(3f,y,21f,y);canvas.drawCircle(8f,6f,2f,paint);canvas.drawCircle(16f,12f,2f,paint);canvas.drawCircle(10f,18f,2f,paint)}
            "back"->{canvas.drawArc(RectF(4f,4f,21f,21f),-110f,290f,false,paint);path(3f,4f,3f,10f,9f,10f)}
            "forward"->{canvas.drawArc(RectF(3f,4f,20f,21f),-70f,-290f,false,paint);path(21f,4f,21f,10f,15f,10f)}
            "check"->path(5f,12f,10f,17f,20f,6f)
            "uncheck"->canvas.drawCircle(12f,12f,8f,paint)
            "previous"->{line(5f,6f,5f,18f);val p=Path();p.moveTo(18f,6f);p.lineTo(8f,12f);p.lineTo(18f,18f);p.close();canvas.drawPath(p,paint)}
            "next"->{line(19f,6f,19f,18f);val p=Path();p.moveTo(6f,6f);p.lineTo(16f,12f);p.lineTo(6f,18f);p.close();canvas.drawPath(p,paint)}
            "grip"->{line(7f,9f,17f,9f);line(7f,15f,17f,15f)}
        }
        canvas.restore()
    }
}

/** Capture color targets once; theme animation keeps the existing views and layout. */
internal fun View.themeTargets(from:Palette,to:Palette):List<(Float)->Unit> {
    val updates=mutableListOf<(Float)->Unit>()
    val old=from.values();val next=to.values()
    fun target(value:Int):Int=old.indexOf(value).let{if(it<0)value else next[it]}
    fun color(value:Int,set:(Int)->Unit,destination:Int=target(value)) {
        if(value!=destination)updates.add{fraction->set(ColorUtils.blendARGB(value,destination,fraction))}
    }
    fun drawable(d:Drawable?) {
        when(d){
            is ColorDrawable->color(d.color,{d.color=it})
            is ThemeShape->{
                d.color?.defaultColor?.let{color(it,{v->d.setColor(v)})}
                d.outline?.let{color(it,{v->d.setStroke(context.dp(1),v);d.outline=v})}
            }
            is GradientDrawable->d.color?.defaultColor?.let{color(it,{v->d.setColor(v)})}
            is LayerDrawable->for(i in 0 until d.numberOfLayers)drawable(d.getDrawable(i))
        }
    }
    fun visit(view:View) {
        if((view.tag as? String)?.startsWith("theme-")==true)return
        if(view is ThemeFrame)view.palette=to
        drawable(view.background)
        if(view is TextView){color(view.currentTextColor,{view.setTextColor(it)});color(view.currentHintTextColor,{view.setHintTextColor(it)})}
        if(view is Glyph){
            val destination=if(view.symbol=="brand")if(to.dark)to.accent else Color.parseColor("#A95D35")else target(view.ink)
            color(view.ink,{view.ink=it;view.invalidate()},destination)
        }
        if(view is SeekBar){
            view.progressTintList?.defaultColor?.let{color(it,{v->view.progressTintList=ColorStateList.valueOf(v)})}
            view.thumbTintList?.defaultColor?.let{color(it,{v->view.thumbTintList=ColorStateList.valueOf(v)})}
        }
        if(view is ListeningProgress || view is ListeningRuler)updates.add{view.invalidate()}
        if(view is android.widget.NumberPicker && android.os.Build.VERSION.SDK_INT>=29)color(view.textColor,{view.setTextColor(it)})
        if(view is Switch)updates.add{f->
            view.thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(to.accent,ColorUtils.blendARGB(from.muted,to.muted,f)))
            view.trackTintList=ColorStateList.valueOf(ColorUtils.blendARGB(from.line,to.line,f))
        }
        if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))
    }
    visit(this);return updates
}
fun clock(ms:Long):String {
    val s=ms.coerceAtLeast(0)/1000
    return if(s>=3600) "%d:%02d:%02d".format(s/3600,(s/60)%60,s%60) else "%02d:%02d".format(s/60,s%60)
}

/** Cached RecyclerView children must adopt the theme before their first visible frame. */
internal class ThemeFrame(context:Context):android.widget.FrameLayout(context) {
    var palette=Palette(context.nightDark())
    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        val current=Palette(context.nightDark())
        if(palette.dark!=current.dark)themeTargets(palette,current).forEach{it(1f)}
        palette=current
    }
}
