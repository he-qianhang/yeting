package cn.yeting.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.*

internal data class SteadyProfile(val preDb:Double,val postDb:Double,val limited:Boolean=false) {
    init {require(preDb.isFinite() && postDb.isFinite() && preDb in -100.0..SteadyTuning.MAX_BOOST_DB && postDb>=0 && preDb+postDb<=SteadyTuning.MAX_BOOST_DB+.0001)}
    val pre get()=10.0.pow(preDb/20)
    val post get()=10.0.pow(postDb/20)
}

/** Optional, rebuildable analysis; library.json and original audio are never rewritten here. */
internal class SteadyAudio(private val context:Context) {
    private val directory=File(context.filesDir,"steady-v${SteadyTuning.VERSION}").apply{mkdirs()}
    private val legacyDirectory=File(context.filesDir,"steady-v2")
    private val profiles=ConcurrentHashMap<String,JSONObject>()
    private val cacheLock=Any()
    private val failures=ConcurrentHashMap.newKeySet<String>()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var process:Process?=null
    @Volatile var workingId:String?=null;private set
    @Volatile var failureDetail="";private set
    var changed:((String)->Unit)?=null
    val enabled get()=NightApp.store.prefs.getBoolean("steadyVolume",false)
    fun failed(id:String)=id in failures
    private fun key(id:String)=java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun cacheFile(id:String)=AtomicFile(File(directory,key(id)+".json"))
    private fun save(id:String,saved:JSONObject)=synchronized(cacheLock){
        val target=cacheFile(id);val stream=target.startWrite()
        try{stream.write(saved.toString().toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
        catch(error:Exception){target.failWrite(stream);throw error}
        profiles[id]=saved
    }

    fun profile(id:String):SteadyProfile?=synchronized(cacheLock){readProfile(id)}
    private fun readProfile(id:String):SteadyProfile? {
        val entry=NightApp.store.get(id) ?: return null
        val audio=NightApp.store.audioFile(entry)
        if(!audio.isFile)return null
        return runCatching {
            val saved=profiles[id] ?: runCatching{JSONObject(String(cacheFile(id).readFully(),Charsets.UTF_8))}.getOrElse{
                JSONObject(String(AtomicFile(File(legacyDirectory,key(id)+".json")).readFully(),Charsets.UTF_8))
            }.also{profiles[id]=it}
            val version=saved.getInt("version")
            if(version !in listOf(2,SteadyTuning.VERSION) || saved.getLong("bytes")!=audio.length() || saved.getLong("modified")!=audio.lastModified())return null
            val pre=saved.getDouble("pre_db");val post=saved.getDouble("post_db")
            if(version==2){
                check(pre>= -100 && pre<=SteadyTuning.LEGACY_BOOST_DB && post>=0 && pre+post<=SteadyTuning.LEGACY_BOOST_DB+.0001)
                // v2's below -45 LUFS / nonfinite branch ALWAYS stored exactly 0/0.
                // Other valid v2 profiles therefore permit the fixed +6 dB correction.
                if(pre!=0.0 || post!=0.0){
                    val migrated=JSONObject(saved.toString()).put("version",SteadyTuning.VERSION)
                        .put("post_db",post+6.0).put("migrated_from",2)
                    val result=SteadyProfile(pre,post+6.0,saved.optBoolean("limited"))
                    save(id,migrated);return result
                }
            }
            SteadyProfile(pre,post,saved.optBoolean("limited"))
        }.getOrNull()
    }
    fun needsAnalysis(id:String):Boolean=profile(id)==null || profiles[id]?.optInt("version")!=SteadyTuning.VERSION

    fun allowRetry(id:String?=null){if(id==null)failures.clear()else failures.remove(id)}
    fun cancel(){process?.destroyForcibly()}
    private fun notify(id:String){main.post{changed?.invoke(id)}}

    @Synchronized fun analyze(id:String,cancelled:()->Boolean) {
        if(cancelled())return
        if(!needsAnalysis(id))return
        workingId=id;failures.remove(id);notify(id)
        var temporary:File?=null
        var analyzedIdentity:String?=null
        try {
            val entry=NightApp.store.get(id) ?: return
            val audio=NightApp.store.audioFile(entry)
            check(audio.isFile)
            val bytes=audio.length();val modified=audio.lastModified()
            analyzedIdentity=audio.absolutePath+"|"+bytes+"|"+modified
            // Only unpack bundled runtime files; no source request or updater is invoked.
            YoutubeDL.init(context);FFmpeg.init(context)
            temporary=File.createTempFile("steady-",".log",context.cacheDir)
            fun measure(filter:String,complex:Boolean=false):JSONObject {
                check(!cancelled())
                val args=listOf(File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so").absolutePath,
                    "-hide_banner","-nostdin","-nostats","-xerror","-threads","1","-filter_threads","1","-filter_complex_threads","1","-i",audio.absolutePath,
                    if(complex)"-filter_complex"else "-af",filter,"-f","null","-")
                val builder=ProcessBuilder(args).redirectErrorStream(true).redirectOutput(temporary)
                builder.environment()["LD_LIBRARY_PATH"]=listOf("python","ffmpeg").joinToString(":"){File(context.noBackupFilesDir,"youtubedl-android/packages/$it/usr/lib").absolutePath}
                val child=builder.start();process=child
                try {
                    check(child.waitFor(20,TimeUnit.MINUTES)){"Audio analysis timed out"}
                    check(!cancelled()){"Audio analysis interrupted"}
                    check(child.exitValue()==0){"Audio analysis exit ${child.exitValue()}: ${temporary.readText().takeLast(1800)}"}
                    val log=temporary.readText()
                    check(log.length<2_000_000)
                    val start=log.lastIndexOf('{');val end=log.lastIndexOf('}')
                    check(start>=0 && end>start)
                    return JSONObject(log.substring(start,end+1))
                }finally {if(child.isAlive)child.destroyForcibly();process=null}
            }
            val meter="loudnorm=I=${SteadyTuning.TARGET_LUFS}:TP=-2:print_format=json"
            val input=measure(meter)
            fun number(value:String)=when(value.lowercase()){"-inf"->Double.NEGATIVE_INFINITY;"inf"->Double.POSITIVE_INFINITY;"nan"->Double.NaN;else->value.toDouble()}
            val loudness=number(input.getString("input_i"))
            val peak=number(input.getString("input_tp"))
            var pre=0.0;var post=0.0;var limited=false
            if(loudness.isFinite() && loudness> -60 && peak.isFinite()){
                val cap=if(loudness< -45)0.0 else SteadyTuning.MAX_BOOST_DB
                val difference=SteadyTuning.DETECTOR_LUFS-loudness
                pre=min(if(abs(difference)>.5)difference else 0.0,min(cap,SteadyTuning.LEGACY_BOOST_DB)).coerceAtLeast(-100.0)
                val graph="[0:a]volume=${pre}dB,${SteadyTuning.slowFilter},asplit=2[main][detector];"+
                    "[main]adelay=5:all=1[delayed];[detector]apad=pad_dur=0.005[control];"+
                    "[delayed][control]${SteadyTuning.fastFilter},atrim=start=0.005,$meter"
                val compressed=measure(graph,true).getString("input_i").toDouble()
                if(compressed.isFinite()){
                    val requested=max(0.0,SteadyTuning.TARGET_LUFS-compressed)
                    post=min(cap-pre,requested);limited=requested>post+.001 || difference>cap+.001
                }
            }
            val result=SteadyProfile(pre,post,limited)
            check(!cancelled() && audio.isFile && audio.length()==bytes && audio.lastModified()==modified)
            val saved=JSONObject().put("version",SteadyTuning.VERSION).put("bytes",bytes).put("modified",modified)
                .put("pre_db",result.preDb).put("post_db",result.postDb).put("limited",result.limited)
                .put("input_lufs",if(loudness.isFinite())loudness else JSONObject.NULL).put("low_level",!loudness.isFinite() || loudness< -45)
            save(id,saved)
        }catch(error:Exception){
            val current=NightApp.store.get(id)?.let{NightApp.store.audioFile(it)}
            val same=analyzedIdentity==null || (current!=null && analyzedIdentity==current.absolutePath+"|"+current.length()+"|"+current.lastModified())
            if(!cancelled() && same){
            failureDetail=(error.javaClass.simpleName+": "+error.message.orEmpty()).takeLast(2000)
            android.util.Log.w("YeTingSteady",failureDetail);failures.add(id)
        }}
        finally {temporary?.delete();workingId=null;notify(id)}
    }
}
