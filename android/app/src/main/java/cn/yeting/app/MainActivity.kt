package cn.yeting.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.res.Configuration
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup

import android.view.MotionEvent
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.core.view.doOnPreDraw

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity:Activity() {
    private val store get()=NightApp.store
    private lateinit var colors:Palette
    private lateinit var root:LinearLayout
    private lateinit var host:FrameLayout
    private var libraryPage:LinearLayout?=null
    private var playerPage:LinearLayout?=null
    private var displayedPage:View?=null
    private var playerEntryId:String?=null
    private var libraryList:RecyclerView?=null
    private var libraryAdapter:LibraryAdapter?=null
    private var libraryCount:TextView?=null
    private var librarySelection:LinearLayout?=null
    private var librarySelecting=false
    private var selectionAnimation:ValueAnimator?=null
    private var miniContainer:LinearLayout?=null
    private var miniId:String?=null
    private var miniTitle:TextView?=null
    private val filterTabs=mutableMapOf<String,TextView>()
    private var themeAnimation:ValueAnimator?=null
    private var gestureActive=false
    private var refreshDeferred=false
    private val swipePaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler=Handler(Looper.getMainLooper())
    private var dialog:Dialog?=null
    private var screen="library"
    private var filter="all"
    private var selected:String?=null
    private var progress:SeekBar?=null
    private var elapsed:TextView?=null
    private var remaining:TextView?=null
    private var playGlyph:Glyph?=null
    private var sleepText:TextView?=null
    private var sleepGlyph:Glyph?=null
    private var speedText:TextView?=null
    private var miniDetail:TextView?=null
    private var miniGlyph:Glyph?=null
    private var scroller:ScrollView?=null
    private var dragging=false
    private var clipboardCheckPending=false
    private var metadataChecked=false
    private var modeDetail:TextView?=null
    private var playerTitle:TextView?=null
    private var playerAuthor:TextView?=null
    private var playerArt:Glyph?=null
    private var playerPlatform:TextView?=null
    private var playerSource:TextView?=null
    private var queueRefresh:(()->Unit)?=null
    private var speedRefresh:(()->Unit)?=null
    private var shownRecoveryNotice=""
    private val selectedIds=mutableSetOf<String>()
    private var selectionScope:String?=null
    private var pendingUndo:Library.DeletedBatch?=null
    private val finishDelete=Runnable {pendingUndo?.let{store.finishUndo(it)};pendingUndo=null;refreshUndoBars()}
    private val refresh=Runnable {if(!isFinishing){
        if(gestureActive || themeAnimation?.isRunning==true)refreshDeferred=true
        else {render();queueRefresh?.invoke()}
    }}
    private val libraryChanged:()->Unit={handler.removeCallbacks(refresh);handler.postDelayed(refresh,200)}
    private val tick=object:Runnable {
        override fun run(){updatePlayback();handler.postDelayed(this,500)}
    }
    private val service get()=PlaybackService.active
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        screen=state?.getString("screen") ?: "library";selected=state?.getString("selected") ?: store.prefs.getString("last",null)
        filter=state?.getString("filter") ?: "all"
        WindowCompat.setDecorFitsSystemWindows(window,false)
        store.observe(libraryChanged);PlaybackService.changed={runOnUiThread {
            speedRefresh?.invoke()
            val id=service?.currentId
            if(id!=null && id!=selected){selected=id;handler.post(refresh)}else updatePlayback()
            queueRefresh?.invoke()
        }}
        if(service==null)startService(Intent(this,PlaybackService::class.java))
        render()
        if(store.recoveryError) toast("收藏数据暂时无法读取，原文件已保留，请联系开发者")
    }
    override fun onResume(){super.onResume();handler.post(tick);render();clipboardCheckPending=true
        if(!metadataChecked && !store.recoveryError && store.all().any{!it.metadataReady} && getSystemService(ConnectivityManager::class.java).activeNetwork!=null){metadataChecked=true;startPreparation(null,true)}
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus && clipboardCheckPending){clipboardCheckPending=false;handler.postDelayed({checkClipboard()},600)}}
    override fun onPause(){handler.removeCallbacks(tick);super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);pendingUndo?.let{store.finishUndo(it)};store.unobserve(libraryChanged);PlaybackService.changed=null;themeAnimation?.cancel();(dialog as? ListeningSheet)?.closeAll(true);super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putString("screen",screen);out.putString("filter",filter);out.putString("selected",selected);super.onSaveInstanceState(out)}
    override fun onBackPressed(){if(selectedIds.isNotEmpty()){selectedIds.clear();selectionScope=null;render()}else if(screen=="player"){screen="library";render()}else super.onBackPressed()}
    override fun onConfigurationChanged(configuration:Configuration){
        super.onConfigurationChanged(configuration)
        if(store.prefs.getString("theme","system")=="system")changeAppearance()
    }
    private fun endGesture(){gestureActive=false;if(refreshDeferred){refreshDeferred=false;handler.post(refresh)}}
    private fun closePanels(){(dialog as? ListeningSheet)?.closeAll() ?: dialog?.dismiss()}
    private fun alertTheme()=if(isDark())R.style.NightDialog else R.style.DayDialog
    private fun isDark()=nightDark()
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun text(value:String,size:Float=15f,muted:Boolean=false,bold:Boolean=false)=TextView(this).apply {
        this.text=value;textSize=size;setTextColor(if(muted)colors.muted else colors.text)
        if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        includeFontPadding=false
    }
    private fun pad(view:View,l:Int,t:Int,r:Int,b:Int){view.setPadding(dp(l),dp(t),dp(r),dp(b))}
    private fun space(parent:LinearLayout,height:Int){parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height)))}
    private fun button(label:String,primary:Boolean=false,action:()->Unit):TextView=text(label,15f,bold=primary).apply{
        gravity=Gravity.CENTER;minHeight=dp(50);pad(this,14,10,14,10);ripple(if(primary)colors.accent else colors.soft)
        if(primary)setTextColor(colors.onAccent);setOnClickListener{action()}
    }
    private fun toggleSelected(id:String,scope:String,update:()->Unit) {
        if(selectionScope!=scope){selectedIds.clear();selectionScope=scope}
        if(!selectedIds.add(id))selectedIds.remove(id)
        if(selectedIds.isEmpty())selectionScope=null
        update()
    }
    private fun selectionToolbar(scope:String,ids:()->List<String>,update:()->Unit)=row().apply {
        pad(this,if(scope=="library")20 else 0,2,if(scope=="library")20 else 0,10)
        addView(text("${selectedIds.size} 条已选",13f,true).apply{tag="selection-count";maxLines=1},LinearLayout.LayoutParams(0,-2,1f))
        fun action(label:String,primary:Boolean=false,run:()->Unit){
            addView(button(label,primary,run).apply{textSize=13f;minHeight=dp(46);pad(this,10,8,10,8)},LinearLayout.LayoutParams(-2,dp(46)).apply{marginStart=dp(8)})
        }
        action("全选"){selectedIds.addAll(ids());selectionScope=scope;update()}
        action("删除",true){
            val chosen=selectedIds.toSet()
            if(chosen.isNotEmpty())AlertDialog.Builder(this@MainActivity,alertTheme()).setTitle("删除 ${chosen.size} 条收藏？")
                .setMessage("对应的音频也会删除。").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->selectedIds.clear();selectionScope=null;deleteEntries(chosen);update()}.show()
        }
        action("取消"){selectedIds.clear();selectionScope=null;update()}
    }
    private fun undoNotice()=row().apply {
        tag="undo-notice"
        background=shape(colors.soft,16f,context=this@MainActivity);pad(this,16,4,6,4)
        addView(text("已删除 ${pendingUndo?.rows?.size ?: 0} 条收藏",14f),LinearLayout.LayoutParams(0,-2,1f))
        addView(button("撤销",true){
            val batch=pendingUndo ?: return@button
            handler.removeCallbacks(finishDelete);pendingUndo=null;store.undo(batch)
            refreshUndoBars();service?.queueChanged();render();queueRefresh?.invoke()
        })
        visibility=if(pendingUndo==null)View.GONE else View.VISIBLE
    }
    private fun refreshUndoBars(){
        fun update(view:View?){view?.findViewWithTag<LinearLayout>("undo-notice")?.apply{
            visibility=if(pendingUndo==null)View.GONE else View.VISIBLE
            (getChildAt(0) as TextView).text="已删除 ${pendingUndo?.rows?.size ?: 0} 条收藏"
        }}
        if(::host.isInitialized)update(host)
        update((dialog as? ListeningSheet)?.currentPage)
    }
    private fun deleteEntries(ids:Set<String>) {
        if(ids.isEmpty())return
        handler.removeCallbacks(finishDelete);pendingUndo?.let{store.finishUndo(it)}
        ids.forEach{PreparationService.cancel(it);SourceDiagnostics(this).clear(it)}
        service?.currentId?.takeIf{it in ids}?.let{service?.detach(it)}
        pendingUndo=store.deleteWithUndo(ids)
        if(selected in ids){selected=null;screen="library"}
        selectedIds.removeAll(ids);if(selectedIds.isEmpty())selectionScope=null
        service?.queueChanged()
        render();refreshUndoBars();queueRefresh?.invoke()
        handler.postDelayed(finishDelete,5000)
    }
    private inner class SwipeRow(item:View,onDelete:()->Unit,onSelect:()->Unit):FrameLayout(this@MainActivity) {
        private val foreground=item
        private var startX=0f
        private var startY=0f
        private var tracking=false
        private var owningGesture=false
        private var thresholdFelt=false
        private var initialOffset=0f
        private var settle:ValueAnimator?=null
        init {
            setWillNotDraw(false);clipChildren=true
            background=shape(colors.bg,18f,context=this@MainActivity);clipToOutline=true
            foreground.clipToOutline=true
            addView(foreground,FrameLayout.LayoutParams(-1,-2))
        }
        override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{settle?.cancel();initialOffset=foreground.translationX;startX=event.rawX;startY=event.rawY;tracking=false;thresholdFelt=false}
                MotionEvent.ACTION_MOVE->{
                    val dx=event.rawX-startX;val dy=event.rawY-startY
                    if(kotlin.math.abs(dx)>dp(12) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.3f){
                        tracking=true;owningGesture=true;gestureActive=true;parent?.requestDisallowInterceptTouchEvent(true);return true
                    }
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(!tracking && foreground.translationX!=0f)finish()
            }
            return super.onInterceptTouchEvent(event)
        }
        override fun onDraw(canvas:Canvas){super.onDraw(canvas);drawSwipe(canvas,foreground.translationX,width,height)}
        private fun move(offset:Float){foreground.translationX=offset;foreground.alpha=if(offset>0)1f-.3f*(offset/dp(120)).coerceIn(0f,1f)else 1f;invalidate()}
        private fun finish(action:(()->Unit)?=null){
            tracking=false
            val start=foreground.translationX
            val destination=if(action===deleteAction)dp(120).toFloat()else 0f
            settle=ValueAnimator.ofFloat(start,destination).apply{
                duration=if(action===deleteAction)180 else 210;interpolator=listeningEase
                addUpdateListener{move(it.animatedValue as Float)}
                addListener(object:AnimatorListenerAdapter(){
                    private var cancelled=false
                    override fun onAnimationCancel(animation:Animator){cancelled=true}
                    override fun onAnimationEnd(animation:Animator){
                        if(!cancelled){
                            if(action===deleteAction){collapse();return}
                            action?.invoke();move(0f);owningGesture=false;endGesture()
                        }
                    }
                });start()
            }
        }
        private val deleteAction=onDelete
        private val selectAction=onSelect
        private fun collapse(){
            settle=ValueAnimator.ofInt(height,0).apply{
                duration=190;interpolator=listeningEase
                addUpdateListener{layoutParams=layoutParams.apply{height=it.animatedValue as Int}}
                addListener(object:AnimatorListenerAdapter(){
                    private var cancelled=false
                    override fun onAnimationCancel(animation:Animator){cancelled=true}
                    override fun onAnimationEnd(animation:Animator){if(!cancelled){deleteAction();owningGesture=false;endGesture()}}
                });start()
            }
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(!tracking)return super.onTouchEvent(event)
            when(event.actionMasked){
                MotionEvent.ACTION_MOVE->{
                    val offset=(initialOffset+event.rawX-startX).coerceIn(-dp(60).toFloat(),dp(120).toFloat());move(offset)
                    if(!thresholdFelt && (offset>dp(72) || offset< -dp(48))){thresholdFelt=true;TouchFeedback.threshold(this)}
                }
                MotionEvent.ACTION_UP->{val dx=foreground.translationX;finish(if(dx>dp(72))deleteAction else if(dx< -dp(48))selectAction else null)}
                MotionEvent.ACTION_CANCEL->finish()
            };return true
        }
        override fun onDetachedFromWindow(){settle?.cancel();move(0f);if(owningGesture){owningGesture=false;endGesture()};super.onDetachedFromWindow()}
    }
    private fun drawSwipe(canvas:Canvas,offset:Float,width:Int,height:Int,left:Int=0,top:Int=0) {
        if(kotlin.math.abs(offset)<1f)return
        val fraction=(kotlin.math.abs(offset)/dp(if(offset>0)72 else 48)).coerceIn(0f,1f)
        val paint=swipePaint
        canvas.save()
        paint.style=Paint.Style.FILL;paint.color=if(offset>0)colors.error else colors.soft;paint.alpha=(fraction*255).toInt()
        canvas.drawRoundRect(left.toFloat(),top.toFloat(),(left+width).toFloat(),(top+height).toFloat(),dp(18).toFloat(),dp(18).toFloat(),paint)
        paint.color=if(offset>0)if(colors.dark)colors.onAccent else Color.WHITE else colors.text
        paint.alpha=(((fraction-.15f)/.85f).coerceIn(0f,1f)*255).toInt()
        val x=if(offset>0)left+offset/2 else left+width+offset/2
        canvas.translate(x,top+height/2f);canvas.scale(dp(24)/24f,dp(24)/24f)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.8f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        if(offset>0){
            canvas.drawLine(-8f,-6f,8f,-6f,paint);canvas.drawLine(-3f,-9f,3f,-9f,paint)
            canvas.drawRoundRect(-6f,-6f,6f,9f,2f,2f,paint)
            canvas.drawLine(-2f,-2f,-2f,5f,paint);canvas.drawLine(2f,-2f,2f,5f,paint)
        }else {
            canvas.drawCircle(0f,0f,8f,paint)
            if(fraction>=1f){canvas.drawLine(-4f,0f,-1f,3f,paint);canvas.drawLine(-1f,3f,5f,-4f,paint)}
        }
        paint.style=Paint.Style.FILL;paint.alpha=255
        canvas.restore()
    }
    private fun icon(symbol:String,label:String,fill:Int?=null,size:Int=48,action:()->Unit):Glyph=Glyph(this,symbol,if(fill==colors.accent)colors.onAccent else colors.text).apply{
        contentDescription=label;isFocusable=true;layoutParams=LinearLayout.LayoutParams(dp(size),dp(size));ripple(fill ?: Color.TRANSPARENT,size/2);setOnClickListener{action()}
    }
    private fun header(title:String,left:View?=null,right:View?=null):LinearLayout=row().apply {
        pad(this,16,12,16,8)
        if(left==null && title=="夜听")addView(Glyph(this@MainActivity,"brand",if(isDark())colors.accent else Color.parseColor("#A95D35")),LinearLayout.LayoutParams(dp(36),dp(40)).apply{marginEnd=dp(8)})
        if(left!=null)addView(left)
        val titleView=text(title,if(left==null)25f else 14f,muted=left!=null,bold=left==null).apply{gravity=if(left==null)Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER}
        if(title=="夜听"){
            val name=row();name.addView(titleView)
            libraryCount=text("",12f,true).apply{visibility=if(resources.configuration.fontScale>1.2f)View.GONE else View.VISIBLE}
            name.addView(libraryCount,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            addView(name,LinearLayout.LayoutParams(0,dp(48),1f))
        }else addView(titleView,LinearLayout.LayoutParams(0,dp(48),1f))
        if(right!=null)addView(right)
    }
    private fun render() {
        if(!::host.isInitialized){
            colors=Palette(isDark());host=FrameLayout(this).apply{setBackgroundColor(colors.bg)};setContentView(host)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colors.bg))
            WindowInsetsControllerCompat(window,host).apply{isAppearanceLightStatusBars=!isDark();isAppearanceLightNavigationBars=!isDark()}
            ViewCompat.setOnApplyWindowInsetsListener(host){view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
            ViewCompat.requestApplyInsets(host)
        }
        if(screen=="player" && entry()==null)screen="library"
        if(screen=="library"){
            if(libraryPage==null){root=column().apply{setBackgroundColor(colors.bg)};libraryPage=root;host.addView(root,FrameLayout.LayoutParams(-1,-1));renderLibrary()}
            root=libraryPage!!;updateLibrary()
        }else {
            if(playerPage==null){
                progress=null;elapsed=null;remaining=null;playGlyph=null;sleepText=null;speedText=null;modeDetail=null
                root=column().apply{setBackgroundColor(colors.bg)};playerPage=root
                host.addView(root,FrameLayout.LayoutParams(-1,-1));renderPlayer()
            };root=playerPage!!
        }
        if(displayedPage!==root){
            val outgoing=displayedPage;val incoming=root;displayedPage=incoming
            listOfNotNull(libraryPage,playerPage).forEach{it.animate().cancel();it.alpha=1f;it.translationY=0f}
            incoming.visibility=View.VISIBLE
            if(outgoing!=null && ValueAnimator.areAnimatorsEnabled()){
                if(screen=="player"){
                    incoming.bringToFront()
                    incoming.doOnPreDraw{
                        if(displayedPage!==incoming)return@doOnPreDraw
                        incoming.translationY=incoming.height.toFloat()
                        outgoing.animate().translationY(-dp(18).toFloat()).setDuration(340).setInterpolator(listeningEase).start()
                        incoming.animate().translationY(0f).setDuration(340).setInterpolator(listeningEase).withEndAction{
                            if(displayedPage===incoming){outgoing.visibility=View.GONE;outgoing.translationY=0f}
                        }.start()
                    }
                }else {
                    outgoing.bringToFront()
                    outgoing.animate().translationY(outgoing.height.toFloat()).setDuration(290).setInterpolator(listeningEase).withEndAction{
                        if(displayedPage===incoming){outgoing.visibility=View.GONE;outgoing.translationY=0f;incoming.bringToFront()}
                    }.start()
                }
            }else {outgoing?.visibility=View.GONE;incoming.bringToFront()}
        }
        updatePlayback()
    }
    private data class LibraryCard(val key:String,val entry:Entry?=null,val selected:Boolean=false,val selecting:Boolean=false,val preparation:String?=null)
    private inner class LibraryHolder(val body:FrameLayout):RecyclerView.ViewHolder(body)
    private inner class LibraryAdapter:RecyclerView.Adapter<LibraryHolder>() {
        var items=listOf<LibraryCard>()
        private val ids=mutableMapOf<String,Long>()
        init {setHasStableIds(true)}
        override fun getItemId(position:Int)=ids.getOrPut(items[position].key){ids.size.toLong()}
        override fun getItemCount()=items.size
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=LibraryHolder(ThemeFrame(this@MainActivity).apply{layoutParams=RecyclerView.LayoutParams(-1,-2)})
        override fun onBindViewHolder(holder:LibraryHolder,position:Int){
            val card=items[position];val box=holder.body;box.removeAllViews()
            if(card.preparation!=null){
                val line=row().apply{pad(this,6,4,0,10);tag="preparation-row"}
                line.addView(text(card.preparation,13f,true),LinearLayout.LayoutParams(0,-2,1f))
                line.addView(button(if(PreparationService.active)"准备中"else "准备全部"){prepare()}.apply{
                    textSize=13f;minHeight=dp(44);isEnabled=!PreparationService.active;contentDescription="准备全部待准备收藏";ripple(Color.TRANSPARENT);setTextColor(colors.text)
                })
                box.addView(line)
            }else if(card.entry!=null){
                val e=card.entry
                box.addView(SwipeRow(entryRow(e),{deleteEntries(setOf(e.id))},{toggleSelected(e.id,"library"){render()}}).apply{tag=e.id})
            }else {
                val empty=column();space(empty,32)
                empty.addView(Glyph(this@MainActivity,"brand",colors.muted),LinearLayout.LayoutParams(dp(56),dp(56)).apply{gravity=Gravity.CENTER_HORIZONTAL})
                space(empty,18);empty.addView(text(if(store.all().isEmpty())"今晚想听什么？"else "这里还没有内容",20f,bold=true).apply{gravity=Gravity.CENTER})
                space(empty,9);empty.addView(text(if(store.all().isEmpty())"复制抖音、B站或YouTube链接，回来收好。"else if(filter=="done")"听完的内容，会留在这里。"else "新收藏会出现在这里。",13f,true).apply{gravity=Gravity.CENTER})
                if(store.all().isEmpty()){space(empty,24);empty.addView(button("添加第一条",true){addLink()})};box.addView(empty)
            }
        }
        fun update(next:List<LibraryCard>){
            if(items==next)return
            val before=items
            val diff=DiffUtil.calculateDiff(object:DiffUtil.Callback(){
                override fun getOldListSize()=before.size
                override fun getNewListSize()=next.size
                override fun areItemsTheSame(a:Int,b:Int)=before[a].key==next[b].key
                override fun areContentsTheSame(a:Int,b:Int)=before[a]==next[b]
            })
            val lm=libraryList?.layoutManager as? LinearLayoutManager
            val first=lm?.findFirstVisibleItemPosition() ?: -1
            val anchor=before.getOrNull(first)?.key
            val top=(lm?.findViewByPosition(first)?.top ?: 0)-(libraryList?.paddingTop ?: 0)
            items=next;diff.dispatchUpdatesTo(this)
            if(anchor!=null && before.firstOrNull()?.preparation!=next.firstOrNull()?.preparation){
                next.indexOfFirst{it.key==anchor}.takeIf{it>=0}?.let{lm?.scrollToPositionWithOffset(it,top)}
            }
        }
    }
    private fun renderLibrary() {
        val actions=row().apply{addView(icon("settings","设置"){settings()});addView(icon("plus","添加分享链接",colors.accent){addLink()},LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(8)})}
        root.addView(header("夜听",right=actions))
        val tabs=row();pad(tabs,20,14,20,12)
        val filters=row()
        listOf("all" to "全部","pending" to "待听","done" to "已听").forEach{(key,label)->
            val tab=text(label,14f).apply{gravity=Gravity.CENTER;minHeight=dp(46);pad(this,10,8,10,8);setOnClickListener{
                if(filter!=key){selectedIds.clear();selectionScope=null;filter=key;render();libraryList?.scrollToPosition(0)}
            }};filterTabs[key]=tab;filters.addView(tab,LinearLayout.LayoutParams(0,dp(46),1f).apply{marginEnd=dp(2)})
        };tabs.addView(filters,LinearLayout.LayoutParams(0,dp(46),1f))
        root.addView(tabs)
        librarySelection=selectionToolbar("library",{visibleEntries().map{it.id}}){render()}.apply{visibility=View.GONE};root.addView(librarySelection)
        libraryAdapter=LibraryAdapter()
        libraryList=RecyclerView(this).apply{
            layoutManager=LinearLayoutManager(this@MainActivity);adapter=libraryAdapter
            pad(this,20,4,20,18);clipToPadding=false
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations=false
            itemAnimator?.apply{addDuration=200;removeDuration=180;moveDuration=220}
        };root.addView(libraryList,LinearLayout.LayoutParams(-1,0,1f))
        miniContainer=column();root.addView(miniContainer)
        root.addView(undoNotice())
    }
    private fun visibleEntries()=store.all().filter{filter=="all" || (if(filter=="done")it.completed else !it.completed)}
    private fun updateLibrary(){
        val all=store.all()
        libraryCount?.text=if(all.isEmpty())""else "${all.size}"
        filterTabs.forEach{(key,tab)->tab.ripple(if(filter==key)colors.text else Color.TRANSPARENT,24);tab.setTextColor(if(filter==key)colors.bg else colors.muted)}
        librarySelection?.apply{
            if(librarySelecting!=(selectionScope=="library")){
                librarySelecting=selectionScope=="library";selectionAnimation?.cancel()
                val target=if(librarySelecting)dp(58)else 0
                val start=if(visibility==View.GONE)0 else height
                visibility=View.VISIBLE
                selectionAnimation=ValueAnimator.ofInt(start,target).apply{
                    duration=210;interpolator=listeningEase
                    addUpdateListener{layoutParams=layoutParams.apply{height=it.animatedValue as Int}}
                    addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){if(!librarySelecting)visibility=View.GONE}});start()
                }
            }
            findViewWithTag<TextView>("selection-count")?.text="${selectedIds.size} 条已选"
        }
        val cards=mutableListOf<LibraryCard>()
        val pending=all.count{it.state!=Readiness.READY}
        if(pending>0 || PreparationService.active)cards.add(LibraryCard("preparation",preparation=if(PreparationService.active)"正在准备 · $pending 条待完成"else "$pending 条待准备"))
        val entries=visibleEntries()
        if(entries.isEmpty())cards.add(LibraryCard("empty-$filter-${all.isEmpty()}"))
        entries.forEach{cards.add(LibraryCard(it.id,it,it.id in selectedIds,selectionScope=="library"))}
        libraryAdapter?.update(cards)
        val current=store.get(service?.currentId ?: store.prefs.getString("last",null))?.takeIf{it.state==Readiness.READY}
        if(current?.id!=miniId){
            miniContainer?.removeAllViews();miniDetail=null;miniGlyph=null;miniId=current?.id
            if(current!=null)miniPlayer(current)
        };miniTitle?.text=current?.title
        refreshUndoBars()
    }
    private fun entryRow(e:Entry):LinearLayout=row().apply {
        tag=e.id
        pad(this,18,12,0,12)
        minimumHeight=dp(84)
        val cover=Glyph(this@MainActivity,e.platform,colors.muted).apply{background=shape(colors.soft,14f,context=this@MainActivity);contentDescription=null}
        addView(cover,LinearLayout.LayoutParams(dp(50),dp(50)).apply{marginEnd=dp(14)})
        background=shape(if(selectionScope=="library" && e.id in selectedIds)colors.soft else colors.bg,18f,context=this@MainActivity)
        val content=column().apply{isFocusable=true;contentDescription="${e.title}，${e.message}";ripple(Color.TRANSPARENT,6);pad(this,0,5,3,5);setOnClickListener{if(selectionScope=="library")toggleSelected(e.id,"library"){render()}else openEntry(e)}}
        content.addView(text(e.title,15f,bold=true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;tag="entry-title"})
        space(content,7)
        val meta=row();meta.addView(text(e.author.ifEmpty{if(e.metadataReady)e.platform else "作者待补齐"},12f,true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;tag="entry-author"},LinearLayout.LayoutParams(0,-2,1f))
        meta.addView(text(when(e.state){Readiness.READY->if(e.completed)"已听完"else clock(e.durationMs);Readiness.WORKING->"准备中";Readiness.ERROR->if(e.message.startsWith("读取信息"))"信息失败"else "准备失败";else->"待准备"},11f,true).apply{pad(this,8,0,0,0);if(e.state==Readiness.ERROR)setTextColor(colors.error)})
        content.addView(meta)
        addView(content,LinearLayout.LayoutParams(0,-2,1f))
        addView(icon(if(selectionScope=="library")if(e.id in selectedIds)"check"else "uncheck"else "source",if(selectionScope=="library")"选择收藏"else "打开${e.platform}原视频"){if(selectionScope=="library")toggleSelected(e.id,"library"){render()}else openSource(e)})
        setOnClickListener{if(selectionScope=="library")toggleSelected(e.id,"library"){render()}else openEntry(e)}
        setOnLongClickListener{entryActions(e);true};content.setOnLongClickListener{entryActions(e);true}
    }
    private fun openEntry(e:Entry) {
        if(e.state==Readiness.READY){selected=e.id;screen="player";play(e.id);render()}else entryActions(e)
    }
    private fun miniPlayer(e:Entry) {
        val wrap=column();pad(wrap,14,10,14,12)
        val bar=row().apply{background=shape(colors.surface,18f,colors.line,this@MainActivity);pad(this,12,10,10,10)}
        bar.addView(Glyph(this,e.platform,colors.muted),LinearLayout.LayoutParams(dp(30),dp(32)))
        val body=column().apply{pad(this,10,0,8,0);setOnClickListener{selected=e.id;screen="player";render()};isFocusable=true;contentDescription="展开正在听"}
        miniTitle=text(e.title,14f,bold=true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END};body.addView(miniTitle)
        space(body,5);miniDetail=text("",11f,true);body.addView(miniDetail)
        bar.addView(body,LinearLayout.LayoutParams(0,-2,1f));miniGlyph=icon("play","播放或暂停",colors.text){toggle()}.apply{ink=colors.bg};bar.addView(miniGlyph)
        wrap.addView(bar);miniContainer?.addView(wrap)
    }
    private fun entry():Entry? {
        val id=if(screen=="player")selected ?: service?.currentId else service?.currentId ?: selected
        return store.get(id) ?: store.get(service?.currentId) ?: store.get(store.prefs.getString("last",null))
    }
    private fun renderPlayer() {
        val e=entry() ?: return
        root.addView(header("正在听",icon("down","收起播放器"){screen="library";render()},icon("more","当前条目选项"){entryActions(entry() ?: e)}))
        scroller=ScrollView(this).apply{isFillViewport=true}
        val content=column();pad(content,24,8,24,18)
        scroller!!.addView(content);root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        val art=column().apply{gravity=Gravity.CENTER;background=shape(colors.art,28f,context=this@MainActivity)}
        playerArt=Glyph(this,e.platform,colors.muted);art.addView(playerArt,LinearLayout.LayoutParams(dp(80),dp(70)))
        space(art,14);playerPlatform=text("${e.platform} · 原声",14f,true).apply{gravity=Gravity.CENTER};art.addView(playerPlatform)
        content.addView(art,LinearLayout.LayoutParams(dp(176),dp(176)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(12);bottomMargin=dp(20)})
        playerTitle=text(e.title,23f,bold=true).apply{maxLines=3;ellipsize=TextUtils.TruncateAt.END;contentDescription=e.title;setOnClickListener{sheet("完整标题",.5f){box,_->box.addView(text(entry()?.title ?: e.title,18f).apply{setLineSpacing(dp(6).toFloat(),1f);setTextIsSelectable(true)})}}};content.addView(playerTitle);space(content,9)
        playerAuthor=text(e.author.ifEmpty{e.platform},14f,true);content.addView(playerAuthor)
        playerSource=text("打开${e.platform}原视频 ↗",12f,true).apply{gravity=Gravity.CENTER_VERTICAL;minHeight=dp(48);setOnClickListener{openSource(entry() ?: e)};isFocusable=true}
        content.addView(playerSource)
        val times=row();elapsed=text("00:00",12f,true);remaining=text(clock(e.durationMs),12f,true).apply{gravity=Gravity.END}
        times.addView(elapsed,LinearLayout.LayoutParams(0,-2,1f));times.addView(remaining,LinearLayout.LayoutParams(0,-2,1f));content.addView(times)
        space(content,6)
        progress=ListeningProgress(this).apply{entryKey=e.id}
        content.addView(progress,LinearLayout.LayoutParams(-1,dp(72)))
        progress!!.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true}
            override fun onStopTrackingTouch(bar:SeekBar){service?.seek((entry()?.durationMs ?: 0)*bar.progress/1000);dragging=false}
            override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean){if(user){val duration=entry()?.durationMs ?: 0;elapsed?.text=clock(duration*value/1000);remaining?.text="−"+clock(duration-duration*value/1000)}}
        })
        val transport=row().apply{gravity=Gravity.CENTER;pad(this,0,20,0,20)}
        fun seekButton(symbol:String,label:String,delta:Long):LinearLayout=column().apply{gravity=Gravity.CENTER;contentDescription=label;isFocusable=true;ripple(Color.TRANSPARENT,20);setOnClickListener{service?.let{it.seek(it.player.currentPosition+delta)}};addView(Glyph(this@MainActivity,symbol,colors.muted),LinearLayout.LayoutParams(dp(30),dp(28)));space(this,4);addView(text("15 秒",11f,true).apply{gravity=Gravity.CENTER})}
        fun transportSlot(view:View)=transport.addView(row().apply{gravity=Gravity.CENTER;addView(view)},LinearLayout.LayoutParams(0,dp(72),1f))
        transportSlot(icon("previous","上一条"){service?.previous()})
        transportSlot(seekButton("back","后退15秒",-15000))
        playGlyph=icon("play","播放",colors.accent,72){toggle()};transport.addView(playGlyph,LinearLayout.LayoutParams(dp(72),dp(72)))
        transportSlot(seekButton("forward","前进15秒",15000))
        transportSlot(icon("next","下一条"){service?.next()});content.addView(transport)
        val tools=row().apply{gravity=Gravity.CENTER}
        val speed=column().apply{gravity=Gravity.CENTER;ripple(Color.TRANSPARENT);minimumHeight=dp(66);setOnClickListener{speedSheet()};contentDescription="倍速";isFocusable=true}
        speedText=text("1×",15f).apply{gravity=Gravity.CENTER};speed.addView(speedText);space(speed,6);speed.addView(text("倍速",12f,true).apply{gravity=Gravity.CENTER});tools.addView(speed,LinearLayout.LayoutParams(0,dp(70),1f))
        val sleep=column().apply{gravity=Gravity.CENTER;ripple(colors.soft);setOnClickListener{sleepSheet()};contentDescription="睡眠停止";isFocusable=true}
        sleepGlyph=Glyph(this,"sun",colors.text);sleep.addView(sleepGlyph,LinearLayout.LayoutParams(dp(25),dp(25)));space(sleep,5);sleepText=text("无限制播放",12f).apply{gravity=Gravity.CENTER};sleep.addView(sleepText);tools.addView(sleep,LinearLayout.LayoutParams(0,dp(70),1.65f))
        val queue=column().apply{gravity=Gravity.CENTER;ripple(Color.TRANSPARENT);setOnClickListener{queueSheet()};contentDescription="待听列表";isFocusable=true}
        queue.addView(Glyph(this,"queue",colors.muted),LinearLayout.LayoutParams(dp(25),dp(25)));space(queue,5);queue.addView(text("待听列表",12f,true).apply{gravity=Gravity.CENTER});tools.addView(queue,LinearLayout.LayoutParams(0,dp(70),1f));content.addView(tools)
        space(content,14);modeDetail=text("",12f,true).apply{gravity=Gravity.CENTER};content.addView(modeDetail)
    }
    private fun updatePlayback() {
        (dialog as? ListeningSheet)?.currentPage?.findViewWithTag<TextView>("steady-state")?.text=service?.steadyStatus ?: "等待播放器连接"
        speedRefresh?.invoke()
        service?.recoveryNotice?.takeIf{it.isNotEmpty() && it!=shownRecoveryNotice}?.let{shownRecoveryNotice=it;toast(it)}
        val s=service;val e=entry() ?: return
        if(playerEntryId!=e.id){playerEntryId=e.id;dragging=false}
        (progress as? ListeningProgress)?.entryKey=e.id
        if(playerTitle?.text?.toString()!=e.title)playerTitle?.apply{text=e.title;contentDescription=e.title}
        val author=e.author.ifEmpty{e.platform};if(playerAuthor?.text?.toString()!=author)playerAuthor?.text=author
        if(playerArt?.symbol!=e.platform)playerArt?.apply{symbol=e.platform;invalidate()}
        val sound=if(NightApp.steady.enabled)"平稳音量"else "原声"
        playerPlatform?.let{if(it.text.toString()!="${e.platform} · $sound")it.text="${e.platform} · $sound"}
        playerSource?.let{val label="打开${e.platform}原视频 ↗";if(it.text.toString()!=label)it.text=label;it.contentDescription="打开${e.platform}原视频"}
        val position=if(s?.currentId==e.id)s.player.currentPosition else e.positionMs
        val playing=s?.player?.isPlaying==true
        if(!dragging){progress?.progress=if(e.durationMs>0)(position*1000/e.durationMs).toInt().coerceIn(0,1000)else 0;elapsed?.text=clock(position)}
        if(!dragging)remaining?.text="−"+clock(e.durationMs-position)
        playGlyph?.apply{symbol=if(playing)"pause"else "play";contentDescription=if(playing)"暂停"else "播放";invalidate()}
        miniGlyph?.apply{symbol=if(playing)"pause"else "play";contentDescription=if(playing)"暂停"else "播放";invalidate()}
        miniDetail?.text="${if(playing)"正在播放"else "已暂停"} · ${clock(position)} / ${clock(e.durationMs)}"
        speedText?.text=PlaybackSpeed.label(s?.player?.playbackParameters?.speed ?: store.prefs.getFloat("speed",1f))
        sleepText?.text=sleepLabel()
        sleepGlyph?.apply{symbol=if(s?.policy?.sleep=="off" || s==null)"sun"else "moon";invalidate()}
        modeDetail?.text=modeLabel()+" · "+sleepLabel()
        if(s?.lastError?.isNotEmpty()==true) {elapsed?.text=s.lastError}
    }
    private fun sleepLabel():String {
        val p=service?.policy
        if(p?.finishingCurrent==true)return "本条结束后停止"
        return when(p?.sleep ?: "off") {"off"->"无限制播放";"minutes"->"${clock((p?.remainingSeconds(SystemClock.elapsedRealtime()) ?: 0)*1000)} 后${if(p?.finishCurrent==true)"播完停止"else "停止"}";"count"->"再听${p?.remainingTracks ?: 1}条后停止";else->"播完这条停止"}
    }
    private fun play(id:String) {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED && !store.prefs.getBoolean("notificationAsked",false)) {
            store.prefs.edit().putBoolean("notificationAsked",true).apply();requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),9)
        }
        startService(Intent(this,PlaybackService::class.java).setAction("cn.yeting.PLAY").putExtra("id",id))
    }
    private fun toggle() {if(service!=null){service!!.toggle();updatePlayback()}else (selected ?: store.prefs.getString("last",null))?.let{play(it)}}
    private fun openSource(e:Entry) {
        service?.pause();closePanels()
        try {SourceLinks.open(this,e)}catch(_:Exception){toast("没有找到能打开链接的应用")}
    }
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_SHORT).show()
    private fun sheet(title:String,maxFraction:Float=.88f,push:Boolean=false,overlay:Boolean=false,headerValue:View?=null,build:(LinearLayout,Dialog)->Unit) {
        val d=(dialog as? ListeningSheet) ?: ListeningSheet(this).also{sheet->
            dialog=sheet
            sheet.onPageChanged={refreshUndoBars()}
            sheet.setOnDismissListener{if(dialog===sheet){dialog=null;queueRefresh=null;speedRefresh=null};handler.post(refresh)}
        }
        if(!push){queueRefresh=null;speedRefresh=null}
        val panel=SheetPanel(this,maxFraction).apply{background=shape(colors.surface,26f,context=this@MainActivity)}
        val handle=View(this).apply{background=shape(colors.line,3f,context=this@MainActivity)};panel.addView(handle,LinearLayout.LayoutParams(dp(34),dp(4)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(12);bottomMargin=dp(8)})
        val top=row().apply{gravity=Gravity.CENTER_VERTICAL;pad(this,22,0,22,0)};top.addView(text(title,21f,bold=true).apply{gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(52),1f));if(headerValue!=null)top.addView(headerValue,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(12)});top.addView(icon("close","关闭面板"){d.dismiss()});panel.addView(top)
        val content=column();pad(content,22,8,22,24);build(content,d)
        val scroll=ScrollView(this).apply{addView(content);isFillViewport=false;isVerticalScrollBarEnabled=true}
        panel.addView(scroll,LinearLayout.LayoutParams(-1,-2,1f))
        d.page(panel,push,overlay)
    }
    private fun input(hint:String,multiline:Boolean=false)=EditText(this).apply{this.hint=hint;textSize=16f;setTextColor(colors.text);setHintTextColor(colors.muted);background=shape(colors.bg,14f,colors.line,this@MainActivity);pad(this,14,12,14,12);minHeight=dp(if(multiline)120 else 52);gravity=Gravity.TOP;inputType=if(multiline)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT;maxLines=if(multiline)5 else 1}
    private fun addLink()=sheet("添加到夜听"){box,d->
        box.addView(text("粘贴整段分享文字，或视频链接",13f,true));space(box,10)
        val input=input("整段分享文字也可以",true);box.addView(input)
        val error=text("",13f).apply{setTextColor(colors.error);visibility=View.GONE};box.addView(error)
        space(box,12);box.addView(button("粘贴分享链接"){
            val clip=getSystemService(ClipboardManager::class.java).primaryClip
            if(clip!=null && clip.itemCount>0)input.setText(clip.getItemAt(0).coerceToText(this))else toast("剪贴板里还没有分享文字")
        });space(box,10)
        box.addView(button("加入收藏",true){
            try {
                collect(SharedLink.from(input.text.toString()),d)
            }catch(e:Exception){error.text=e.message;error.visibility=View.VISIBLE}
        })
    }
    private fun collect(link:SharedLink,d:Dialog?=null) {
        val(entry,added)=store.add(link);d?.dismiss();filter="all";screen="library";render();toast(if(added)"已经收好了"else "这条已经收好了")
        if(added)prepare(entry.id)else{selected=entry.id;libraryAdapter?.items?.indexOfFirst{it.key==entry.id}?.takeIf{it>=0}?.let{libraryList?.smoothScrollToPosition(it)}}
    }
    private fun checkClipboard() {
        if(isFinishing || dialog!=null || !hasWindowFocus() || !store.prefs.getBoolean("autoClipboard",true))return
        val clip=runCatching{getSystemService(ClipboardManager::class.java).primaryClip}.getOrNull() ?: return
        if(clip.itemCount==0)return
        val raw=clip.getItemAt(0).text?.toString() ?: return
        val link=runCatching{SharedLink.from(raw)}.getOrNull() ?: return
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest((link.url+"\n"+raw.trim()).toByteArray()).joinToString(""){"%02x".format(it)}
        if(store.prefs.getString("lastClipboard",null)==fingerprint)return
        store.prefs.edit().putString("lastClipboard",fingerprint).apply()
        if(store.find(link.url)!=null)return
        sheet("发现一条${link.platform}链接"){box,d->
            box.addView(text("要加入今晚的待听吗？",15f));space(box,14)
            box.addView(text(link.title,14f,true).apply{maxLines=3;ellipsize=TextUtils.TruncateAt.END});space(box,8)
            box.addView(text(link.url,12f,true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.MIDDLE});space(box,18)
            box.addView(button("加入待听",true){runCatching{collect(link,d)}.onFailure{toast(it.message ?: "暂时无法添加")}});space(box,8)
            box.addView(button("暂不添加"){d.dismiss()})
        }
    }
    private fun sourceConnection(platform:String) {
        service?.pause();closePanels();startActivity(Intent(this,SourceSessionActivity::class.java).putExtra("platform",platform))
    }
    private fun startPreparation(id:String?,metadataOnly:Boolean=false) {
        val intent=Intent(this,PreparationService::class.java).putExtra("metadataOnly",metadataOnly)
        if(id!=null)intent.putExtra("id",id)
        startForegroundService(intent)
    }
    private fun prepare(id:String?=null) {
        fun start(){startPreparation(id);closePanels();toast("正在手机上准备，完成后可以离线听")}
        val cm=getSystemService(ConnectivityManager::class.java);val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        if(caps==null){toast("收藏已保留，联网后再准备就好");return}
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !store.prefs.getBoolean("mobilePreparation",false))AlertDialog.Builder(this,alertTheme()).setTitle("使用流量准备音频？").setMessage("部分来源需要先获取完整视频再提取原声，会消耗更多流量。").setNegativeButton("先只收藏"){_,_->startPreparation(id,true)}.setPositiveButton("允许并记住"){_,_->store.prefs.edit().putBoolean("mobilePreparation",true).apply();start()}.show()else start()
    }
    private fun choice(box:LinearLayout,label:String,detail:String="",checked:Boolean=false,action:()->Unit) {
        val item=row().apply{ripple(colors.bg,16);pad(this,14,14,14,14);minimumHeight=dp(56);isFocusable=true;contentDescription=label;setOnClickListener{action()}}
        val body=column();body.addView(text(label,15f));if(detail.isNotEmpty()){space(body,5);body.addView(text(detail,12f,true))}
        item.addView(body,LinearLayout.LayoutParams(0,-2,1f));if(checked)item.addView(Glyph(this,"check",colors.text),LinearLayout.LayoutParams(dp(30),dp(30)))
        box.addView(item,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
    }
    private fun entryActions(e:Entry,fromQueue:Boolean=false) {
        sheet("详细信息",.5f,push=fromQueue){box,d->
        val info=column().apply{background=shape(colors.bg,18f,context=this@MainActivity);pad(this,16,16,16,16)}
        info.addView(text(e.title,18f,bold=true).apply{setLineSpacing(dp(4).toFloat(),1f);setTextIsSelectable(true)});space(info,12)
        info.addView(text("${e.author.ifEmpty{"作者待补齐"}} · ${e.platform}",13f,true));space(info,9)
        info.addView(text("${clock(e.durationMs)} · 听到 ${clock(e.positionMs)}",12f,true));space(info,9)
        info.addView(text(e.message,12f,true));box.addView(info);space(box,16)
        choice(box,"打开${e.platform}原视频"){openSource(e)}
        if(e.state==Readiness.WORKING)choice(box,"取消准备"){PreparationService.cancel(e.id);d.dismiss()}
        if(e.platform!="YouTube" && (e.state==Readiness.ERROR || !e.metadataReady))choice(box,"连接${e.platform}","需要时在这部手机上登录或验证"){sourceConnection(e.platform)}
        if(e.state!=Readiness.WORKING && e.state!=Readiness.READY)choice(box,if(e.state==Readiness.ERROR)"重试准备"else "准备这条音频","在手机上获取原声音频"){prepare(e.id)}
        SourceDiagnostics(this).report(e.id)?.let{report->choice(box,"复制诊断信息","本次准备记录，不包含账号或登录信息"){
            getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("夜听诊断信息",report));toast("诊断信息已复制")
        }}
        choice(box,"删除收藏","同时删除手机上的音频"){
            AlertDialog.Builder(this,alertTheme()).setTitle("删除这条收藏？").setMessage("音频也会删除。").setNegativeButton("保留",null).setPositiveButton("删除"){_,_->d.dismiss();deleteEntries(setOf(e.id))}.show()
        }
        }
    }
    private fun speedSheet(){
        var boundId=service?.currentId
        var baseline=PlaybackSpeed.normalize(store.prefs.getFloat("speed",1f))
        var editedGlobal=false
        var rebinding=false
        val speed=PlaybackSpeed.normalize(service?.player?.playbackParameters?.speed ?: store.prefs.getFloat("speed",1f))
        val value=text(PlaybackSpeed.label(speed),22f,bold=true).apply{setTextColor(colors.accent);gravity=Gravity.END;tag="speed-value"}
        sheet("播放速度",headerValue=value){box,_->
            val ruler=ListeningRuler(this,15,{PlaybackSpeed.label(.5f+it/10f)},{it%5==0}).apply{
                progress=(speed*10).toInt()-5;contentDescription="播放速度刻度"
            }
            box.addView(ruler,LinearLayout.LayoutParams(-1,dp(76)))
            val scope=Switch(this).apply{
                text="仅应用到当前条目";textSize=14f;setTextColor(colors.text);minHeight=dp(52);tag="speed-scope"
                background=shape(colors.bg,16f,context=this@MainActivity);pad(this,14,4,14,4);buttonTint(this)
            }
            val global=text("",12f,true).apply{tag="speed-global"}
            fun sync(){
                val s=service
                if(boundId!=s?.currentId){boundId=s?.currentId;baseline=PlaybackSpeed.normalize(store.prefs.getFloat("speed",1f));editedGlobal=false}
                rebinding=true
                val rate=PlaybackSpeed.normalize(s?.player?.playbackParameters?.speed ?: store.prefs.getFloat("speed",1f))
                ruler.progress=(rate*10).toInt()-5;scope.isChecked=s?.speedForCurrent==true;scope.isEnabled=s?.currentId!=null
                value.text=PlaybackSpeed.label(rate);ViewCompat.setStateDescription(ruler,value.text)
                global.text="后续条目 · "+PlaybackSpeed.label(store.prefs.getFloat("speed",1f))
                rebinding=false
            }
            ruler.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(bar:SeekBar){}
                override fun onStopTrackingTouch(bar:SeekBar){}
                override fun onProgressChanged(bar:SeekBar,index:Int,user:Boolean){if(user && !rebinding){
                    val next=(index+5)/10f
                    if(service?.speedForCurrent!=true)editedGlobal=true
                    service?.speed(next) ?: store.prefs.edit().putFloat("speed",next).apply()
                    value.text=PlaybackSpeed.label(next);ViewCompat.setStateDescription(ruler,value.text);ruler.tickFeedback();updatePlayback()
                }}
            })
            ViewCompat.setStateDescription(ruler,value.text)
            space(box,10)
            scope.setOnCheckedChangeListener{_,checked->if(!rebinding)service?.let{
                if(checked && editedGlobal)store.prefs.edit().putFloat("speed",baseline).apply()
                editedGlobal=false
                it.speed(it.player.playbackParameters.speed,checked)
                if(!checked)baseline=store.prefs.getFloat("speed",1f)
                sync()
            }}
            box.addView(scope);space(box,8);box.addView(global)
            speedRefresh={sync()};sync()
        }
    }
    private fun sleepSheet()=sheet("睡眠停止"){box,d->
        val policy=service?.policy
        var mode=when(policy?.sleep){"minutes"->"minutes";"end","count"->"count";else->"off"}
        var count=policy?.remainingTracks?.coerceAtLeast(1) ?: 1
        var minutes=if(policy?.sleep=="minutes")((policy.remainingSeconds(SystemClock.elapsedRealtime())+59)/60).toInt().coerceAtLeast(1)else 30
        val status=text("",15f,bold=true).apply{tag="sleep-draft"};box.addView(status);space(box,16)
        fun group()=column().apply{background=shape(colors.bg,18f,context=this@MainActivity);pad(this,12,12,12,4)}
        val countBox=group();val countLabel=text("",14f);countBox.addView(countLabel)
        val tracks=ListeningRuler(this,9,{"${it+1}"},{it in listOf(0,4,9)}).apply{contentDescription="播放条数刻度";progress=(count-1).coerceIn(0,9)}
        countBox.addView(tracks,LinearLayout.LayoutParams(-1,dp(76)));box.addView(countBox);space(box,10)
        val timeBox=group();val minuteLabel=text("",14f);timeBox.addView(minuteLabel)
        val timer=ListeningRuler(this,23,{"${(it+1)*5}"},{it in listOf(0,5,11,17,23)}).apply{contentDescription="停止分钟刻度";progress=(minutes/5-1).coerceIn(0,23)}
        timeBox.addView(timer,LinearLayout.LayoutParams(-1,dp(76)));box.addView(timeBox);space(box,8)
        val finish=Switch(this).apply{
            text="到时播完当前条";textSize=14f;setTextColor(colors.text);minHeight=dp(48)
            isChecked=policy?.finishCurrent==true;buttonTint(this)
        };box.addView(finish)
        val unlimited=button("无限制"){mode="off";finish.isChecked=false}
        fun update(){
            status.text=when(mode){"off"->"无限制播放";"minutes"->"$minutes 分钟后${if(finish.isChecked)"播完当前条停止"else "停止"}";else->"播完 $count 条停止"}
            countLabel.text="播放条数 · $count 条";minuteLabel.text="倒计时 · $minutes 分钟"
            tracks.active=mode=="count";timer.active=mode=="minutes";finish.isEnabled=mode=="minutes";finish.alpha=if(finish.isEnabled)1f else .45f
            tracks.selectionVisible=count in 1..10;timer.selectionVisible=minutes in 5..120 && minutes%5==0
            unlimited.ripple(if(mode=="off")colors.accent else colors.soft);unlimited.setTextColor(if(mode=="off")colors.onAccent else colors.text)
            ViewCompat.setStateDescription(tracks,"$count 条");ViewCompat.setStateDescription(timer,"$minutes 分钟")
        }
        unlimited.setOnClickListener{mode="off";finish.isChecked=false;update()}
        fun watch(ruler:ListeningRuler,change:(Int)->Unit){ruler.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){change(bar.progress);update()}
            override fun onStopTrackingTouch(bar:SeekBar){}
            override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean){if(user){change(value);ruler.tickFeedback();update()}}
        })}
        watch(tracks){mode="count";count=it+1};watch(timer){mode="minutes";minutes=(it+1)*5}
        finish.setOnCheckedChangeListener{_,_->update()}
        val shortcuts=row()
        fun shortcut(label:String,action:()->Unit){
            val control=button(label,action=action).apply{textSize=13f;pad(this,6,10,6,10);isFocusable=true;contentDescription=label}
            control.setOnClickListener{control.requestFocusFromTouch();action()}
            shortcuts.addView(control,LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(6)})
        }
        shortcut("自定义条数"){numberDialog("自定义条数","条",99,count){count=it;mode="count";tracks.progress=(count-1).coerceIn(0,9);update()}}
        shortcut("自定义分钟数"){numberDialog("自定义分钟数","分钟",240,minutes){minutes=it;mode="minutes";timer.progress=(minutes/5-1).coerceIn(0,23);update()}}
        space(box,8);box.addView(shortcuts);space(box,8);box.addView(unlimited);space(box,8)
        box.addView(text("当前条算第 1 条；无限制时按播放方式继续。",12f,true));space(box,14)
        box.addView(button("应用",true){
            service?.let{it.continuous(true);when(mode){"off"->it.sleep("off");"minutes"->it.sleep("minutes",minutes,finish.isChecked);else->it.sleep(if(count==1)"end"else "count",count)}}
            (d as? ListeningSheet)?.closeAll();updatePlayback()
        })
        update()
    }
    private fun numberDialog(title:String,unit:String,max:Int,initial:Int,submit:(Int)->Unit) {
        sheet(title,maxFraction=.58f,push=true,overlay=true){box,d->
            val picker=ListeningNumberPicker(this).apply{
                minValue=1;maxValue=max;value=initial.coerceIn(1,max);wrapSelectorWheel=false
                descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS;contentDescription=title+"选择器"
                if(Build.VERSION.SDK_INT>=29){setTextColor(colors.text);setTextSize(24*resources.displayMetrics.scaledDensity)}
            }
            val selection=row().apply{gravity=Gravity.CENTER;background=shape(colors.bg,18f,context=this@MainActivity)}
            selection.addView(picker,LinearLayout.LayoutParams(dp(140),dp(180)))
            selection.addView(text(unit,17f,true),LinearLayout.LayoutParams(-2,-2))
            box.addView(selection);space(box,16)
            box.addView(button("确定",true){submit(picker.value);d.dismiss()})
        }
    }
    private fun modeLabel()=when(service?.mode ?: store.prefs.getString("playMode","queue")){"repeat"->"单首循环";"shuffle"->"随机播放";else->"列表循环"}
    private fun queueSheet(firstPosition:Int=0,topOffset:Int=0):Unit=sheet("待听列表"){box,d->
        var shuffled=service?.mode=="shuffle"
        val modes=row()
        val modeButtons=mutableMapOf<String,TextView>()
        fun updateModes(){modeButtons.forEach{(key,view)->val chosen=(service?.mode ?: store.prefs.getString("playMode","queue"))==key;view.ripple(if(chosen)colors.accent else colors.bg);view.setTextColor(if(chosen)colors.onAccent else colors.text)}}
        listOf("queue" to "列表循环","repeat" to "单首循环","shuffle" to "随机播放").forEach{(key,label)->
            val button=button(label){shuffled=key=="shuffle";service?.playbackMode(key);updateModes()}.apply{textSize=13f;pad(this,4,10,4,10)}
            modeButtons[key]=button;modes.addView(button,LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(4)})
        };updateModes()
        box.addView(modes);space(box,12);box.addView(text(sleepLabel(),12f,true).apply{tag="queue-sleep"});space(box,10)
        box.addView(undoNotice())
        val items=store.queue(shuffled).toMutableList()
        var refreshSelection:()->Unit={}
        val selectionBar=selectionToolbar("queue",{store.queue(shuffled).map{it.id}}){refreshSelection()}
        selectionBar.visibility=if(selectionScope=="queue")View.VISIBLE else View.GONE
        box.addView(selectionBar)
        var dragCancelled=false
        val recycler=RecyclerView(this).apply{layoutManager=LinearLayoutManager(this@MainActivity);(itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations=false;isNestedScrollingEnabled=false;setOnTouchListener{v,event->if(event.actionMasked==MotionEvent.ACTION_CANCEL)dragCancelled=true;v.parent?.requestDisallowInterceptTouchEvent(true);false}}
        var moving=false;var moved=false
        class Holder(val body:FrameLayout):RecyclerView.ViewHolder(body)
        lateinit var touch:ItemTouchHelper
        val queueIds=mutableMapOf<String,Long>()
        val adapter=object:RecyclerView.Adapter<Holder>(){
            init {setHasStableIds(true)}
            override fun getItemId(position:Int)=queueIds.getOrPut(items[position].id){queueIds.size.toLong()}
            override fun getItemCount()=items.size
            override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Holder(ThemeFrame(this@MainActivity).apply{layoutParams=RecyclerView.LayoutParams(-1,-2)})
            override fun onBindViewHolder(holder:Holder,position:Int){
                val e=store.get(items[position].id) ?: items[position]
                val wrapper=holder.body;wrapper.removeAllViews()
                val r=row();r.minimumHeight=dp(72);r.tag="queue-${e.id}";pad(r,18,10,0,10)
                r.background=shape(if(selectionScope=="queue" && e.id in selectedIds)colors.soft else colors.surface,18f,context=this@MainActivity)
                r.addView(Glyph(this@MainActivity,e.platform,colors.muted).apply{background=shape(colors.soft,12f,context=this@MainActivity)},LinearLayout.LayoutParams(dp(38),dp(42)).apply{marginEnd=dp(10)})
                val body=column().apply{ripple(Color.TRANSPARENT);isFocusable=true;contentDescription="队列：${e.title}";setOnClickListener{if(selectionScope=="queue")toggleSelected(e.id,"queue"){refreshSelection()}else{d.dismiss();openEntry(e)}};setOnLongClickListener{
                    entryActions(e,true);true
                }}
                body.addView(text(e.title,14f,bold=true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END});space(body,6)
                body.addView(text(e.author.ifEmpty{e.platform}+(if(e.state!=Readiness.READY)" · 待准备"else ""),12f,true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END})
                r.addView(body,LinearLayout.LayoutParams(0,-2,1f))
                if(selectionScope=="queue" || service?.currentId==e.id)r.addView(Glyph(this@MainActivity,if(selectionScope=="queue" && e.id !in selectedIds)"uncheck"else "check",colors.text),LinearLayout.LayoutParams(dp(24),dp(40)))
                if(selectionScope!="queue")r.addView(Glyph(this@MainActivity,"grip",colors.muted).apply{contentDescription="拖动排序：${e.title}";setOnTouchListener{_,event->if(event.action==MotionEvent.ACTION_DOWN){holder.itemView.isHapticFeedbackEnabled=store.prefs.getBoolean("haptics",true);touch.startDrag(holder);true}else false}},LinearLayout.LayoutParams(dp(48),dp(48)))
                wrapper.addView(SwipeRow(r,{deleteEntries(setOf(e.id))},{toggleSelected(e.id,"queue"){refreshSelection()}}))
            }
        }
        recycler.adapter=adapter
        var queueCurrent=service?.currentId
        refreshSelection={selectionBar.visibility=if(selectionScope=="queue")View.VISIBLE else View.GONE
            (selectionBar.findViewWithTag<TextView>("selection-count"))?.text="${selectedIds.size} 条已选"
            adapter.notifyItemRangeChanged(0,items.size)}
        touch=ItemTouchHelper(object:ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN,0){
            override fun isLongPressDragEnabled()=false
            override fun onMove(rv:RecyclerView,from:RecyclerView.ViewHolder,to:RecyclerView.ViewHolder):Boolean{val a=from.bindingAdapterPosition;val b=to.bindingAdapterPosition;if(a !in items.indices || b !in items.indices || a==b)return false;items.add(b,items.removeAt(a));adapter.notifyItemMoved(a,b);moved=true;return true}
            override fun onSwiped(holder:RecyclerView.ViewHolder,direction:Int){}
            override fun onSelectedChanged(holder:RecyclerView.ViewHolder?,state:Int){if(state!=ItemTouchHelper.ACTION_STATE_IDLE){moving=true;gestureActive=true;dragCancelled=false};super.onSelectedChanged(holder,state)}
            override fun clearView(rv:RecyclerView,holder:RecyclerView.ViewHolder){super.clearView(rv,holder);if(moved){store.reorderQueue(items.map{it.id},shuffled);service?.queueChanged();if(!dragCancelled)TouchFeedback.drop(holder.itemView);moved=false};moving=false;endGesture()}
        });touch.attachToRecyclerView(recycler)
        box.addView(recycler,LinearLayout.LayoutParams(-1,minOf(dp(76)*items.size,(resources.displayMetrics.heightPixels*.46).toInt()).coerceAtLeast(dp(72))))
        recycler.post{(recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(firstPosition,topOffset)}
        queueRefresh={if(moving || gestureActive)refreshDeferred=true else recycler.post{if(dialog===d && !gestureActive){val fresh=store.queue(shuffled)
            selectionBar.visibility=if(selectionScope=="queue")View.VISIBLE else View.GONE
            selectionBar.findViewWithTag<TextView>("selection-count")?.text="${selectedIds.size} 条已选"
            val current=service?.currentId
            if(current!=queueCurrent){listOf(queueCurrent,current).forEach{id->items.indexOfFirst{it.id==id}.takeIf{it>=0}?.let{adapter.notifyItemChanged(it)}};queueCurrent=current}
            if(items!=fresh){
                val before=items.toList()
                val diff=DiffUtil.calculateDiff(object:DiffUtil.Callback(){
                    override fun getOldListSize()=before.size
                    override fun getNewListSize()=fresh.size
                    override fun areItemsTheSame(a:Int,b:Int)=before[a].id==fresh[b].id
                    override fun areContentsTheSame(a:Int,b:Int)=before[a]==fresh[b]
                });items.clear();items.addAll(fresh);diff.dispatchUpdatesTo(adapter)
            }}}}
    }
    private fun settings():Unit=sheet("设置"){box,d->
        val switches=column().apply{background=shape(colors.bg,18f,context=this@MainActivity);pad(this,14,4,14,4)}
        val clipboard=Switch(this).apply{text="自动识别剪贴板";textSize=15f;setTextColor(colors.text);minHeight=dp(58);isChecked=store.prefs.getBoolean("autoClipboard",true);buttonTint(this);setOnCheckedChangeListener{_,checked->store.prefs.edit().putBoolean("autoClipboard",checked).apply()}};switches.addView(clipboard)
        val mobile=Switch(this).apply{text="允许用流量准备";textSize=15f;setTextColor(colors.text);minHeight=dp(58);isChecked=store.prefs.getBoolean("mobilePreparation",false);buttonTint(this);setOnCheckedChangeListener{_,checked->store.prefs.edit().putBoolean("mobilePreparation",checked).apply()}};switches.addView(mobile)
        val haptics=Switch(this).apply{text="触感反馈";textSize=15f;setTextColor(colors.text);minHeight=dp(58);isChecked=store.prefs.getBoolean("haptics",true);buttonTint(this);setOnCheckedChangeListener{_,checked->store.prefs.edit().putBoolean("haptics",checked).apply()}};switches.addView(haptics)
        box.addView(switches);space(box,20)
        val steady=Switch(this).apply{text="平稳音量";textSize=15f;setTextColor(colors.text);minHeight=dp(58);tag="steady-volume";isChecked=NightApp.steady.enabled;buttonTint(this)
            setOnCheckedChangeListener{_,checked->service?.steadyVolume(checked)}}
        box.addView(steady);box.addView(text("让声音更均匀，压住突然的大声。可以先听，处理完成后会慢慢切换。",12f,true))
        box.addView(text(service?.steadyStatus ?: "等待播放器连接",12f,true).apply{tag="steady-state"})
        space(box,20);box.addView(text("外观",13f,true));space(box,10)
        val themeRow=row()
        listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(key,label)->
            themeRow.addView(button(label,store.prefs.getString("theme","system")==key){store.prefs.edit().putString("theme",key).apply();changeAppearance();updateThemeButtons(themeRow)}.apply{tag="theme-$key";textSize=12f;setPadding(0,0,0,0)},LinearLayout.LayoutParams(0,dp(46),1f).apply{marginEnd=dp(4)})
        }
        box.addView(themeRow)
        space(box,16);choice(box,"复制播放诊断","后台中断后可复制，帮助排查原因"){
            getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("夜听播放诊断",service?.playbackReport() ?: PlaybackDiagnostics(this).report()));toast("播放诊断已复制")
        }
        space(box,22);box.addView(text("夜听 ${packageManager.getPackageInfo(packageName,0).versionName} · 只留原声，慢慢听",12f,true).apply{gravity=Gravity.CENTER})
    }
    private fun buttonTint(view:Switch){
        view.thumbTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(colors.accent,colors.muted))
        view.trackTintList=android.content.res.ColorStateList.valueOf(colors.line)
    }
    private fun updateThemeButtons(row:LinearLayout){
        val selected=store.prefs.getString("theme","system")
        listOf("system","light","dark").forEach{key->row.findViewWithTag<TextView>("theme-$key")?.apply{
            ripple(if(selected==key)colors.accent else colors.soft);setTextColor(if(selected==key)colors.onAccent else colors.text)
        }}
    }
    private fun changeAppearance(){
        themeAnimation?.end()
        val from=colors;val to=Palette(isDark());colors=to
        val updates=host.themeTargets(from,to)+(dialog as? ListeningSheet)?.themeTargets(from,to).orEmpty()
        WindowInsetsControllerCompat(window,host).apply{isAppearanceLightStatusBars=!to.dark;isAppearanceLightNavigationBars=!to.dark}
        (dialog as? ListeningSheet)?.updateSystemBars()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(to.bg))
        if(!ValueAnimator.areAnimatorsEnabled()){updates.forEach{it(1f)};return}
        themeAnimation=ValueAnimator.ofFloat(0f,1f).apply{
            duration=200;interpolator=listeningEase;addUpdateListener{animator->updates.forEach{it(animator.animatedValue as Float)}}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){if(refreshDeferred){refreshDeferred=false;handler.post(refresh)}}});start()
        }
    }
}
