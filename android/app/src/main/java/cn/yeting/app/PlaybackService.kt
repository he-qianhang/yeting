package cn.yeting.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    lateinit var player:ExoPlayer; private set
    private var session:MediaSession?=null
    private val handler=Handler(Looper.getMainLooper())
    private val store get()=NightApp.store
    var currentId:String?=null; private set
    lateinit var policy:ListeningPolicy; private set
    var lastError=""; private set
    private var lastSaved=0L
    private val history=mutableListOf<String>()
    private var historyCursor=-1
    private var replacingItems=false
    private var speedEntryId:String?=null
    private lateinit var recovery:SleepRecovery
    private lateinit var diagnostics:PlaybackDiagnostics
    private var lastForeground=false
    var recoveryNotice=""; private set
    val speedForCurrent get()=speedEntryId!=null && speedEntryId==currentId
    val mode get()=store.prefs.getString("playMode","queue") ?: "queue"
    private val tick=object:Runnable {
        override fun run() {
            val foreground=isPlaybackOngoing
            if(foreground!=lastForeground){lastForeground=foreground;record("foreground")}
            if(policy.expired(SystemClock.elapsedRealtime())) {
                if(policy.reachDeadline(SystemClock.elapsedRealtime(),player.isPlaying)){player.pause();save()}
                persistPolicy();syncUpcoming();notifyUi()
            }
            if(player.isPlaying && SystemClock.elapsedRealtime()-lastSaved>=2000) save()
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate() {
        super.onCreate();active=this
        diagnostics=PlaybackDiagnostics(this);recovery=SleepRecovery(this)
        val restored=recovery.load();policy=restored.policy;recoveryNotice=restored.notice
        store.prefs.edit().putString("sleep",policy.sleep).apply()
        player=ExoPlayer.Builder(this).setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(15_000).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        val speed=PlaybackSpeed.normalize(store.prefs.getFloat("speed",1f))
        store.prefs.edit().putFloat("speed",speed).apply();player.setPlaybackSpeed(speed)
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session=MediaSession.Builder(this,player).setSessionActivity(intent).build().also{addSession(it)}
        record("service_created")
        player.addListener(object:Player.Listener {
            override fun onIsPlayingChanged(isPlaying:Boolean) {save();record("playing");notifyUi()}
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){record("play_when_ready_$reason")}
            override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) {
                if(replacingItems || mediaItem==null)return
                val previous=currentId
                if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous!=null) {
                    store.update(previous){it.positionMs=it.durationMs;it.completed=true}
                    if(!policy.completed(SystemClock.elapsedRealtime())) {
                        player.pause();policy.choose("off",SystemClock.elapsedRealtime());persistPolicy()
                        // A prequeued item can reach its boundary before the 500ms timer tick.
                        // Keep the completed item selected and prevent the next item from playing.
                        val entry=store.get(previous)
                        if(entry!=null){
                            replacingItems=true
                            try {player.setMediaItem(mediaItem(entry));player.prepare();player.seekTo(entry.durationMs)}
                            finally {replacingItems=false}
                        }
                        notifyUi();return
                    }
                }
                val id=mediaItem.mediaId
                if(id!=previous) {
                    restoreSpeedFor(id);currentId=id
                    if(history.getOrNull(historyCursor)!=id){while(history.size>historyCursor+1)history.removeAt(history.lastIndex);history.add(id);historyCursor=history.lastIndex}
                    store.prefs.edit().putString("last",id).apply()
                }
                persistPolicy();syncUpcoming();record("track_transition");notifyUi()
            }
            override fun onPlaybackStateChanged(state:Int) {
                if(state==Player.STATE_ENDED && !replacingItems) {
                    val id=currentId
                    if(id!=null) store.update(id) {it.positionMs=it.durationMs;it.completed=true}
                    policy.completed(SystemClock.elapsedRealtime())
                    policy.choose("off",SystemClock.elapsedRealtime());persistPolicy()
                }
                notifyUi()
            }
            override fun onPlayerError(error:androidx.media3.common.PlaybackException) {
                lastError="音频暂时无法播放，可清理后重新准备";save();record("error_${error.errorCode}");notifyUi()
            }
        })
        restored.entryId?.let{store.get(it)}?.takeIf{it.state==Readiness.READY && store.audioFile(it).isFile}?.let{entry->
            currentId=entry.id;replacingItems=true
            // Restoring a limit must not start decoders/foreground playback before a user resumes.
            try{player.setMediaItem(mediaItem(entry));player.seekTo(entry.positionMs)}
            finally{replacingItems=false}
            syncUpcoming()
        }
        handler.post(tick)
    }
    fun play(id:String,restart:Boolean=false,remember:Boolean=true) {
        if((id!=currentId || restart) && stopAfterTailSkip())return
        val entry=store.get(id) ?: return
        if(entry.state!=Readiness.READY || !store.audioFile(entry).isFile) {lastError="这条音频还没准备好";notifyUi();return}
        recoveryNotice=""
        if(currentId==id && player.playbackState!=Player.STATE_ENDED && !restart) {resumeCurrent();return}
        player.pause();save();replacingItems=true;player.stop();restoreSpeedFor(id);currentId=id;lastError=""
        if(remember && history.getOrNull(historyCursor)!=id){while(history.size>historyCursor+1)history.removeAt(history.lastIndex);history.add(id);historyCursor=history.lastIndex}
        player.setMediaItem(mediaItem(entry))
        replacingItems=false
        syncUpcoming()
        player.seekTo(if(restart || entry.completed || entry.positionMs>=entry.durationMs-500) 0 else entry.positionMs)
        player.prepare();player.play()
        recoveryNotice="";persistPolicy();store.prefs.edit().putString("last",id).apply();record("play_requested");notifyUi()
    }
    fun pause() {player.pause();save();notifyUi()}
    fun toggle() {recoveryNotice="";if(player.isPlaying)pause() else if(currentId!=null)resumeCurrent() else store.prefs.getString("last",null)?.let {play(it)}}
    private fun resumeCurrent(){
        val entry=store.get(currentId)
        if(player.playbackState==Player.STATE_ENDED || (player.playbackState==Player.STATE_IDLE && entry?.completed==true)){
            player.seekTo(0);entry?.let{store.update(it.id,notify=false){saved->saved.positionMs=0;saved.completed=false}};syncUpcoming()
        }
        if(player.playbackState==Player.STATE_IDLE)player.prepare()
        player.play()
    }
    fun seek(position:Long) {if(currentId!=null){player.seekTo(position.coerceIn(0,player.duration.takeIf{it>0} ?: store.get(currentId)?.durationMs?.takeIf{it>0} ?: Long.MAX_VALUE));save(userSeek=true);notifyUi()}}
    private fun restoreSpeedFor(id:String?) {
        if(id!=currentId){speedEntryId=null;player.setPlaybackSpeed(PlaybackSpeed.normalize(store.prefs.getFloat("speed",1f)))}
    }
    fun speed(value:Float)=speed(value,speedForCurrent)
    fun speed(value:Float,currentOnly:Boolean) {
        require(value in .5f..2f && kotlin.math.abs(PlaybackSpeed.normalize(value)-value)<.001f)
        speedEntryId=if(currentOnly)currentId else null
        if(!currentOnly)store.prefs.edit().putFloat("speed",value).apply()
        player.setPlaybackSpeed(value);notifyUi()
    }
    fun sleep(mode:String,minutes:Int=0,finishCurrent:Boolean=false) {recoveryNotice="";policy.choose(mode,SystemClock.elapsedRealtime(),minutes,finishCurrent);persistPolicy();syncUpcoming();record("sleep_changed");notifyUi()}
    fun continuous(enabled:Boolean) {policy.continuous=enabled;persistPolicy();syncUpcoming();notifyUi()}
    fun playbackMode(value:String) {
        require(value in listOf("queue","repeat","shuffle"))
        if(value=="shuffle" && mode!="shuffle")store.shuffleQueue(currentId)
        store.prefs.edit().putString("playMode",value).apply();policy.continuous=true;syncUpcoming();notifyUi()
    }
    fun queueChanged(){while(history.size>historyCursor+1)history.removeAt(history.lastIndex);syncUpcoming();notifyUi()}
    private fun persistPolicy() {store.prefs.edit().putBoolean("continuous",policy.continuous).putString("sleep",policy.sleep).apply();recovery.save(policy,currentId)}
    private fun record(event:String){diagnostics.event(event,"registered=${sessions.isNotEmpty()} foreground=$isPlaybackOngoing playing=${player.isPlaying} position=${player.currentPosition} sleep=${policy.sleep} deadline=${policy.deadlineMs} tracks=${policy.remainingTracks}")}
    fun playbackReport()=diagnostics.report()
    private fun mediaItem(entry:Entry)=MediaItem.Builder().setMediaId(entry.id).setUri(android.net.Uri.fromFile(store.audioFile(entry)))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.author.ifEmpty {entry.platform}).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST).build()).build()
    private fun nextReady(id:String?):Entry? {
        if(id==null)return null
        if(mode=="repeat")return store.get(id)?.takeIf{it.state==Readiness.READY && store.audioFile(it).isFile}
        val all=store.queue(mode=="shuffle");val index=all.indexOfFirst{it.id==id}
        return if(index>=0)all.drop(index+1).firstOrNull{it.state==Readiness.READY && store.audioFile(it).isFile}else null
    }
    private fun syncUpcoming() {
        if(replacingItems || currentId==null || player.mediaItemCount==0)return
        val now=SystemClock.elapsedRealtime()
        val wanted=if(policy.shouldAdvance(now) && (policy.sleep!="count" || policy.remainingTracks>1))nextReady(currentId)else null
        replacingItems=true
        try {
            val index=player.currentMediaItemIndex
            if(index<0)return
            if(player.mediaItemCount>index+1)player.removeMediaItems(index+1,player.mediaItemCount)
            if(index>0)player.removeMediaItems(0,index)
            if(wanted!=null)player.addMediaItem(mediaItem(wanted))
        }finally{replacingItems=false}
    }
    fun next() {
        if(stopAfterTailSkip())return
        while(historyCursor+1<history.size){historyCursor++;val id=history[historyCursor];val e=store.get(id);if(e?.state==Readiness.READY && store.audioFile(e).isFile){play(id,remember=false);return}}
        val all=store.queue(mode=="shuffle");val index=all.indexOfFirst {it.id==currentId}
        val next=if(index>=0) all.drop(index+1).firstOrNull {it.state==Readiness.READY && store.audioFile(it).isFile} else null
        if(next!=null) play(next.id) else {player.pause();save()}
    }
    fun previous() {
        if(stopAfterTailSkip())return
        while(historyCursor>0){historyCursor--;val id=history[historyCursor];val e=store.get(id);if(e?.state==Readiness.READY && store.audioFile(e).isFile){play(id,remember=false);return}}
        if(mode=="shuffle")return
        val all=store.queue(mode=="shuffle");val index=all.indexOfFirst{it.id==currentId}
        all.take(index.coerceAtLeast(0)).lastOrNull{it.state==Readiness.READY && store.audioFile(it).isFile}?.let{history.add(0,it.id);historyCursor=0;play(it.id,remember=false)}
    }
    fun detach(id:String) {if(currentId==id){pause();replacingItems=true;player.clearMediaItems();replacingItems=false;restoreSpeedFor(null);currentId=null;notifyUi()}else syncUpcoming()}
    private fun stopAfterTailSkip():Boolean {
        val expired=policy.expired(SystemClock.elapsedRealtime())
        if(!policy.finishingCurrent && !expired)return false
        player.pause();save();policy.choose("off",SystemClock.elapsedRealtime());persistPolicy();syncUpcoming();notifyUi();return true
    }
    private fun save(userSeek:Boolean=false) {
        val id=currentId ?: return
        if(player.playbackState!=Player.STATE_IDLE || userSeek) {
            val pos=player.currentPosition.coerceAtLeast(0)
            store.update(id,notify=false) {it.positionMs=pos;if((player.isPlaying && pos<it.durationMs-1000) || (userSeek && pos<it.durationMs))it.completed=false}
            lastSaved=SystemClock.elapsedRealtime()
        }
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo)=session
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            "cn.yeting.PLAY" -> intent.getStringExtra("id")?.let {play(it)}
            "cn.yeting.TOGGLE" -> toggle()
            "cn.yeting.PAUSE" -> pause()
        }
        return super.onStartCommand(intent,flags,startId)
    }
    override fun onTaskRemoved(rootIntent:Intent?) {save();persistPolicy();record("task_removed");super.onTaskRemoved(rootIntent)}
    override fun onDestroy() {handler.removeCallbacks(tick);save();persistPolicy();record("service_destroyed");active=null;session?.let{removeSession(it);it.release()};player.release();notifyUi();super.onDestroy()}
    companion object {
        var active:PlaybackService?=null;private set
        var changed:(()->Unit)?=null
        private fun notifyUi(){changed?.invoke()}
    }
}
