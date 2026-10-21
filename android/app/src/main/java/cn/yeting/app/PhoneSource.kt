package cn.yeting.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PhoneWork(val id:String,val platform:String,val title:String,val author:String,val durationMs:Long,val sourceUrl:String,val mediaUrl:String="",val appUri:String="")

class PhoneSource(private val context:Context) {
    private val browser=PhoneBrowser(context)
    private val http=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    @get:JvmName("getSourceAttempts")
    internal val attempts=SourceAttempts({SourceDiagnostics(context).networkState()})
    private val youtube=YouTubeAccess(attempts,{SourceDiagnostics(context).networkState().identity},
        {android.os.SystemClock.elapsedRealtime()},{id,cancelled->YouTubeProof.create(context,id,cancelled)})
    fun resolve(link:SharedLink,cancelled:()->Boolean={false}):PhoneWork {
        initialize(context)
        val url=expand(link.url)
        if(link.platform=="抖音") {
            val id=Regex("/(?:share/)?video/(\\d+)").find(url)?.groupValues?.get(1) ?: error("没有找到具体作品，请重新复制分享链接")
            val canonical="https://www.douyin.com/video/$id"
            val d=browser.resolve(canonical,id,link.platform,cancelled)
            val media=d.getString("media_url")
            require(media.startsWith("https://"))
            return PhoneWork(id,link.platform,d.getString("title"),d.optString("author"),d.optLong("duration_ms"),canonical,media,"snssdk1128://aweme/detail/$id")
        }
        if(link.platform=="B站") {
            val id=Regex("BV[A-Za-z0-9]{10}").find(url)?.value ?: error("请使用带作品编号的B站视频链接")
            val part=android.net.Uri.parse(url).getQueryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val canonical="https://www.bilibili.com/video/$id?p=$part"
            try{return biliDirect(id,part,canonical)}catch(e:Exception){if(cancelled() || e is InterruptedException)throw InterruptedException()}
            fun nativeInfo():PhoneWork {
                if(cancelled())throw InterruptedException()
                val d=metadata(canonical,link.platform,cancelled)
                return PhoneWork(id+"_p"+part,link.platform,d.getString("title"),d.optString("uploader",d.optString("channel")),(d.optDouble("duration",0.0)*1000).toLong(),canonical)
            }
            try{return nativeInfo()}catch(e:Exception){if(cancelled() || e is InterruptedException)throw InterruptedException()}
            try {
                val d=browser.resolve(canonical,id,link.platform,cancelled)
                return PhoneWork(id+"_p"+part,link.platform,d.getString("title"),d.optString("author"),d.optLong("duration_ms"),canonical)
            }catch(e:Exception){if(cancelled() || e is InterruptedException)throw InterruptedException();return biliDirect(id,part,canonical)}
        }
        val d=metadata(url,link.platform,cancelled)
        return PhoneWork(d.getString("id"),link.platform,d.getString("title"),d.optString("uploader",d.optString("channel")),(d.optDouble("duration",0.0)*1000).toLong(),d.optString("webpage_url",url))
    }
    private fun metadata(url:String,platform:String,cancelled:()->Boolean):JSONObject {
        fun read(cookie:File?,proof:YouTubeProof?=null):JSONObject {
            fun request():JSONObject {
                val req=baseRequest(url,cookie,platform=="YouTube").addOption("--dump-single-json").addOption("--skip-download")
                if(proof!=null)applyProof(req,proof)
                return JSONObject(execute(req,"metadata-"+UUID.randomUUID(),cancelled).out)
            }
            return if(platform=="YouTube")attempts.run("metadata",if(proof!=null)"local_proof"else if(cookie==null)"anonymous"else "local_session",cancelled,::request)else request()
        }
        if(platform=="YouTube") {
            return youtube.run("metadata",SharedLink.from(url).url.substringAfter("?v="),cancelled,{
                try{read(null)}catch(e:Exception){
                    if(cancelled() || !needsAccount(e) || !hasYouTubeSession())throw e
                    withCookieFile(platform){read(it)}
                }
            },{read(null,it)})
        }
        return withCookieFile(platform){read(it)}
    }
    private fun biliDirect(id:String,part:Int,canonical:String):PhoneWork {
        fun api(path:String):JSONObject {
            val req=Request.Builder().url("https://api.bilibili.com$path").header("User-Agent",PhoneBrowser.USER_AGENT).header("Referer",canonical)
            CookieManager.getInstance().getCookie("https://www.bilibili.com/")?.let{req.header("Cookie",it)}
            return http.newCall(req.build()).execute().use{r->
                check(r.isSuccessful){"来源需要连接验证后重试"}
                val data=JSONObject(r.body?.string().orEmpty())
                check(data.optInt("code",-1)==0){"来源暂时不可用，请打开B站查看"}
                data.getJSONObject("data")
            }
        }
        val video=api("/x/web-interface/view?bvid=$id")
        val pages=video.getJSONArray("pages")
        val page=(0 until pages.length()).map{pages.getJSONObject(it)}.firstOrNull{it.optInt("page")==part} ?: error("没有找到这条分P，请打开B站查看")
        val play=api("/x/player/playurl?bvid=$id&cid=${page.getLong("cid")}&fnval=4048&fnver=0&fourk=1")
        val audios=play.optJSONObject("dash")?.optJSONArray("audio") ?: error("这条来源没有可用的独立音轨")
        val formats=(0 until audios.length()).map{audios.getJSONObject(it)}
        val audio=formats.firstOrNull{it.optString("mimeType",it.optString("mime_type")).contains("mp4")} ?: formats.first()
        val media=audio.optString("baseUrl",audio.optString("base_url"))
        check(media.startsWith("https://")){"音轨地址暂时不可用，请重试"}
        return PhoneWork(id+"_p"+part,"B站",video.getString("title")+if(pages.length()>1)" · "+page.optString("part")else "",video.getJSONObject("owner").getString("name"),page.optLong("duration")*1000,canonical,media,"bilibili://video/${video.getLong("aid")}?page=$part")
    }
    fun download(work:PhoneWork,directory:File,processId:String,cancelled:()->Boolean={false},progress:(Int)->Unit={}):File {
        initialize(context);directory.mkdirs()
        fun acquire(cookie:File?,proof:YouTubeProof?=null):File {
            if(cancelled())throw InterruptedException()
            val req=baseRequest(work.mediaUrl.ifEmpty{work.sourceUrl},if(work.mediaUrl.isEmpty())cookie else null,work.platform=="YouTube")
                .addOption("--no-simulate").addOption("--extract-audio").addOption("--audio-format","best")
                .addOption("--format",if(work.mediaUrl.isEmpty())"bestaudio[ext=m4a]/bestaudio/best"else "best")
                .addOption("--output",File(directory,"audio.%(ext)s").absolutePath)
                .addOption("--print","after_move:filepath").addOption("--newline").addOption("--no-mtime")
                .addOption("--add-header","Referer: ${work.sourceUrl}")
            if(proof!=null)applyProof(req,proof)
            val response=execute(req,processId,cancelled){percent,_,_->
                progress(percent.toInt().coerceIn(0,100))
            }
            if(cancelled())throw InterruptedException()
            val path=response.out.lineSequence().map{it.trim()}.filter{it.startsWith(directory.absolutePath+"/")}.lastOrNull() ?: error("音频处理未完成，请重试")
            val audio=File(path)
            check(audio.isFile && audio.canonicalFile.parentFile==directory.canonicalFile && audio.length()>1024){"音频文件不完整，请重试"}
            val retriever=MediaMetadataRetriever()
            val duration=try{retriever.setDataSource(audio.absolutePath);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0}finally{retriever.release()}
            check(duration>0 && (work.durationMs<=0 || kotlin.math.abs(duration-work.durationMs)<5000)){"音频时长不完整，请重新准备"}
            return audio
        }
        if(work.platform=="YouTube") {
            fun tracked(cookie:File?,proof:YouTubeProof?=null)=attempts.run("download",if(proof!=null)"local_proof"else if(cookie==null)"anonymous"else "local_session",cancelled){acquire(cookie,proof)}
            if(work.mediaUrl.isNotEmpty())return tracked(null)
            return youtube.run("download",work.id,cancelled,{
                try{tracked(null)}catch(e:Exception){
                    if(cancelled() || !needsAccount(e) || !hasYouTubeSession())throw e
                    directory.listFiles()?.forEach{it.delete()}
                    withCookieFile(work.platform){tracked(it)}
                }
            },{proof->
                // A different client can select a different audio format; do not resume its predecessor's bytes.
                directory.listFiles()?.forEach{it.delete()}
                tracked(null,proof)
            })
        }
        return withCookieFile(work.platform){cookie->acquire(cookie)}
    }
    @JvmName("executeSourceRequest")
    internal fun execute(req:YoutubeDLRequest,processId:String,cancelled:()->Boolean,
        progress:((Float,Long,String)->Unit)?=null):com.yausername.youtubedl_android.YoutubeDLResponse {
        if(cancelled())throw InterruptedException()
        val finished=AtomicBoolean(false)
        // Metadata can produce no stdout for a long time. Cancellation cannot depend on progress callbacks.
        val watcher=Thread({
            try{while(!finished.get()){if(cancelled())YoutubeDL.destroyProcessById(processId);Thread.sleep(100)}}catch(_:InterruptedException){}
        },"source-cancellation").apply{isDaemon=true;start()}
        try {
            val response=YoutubeDL.execute(req,processId,progress)
            if(cancelled())throw InterruptedException()
            return response
        }finally{finished.set(true);watcher.interrupt()}
    }
    private fun expand(url:String):String {
        val host=android.net.Uri.parse(url).host
        if(host !in listOf("v.douyin.com","b23.tv"))return url
        http.newCall(Request.Builder().url(url).header("User-Agent",PhoneBrowser.USER_AGENT).build()).execute().use{r->
            val resolved=r.request.url.toString()
            SharedLink.from(resolved)
            return resolved
        }
    }
    private fun baseRequest(url:String,cookie:File?,youtube:Boolean)=YoutubeDLRequest(url).apply {
        addOption("--ignore-config");addOption("--no-playlist");addOption("--socket-timeout","15")
        val retries=if(youtube)"0"else "1"
        addOption("--retries",retries);addOption("--fragment-retries",retries);addOption("--extractor-retries",retries)
        if(!youtube)addOption("--user-agent",PhoneBrowser.USER_AGENT)
        if(cookie!=null)addOption("--cookies",cookie.absolutePath)
    }
    private fun applyProof(request:YoutubeDLRequest,proof:YouTubeProof){
        request.addOption("--user-agent",YouTubeProof.USER_AGENT).addOption("--extractor-args",proof.arguments())
    }
    private fun <T> withCookieFile(platform:String,block:(File?)->T):T {
        val domain=when(platform){"抖音"->"douyin.com";"B站"->"bilibili.com";else->"youtube.com"}
        val raw=CookieManager.getInstance().getCookie("https://www.$domain/").orEmpty()
        if(raw.isEmpty())return block(null)
        val file=File.createTempFile("source-session-",".txt",context.cacheDir)
        try {
            val lines=raw.split(';').mapNotNull {part->val kv=part.trim().split('=',limit=2);if(kv.size==2 && kv.none{it.contains('\n') || it.contains('\r') || it.contains('\t')})".$domain\tTRUE\t/\tTRUE\t0\t${kv[0]}\t${kv[1]}"else null}
            file.writeText("# Netscape HTTP Cookie File\n"+lines.joinToString("\n")+"\n")
            return block(file)
        }finally{file.delete()}
    }
    private fun hasYouTubeSession()=CookieManager.getInstance().getCookie("https://www.youtube.com/")?.contains("SAPISID=")==true
    companion object {
        private fun needsAccount(error:Exception)=SourceErrors.category(error) in listOf("login_required","age_restricted")
        private var initialized=false
        @Synchronized fun initialize(context:Context) {
            if(initialized)return
            YoutubeDL.init(context);FFmpeg.init(context)
            val target=File(context.noBackupFilesDir,"youtubedl-android/yt-dlp/yt-dlp")
            val temp=File(target.parentFile,"extractor-new")
            context.resources.openRawResource(R.raw.ytdlp).use{source->temp.outputStream().use{source.copyTo(it)}}
            check(temp.renameTo(target)){"音频组件初始化失败，请重启夜听"}
            initialized=true
        }
        fun friendlyError(error:Exception,platform:String="",phase:String=""):String {
            val reason=when(SourceErrors.category(error)) {
                "cancelled" -> "准备已取消，可以稍后重试"
                "bot_verification" -> "YouTube 要求验证本次访问，已停止自动重试；收藏已保留，可稍后再试"
                "login_required","age_restricted","restricted" -> if(platform=="YouTube")"这条 YouTube 内容需要账号或访问权限，暂时无法准备"else "来源需要登录或验证，请在来源连接中完成后重试"
                "script_runtime" -> "音频解析组件未能完成处理，请复制诊断信息"
                "proof_generation" -> "暂时未能完成 YouTube 访问验证，收藏已保留，可稍后重试或复制诊断信息"
                "http_403" -> if(platform=="YouTube")"YouTube 拒绝了本次请求，可稍后重试或复制诊断信息"else "来源拒绝了本次请求，请连接来源后重试"
                "rate_limit" -> "来源请求过于频繁，请稍后再准备"
                "tls_certificate" -> "来源的安全证书校验未通过，已停止自动重试，可复制诊断信息"
                "tls_eof","connection_reset","connection_closed" -> "与来源的连接中途断开，收藏已保留，可稍后重试"
                "tls_handshake" -> "与来源建立安全连接失败，可稍后重试或复制诊断信息"
                "dns" -> "暂时无法查询来源地址，收藏已保留，可稍后重试"
                "timeout" -> "来源响应超时，收藏已保留，可稍后重试"
                "connection_refused","network_unreachable","connection" -> "暂时无法连接来源，收藏已保留，可稍后重试"
                "unavailable" -> "这条内容暂时不可用，可以打开原平台查看"
                "no_formats" -> "暂时没有可获取的音轨，请稍后重试"
                "incomplete_audio" -> "获取的音频不完整，请重试"
                else -> "准备没有完成，可以重试或复制诊断信息"
            }
            return if(phase.isBlank())reason else "$phase：$reason"
        }
    }
}
