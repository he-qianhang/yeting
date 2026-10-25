package cn.yeting.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import java.util.concurrent.Executors

/** Foreground local processing also works when the user leaves the settings screen. */
class SteadyAudioService:Service() {
    private val executor=Executors.newSingleThreadExecutor()
    @Volatile private var stopped=false
    @Volatile private var priority:String?=null
    private var wake:PowerManager.WakeLock?=null
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("steady","平稳音量",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"steady").setSmallIcon(R.drawable.ic_app).setContentTitle("正在平衡音频声音")
            .setContentText("可以先听，处理完成后会慢慢切换").setContentIntent(pending).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(13,notification,if(Build.VERSION.SDK_INT>=35)ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(13,notification)
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"yeting:steady").apply{acquire(60*60*1000L)}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        intent?.getStringExtra("id")?.let{priority=it}
        executor.execute{
            try {
                val store=NightApp.store
                while(!stopped && NightApp.steady.enabled){
                    val entries=store.all().filter{it.state==Readiness.READY && !NightApp.steady.failed(it.id) && NightApp.steady.needsAnalysis(it.id)}
                    val service=PlaybackService.active;val current=service?.currentId
                    val next=service?.nextReady(current)?.id
                    val entry=entries.firstOrNull{it.id==current} ?: entries.firstOrNull{it.id==next} ?: entries.firstOrNull{it.id==priority} ?: entries.firstOrNull() ?: break
                    NightApp.steady.analyze(entry.id){stopped || !NightApp.steady.enabled || store.get(entry.id)==null || Thread.currentThread().isInterrupted}
                }
            }finally{stopSelfResult(startId)}
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){stopSelf()}
    override fun onDestroy(){stopped=true;NightApp.steady.cancel();executor.shutdownNow();wake?.let{if(it.isHeld)it.release()};super.onDestroy()}
    companion object {
        fun start(context:Context,id:String?=null){
            if(NightApp.steady.enabled)context.startForegroundService(Intent(context,SteadyAudioService::class.java).apply{if(id!=null)putExtra("id",id)})
        }
    }
}
