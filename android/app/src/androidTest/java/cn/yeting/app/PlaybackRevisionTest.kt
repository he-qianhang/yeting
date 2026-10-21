package cn.yeting.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaSessionService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackRevisionTest {
    private val store get()=NightApp.store
    private val device get()=UiDevice.getInstance(DeviceFixture.inst)

    @Before fun qaOnly(){
        DeviceFixture.requireQaEmulator()
        assertFalse("Finish SleepRecoveryDeviceTest#verify before other playback tests",File(DeviceFixture.context.getExternalFilesDir(null),"sleep-recovery-device-v0.2.9.json").exists())
        assertFalse("Do not interrupt preparation",PreparationService.active)
        assertTrue("Detach existing playback before running isolated fixtures",DeviceFixture.ui{PlaybackService.active?.currentId==null})
    }

    private fun launch():MainActivity {
        // Unlike DeviceFixture.launch(), leave existing clipboard-test entries and clipboard intact.
        val a=DeviceFixture.inst.startActivitySync(Intent(DeviceFixture.context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        DeviceFixture.await{DeviceFixture.ui{a.hasWindowFocus() && PlaybackService.active!=null}}
        return a
    }
    private fun sheet(a:MainActivity)=MainActivity::class.java.getDeclaredField("dialog").apply{isAccessible=true}.get(a) as? ListeningSheet
    private fun speedPanel(a:MainActivity):View {
        DeviceFixture.ui{MainActivity::class.java.getDeclaredMethod("speedSheet").apply{isAccessible=true}.invoke(a)}
        DeviceFixture.await{DeviceFixture.ui{sheet(a)?.currentPage?.let{it.isAttachedToWindow && it.translationY==0f}==true}}
        return DeviceFixture.ui{sheet(a)!!.currentPage!!}
    }
    private inline fun <reified T:View> find(v:View):T? {
        val all=mutableListOf(v)
        while(all.isNotEmpty()){
            val x=all.removeAt(0);if(x is T)return x
            if(x is ViewGroup)for(i in 0 until x.childCount)all.add(x.getChildAt(i))
        }
        return null
    }
    private fun ruler(panel:View,speed:Float)=DeviceFixture.ui{
        val bar=find<SeekBar>(panel) ?: error("Speed ruler is missing")
        assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,(speed*10-5).toInt().toFloat())}))
    }
    private fun scope(panel:View,checked:Boolean)=DeviceFixture.ui{
        val toggle=find<Switch>(panel) ?: error("Current-entry switch is missing")
        if(toggle.isChecked!=checked)toggle.performClick()
        assertEquals(checked,toggle.isChecked)
    }
    private fun assertSpeed(s:PlaybackService,value:Float,currentOnly:Boolean){
        assertEquals(value,DeviceFixture.ui{s.player.playbackParameters.speed},.001f)
        assertEquals(currentOnly,DeviceFixture.ui{s.speedForCurrent})
    }
    private fun stopService(){
        val stopped=DeviceFixture.ui{PlaybackService.active?.let{it.pauseAllPlayersAndStopSelf();true} ?: false}
        if(!stopped)DeviceFixture.context.stopService(Intent(DeviceFixture.context,PlaybackService::class.java))
        DeviceFixture.await{DeviceFixture.ui{PlaybackService.active==null}}
    }

    private val savedKeys=listOf("queue","shuffledQueue","speed","sleep","continuous","sleepCheckpoint","playMode","last","autoClipboard")
    private val policyKeys=listOf("continuous","sleep","deadlineMs","remainingTracks","finishCurrent","finishingCurrent")
    private fun isolated(block:(List<Entry>)->Unit){
        val saved=savedKeys.associateWith{store.prefs.all[it]}
        val original=DeviceFixture.ui{PlaybackService.active}
        // Preserve the exact deadline and remaining count, rather than restarting a timer in finally.
        val policy=DeviceFixture.ui{original?.policy?.let{p->policyKeys.associateWith{name->
            ListeningPolicy::class.java.getDeclaredField(name).apply{isAccessible=true}.get(p)
        }}}
        val clips=mutableListOf<Entry>()
        val activitiesBefore=DeviceFixture.ui{androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).toList()}
        try{
            DeviceFixture.ui{original?.sleep("off")}
            store.prefs.edit().putBoolean("autoClipboard",false).remove("sleepCheckpoint").commit()
            val suffix=System.nanoTime().toString().takeLast(12)
            repeat(3){i->
                val url="https://www.douyin.com/video/999029$suffix$i"
                assertNull("Never overwrite an existing fixture",store.find(url))
                val added=store.add(SharedLink.from(url));assertTrue(added.second)
                val e=added.first;clips.add(e)
                val file=File(store.audioDir,e.id+".m4a")
                DeviceFixture.inst.context.assets.open("six-seconds.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
                store.update(e.id){it.title="Playback revision QA $i";it.author="本地合成测试";it.audio=file.name
                    it.durationMs=6000;it.metadataReady=true;it.state=Readiness.READY;it.positionMs=0;it.completed=false}
            }
            // Appended fixtures are the queue tail: even an unexpected auto-transition cannot reach real entries.
            block(clips.map{store.get(it.id)!!})
        }finally{
            try{
                DeviceFixture.ui{
                    PlaybackService.active?.let{s->
                        if(s.currentId in clips.map{it.id})s.detach(s.currentId!!)
                        s.speed(saved["speed"] as? Float ?: 1f,false)
                        s.sleep("off")
                        policy?.forEach{(name,value)->ListeningPolicy::class.java.getDeclaredField(name).apply{isAccessible=true}.set(s.policy,value)}
                        // Persist restored private fields before a later service teardown can save them.
                        PlaybackService::class.java.getDeclaredMethod("persistPolicy").apply{isAccessible=true}.invoke(s)
                    }
                    val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.STARTED,
                        androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STOPPED)
                        .flatMap{monitor.getActivitiesInStage(it)}.filterIsInstance<MainActivity>()
                        .filter{it !in activitiesBefore}.distinct().forEach{sheet(it)?.closeAll(true);it.finish()}
                }
                if(original==null){
                    stopService()
                }
            }finally{
                clips.forEach{store.delete(it.id)}
                val edit=store.prefs.edit()
                saved.forEach{(key,value)->when(value){
                    null->edit.remove(key)
                    is String->edit.putString(key,value)
                    is Float->edit.putFloat(key,value)
                    is Boolean->edit.putBoolean(key,value)
                    else->error("Unexpected preference type for $key")
                }}
                assertTrue("Restore playback preferences",edit.commit())
            }
        }
    }

    /** Old-APK reproducer: all app members referenced here already exist in 0.2.8. */
    @Test fun speedSheetAdjustThenEnableCurrentRestoresOriginalGlobalOnNext()=isolated{clips->
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("queue");s.sleep("count",clips.size);s.speed(1f,false);s.play(clips[0].id,true);s.pause()}
        val panel=speedPanel(a)
        ruler(panel,1.5f)
        assertSpeed(s,1.5f,false)
        scope(panel,true)
        assertSpeed(s,1.5f,true)
        DeviceFixture.ui{sheet(a)!!.closeAll(true);s.next();s.pause()}
        assertEquals(clips[1].id,DeviceFixture.ui{s.currentId})
        val speed=DeviceFixture.ui{s.player.playbackParameters.speed};val global=store.prefs.getFloat("speed",0f)
        assertEquals("After next: player=$speed, global preference=$global",1f,speed,.001f)
        assertEquals("Current-entry editing must preserve the original global speed",1f,global,.001f)
        assertFalse(DeviceFixture.ui{s.speedForCurrent})
    }

    /** Do not create a MediaController or invoke onGetSession: that can register the missing session. */
    @Test fun coldDirectPlaybackRegistersSessionAndBecomesForegroundWithoutController()=isolated{clips->
        launch() // Keep the app visible so startService uses the same permitted path as MainActivity.
        stopService()
        DeviceFixture.context.startService(Intent(DeviceFixture.context,PlaybackService::class.java)
            .setAction("cn.yeting.PLAY").putExtra("id",clips[0].id))
        DeviceFixture.await{DeviceFixture.ui{PlaybackService.active?.let{it.currentId==clips[0].id && it.player.isPlaying}==true}}
        val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.sleep("count",clips.size)}
        val mediaService:MediaSessionService=s
        assertTrue("Direct playback did not register its session with MediaSessionService",DeviceFixture.ui{mediaService.sessions.isNotEmpty()})
        DeviceFixture.await(5000){DeviceFixture.ui{mediaService.isPlaybackOngoing}}
        assertTrue(DeviceFixture.ui{mediaService.sessions.any{it.player===s.player}})
        assertForegroundNotification(s)
    }

    @Test fun speedSheetEnableThenAdjustPreservesGlobalOnePointTwoOnNext()=isolated{clips->
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("queue");s.sleep("count",clips.size);s.speed(1.2f,false);s.play(clips[0].id,true);s.pause()}
        val panel=speedPanel(a);scope(panel,true);ruler(panel,1.8f)
        assertSpeed(s,1.8f,true)
        assertEquals(1.2f,store.prefs.getFloat("speed",0f),.001f)
        DeviceFixture.ui{sheet(a)!!.closeAll(true);s.next();s.pause()}
        assertEquals(clips[1].id,DeviceFixture.ui{s.currentId});assertSpeed(s,1.2f,false)
        assertEquals(1.2f,store.prefs.getFloat("speed",0f),.001f)
    }

    @Test fun speedSheetAdjustThenEnablePreservesGlobalOnePointTwoOnNext()=isolated{clips->
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("queue");s.sleep("count",clips.size);s.speed(1.2f,false);s.play(clips[0].id,true);s.pause()}
        val panel=speedPanel(a);ruler(panel,1.7f);scope(panel,true)
        assertSpeed(s,1.7f,true)
        assertEquals(1.2f,store.prefs.getFloat("speed",0f),.001f)
        DeviceFixture.ui{sheet(a)!!.closeAll(true);s.next();s.pause()}
        assertEquals(clips[1].id,DeviceFixture.ui{s.currentId});assertSpeed(s,1.2f,false)
        assertEquals(1.2f,store.prefs.getFloat("speed",0f),.001f)
    }

    @Test fun temporarySpeedSurvivesPauseReopenAndActualSameEntryLoop()=isolated{clips->
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("repeat");s.sleep("off");s.speed(1.2f,false);s.play(clips[0].id,true);s.pause()}
        var panel=speedPanel(a);scope(panel,true);ruler(panel,1.8f)
        DeviceFixture.ui{sheet(a)!!.closeAll(true);s.play(clips[0].id);s.pause()}
        assertSpeed(s,1.8f,true)
        panel=speedPanel(a)
        assertTrue(DeviceFixture.ui{find<Switch>(panel)!!.isChecked})
        assertEquals(13,DeviceFixture.ui{find<SeekBar>(panel)!!.progress})
        DeviceFixture.ui{sheet(a)!!.closeAll(true)}
        val repeated=java.util.concurrent.atomic.AtomicBoolean(false)
        val listener=object:Player.Listener{
            override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int){
                if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && item?.mediaId==clips[0].id)repeated.set(true)
            }
        }
        try{
            DeviceFixture.ui{s.player.addListener(listener);s.seek(5000);s.play(clips[0].id)}
            DeviceFixture.await(10000){repeated.get()}
            DeviceFixture.ui{s.pause()}
            assertEquals(clips[0].id,DeviceFixture.ui{s.currentId});assertSpeed(s,1.8f,true)
            assertEquals(1.2f,store.prefs.getFloat("speed",0f),.001f)
        }finally{DeviceFixture.ui{s.player.removeListener(listener)}}
    }

    @Test fun openSpeedPanelRebindsOnManualAndAutomaticTrackChanges()=isolated{clips->
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("queue");s.sleep("count",clips.size);s.speed(1.2f,false);s.play(clips[0].id,true);s.pause()}
        val panel=speedPanel(a);scope(panel,true);ruler(panel,1.8f)
        DeviceFixture.ui{s.next();s.pause()}
        assertEquals(clips[1].id,DeviceFixture.ui{s.currentId})
        assertPanelSpeed(a,panel,1.2f,false);assertSpeed(s,1.2f,false)
        // Editing the newly bound entry must use its global baseline, not the old entry's draft.
        ruler(panel,1.6f);scope(panel,true)
        assertSpeed(s,1.6f,true);assertEquals(1.2f,store.prefs.getFloat("speed",0f),.001f)
        DeviceFixture.ui{s.seek(5000);s.play(clips[1].id)}
        DeviceFixture.await(10000){DeviceFixture.ui{s.currentId==clips[2].id}}
        DeviceFixture.ui{s.pause()}
        assertPanelSpeed(a,panel,1.2f,false);assertSpeed(s,1.2f,false)
        assertEquals(1.2f,store.prefs.getFloat("speed",0f),.001f)
        DeviceFixture.ui{s.previous();s.pause()}
        assertEquals(clips[1].id,DeviceFixture.ui{s.currentId});assertPanelSpeed(a,panel,1.2f,false)
        scope(panel,true);ruler(panel,1.5f);scope(panel,false)
        assertSpeed(s,1.5f,false);assertEquals(1.5f,store.prefs.getFloat("speed",0f),.001f)
    }

    private fun assertPanelSpeed(a:MainActivity,panel:View,value:Float,currentOnly:Boolean){
        DeviceFixture.await(3000){DeviceFixture.ui{
            val toggle=panel.findViewWithTag<Switch>("speed-scope")
            sheet(a)?.currentPage===panel && panel.isAttachedToWindow && toggle?.isChecked==currentOnly &&
                find<SeekBar>(panel)?.progress==(value*10-5).toInt() &&
                panel.findViewWithTag<TextView>("speed-value")?.text?.toString()==PlaybackSpeed.label(value)
        }}
        val global=DeviceFixture.ui{panel.findViewWithTag<TextView>("speed-global")?.text?.toString()}
            ?: error("Global-speed explanation is missing")
        assertTrue(global,global.contains(PlaybackSpeed.label(store.prefs.getFloat("speed",1f))))
    }

    @Test fun directPlaybackSurvivesHomeLockAndTaskRemovalWithoutController()=isolated{clips->
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("repeat");s.sleep("off")}
        DeviceFixture.context.startService(Intent(DeviceFixture.context,PlaybackService::class.java)
            .setAction("cn.yeting.PLAY").putExtra("id",clips[0].id))
        DeviceFixture.await{DeviceFixture.ui{s.player.isPlaying}}
        assertForegroundNotification(s)
        try{
            assertTrue(device.pressHome());assertPlaybackAdvances(s,clips[0].id)
            device.sleep();assertFalse(device.isScreenOn);assertPlaybackAdvances(s,clips[0].id)
            device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
            val tasks=DeviceFixture.context.getSystemService(ActivityManager::class.java).appTasks
            val task=tasks.single{it.taskInfo.taskId==DeviceFixture.ui{a.taskId}}
            DeviceFixture.ui{task.finishAndRemoveTask()}
            DeviceFixture.await{DeviceFixture.ui{a.isDestroyed}}
            assertPlaybackAdvances(s,clips[0].id);assertForegroundNotification(s)
        }finally{device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")}
    }

    private fun assertPlaybackAdvances(s:PlaybackService,id:String){
        val before=DeviceFixture.ui{s.player.currentPosition};val start=SystemClock.elapsedRealtime()
        DeviceFixture.await(3000){DeviceFixture.ui{
            assertSame("Task lifecycle replaced the active service",s,PlaybackService.active)
            s.currentId==id && s.player.isPlaying && SystemClock.elapsedRealtime()-start>=700 &&
                kotlin.math.abs(s.player.currentPosition-before)>=200
        }}
        assertTrue(DeviceFixture.ui{(s as MediaSessionService).isPlaybackOngoing})
    }

    @Test fun pausedDirectPlaybackStopsWhenTaskIsRemovedWithoutController()=isolated{clips->
        val a=launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("repeat");s.sleep("off");s.play(clips[0].id,true)}
        DeviceFixture.await{DeviceFixture.ui{s.player.isPlaying}};assertForegroundNotification(s)
        DeviceFixture.ui{s.pause()}
        // Media3's foreground grace period is separate from the paused player's state.
        val task=DeviceFixture.context.getSystemService(ActivityManager::class.java).appTasks.single{it.taskInfo.taskId==DeviceFixture.ui{a.taskId}}
        DeviceFixture.ui{task.finishAndRemoveTask()}
        DeviceFixture.await{DeviceFixture.ui{PlaybackService.active==null}}
        assertFalse(DeviceFixture.context.getSystemService(NotificationManager::class.java).activeNotifications.any{
            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE!=0
        })
    }

    @Test fun rapidPlayPauseTaskRemovalAndReopenStayQuietPastForegroundTimeout()=isolated{clips->
        var a=launch();var s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("repeat");s.speed(1f,false);s.sleep("minutes",10)}
        val deadline=DeviceFixture.ui{s.policy.deadlineMs}
        repeat(3){cycle->
            val previous=s;val oldActivity=a
            // Pause in the same main-thread turn as prepare/play, exercising notification/startup races.
            DeviceFixture.ui{
                if(cycle==2)s.toggle()else s.play(clips[0].id,cycle==0)
                s.pause()
            }
            assertFalse(DeviceFixture.ui{s.player.playWhenReady || s.player.isPlaying})
            val position=DeviceFixture.ui{s.player.currentPosition}
            val taskId=DeviceFixture.ui{a.taskId}
            val task=DeviceFixture.context.getSystemService(ActivityManager::class.java).appTasks.single{it.taskInfo.taskId==taskId}
            DeviceFixture.ui{task.finishAndRemoveTask()}
            DeviceFixture.await{DeviceFixture.ui{oldActivity.isDestroyed && PlaybackService.active==null}}
            a=launch();s=DeviceFixture.ui{PlaybackService.active!!}
            assertNotSame(previous,s)
            assertEquals(clips[0].id,DeviceFixture.ui{s.currentId})
            assertEquals(Player.STATE_IDLE,DeviceFixture.ui{s.player.playbackState})
            assertFalse(DeviceFixture.ui{s.player.playWhenReady || s.player.isPlaying})
            assertEquals(position.toDouble(),DeviceFixture.ui{s.player.currentPosition}.toDouble(),150.0)
            assertEquals("minutes",DeviceFixture.ui{s.policy.sleep})
            assertEquals(deadline,DeviceFixture.ui{s.policy.deadlineMs})
            assertEquals(0,DeviceFixture.ui{s.policy.remainingTracks})
        }
        // Keep the final restored service alive and untouched beyond the 15s system FGS timeout.
        // A delayed foreground-start crash must fail this instrumentation, not get hidden by cleanup.
        val quietStart=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-quietStart<20_000){
            DeviceFixture.ui{
                assertSame(s,PlaybackService.active)
                assertEquals(clips[0].id,s.currentId)
                assertEquals(Player.STATE_IDLE,s.player.playbackState)
                assertFalse(s.player.playWhenReady || s.player.isPlaying)
                assertEquals("minutes",s.policy.sleep)
                assertEquals(deadline,s.policy.deadlineMs)
                assertEquals(0,s.policy.remainingTracks)
            }
            Thread.sleep(250)
        }
        assertTrue(SystemClock.elapsedRealtime()-quietStart>15_000)
    }

    @Test fun readyTailPauseResumesAtItsChosenPosition()=isolated{clips->
        launch();val s=DeviceFixture.ui{PlaybackService.active!!}
        DeviceFixture.ui{s.playbackMode("repeat");s.sleep("minutes",10);s.play(clips[0].id,true)}
        DeviceFixture.await{DeviceFixture.ui{s.player.isPlaying}}
        DeviceFixture.ui{s.pause();s.seek(5800)}
        DeviceFixture.await{DeviceFixture.ui{s.player.playbackState==Player.STATE_READY}}
        DeviceFixture.ui{
            s.toggle();s.pause()
            assertTrue("Normal pause/continue must not restart the last 500ms",s.player.currentPosition>=5700)
            s.play(clips[0].id);s.pause()
            assertTrue("Same-entry play must not restart the paused tail",s.player.currentPosition>=5700)
        }
    }

    @Suppress("DEPRECATION")
    private fun assertForegroundNotification(s:PlaybackService){
        val manager=DeviceFixture.context.getSystemService(ActivityManager::class.java)
        DeviceFixture.await(5000){manager.getRunningServices(Int.MAX_VALUE).any{it.service.className==PlaybackService::class.java.name && it.foreground}}
        val notifications=DeviceFixture.context.getSystemService(NotificationManager::class.java)
        DeviceFixture.await(5000){notifications.activeNotifications.any{n->
            n.notification.flags and Notification.FLAG_FOREGROUND_SERVICE!=0 &&
                n.notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION)!=null
        }}
        assertTrue("The directly started service must still be playing",DeviceFixture.ui{s.player.isPlaying})
    }
}
