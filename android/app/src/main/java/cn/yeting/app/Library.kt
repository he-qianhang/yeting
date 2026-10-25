package cn.yeting.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

class NightApp : Application() {
    override fun onCreate() { super.onCreate(); store = Library(this);steady=SteadyAudio(this) }
    companion object { lateinit var store: Library; private set;@get:JvmName("getSteady") internal lateinit var steady:SteadyAudio;private set }
}

enum class Readiness { WAITING, WORKING, READY, ERROR, LINK_ONLY }
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var platform: String,
    var title: String,
    var author: String = "",
    var durationMs: Long = 0,
    var positionMs: Long = 0,
    var completed: Boolean = false,
    var state: Readiness = Readiness.WAITING,
    var message: String = "等待准备",
    var mediaId: String = "",
    var jobId: String = "",
    var audio: String = "",
    val created: Long = System.currentTimeMillis(),
    var sharedUrl: String = url,
    var sharedText: String = "",
    var metadataReady: Boolean = false,
    var aliases: List<String> = emptyList(),
    var appUri: String = ""
) {
    fun json() = JSONObject().put("sharedUrl",sharedUrl).put("sharedText",sharedText).put("id",id).put("url",url).put("platform",platform).put("title",title)
        .put("author",author).put("durationMs",durationMs).put("positionMs",positionMs).put("completed",completed)
        .put("state",state.name).put("message",message).put("mediaId",mediaId).put("jobId",jobId).put("audio",audio).put("created",created)
        .put("metadataReady",metadataReady).put("aliases",JSONArray(aliases)).put("appUri",appUri)
    companion object {
        fun parse(o: JSONObject) = Entry(o.getString("id"),o.getString("url"),o.getString("platform"),o.getString("title"),
            o.optString("author"),o.optLong("durationMs"),o.optLong("positionMs"),o.optBoolean("completed"),
            Readiness.valueOf(o.getString("state")),o.optString("message"),o.optString("mediaId"),o.optString("jobId"),o.optString("audio"),o.optLong("created")).apply {
                sharedUrl=o.optString("sharedUrl",url);sharedText=o.optString("sharedText")
                metadataReady=o.optBoolean("metadataReady",state==Readiness.READY && author.isNotBlank())
                val a=o.optJSONArray("aliases");aliases=if(a!=null)(0 until a.length()).map{a.getString(it)}else emptyList()
                appUri=o.optString("appUri")
            }
    }
}

data class SharedLink(val url: String, val platform: String, val title: String, val author: String, val rawText: String="") {
    companion object {
        fun from(text: String): SharedLink {
            val normalized=text.replace('\u00a0',' ').replace('\u200b',' ').replace('\ufeff',' ')
            val found = Regex("https?://[^\\s<>\"\\u3000]+").find(normalized)?.value
                ?.trimEnd('，','。','；',';',')','）',']','】') ?: throw IllegalArgumentException("没有找到链接，请粘贴完整的分享文字")
            val uri = runCatching { URI(found) }.getOrNull() ?: throw IllegalArgumentException("链接格式不正确")
            require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1,443)) { "请使用平台提供的 HTTPS 分享链接" }
            val host = uri.host?.lowercase() ?: throw IllegalArgumentException("链接格式不正确")
            val platform = when(host) {
                "v.douyin.com","www.douyin.com","www.iesdouyin.com","m.douyin.com" -> "抖音"
                "b23.tv","www.bilibili.com","m.bilibili.com","bilibili.com" -> "B站"
                "youtu.be","www.youtube.com","m.youtube.com","youtube.com" -> "YouTube"
                else -> throw IllegalArgumentException("目前可以收藏抖音、B站和 YouTube 链接")
            }
            val id = if (platform == "抖音") Regex("/(?:share/)?video/(\\d+)").find(uri.path)?.groupValues?.get(1) else null
            val query=uri.rawQuery.orEmpty().split('&').mapNotNull {val p=it.split('=',limit=2);if(p.size==2)p[0] to java.net.URLDecoder.decode(p[1],"UTF-8")else null}.toMap()
            val canonical = when {
                id!=null -> "https://www.douyin.com/video/$id"
                platform=="B站" && Regex("BV[A-Za-z0-9]{10}").containsMatchIn(uri.path) -> "https://www.bilibili.com/video/"+Regex("BV[A-Za-z0-9]{10}").find(uri.path)!!.value+"?p="+(query["p"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                platform=="YouTube" -> {
                    val video=if(host=="youtu.be")uri.path.trim('/')else query["v"] ?: Regex("/(?:shorts|live|embed)/([A-Za-z0-9_-]+)").find(uri.path)?.groupValues?.get(1)
                    require(!video.isNullOrBlank() && video.matches(Regex("[A-Za-z0-9_-]+"))){"请复制具体的视频链接，暂不支持整个频道或播放列表"}
                    "https://www.youtube.com/watch?v=$video"
                }
                else -> URI(uri.scheme,uri.authority,uri.path,uri.query,null).toASCIIString()
            }
            val author = Regex("【([^】]+)的作品】").find(text)?.groupValues?.get(1).orEmpty()
            val plain = text.substringAfter("的作品】","").substringBefore(found).trim()
            val caption=text.replace(found,"").trim().trimEnd('，','。')
            val title = (plain.takeIf {it.length>3} ?: caption.takeIf{it.length>3})?.take(180) ?: "$platform 收藏"
            return SharedLink(canonical,platform,title,author,text.trim())
        }
    }
}

