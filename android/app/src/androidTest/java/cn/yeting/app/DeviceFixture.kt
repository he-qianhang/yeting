package cn.yeting.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object DeviceFixture {
    val inst get()=InstrumentationRegistry.getInstrumentation()
    val context get()=inst.targetContext
    val store get()=NightApp.store
    fun requireQaEmulator(){
        val device=UiDevice.getInstance(inst)
        assertEquals("Tests require an isolated emulator", "1",device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("Use the dedicated empty QA emulator", "YeTingOpenSourceQA",device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
    }
    fun <T> ui(block:()->T):T {val out=AtomicReference<T>();inst.runOnMainSync{out.set(block())};return out.get()}
    fun await(timeout:Long=15000,condition:()->Boolean) {val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(100)};fail("Condition timed out after $timeout ms")}
    fun launch(){
        requireQaEmulator()
        store.all().filter{it.url.contains("night_clipboard_test")}.forEach{store.delete(it.id)}
        val clipboard=store.prefs.getBoolean("autoClipboard",true)
        store.prefs.edit().putBoolean("autoClipboard",false).commit()
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await{ui{activity.hasWindowFocus() && PlaybackService.active!=null}}
        ui{context.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip()}
        store.prefs.edit().putBoolean("autoClipboard",clipboard).commit()
    }
    fun shortClips():List<Entry> {
        requireQaEmulator()
        return (0..2).map{i->
            val e=store.add(SharedLink.from("https://www.douyin.com/video/99990000$i")).first
            val f=File(store.audioDir,e.id+".m4a");inst.context.assets.open("six-seconds.m4a").use{a->f.outputStream().use{a.copyTo(it)}}
            store.update(e.id){it.title="QA $i";it.author="测试作者";it.audio=f.name;it.durationMs=6000;it.metadataReady=true;it.state=Readiness.READY;it.positionMs=0;it.completed=false}
            store.get(e.id)!!
        }
    }
}
