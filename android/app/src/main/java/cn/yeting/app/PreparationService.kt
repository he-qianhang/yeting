package cn.yeting.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.IBinder
import android.os.PowerManager
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class PreparationService:Service() {
    private val executor=Executors.newSingleThreadExecutor()
    private val store get()=NightApp.store
    private var wake:PowerManager.WakeLock?=null
    @Volatile private var stopped=false
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate();active=true
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("preparing","准备音频",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"preparing").setSmallIcon(R.drawable.ic_app).setContentTitle("夜听正在整理待听内容").setContentText("准备完成后，可以离线收听").setContentIntent(pending).setOngoing(true).build()
        startForeground(12,n)
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"yeting:prepare").apply{acquire(60*60*1000L)}
        File(cacheDir,"preparing").listFiles()?.forEach{it.deleteRecursively()}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val only=intent?.getStringExtra("id")
        val metadataOnly=intent?.getBooleanExtra("metadataOnly",false) ?: false
        if(only!=null)cancelled.remove(only)else if(!metadataOnly)cancelled.clear()
        executor.execute {
            try {
                val candidates=store.all().filter{(only==null || it.id==only) && (if(metadataOnly)!it.metadataReady else it.state!=Readiness.READY)}
                for(entry in candidates){if(stopped)break;if(entry.id !in cancelled)prepare(entry,metadataOnly)}
            }finally{stopSelfResult(startId)}
        }
        return START_NOT_STICKY
    }
    private fun prepare(entry:Entry,metadataOnly:Boolean) {
        val source=PhoneSource(applicationContext)
        var id=entry.id
        var phase="读取信息"
        val directory=File(cacheDir,"preparing/${entry.id}")
        currentId=id
        try {
            fun isCancelled()=stopped || id in cancelled || store.get(id)==null || Thread.currentThread().isInterrupted
            if(isCancelled())return
            store.update(id){if(!metadataOnly)it.state=Readiness.WORKING;it.message="正在获取${entry.platform}信息"}
            val work=source.resolve(SharedLink.from(entry.url),::isCancelled)
            id=store.applyMetadata(id,work);currentId=id
            if(isCancelled())return
            SourceDiagnostics(applicationContext).clear(id)
            if(metadataOnly){
                if(entry.platform=="YouTube")runCatching{SourceDiagnostics(applicationContext).succeeded(id,entry.platform,phase,"metadata_ready",source.attempts)}
                store.update(id){it.message=if(it.state==Readiness.READY)"已经可以听了"else "信息已补齐，准备后可离线听"};return
            }
            if(store.get(id)?.state==Readiness.READY)return
            phase="准备音频"
            store.update(id){it.state=Readiness.WORKING;it.message="正在准备原声音频"}
            var last=0L
            val audio=source.download(work,directory,processId(id),::isCancelled){percent->
                if(System.currentTimeMillis()-last>700){store.update(id){it.message=if(percent>=100)"正在整理原声音轨"else "正在准备音频 · $percent%"};last=System.currentTimeMillis()}
            }
            if(isCancelled())return
            val retriever=MediaMetadataRetriever()
            val duration=try{retriever.setDataSource(audio.absolutePath);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: work.durationMs}finally{retriever.release()}
            val target=File(store.audioDir,id+"."+audio.extension)
            check(audio.renameTo(target)){"手机空间不足，请清理后重试"}
            store.update(id){it.audio=target.name;it.durationMs=duration;it.state=Readiness.READY;it.message="已经可以听了";it.jobId=""}
            if(entry.platform=="YouTube")runCatching{SourceDiagnostics(applicationContext).succeeded(id,entry.platform,phase,"audio_ready",source.attempts)}
        }catch(e:Exception){
            runCatching{SourceDiagnostics(applicationContext).failed(id,entry.platform,phase,e,source.attempts)}
            store.update(id){if(!metadataOnly)it.state=Readiness.ERROR;it.message=PhoneSource.friendlyError(e,entry.platform,phase)}
        }finally{
            if(id in cancelled && store.get(id)?.state==Readiness.WORKING)store.update(id){it.state=Readiness.WAITING;it.message="准备已取消，可以稍后重试"}
            directory.deleteRecursively();currentId=null
        }
    }
    override fun onDestroy(){stopped=true;currentId?.let{YoutubeDL.destroyProcessById(processId(it))};executor.shutdownNow();wake?.let{if(it.isHeld)it.release()};active=false;currentId=null;super.onDestroy()}
    companion object {
        @Volatile var active=false;private set
        @Volatile var currentId:String?=null;private set
        private val cancelled=ConcurrentHashMap.newKeySet<String>()
        private fun processId(id:String)="prepare-$id"
        fun cancel(id:String){cancelled.add(id);YoutubeDL.destroyProcessById(processId(id))}
    }
}
