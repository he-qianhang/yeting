package cn.yeting.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.view.ViewGroup
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MotionInteractionTest {
    @org.junit.Before fun qaOnly(){DeviceFixture.requireQaEmulator()}

    private val device get()=UiDevice.getInstance(DeviceFixture.inst)
    private val store get()=NightApp.store
    private fun launch():MainActivity {
        val monitor=DeviceFixture.inst.addMonitor(MainActivity::class.java.name,null,false)
        try {DeviceFixture.launch();return monitor.waitForActivityWithTimeout(5000) as MainActivity}
        finally {DeviceFixture.inst.removeMonitor(monitor)}
    }
    private fun dialog(activity:Activity)=MainActivity::class.java.getDeclaredField("dialog").apply{isAccessible=true}.get(activity) as? ListeningSheet
    private fun content(activity:Activity)=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
    private fun bounds(view:View)=Rect().also{view.getGlobalVisibleRect(it)}

    @Test fun appearanceKeepsTheSameWindowPanelAndGeometryThroughoutSwitches(){
        val before=store.prefs.getString("theme","system")
        val activity=launch();device.findObject(By.desc("设置")).click();device.waitForIdle()
        val sheet=DeviceFixture.ui{dialog(activity)!!};val panel=DeviceFixture.ui{sheet.currentPage!!}
        DeviceFixture.await{DeviceFixture.ui{panel.isLaidOut && panel.translationY==0f}}
        val original=DeviceFixture.ui{bounds(panel)};val geometry=mutableListOf<Rect>();var detached=false
        val frameTimes=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val layoutTimes=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val drawTimes=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val gpuTimes=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val frameThread=HandlerThread("motion-frames").apply{start()}
        val frameListener=Window.OnFrameMetricsAvailableListener{_,metrics,_->
            frameTimes.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION));layoutTimes.add(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
            drawTimes.add(metrics.getMetric(FrameMetrics.DRAW_DURATION));gpuTimes.add(metrics.getMetric(FrameMetrics.GPU_DURATION))
        }
        DeviceFixture.ui{sheet.window!!.addOnFrameMetricsAvailableListener(frameListener,Handler(frameThread.looper))}
        DeviceFixture.ui{
            panel.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:View){}
                override fun onViewDetachedFromWindow(v:View){detached=true}
            })
            panel.viewTreeObserver.addOnPreDrawListener{geometry.add(bounds(panel));true}
        }
        try {
            listOf("浅色","深色","跟随系统","深色").forEach{label->
                device.findObject(By.text(label)).click();device.waitForIdle()
                assertSame(sheet,DeviceFixture.ui{dialog(activity)})
                assertSame(panel,DeviceFixture.ui{sheet.currentPage})
            }
            assertFalse("Theme change must not detach the panel",detached)
            File(DeviceFixture.context.getExternalFilesDir(null),"theme-geometry-v0.2.2.json").writeText(JSONObject()
                .put("baseline",original.toShortString()).put("drawn_bounds",JSONArray(geometry.map{it.toShortString()})).put("panel_detached",detached).toString(2))
            assertTrue("Panel geometry must stay fixed on every drawn frame",geometry.isNotEmpty() && geometry.all{it==original})
            val durations=synchronized(frameTimes){frameTimes.sorted()}
            fun p95(times:MutableList<Long>):Long=synchronized(times){times.sorted().let{it[((it.size-1)*.95).toInt()]}}
            assertTrue("Color transitions should produce measured frames",durations.isNotEmpty())
            File(DeviceFixture.context.getExternalFilesDir(null),"theme-frame-times-v0.2.2.json").writeText(JSONObject()
                .put("device_kind","Android emulator").put("frames",durations.size).put("median_ns",durations[durations.size/2])
                .put("p95_ns",durations[((durations.size-1)*.95).toInt()]).put("max_ns",durations.last())
                .put("layout_p95_ns",p95(layoutTimes)).put("draw_p95_ns",p95(drawTimes)).put("gpu_p95_ns",p95(gpuTimes))
                .put("frames_over_16ms",durations.count{it>16_666_667}).put("real_xiaomi_performance_verified",false).toString(2))
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"settings-v0.2.2.png"))
        }finally{DeviceFixture.ui{sheet.window?.removeOnFrameMetricsAvailableListener(frameListener);sheet.closeAll(true)};frameThread.quitSafely();store.prefs.edit().putString("theme",before).commit()}
    }

    @Test fun longPanelIsCappedFromFirstDrawAndClosingKeepsTheHomeRoot(){
        val clips=DeviceFixture.shortClips();val e=clips[0].copy(title="超长标题与详细信息。".repeat(300))
        store.update(e.id){it.title=e.title}
        val activity=launch();val home=DeviceFixture.ui{content(activity)}
        val heights=mutableListOf<Int>();val tops=mutableListOf<Float>();val opacity=mutableListOf<Float>()
        try {
            DeviceFixture.ui{
                MainActivity::class.java.getDeclaredMethod("entryActions",Entry::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,e,false)
                val panel=dialog(activity)!!.currentPage!!
                panel.viewTreeObserver.addOnPreDrawListener{heights.add(panel.height);tops.add(panel.top+panel.translationY);opacity.add(panel.alpha);true}
            }
            device.waitForIdle();Thread.sleep(350)
            assertTrue(heights.isNotEmpty());assertTrue("No oversized intermediate frame",heights.all{it<=device.displayHeight/2})
            assertTrue("Panel must stay opaque",opacity.all{it==1f})
            assertTrue("Entrance must not reverse or jump down",tops.zipWithNext().all{(a,b)->b<=a+1})
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"detail-half-v0.2.2.png"))
            device.findObject(By.desc("关闭面板")).click();DeviceFixture.await{DeviceFixture.ui{dialog(activity)==null}}
            assertSame(home,DeviceFixture.ui{content(activity)})
            File(DeviceFixture.context.getExternalFilesDir(null),"motion-geometry-v0.2.2.json").writeText(JSONObject()
                .put("heights",JSONArray(heights)).put("visual_tops",JSONArray(tops)).put("opaque_on_all_sampled_draws",true).put("same_home_root",true).toString(2))
        }finally{clips.forEach{store.delete(it.id)}}
    }

    @Test fun smallSwipesLeaveNoTranslationOrActionAndMultiSelectHasGaps(){
        val clips=DeviceFixture.shortClips();val activity=launch()
        try {
            DeviceFixture.await{device.hasObject(By.descStartsWith("QA 0，"))};device.waitForIdle()
            val row=DeviceFixture.ui{content(activity).findViewWithTag<ViewGroup>(clips[0].id)}
            val b=device.findObject(By.descStartsWith("QA 0，")).visibleBounds
            for(direction in listOf(-1,1,-1,1)){
                device.swipe(b.centerX(),b.centerY(),b.centerX()+DeviceFixture.context.dp(40)*direction,b.centerY(),20)
                device.waitForIdle()
                DeviceFixture.await{DeviceFixture.ui{row.getChildAt(0).translationX==0f}}
                assertEquals(0f,DeviceFixture.ui{row.getChildAt(0).translationX},.5f)
                assertNotNull(store.get(clips[0].id));assertFalse(device.hasObject(By.text("1 条已选")))
            }
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"swipe-restored-v0.2.2.png"))
            device.swipe(b.right-20,b.centerY(),b.left+20,b.centerY(),24)
            DeviceFixture.await{device.hasObject(By.text("1 条已选"))};device.waitForIdle()
            val count=device.findObject(By.text("1 条已选")).visibleBounds
            val select=device.findObject(By.text("全选")).visibleBounds
            val delete=device.findObject(By.text("删除")).visibleBounds
            val cancel=device.findObject(By.text("取消")).visibleBounds
            assertTrue(count.left>=DeviceFixture.context.dp(20));assertTrue(cancel.right<=device.displayWidth-DeviceFixture.context.dp(20))
            assertTrue(delete.left-select.right>=DeviceFixture.context.dp(8));assertTrue(cancel.left-delete.right>=DeviceFixture.context.dp(8))
            device.takeScreenshot(File(DeviceFixture.context.getExternalFilesDir(null),"selection-spacing-v0.2.2.png"))
            device.findObject(By.text("取消")).click()
        }finally{clips.forEach{store.delete(it.id)}}
    }

    @Test fun sourceDiagnosticsClassifyWithoutRetainingPrivateErrorData(){
        val diagnostics=SourceDiagnostics(DeviceFixture.context)
        val id="diagnostic-test"
        try {
            diagnostics.failed(id,"YouTube","读取信息",Exception("Sign in to confirm you're not a bot. Cookie: private-cookie Authorization: private-token https://private.example/path?key=secret"))
            val report=diagnostics.report(id)!!
            assertEquals("bot_verification",JSONObject(report).getString("category"))
            listOf("private-cookie","private-token","private.example","secret").forEach{assertFalse(report.contains(it))}
        }finally{diagnostics.clear(id)}
    }

    @Test fun realLongPressReturnsToTheSameQueueAndScrollPosition(){
        val clips=DeviceFixture.shortClips();val original=store.queue().map{it.id}
        store.reorderQueue(clips.map{it.id}+original.filter{it !in clips.map{c->c.id}})
        val activity=launch()
        try {
            device.findObject(By.descStartsWith("QA 0，")).click();DeviceFixture.await{device.hasObject(By.desc("待听列表"))}
            DeviceFixture.ui{PlaybackService.active!!.pause()}
            device.findObject(By.desc("待听列表")).click();device.waitForIdle()
            val sheet=DeviceFixture.ui{dialog(activity)!!};val queue=DeviceFixture.ui{sheet.currentPage!!}
            DeviceFixture.await{DeviceFixture.ui{queue.translationY==0f && queue.isLaidOut}}
            fun findList(view:View):androidx.recyclerview.widget.RecyclerView? {
                if(view is androidx.recyclerview.widget.RecyclerView)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)findList(view.getChildAt(i))?.let{return it}
                return null
            }
            val list=DeviceFixture.ui{findList(queue)!!}
            val layout=list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            DeviceFixture.ui{layout.scrollToPositionWithOffset(2,-10)};device.waitForIdle()
            val before=DeviceFixture.ui{layout.findFirstVisibleItemPosition() to layout.findViewByPosition(layout.findFirstVisibleItemPosition())!!.top}
            val e=store.queue()[before.first+1]
            device.findObject(By.desc("队列："+e.title)).longClick()
            DeviceFixture.await{device.hasObject(By.text("详细信息"))};device.waitForIdle()
            device.findObject(By.desc("关闭面板")).click()
            DeviceFixture.await{device.hasObject(By.text("待听列表"))};device.waitForIdle()
            assertSame(queue,DeviceFixture.ui{sheet.currentPage})
            val after=DeviceFixture.ui{layout.findFirstVisibleItemPosition() to layout.findViewByPosition(layout.findFirstVisibleItemPosition())!!.top}
            assertEquals(before,after)
            File(DeviceFixture.context.getExternalFilesDir(null),"queue-preservation-v0.2.2.json").writeText(JSONObject()
                .put("same_panel",true).put("same_list",true).put("first_visible_before",before.first).put("first_visible_after",after.first)
                .put("offset_before",before.second).put("offset_after",after.second).put("real_long_press",true).toString(2))
            DeviceFixture.ui{sheet.closeAll(true)}
        }finally{
            DeviceFixture.ui{PlaybackService.active?.currentId?.takeIf{id->clips.any{it.id==id}}?.let{PlaybackService.active?.detach(it)}}
            store.reorderQueue(original);clips.forEach{store.delete(it.id)}
        }
    }
}