class Library(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir,"library.json"))
    private val entries = mutableListOf<Entry>()
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<() -> Unit>()
    val prefs = context.getSharedPreferences("night",Context.MODE_PRIVATE)
    val audioDir = File(context.filesDir,"audio").apply { mkdirs() }
    private val undoDir = File(context.filesDir,"delete-undo").apply { mkdirs() }
    var recoveryError: Boolean = false; private set
    init {
        if (file.baseFile.exists()) {
            runCatching {
                val array = JSONArray(String(file.readFully()))
                for(i in 0 until array.length()) entries += Entry.parse(array.getJSONObject(i))
            }.onFailure { entries.clear(); recoveryError = true }
        }
        val migrating=prefs.getInt("schemaVersion",0)<2
        if(migrating && file.baseFile.exists() && !recoveryError) {
            val backup=File(context.filesDir,"library-v0.1-backup.json")
            if(!backup.exists())backup.writeBytes(file.readFully())
        }
        entries.forEach {
            if (it.state == Readiness.WORKING) { it.state=Readiness.WAITING; it.message="准备中断了，可以重新准备" }
            if (it.state == Readiness.READY && !audioFile(it).isFile) { it.state=Readiness.WAITING; it.message="音频已清理，重新准备后可听"; it.audio="" }
            if(migrating && it.state!=Readiness.READY){it.state=Readiness.WAITING;it.message="等待手机准备"}
        }
        if(migrating && !recoveryError){
            save()
            prefs.edit().putInt("schemaVersion",2).putString("playMode","queue").putString("sleep","end").remove("assistant").remove("token").apply()
        }
        if(!recoveryError){queue();queue(true)}
        audioDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        undoDir.listFiles()?.forEach { it.delete() }
    }
    @Synchronized fun all() = entries.map { it.copy() }
    @Synchronized fun get(id: String?) = entries.find { it.id==id }?.copy()
    fun audioFile(entry: Entry) = File(audioDir,entry.audio.ifEmpty { entry.id+".m4a" })
    @Synchronized fun add(link: SharedLink): Pair<Entry,Boolean> {
        find(link.url)?.let { return it to false }
        check(!recoveryError) { "收藏文件暂时无法读取，请先保留数据并联系开发者" }
        val entry = Entry(url=link.url,platform=link.platform,title=link.title,author=link.author,message="等待手机准备").apply{sharedText=link.rawText}
        entries.add(0,entry);save()
        appendQueue(entry.id);changed();return entry.copy() to true
    }
    @Synchronized fun find(url:String)=entries.firstOrNull{it.url==url || it.sharedUrl==url || url in it.aliases}?.copy()
    @Synchronized fun applyMetadata(id:String,work:PhoneWork):String {
        val current=entries.find{it.id==id} ?: return id
        val duplicate=entries.firstOrNull{it.id!=id && it.platform==work.platform && (it.mediaId==work.id || it.url==work.sourceUrl)}
        if(duplicate!=null){
            val keep=if(current.state==Readiness.READY && (duplicate.state!=Readiness.READY || prefs.getString("last",null)==current.id))current else duplicate
            val remove=if(keep===current)duplicate else current
            keep.aliases=(keep.aliases+remove.aliases+remove.url+remove.sharedUrl).distinct()
            keep.positionMs=maxOf(keep.positionMs,remove.positionMs);keep.completed=keep.completed || remove.completed
            keep.title=work.title;keep.author=work.author;keep.durationMs=work.durationMs;keep.mediaId=work.id;keep.url=work.sourceUrl;keep.metadataReady=true;keep.appUri=work.appUri
            if(keep.sharedText.isBlank())keep.sharedText=remove.sharedText
            audioFile(remove).delete();entries.remove(remove)
            if(prefs.getString("last",null)==remove.id)prefs.edit().putString("last",keep.id).apply()
            save();changed();return keep.id
        }
        current.title=work.title;current.author=work.author;current.durationMs=work.durationMs;current.mediaId=work.id;current.url=work.sourceUrl;current.metadataReady=true
        current.appUri=work.appUri
        save();changed();return id
    }
    @Synchronized fun queue(shuffled:Boolean=false):List<Entry> {
        val key=if(shuffled)"shuffledQueue"else "queue"
        val a=runCatching{JSONArray(prefs.getString(key,"[]"))}.getOrDefault(JSONArray())
        val ids=(0 until a.length()).map{a.optString(it)}.filter{id->entries.any{it.id==id}}.distinct().toMutableList()
        entries.forEach{if(it.id !in ids)ids.add(it.id)}
        prefs.edit().putString(key,JSONArray(ids).toString()).apply()
        return ids.mapNotNull{id->entries.find{it.id==id}?.copy()}
    }
    @Synchronized fun reorderQueue(ids:List<String>,shuffled:Boolean=false) {
        val current=queue(shuffled).map{it.id}
        val ordered=ids.distinct().filter{it in current}+current.filter{it !in ids}
        prefs.edit().putString(if(shuffled)"shuffledQueue"else "queue",JSONArray(ordered).toString()).apply();changed()
    }
    @Synchronized fun shuffleQueue(current:String?) {
        val ids=queue().map{it.id}.filter{it!=current}.shuffled().toMutableList()
        if(current!=null && entries.any{it.id==current})ids.add(0,current)
        prefs.edit().putString("shuffledQueue",JSONArray(ids).toString()).apply();changed()
    }
    private fun appendQueue(id:String) {
        for(key in listOf("queue","shuffledQueue")){
            val a=runCatching{JSONArray(prefs.getString(key,"[]"))}.getOrDefault(JSONArray())
            a.put(id);prefs.edit().putString(key,a.toString()).apply()
        }
    }
    @Synchronized fun update(id:String, notify:Boolean=true, block:(Entry)->Unit) {
        val e=entries.find { it.id==id } ?: return
        block(e); save(); if(notify) changed()
    }
    @Synchronized fun clean(id:String) {
        val e=entries.find { it.id==id } ?: return
        audioFile(e).delete(); e.audio=""; e.state=Readiness.WAITING
        e.message="音频已清理，重新准备后可听";e.jobId=""
        save(); changed()
    }
    @Synchronized fun delete(id:String) {
        entries.find { it.id==id }?.let { audioFile(it).delete(); entries.remove(it); save(); changed() }
    }
    data class DeletedBatch(val rows:List<Pair<Int,Entry>>,val queue:List<String>,val shuffled:List<String>,val moved:List<Pair<File,File>>)
    @Synchronized fun deleteWithUndo(ids:Set<String>):DeletedBatch {
        val rows=entries.withIndex().filter{it.value.id in ids}.map{it.index to it.value.copy()}
        val before=queue().map{it.id};val shuffled=queue(true).map{it.id}
        entries.removeAll{it.id in ids};save()
        val moved=rows.mapNotNull{(_,e)->
            val file=audioFile(e)
            if(!file.isFile)null else {
                val destination=File(undoDir,e.id+"-"+file.name)
                if(file.renameTo(destination))destination to file else null
            }
        }
        changed();return DeletedBatch(rows,before,shuffled,moved)
    }
    @Synchronized fun undo(batch:DeletedBatch) {
        batch.rows.forEach{(index,e)->if(entries.none{it.id==e.id})entries.add(index.coerceAtMost(entries.size),e)}
        batch.moved.forEach{(from,to)->if(from.isFile)from.renameTo(to)}
        prefs.edit().putString("queue",JSONArray(batch.queue).toString()).putString("shuffledQueue",JSONArray(batch.shuffled).toString()).apply()
        save();changed()
    }
    fun finishUndo(batch:DeletedBatch){batch.moved.forEach{(file,_)->file.delete()}}
    @Synchronized private fun save() {
        check(!recoveryError) { "原收藏数据需要恢复，请先联系开发者" }
        val data=JSONArray(); entries.forEach { data.put(it.json()) }
        val out=file.startWrite()
        try { out.write(data.toString().toByteArray());file.finishWrite(out) } catch(e:Exception) {file.failWrite(out);throw e}
    }
    fun observe(block:()->Unit) { synchronized(observers) {observers.add(block)} }
    fun unobserve(block:()->Unit) { synchronized(observers) {observers.remove(block)} }
    private fun changed() { main.post { synchronized(observers) {observers.toList()}.forEach {it()} } }
}
