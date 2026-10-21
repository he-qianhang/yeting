package cn.yeting.app

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw

internal val listeningEase=PathInterpolator(.2f,0f,0f,1f)

/** One window owns its panels, so returning from details preserves the queue. */
internal class ListeningSheet(context:Context):Dialog(context) {
    private val host=FrameLayout(context)
    private val pages=mutableListOf<View>()
    private var closing=false
    private var dim:ValueAnimator?=null
    private var overlayPanel:View?=null
    private var overlayShade:View?=null
    private var overlayClosing=false
    private var returnFocus:View?=null
    private var parentAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    var onPageChanged:(()->Unit)?=null
    val currentPage:View? get()=overlayPanel ?: pages.lastOrNull()

    init {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes=attributes.apply{windowAnimations=0}
            WindowCompat.setDecorFitsSystemWindows(this,false)
        }
        host.addView(View(context).apply{setOnClickListener{dismiss()}},FrameLayout.LayoutParams(-1,-1))
        ViewCompat.setOnApplyWindowInsetsListener(host){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        setContentView(host)
        setOnKeyListener{_,key,event->
            if(key==android.view.KeyEvent.KEYCODE_BACK && event.action==android.view.KeyEvent.ACTION_UP){dismiss();true}else false
        }
    }
    fun page(panel:View,push:Boolean,overlay:Boolean=false) {
        if(closing)return
        if(overlay){showOverlay(panel);return}
        if(!push)pages.clear()
        pages.add(panel)
        display(panel,isShowing)
        if(!isShowing){
            show();window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            updateSystemBars()
            dimTo(.5f,260)
        }
    }
    private fun showOverlay(panel:View) {
        if(overlayPanel!=null)return
        val parent=pages.lastOrNull() ?: return
        returnFocus=host.findFocus()
        parentAccessibility=parent.importantForAccessibility
        parent.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val shade=View(context).apply{setBackgroundColor(Color.BLACK);alpha=0f;setOnClickListener{dismiss()}}
        overlayShade=shade;host.addView(shade,FrameLayout.LayoutParams(-1,-1))
        panel.isClickable=true;overlayPanel=panel
        host.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{leftMargin=context.dp(10);rightMargin=context.dp(10);bottomMargin=context.dp(6)})
        panel.doOnPreDraw{
            if(ValueAnimator.areAnimatorsEnabled()){
                shade.animate().alpha(.38f).setDuration(260).start()
                panel.translationY=panel.height.toFloat()
                panel.animate().translationY(0f).setDuration(280).setInterpolator(listeningEase).start()
            }else shade.alpha=.38f
            panel.isFocusableInTouchMode=true;panel.requestFocus()
            panel.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        }
        onPageChanged?.invoke()
    }
    private fun closeOverlay() {
        val panel=overlayPanel ?: return
        if(overlayClosing)return
        overlayClosing=true;panel.animate().cancel()
        fun finish(){
            host.removeView(panel);overlayShade?.let{host.removeView(it)}
            overlayPanel=null;overlayShade=null;overlayClosing=false
            pages.lastOrNull()?.importantForAccessibility=parentAccessibility
            returnFocus?.requestFocus();returnFocus?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
            returnFocus=null;onPageChanged?.invoke()
        }
        if(!ValueAnimator.areAnimatorsEnabled()){finish();return}
        overlayShade?.animate()?.alpha(0f)?.setDuration(190)?.start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(220).setInterpolator(listeningEase).withEndAction{finish()}.start()
    }
    fun updateSystemBars(){window?.let{WindowInsetsControllerCompat(it,host).apply{isAppearanceLightStatusBars=!context.nightDark();isAppearanceLightNavigationBars=!context.nightDark()}}}
    private fun display(panel:View,nested:Boolean,back:Boolean=false) {
        val previous=if(host.childCount>1)host.getChildAt(host.childCount-1)else null
        previous?.animate()?.cancel()
        while(host.childCount>1)host.removeViewAt(1)
        (panel.parent as? ViewGroup)?.removeView(panel)
        panel.isClickable=true
        panel.alpha=1f;panel.translationX=0f;panel.translationY=0f
        val params=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{leftMargin=context.dp(10);rightMargin=context.dp(10);bottomMargin=context.dp(6)}
        if(nested && previous!=null && previous!==panel && ValueAnimator.areAnimatorsEnabled())host.addView(previous,FrameLayout.LayoutParams(params))
        host.addView(panel,params)
        panel.doOnPreDraw {
            if(!ValueAnimator.areAnimatorsEnabled())return@doOnPreDraw
            panel.animate().cancel()
            if(nested){
                panel.translationX=panel.width.toFloat()*(if(back)-1 else 1)
                previous?.animate()?.translationX(panel.width.toFloat()*(if(back)1 else -1))?.setDuration(260)?.setInterpolator(listeningEase)?.start()
                panel.animate().translationX(0f).setDuration(260).setInterpolator(listeningEase).withEndAction{
                    if(previous!==currentPage){host.removeView(previous);previous?.translationX=0f}
                }.start()
            }else {
                panel.translationY=panel.height.toFloat()
                panel.animate().translationY(0f).setDuration(280).setInterpolator(listeningEase).start()
            }
        }
        ViewCompat.requestApplyInsets(host)
        onPageChanged?.invoke()
    }
    override fun dismiss() {
        if(closing)return
        if(overlayPanel!=null){closeOverlay();return}
        if(pages.size>1){pages.removeAt(pages.lastIndex);display(pages.last(),true,true);return}
        closeAll()
    }
    fun closeAll(immediate:Boolean=false) {
        if(closing && !immediate)return
        closing=true
        val panel=currentPage
        if(immediate || panel==null || !isShowing || !ValueAnimator.areAnimatorsEnabled()){
            panel?.animate()?.cancel();dim?.cancel();super.dismiss();return
        }
        panel.animate().cancel()
        dimTo(0f,190)
        panel.animate().translationY(panel.height.toFloat()).setDuration(190).setInterpolator(listeningEase)
            .withEndAction{super.dismiss()}.start()
    }
    private fun dimTo(target:Float,duration:Long) {
        dim?.cancel()
        if(!ValueAnimator.areAnimatorsEnabled()){window?.setDimAmount(target);return}
        dim=ValueAnimator.ofFloat(window?.attributes?.dimAmount ?: 0f,target).apply {
            this.duration=duration;interpolator=listeningEase
            addUpdateListener{window?.setDimAmount(it.animatedValue as Float)};start()
        }
    }
    fun themeTargets(from:Palette,to:Palette)=(pages+listOfNotNull(overlayPanel)).flatMap{it.themeTargets(from,to)}
}

/** The height constraint applies on the first measure, including very long text. */
internal class SheetPanel(context:Context,private val fraction:Float):LinearLayout(context) {
    init {orientation=VERTICAL}
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val available=MeasureSpec.getSize(heightMeasureSpec)
        val limit=(available*fraction).toInt()
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST))
    }
}
