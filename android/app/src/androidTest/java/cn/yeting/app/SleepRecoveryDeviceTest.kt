package cn.yeting.app

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run seed and verify separately, with external adb force-stop cn.yeting.app between them.
 * seed: -e sleepRecoveryCase minutes|minutesFinish|count|expired|bootdifferent|tail
 * Keep the same installed app/test APKs between phases. verify always restores the saved data.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SleepRecoveryDeviceTest {
    private val store get()=NightApp.store
    private val context get()=DeviceFixture.context
    private val device get()=UiDevice.getInstance(DeviceFixture.inst)
    private val manifest get()=File(context.getExternalFilesDir(null),"sleep-recovery-device-v0.2.9.json")
    private val keys=listOf("queue","shuffledQueue","speed","sleep","continuous","sleepCheckpoint","playMode","last","autoClipboard")
    private val cases=listOf("minutes","minutesFinish","count","expired","bootdifferent","tail")

    @Before fun qaOnly(){
        DeviceFixture.requireQaEmulator()
        assertFalse("Do not interrupt preparation",PreparationService.active)
    }
    private fun boot()=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
    private fun launch():MainActivity {
        val a=DeviceFixture.inst.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        DeviceFixture.await{DeviceFixture.ui{a.hasWindowFocus() && PlaybackService.active!=null}}
        return a
    }
    private fun stopService(){
        val stopped=DeviceFixture.ui{PlaybackService.active?.let{it.pauseAllPlayersAndStopSelf();true} ?: false}
        if(!stopped)context.stopService(Intent(context,PlaybackService::class.java))
        DeviceFixture.await{DeviceFixture.ui{PlaybackService.active==null}}
    }
    private fun startService():PlaybackService {
        context.startService(Intent(context,PlaybackService::class.java))
        DeviceFixture.await{DeviceFixture.ui{PlaybackService.active!=null}}
        return DeviceFixture.ui{PlaybackService.active!!}
    }
    private fun write(state:JSONObject){
        // Write the rollback data before changing preferences or adding any fixture.
        manifest.writeText(state.toString(2))
    }
    private fun begin(case:String,external:Boolean):JSONObject {
        assertFalse("Pending seed exists: run verify to restore it before another test",manifest.exists())
        assertTrue(case in cases);assertTrue("BOOT_COUNT unavailable",boot()>=0)
        assertTrue("Detach existing playback before seeding",DeviceFixture.ui{PlaybackService.active?.currentId==null})
        val prefs=JSONObject()
        keys.forEach{key->prefs.put(key,store.prefs.all[key] ?: JSONObject.NULL)}
        val state=JSONObject().put("case",case).put("external",external).put("pid",Process.myPid())
            .put("boot",boot()).put("prefs",prefs).put("ids",JSONArray())
            .put("queue",JSONArray(store.queue().map{it.id})).put("shuffledQueue",JSONArray(store.queue(true).map{it.id}))
        write(state)
        return state
    }
    private fun prepare(state:JSONObject):PlaybackService {
        store.prefs.edit().putBoolean("autoClipboard",false).remove("sleepCheckpoint").commit()
        val suffix=System.nanoTime().toString().takeLast(12)
        repeat(2){i->
            val url="https://www.douyin.com/video/999039$suffix$i"
            assertNull(store.find(url))
            val added=store.add(SharedLink.from(url));assertTrue(added.second)
            val e=added.first;state.getJSONArray("ids").put(e.id);write(state)
            val audio=File(store.audioDir,e.id+".m4a")
            DeviceFixture.inst.context.assets.open("six-seconds.m4a").use{input->audio.outputStream().use{input.copyTo(it)}}
            store.update(e.id){it.title="Sleep recovery QA $i";it.author="本地合成测试";it.audio=audio.name
                it.durationMs=6000;it.positionMs=0;it.completed=false;it.metadataReady=true;it.state=Readiness.READY}
        }
        launch();stopService()
        val s=startService();val id=state.getJSONArray("ids").getString(0)
        DeviceFixture.ui{
            s.playbackMode("repeat");s.speed(1f,false);s.sleep("off");s.play(id,true)
        }
        DeviceFixture.await{DeviceFixture.ui{s.player.isPlaying}}
        DeviceFixture.ui{
            s.pause();s.seek(1200)
            when(state.getString("case")){
                "count"->s.sleep("count",3)
                else->s.sleep("minutes",10,state.getString("case")=="minutesFinish")
            }
        }
        val expected=DeviceFixture.ui{JSONObject().put("entry",id).put("deadline",s.policy.deadlineMs)
            .put("tracks",s.policy.remainingTracks).put("sleep",s.policy.sleep).put("continuous",s.policy.continuous)
            .put("finish",s.policy.finishCurrent).put("position",store.get(id)!!.positionMs)}
        state.put("expected",expected);write(state)
        return s
    }
    private fun injectInvalidCheckpoint(state:JSONObject){
        val checkpoint=JSONObject(store.prefs.getString("sleepCheckpoint",null)
            ?: error("Service did not persist the unfinished limit"))
        when(state.getString("case")){
            "expired"->checkpoint.put("deadline",SystemClock.elapsedRealtime()-1)
            "bootdifferent"->checkpoint.put("boot",boot()+1)
            "tail"->checkpoint.put("sleep","end").put("tracks",1).put("deadline",0).put("finish",false).put("finishing",true)
        }
        assertTrue(store.prefs.edit().putString("sleepCheckpoint",checkpoint.toString()).commit())
        state.put("checkpoint",checkpoint);write(state)
    }
    private fun assertRestored(state:JSONObject,s:PlaybackService,resumeDirectly:Boolean=false){
        val expected=state.getJSONObject("expected");val case=state.getString("case")
        assertFalse("Recovery must never start audio",DeviceFixture.ui{s.player.playWhenReady})
        assertFalse(DeviceFixture.ui{s.player.isPlaying})
        assertEquals("Paused recovery must not prepare decoders",Player.STATE_IDLE,DeviceFixture.ui{s.player.playbackState})
        val notice=DeviceFixture.ui{PlaybackService::class.java.getDeclaredField("recoveryNotice").apply{isAccessible=true}.get(s) as String}
        assertTrue("Recovery must explain the retained or ended limit",notice.isNotBlank())
        if(case in listOf("expired","bootdifferent","tail")){
            assertEquals("off",DeviceFixture.ui{s.policy.sleep})
            assertEquals(0L,DeviceFixture.ui{s.policy.deadlineMs})
            assertEquals(0,DeviceFixture.ui{s.policy.remainingTracks})
            assertFalse(DeviceFixture.ui{s.policy.finishingCurrent})
            assertFalse("Invalid limit must be cleared",store.prefs.contains("sleepCheckpoint"))
            assertEquals("off",store.prefs.getString("sleep",null))
        }else{
            assertRetainedLimit(expected,s)
            assertEquals(expected.getLong("position").toDouble(),DeviceFixture.ui{s.player.currentPosition}.toDouble(),150.0)
            assertEquals(expected.getLong("position"),store.get(expected.getString("entry"))!!.positionMs)
        }
        // Observe a quiet interval as well as the immediate onCreate state.
        val position=DeviceFixture.ui{s.player.currentPosition}
        val until=SystemClock.elapsedRealtime()+800
        while(SystemClock.elapsedRealtime()<until){
            assertFalse(DeviceFixture.ui{s.player.playWhenReady || s.player.isPlaying})
            assertEquals(Player.STATE_IDLE,DeviceFixture.ui{s.player.playbackState})
            assertEquals("Paused recovery must retain its position",position,DeviceFixture.ui{s.player.currentPosition})
            Thread.sleep(100)
        }
        if(case !in listOf("expired","bootdifferent","tail")){
            assertRetainedLimit(expected,s)
            val advances=java.util.concurrent.atomic.AtomicInteger(0)
            val listener=object:Player.Listener{
                override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int){
                    if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)advances.incrementAndGet()
                }
            }
            try{
                DeviceFixture.ui{s.player.addListener(listener);if(resumeDirectly)s.play(expected.getString("entry"))else s.toggle()}
                DeviceFixture.await{DeviceFixture.ui{s.player.isPlaying}}
                DeviceFixture.ui{s.pause()}
                assertFalse(DeviceFixture.ui{s.player.playWhenReady || s.player.isPlaying})
                assertEquals("A single resume must not complete or consume an extra entry",0,advances.get())
                assertFalse(store.get(expected.getString("entry"))!!.completed)
                assertRetainedLimit(expected,s)
            }finally{DeviceFixture.ui{s.player.removeListener(listener)}}
        }
    }
    private fun assertRetainedLimit(expected:JSONObject,s:PlaybackService){
        assertEquals(expected.getString("entry"),DeviceFixture.ui{s.currentId})
        assertEquals(expected.getString("entry"),DeviceFixture.ui{s.player.currentMediaItem?.mediaId})
        assertEquals(expected.getString("sleep"),DeviceFixture.ui{s.policy.sleep})
        assertEquals(expected.getBoolean("continuous"),DeviceFixture.ui{s.policy.continuous})
        assertEquals(expected.getBoolean("finish"),DeviceFixture.ui{s.policy.finishCurrent})
        assertFalse(DeviceFixture.ui{s.policy.finishingCurrent})
        assertEquals("Restart/resume must not replenish or consume tracks",expected.getInt("tracks"),DeviceFixture.ui{s.policy.remainingTracks})
        assertEquals("Keep the original monotonic deadline",expected.getLong("deadline"),DeviceFixture.ui{s.policy.deadlineMs})
        assertEquals(expected.getString("sleep"),store.prefs.getString("sleep",null))
        val checkpoint=JSONObject(store.prefs.getString("sleepCheckpoint",null)!!)
        assertEquals(expected.getLong("deadline"),checkpoint.getLong("deadline"))
        assertEquals(expected.getInt("tracks"),checkpoint.getInt("tracks"))
        assertEquals(expected.getString("entry"),checkpoint.getString("entry"))
    }
    private fun restore(state:JSONObject){
        val ids=state.getJSONArray("ids").let{a->(0 until a.length()).map{a.getString(it)}}
        try{
            DeviceFixture.ui{PlaybackService.active?.let{s->
                assertTrue("Only test entries may be active during cleanup",s.currentId==null || s.currentId in ids)
                s.currentId?.let{s.detach(it)};s.sleep("off")
            }}
            stopService()
            DeviceFixture.ui{
                val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.STARTED,
                    androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STOPPED)
                    .flatMap{monitor.getActivitiesInStage(it)}.filterIsInstance<MainActivity>().distinct().forEach{it.finish()}
            }
        }finally{
            ids.forEach{store.delete(it)}
            val prefs=state.getJSONObject("prefs");val edit=store.prefs.edit()
            keys.forEach{key->if(prefs.isNull(key))edit.remove(key)else when(key){
                "speed"->edit.putFloat(key,prefs.getDouble(key).toFloat())
                "continuous","autoClipboard"->edit.putBoolean(key,prefs.getBoolean(key))
                else->edit.putString(key,prefs.getString(key))
            }}
            assertTrue("Restore pre-test preferences",edit.commit())
            assertEquals(state.getJSONArray("queue").toString(),JSONArray(store.queue().map{it.id}).toString())
            assertEquals(state.getJSONArray("shuffledQueue").toString(),JSONArray(store.queue(true).map{it.id}).toString())
            assertTrue("Remove completed recovery manifest",manifest.delete())
        }
    }

    // Recovery utility only; exclude from acceptance selections and pass counts.
    @Test fun cleanupInterruptedSeed(){
        if(manifest.isFile)restore(JSONObject(manifest.readText()))
    }

    @Test fun seed(){
        org.junit.Assume.assumeTrue("Select #seed explicitly; do not seed during a whole-class run",
            InstrumentationRegistry.getArguments().getString("class")=="${javaClass.name}#seed")
        val case=InstrumentationRegistry.getArguments().getString("sleepRecoveryCase") ?: "minutes"
        val state=begin(case,true)
        try{
            prepare(state)
            val expected=state.getJSONObject("expected")
            if(case in listOf("minutes","minutesFinish"))assertTrue(expected.getLong("deadline")-SystemClock.elapsedRealtime()>590000)
            if(case in listOf("expired","bootdifferent","tail")){
                // Inject only after onDestroy, otherwise its final save would overwrite the test checkpoint.
                stopService();injectInvalidCheckpoint(state)
            }else{
                // Leave a live, paused service for the external force-stop; do not pre-run onDestroy.
                val checkpoint=JSONObject(store.prefs.getString("sleepCheckpoint",null)
                    ?: error("Service did not persist the unfinished limit"))
                assertEquals(expected.getLong("deadline"),checkpoint.getLong("deadline"))
                assertEquals(expected.getInt("tracks"),checkpoint.getInt("tracks"))
                state.put("checkpoint",checkpoint)
            }
            state.put("seedComplete",true);write(state)
        }catch(t:Throwable){try{restore(state)}catch(cleanup:Throwable){t.addSuppressed(cleanup)};throw t}
    }

    @Test fun verify(){
        org.junit.Assume.assumeTrue("Select #verify explicitly after external force-stop",
            InstrumentationRegistry.getArguments().getString("class")=="${javaClass.name}#verify")
        assertTrue("Run seed, then externally force-stop the target app first",manifest.isFile)
        val state=JSONObject(manifest.readText())
        try{
            assertTrue(state.getBoolean("external"));assertTrue(state.optBoolean("seedComplete"))
            assertNotEquals("verify must run in a new process after external force-stop",state.getInt("pid"),Process.myPid())
            assertEquals("Do not reboot the QA device between phases",state.getInt("boot"),boot())
            val expected=state.getJSONObject("expected")
            if(state.getString("case") in listOf("minutes","minutesFinish"))assertTrue("Seed expired; rerun the pair promptly",expected.getLong("deadline")>SystemClock.elapsedRealtime())
            launch();assertRestored(state,DeviceFixture.ui{PlaybackService.active!!})
        }finally{restore(state)}
    }

    private fun serviceRestart(case:String){
        val state=begin(case,false)
        try{
            val previous=prepare(state)
            stopService();injectInvalidCheckpoint(state)
            val fresh=startService();assertNotSame(previous,fresh)
            assertRestored(state,fresh)
        }finally{restore(state)}
    }
    @Test fun minutesServiceRecreationKeepsOriginalDeadlinePaused()=serviceRestart("minutes")
    @Test fun finishCurrentMinutesServiceRecreationKeepsOriginalDeadlinePaused()=serviceRestart("minutesFinish")
    @Test fun countServiceRecreationDoesNotConsumeOrReplenishTracks()=serviceRestart("count")
    @Test fun expiredCheckpointServiceRecreationStaysOffAndPaused()=serviceRestart("expired")
    @Test fun differentBootCheckpointServiceRecreationStaysOffAndPaused()=serviceRestart("bootdifferent")
    @Test fun interruptedTailServiceRecreationStaysOffAndPaused()=serviceRestart("tail")
    private fun completedEntryRecovery(resumeDirectly:Boolean){
        val state=begin("minutes",false)
        try{
            val previous=prepare(state);val id=state.getJSONObject("expected").getString("entry")
            DeviceFixture.ui{previous.seek(6000);store.update(id){it.completed=true}}
            state.getJSONObject("expected").put("position",6000L);write(state)
            stopService();val fresh=startService();assertNotSame(previous,fresh)
            assertRestored(state,fresh,resumeDirectly)
            assertTrue("Completed entry must restart before its old end",DeviceFixture.ui{fresh.player.currentPosition<3000})
        }finally{restore(state)}
    }
    @Test fun completedEntryRecoveryKeepsNewTimerWhenResumedByPlay()=completedEntryRecovery(true)
    @Test fun completedEntryRecoveryKeepsNewTimerWhenResumedByToggle()=completedEntryRecovery(false)
    @Test fun pausedRecoverySeekSurvivesAnotherServiceRecreation(){
        val state=begin("minutes",false)
        try{
            val previous=prepare(state);val id=state.getJSONObject("expected").getString("entry")
            DeviceFixture.ui{previous.seek(6000);store.update(id){it.completed=true}}
            stopService();val restored=startService()
            DeviceFixture.ui{restored.seek(2500)}
            assertFalse("An explicit seek overrides the old completion",store.get(id)!!.completed)
            assertEquals(2500L,store.get(state.getJSONObject("expected").getString("entry"))!!.positionMs)
            state.getJSONObject("expected").put("position",2500L);write(state)
            stopService();assertRestored(state,startService())
            assertTrue("Resume must retain the explicitly chosen position",DeviceFixture.ui{PlaybackService.active!!.player.currentPosition>=2400})
        }finally{restore(state)}
    }
}
