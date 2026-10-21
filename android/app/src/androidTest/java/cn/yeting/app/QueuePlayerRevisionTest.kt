package cn.yeting.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.graphics.Point
import android.graphics.Rect
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueuePlayerRevisionTest {
    private val store get()=NightApp.store
    private val device get()=UiDevice.getInstance(DeviceFixture.inst)
    @Before fun qaOnly(){
        DeviceFixture.requireQaEmulator()
        assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim())
    }
    private fun record(name:String,value:Any)=File(DeviceFixture.context.getExternalFilesDir(null),"$name-v0.2.8.json").writeText(value.toString())
    private fun field(a:Activity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun invoke(a:Activity,name:String)=DeviceFixture.ui{MainActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(a)}
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun page(a:Activity)=field(a,"playerPage") as ViewGroup
    private fun panel(a:Activity)=(field(a,"dialog") as ListeningSheet).currentPage!!
    private fun queue(a:Activity):RecyclerView {
        DeviceFixture.ui{MainActivity::class.java.getDeclaredMethod("queueSheet",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,0,0)}
        device.waitForIdle()
        return DeviceFixture.ui{views(panel(a)).filterIsInstance<RecyclerView>().single()}
    }
    private fun close(a:Activity)=DeviceFixture.ui{(field(a,"dialog") as? ListeningSheet)?.closeAll(true)}
    private fun launch():MainActivity {
        val monitor=DeviceFixture.inst.addMonitor(MainActivity::class.java.name,null,false)
        try{DeviceFixture.launch();return monitor.waitForActivityWithTimeout(5000) as MainActivity}
        finally{DeviceFixture.inst.removeMonitor(monitor)}
    }
    private fun scenario(block:(MainActivity,PlaybackService,List<Entry>)->Unit){
        val before=store.queue().map{it.id};val shuffledBefore=store.queue(true).map{it.id}
        val clips=(0..7).map{i->
            val url=when(i%3){0->"https://www.douyin.com/video/88772000$i";1->"https://www.bilibili.com/video/BV1xx41117R$i";else->"https://youtu.be/queue_player_$i"}
            val e=store.add(SharedLink.from(url)).first;val f=File(store.audioDir,e.id+".m4a")
            DeviceFixture.inst.context.assets.open("six-seconds.m4a").use{input->f.outputStream().use{input.copyTo(it)}}
            store.update(e.id){it.title="排序切歌 QA $i";it.author="本地作者 $i";it.audio=f.name;it.durationMs=6000;it.positionMs=1000L+i*100;it.completed=false;it.state=Readiness.READY;it.metadataReady=true}
            store.get(e.id)!!
        }
        store.reorderQueue(clips.map{it.id}+before)
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        try{
            DeviceFixture.ui{s.sleep("off");s.playbackMode("queue");s.speed(1.2f,false);s.play(clips[1].id);s.pause()}
            DeviceFixture.await{DeviceFixture.ui{s.player.playbackState==androidx.media3.common.Player.STATE_READY}}
            DeviceFixture.ui{s.pause();MainActivity::class.java.getDeclaredMethod("openEntry",Entry::class.java).apply{isAccessible=true}.invoke(a,clips[1])}
            DeviceFixture.await{DeviceFixture.ui{s.currentId==clips[1].id}}
            DeviceFixture.ui{s.pause()};device.waitForIdle()
            block(a,s,clips)
        }finally{
            close(a);DeviceFixture.ui{s.detach(s.currentId ?: "");s.sleep("off");s.speed(1f,false);s.playbackMode("queue")}
            clips.forEach{store.delete(it.id)};store.reorderQueue(before);store.reorderQueue(shuffledBefore,true)
        }
    }
    @Test fun nonAdjacentQueueMovesInsertAndPreserveEveryOtherId(){scenario{a,s,clips->
        val report=JSONArray();val base=store.queue().map{it.id}
        try {for(shuffled in listOf(false,true)){
            DeviceFixture.ui{s.playbackMode(if(shuffled)"shuffle"else "queue")};store.reorderQueue(base,shuffled)
            val rv=queue(a)
            val helper=DeviceFixture.ui{(0 until rv.itemDecorationCount).map{rv.getItemDecorationAt(it)}.filterIsInstance<ItemTouchHelper>().single()}
            val callback=ItemTouchHelper::class.java.getDeclaredField("mCallback").apply{isAccessible=true}.get(helper) as ItemTouchHelper.Callback
            fun move(from:Int,to:Int,expected:List<String>){
                DeviceFixture.ui{(rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0,0)};device.waitForIdle()
                val id=DeviceFixture.ui{s.currentId};val position=DeviceFixture.ui{s.player.currentPosition}
                DeviceFixture.ui{
                    val source=rv.findViewHolderForAdapterPosition(from)!!;val target=rv.findViewHolderForAdapterPosition(to)!!
                    callback.onSelectedChanged(source,ItemTouchHelper.ACTION_STATE_DRAG)
                    assertTrue(callback.onMove(rv,source,target));callback.clearView(rv,source)
                }
                device.waitForIdle()
                val actual=store.queue(shuffled).map{it.id}
                report.put(JSONObject().put("shuffled",shuffled).put("from",from).put("to",to).put("expected",JSONArray(expected)).put("actual",JSONArray(actual)))
                assertEquals("Full insertion order, not a swap",expected,actual)
                assertEquals(expected,Library(DeviceFixture.context).queue(shuffled).map{it.id})
                assertEquals(base.size,actual.distinct().size)
                assertEquals(id,DeviceFixture.ui{s.currentId});assertEquals(position,DeviceFixture.ui{s.player.currentPosition})
                DeviceFixture.ui{for(i in 0 until rv.childCount){val holder=rv.getChildViewHolder(rv.getChildAt(i));val position=holder.bindingAdapterPosition;assertTrue(position in expected.indices);assertNotNull("Visible row $position must match its saved ID",holder.itemView.findViewWithTag<View>("queue-${expected[position]}"))}}
            }
            move(4,0,listOf(base[4])+base.take(4)+base.drop(5))
            move(0,4,base)
            move(0,4,base.subList(1,5)+base.take(1)+base.drop(5))
            DeviceFixture.ui{val holder=rv.getChildViewHolder(rv.getChildAt(0));assertFalse(callback.onMove(rv,holder,holder));assertFalse(callback.onMove(rv,object:RecyclerView.ViewHolder(View(a)){},holder))}
            close(a);queue(a);assertEquals(store.queue(shuffled).map{it.id},Library(DeviceFixture.context).queue(shuffled).map{it.id});close(a)
            if(shuffled)assertEquals(base,store.queue().map{it.id})else store.reorderQueue(base)
        }}finally{record("queue-cross-row",report)}
    }}
    @Test fun realFastAndSlowDragsKeepTheFullInsertionOrder(){scenario{a,s,_->
        val base=store.queue().map{it.id};val report=JSONArray()
        fun drag(source:Point,target:Point,steps:Int,hold:Long=160){
            val start=SystemClock.uptimeMillis()
            fun send(action:Int,x:Int,y:Int){val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x.toFloat(),y.toFloat(),0);DeviceFixture.inst.uiAutomation.injectInputEvent(event,true);event.recycle()}
            send(MotionEvent.ACTION_DOWN,source.x,source.y)
            for(i in 1..steps){send(MotionEvent.ACTION_MOVE,source.x+(target.x-source.x)*i/steps,source.y+(target.y-source.y)*i/steps);Thread.sleep(5)}
            val until=SystemClock.uptimeMillis()+hold
            while(SystemClock.uptimeMillis()<until){send(MotionEvent.ACTION_MOVE,target.x,target.y);Thread.sleep(50)}
            send(MotionEvent.ACTION_MOVE,target.x,target.y);send(MotionEvent.ACTION_UP,target.x,target.y)
        }
        try {for(steps in listOf(8,70)){
            store.reorderQueue(base);queue(a)
            val source=device.findObject(By.desc("拖动排序："+store.get(base[4])!!.title)).visibleCenter
            val target=device.findObject(By.desc("拖动排序："+store.get(base[0])!!.title)).visibleCenter
            // Finish inside the first row, above its midpoint, and let the final move reach a frame before lifting.
            target.y-=DeviceFixture.context.dp(24);drag(source,target,steps)
            DeviceFixture.await{DeviceFixture.ui{field(a,"gestureActive")==false}};device.waitForIdle()
            val expected=listOf(base[4])+base.take(4)+base.drop(5);val actual=store.queue().map{it.id}
            report.put(JSONObject().put("steps",steps).put("expected",JSONArray(expected)).put("actual",JSONArray(actual)))
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"queue-drag-$steps-v0.2.8.png"))
            assertEquals(expected,actual);close(a)
        }
            store.reorderQueue(base);val rv=queue(a)
            val source=device.findObject(By.desc("拖动排序："+store.get(base[0])!!.title)).visibleCenter
            val bounds=DeviceFixture.ui{Rect().apply{assertTrue(rv.getGlobalVisibleRect(this))}}
            val target=Point(source.x,(bounds.bottom+DeviceFixture.context.dp(32)).coerceAtMost(device.displayHeight-20))
            report.put(JSONObject().put("visible_bounds",bounds.toShortString()).put("recycler_height",DeviceFixture.ui{rv.height}).put("target_y",target.y))
            drag(source,target,35,4000)
            DeviceFixture.await{DeviceFixture.ui{field(a,"gestureActive")==false}};device.waitForIdle()
            val actual=store.queue().map{it.id};val expected=base.drop(1)+base.take(1)
            report.put(JSONObject().put("autoscroll_to_end",true).put("expected",JSONArray(expected)).put("actual",JSONArray(actual)))
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"queue-autoscroll-v0.2.8.png"))
            assertEquals(expected,actual);assertEquals(base[1],DeviceFixture.ui{s.currentId})
        }finally{record("queue-real-drag",report)}
    }}
    @Test fun trackChangesKeepPlayerAttachedAndUpdateEveryEntryBinding(){scenario{a,s,clips->
        val original=DeviceFixture.ui{page(a)};val progress=DeviceFixture.ui{views(original).filterIsInstance<ListeningProgress>().single()}
        val frames=JSONArray();var watching=true
        var received:Intent?=null
        val interception=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{if(intent.action==Intent.ACTION_VIEW){received=Intent(intent);return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)};return null}}
        DeviceFixture.inst.addMonitor(interception)
        val sampler=object:Choreographer.FrameCallback{
            override fun doFrame(time:Long){if(watching){val current=page(a);frames.put(JSONObject().put("ms",SystemClock.uptimeMillis()).put("same_page",current===original).put("attached",current.isAttachedToWindow).put("translation_y",current.translationY.toDouble()));Choreographer.getInstance().postFrameCallback(this)}}
        }
        DeviceFixture.ui{Choreographer.getInstance().postFrameCallback(sampler)}
        try {for((description,index) in listOf("下一条" to 2,"上一条" to 1,"下一条" to 2,"下一条" to 3,"上一条" to 2)){
            DeviceFixture.ui{views(page(a)).single{it.contentDescription==description}.performClick()}
            DeviceFixture.await{DeviceFixture.ui{s.currentId==clips[index].id && (field(a,"playerTitle") as TextView).text.toString()==clips[index].title}}
            DeviceFixture.ui{s.pause()};Thread.sleep(450)
            DeviceFixture.ui{
                assertSame("Track changes must reuse the open page",original,page(a));assertTrue(original.isAttachedToWindow)
                assertEquals(0f,original.translationY,.01f);assertSame(progress,views(original).filterIsInstance<ListeningProgress>().single())
                assertEquals(clips[index].id,progress.entryKey)
                assertEquals(clips[index].author,(field(a,"playerAuthor") as TextView).text.toString())
                assertEquals(clips[index].platform,(field(a,"playerArt") as Glyph).symbol)
                assertTrue(views(original).filterIsInstance<TextView>().any{it.text.toString()=="打开${clips[index].platform}原视频 ↗"})
                received=null;views(original).single{it.contentDescription=="打开${clips[index].platform}原视频"}.performClick()
                val intent=received!!
                if(intent.`package`==null || clips[index].platform!="抖音")assertEquals(clips[index].url,intent.dataString)
                else assertTrue(intent.dataString!!.endsWith(clips[index].url.substringAfterLast('/')))
            }
        }
            DeviceFixture.ui{s.speed(1.5f,true);s.next();s.pause()};DeviceFixture.await{DeviceFixture.ui{!s.speedForCurrent && s.player.playbackParameters.speed==1.2f}}
            assertSame(original,DeviceFixture.ui{page(a)})
            assertTrue("Every sampled transition frame stays on the same attached page",(0 until frames.length()).all{frames.getJSONObject(it).let{x->x.getBoolean("same_page") && x.getBoolean("attached") && x.getDouble("translation_y")==0.0}})
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"player-in-place-v0.2.8.png"))
        }finally{DeviceFixture.inst.removeMonitor(interception);DeviceFixture.ui{watching=false;Choreographer.getInstance().removeFrameCallback(sampler)};record("player-switch-frames",frames)}
    }}
    @Test fun naturalAdvanceAndQueueSelectionReuseTheOpenPlayer(){scenario{a,s,clips->
        val original=DeviceFixture.ui{page(a)}
        DeviceFixture.ui{s.seek(5200);s.player.play()}
        DeviceFixture.await(15000){DeviceFixture.ui{s.currentId==clips[2].id}}
        DeviceFixture.ui{s.pause()};Thread.sleep(450);assertSame(original,DeviceFixture.ui{page(a)})
        queue(a);device.findObject(By.desc("队列："+clips[3].title)).click()
        DeviceFixture.await{DeviceFixture.ui{s.currentId==clips[3].id}};DeviceFixture.ui{s.pause()};Thread.sleep(450)
        assertSame(original,DeviceFixture.ui{page(a)})
        DeviceFixture.ui{views(original).single{it.contentDescription=="收起播放器"}.performClick()};device.waitForIdle()
        device.findObject(By.desc("展开正在听")).click();device.waitForIdle()
        assertSame(original,DeviceFixture.ui{page(a)});assertEquals(0f,DeviceFixture.ui{original.translationY},.01f)
    }}
    @Test fun settingsHaveNoSourceSectionAndEntryVerificationStillExists(){scenario{a,_,clips->
        for(theme in listOf("dark","light")){
            store.prefs.edit().putString("theme",theme).commit();invoke(a,"changeAppearance");invoke(a,"settings");device.waitForIdle()
            val texts=DeviceFixture.ui{views(panel(a)).filterIsInstance<TextView>().map{it.text.toString()}}
            assertFalse(texts.contains("来源连接"));assertTrue(texts.contains("自动识别剪贴板"));assertTrue(texts.contains("允许用流量准备"));assertTrue(texts.contains("外观"))
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"settings-$theme-v0.2.8.png"));close(a)
        }
        val entry=clips.first{it.platform=="抖音"};store.update(entry.id){it.state=Readiness.ERROR;it.message="QA 验证入口"}
        DeviceFixture.ui{MainActivity::class.java.getDeclaredMethod("entryActions",Entry::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,store.get(entry.id),false)}
        assertTrue(DeviceFixture.ui{views(panel(a)).filterIsInstance<TextView>().any{it.text.toString()=="连接抖音"}})
    }}
}
