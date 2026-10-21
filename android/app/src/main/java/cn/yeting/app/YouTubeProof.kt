package cn.yeting.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class YouTubeProofException(val step:String,cause:Exception):Exception("访问验证未完成",cause)
internal class ProofScriptException(val reason:String):Exception("访问验证脚本未完成")

/** Short-lived anonymous proofs. Values never go to preferences, files, logs or diagnostics. */
internal class YouTubeProof(val videoId:String,val visitor:String,val player:String,val streaming:String,
    val expires:Long,val network:Long) {
    fun arguments()="youtube:player_client=mweb;player_skip=webpage,configs;visitor_data=$visitor;po_token=mweb.player+$player,mweb.gvs+$streaming"

    companion object {
        const val USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val REQUEST_KEY="O43z0dpjhgX20SCx4KAo"
        fun create(context:Context,videoId:String,cancelled:()->Boolean):YouTubeProof {
            require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
            check(Looper.myLooper()!=Looper.getMainLooper())
            val http=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
                .callTimeout(20,TimeUnit.SECONDS).followRedirects(false).build()
            var step="document"
            try { ProofDocument(context,cancelled).use { document ->
                val headers=document.evaluate("JSON.stringify(window.proofHeaders)")
                fun post(endpoint:String,payload:String):String {
                    val request=Request.Builder().url("https://www.youtube.com/$endpoint")
                        .header("User-Agent",USER_AGENT).header("Origin","https://www.youtube.com")
                        .header("Referer","https://www.youtube.com/")
                    headers.keys().forEach{key->request.header(key,headers.getString(key))}
                    return request(http.newCall(request.post(payload.toRequestBody(
                        "application/json+protobuf".toMediaType())).build()),cancelled)
                }
                val network=SourceDiagnostics(context).networkState().identity
                step="homepage"
                val page=request(http.newCall(Request.Builder().url("https://www.youtube.com/")
                    .header("User-Agent",USER_AGENT).header("Accept-Language","en-US,en;q=0.7").build()),cancelled)
                val config=Regex("ytcfg\\.set\\((\\{.+?\\})\\);",RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
                    ?: error("访问验证未完成")
                val attestation=Regex("window\\.ytAtN\\(\\s*(\\{[\\s\\S]*?\\})\\s*\\)").find(page)?.groupValues?.get(1)
                    ?: error("访问验证未完成")
                val bootstrap=document.evaluate("JSON.stringify(window.bootstrapProof($config,${JSONObject.quote(attestation)}))")
                val visitor=bootstrap.getString("visitor")
                check(visitor.matches(Regex("[A-Za-z0-9_%=-]{1,4096}"))){"访问验证未完成"}
                step="challenge_data"
                val challenge=bootstrap.optJSONObject("challenge")
                    ?: throw ProofRequestException("challenge_missing")
                val scriptUrl=challenge.optJSONObject("interpreterUrl")
                    ?.optString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")?.takeIf{it.isNotBlank()}
                    ?: throw ProofRequestException("script_url_missing")
                step="challenge_url"
                val validated=YouTubeChallenge.url(scriptUrl)
                step="challenge_fetch"
                val script=YouTubeChallenge.load(validated){url->
                    response(http.newCall(Request.Builder().url(url).header("User-Agent",USER_AGENT).build()),cancelled)
                }
                challenge.put("interpreterJavascript",JSONObject().put("privateDoNotAccessOrElseSafeScriptWrappedValue",script))
                step="snapshot"
                document.evaluate("window.snapshotProof($challenge); JSON.stringify({})")
                val snapshot=document.result()
                step="integrity"
                val integrity=JSONArray(post("api/jnn/v1/GenerateIT",JSONArray().put(REQUEST_KEY)
                    .put(snapshot.getString("response")).toString()))
                step="mint"
                document.evaluate("window.mintProof($integrity,${JSONObject.quote(videoId)}); JSON.stringify({})")
                val result=document.result()
                val player=result.getString("player");val streaming=result.getString("content")
                check(listOf(player,streaming).all{it.matches(Regex("[A-Za-z0-9_=-]{20,8192}"))}){"访问验证未完成"}
                val after=SourceDiagnostics(context).networkState().identity
                check(network==after){"访问验证期间网络发生变化"}
                val ttl=(integrity.optLong(1,600)-60).coerceIn(1,600)*1000
                return YouTubeProof(videoId,visitor,player,streaming,SystemClock.elapsedRealtime()+ttl,network)
            }}catch(e:Exception){if(e is InterruptedException)throw e;throw YouTubeProofException(step,e)}
        }
        private fun request(call:Call,cancelled:()->Boolean):String {
            val result=response(call,cancelled)
            if(result.status !in 200..299)throw ProofRequestException("http_status",result.status)
            return result.body
        }
        private fun response(call:Call,cancelled:()->Boolean):YouTubeChallenge.Response {
            val done=CountDownLatch(1);val result=AtomicReference<YouTubeChallenge.Response>();val error=AtomicReference<IOException>()
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException){error.set(e);done.countDown()}
                override fun onResponse(call:Call,response:Response){
                    try{response.use{r->
                        result.set(YouTubeChallenge.Response(r.code,if(r.isSuccessful)r.body?.string().orEmpty()else "",r.header("Location")))
                    }}catch(e:IOException){error.set(e)}finally{done.countDown()}
                }
            })
            try {
                while(!done.await(100,TimeUnit.MILLISECONDS))if(cancelled())throw InterruptedException()
                if(cancelled())throw InterruptedException()
                error.get()?.let{throw it}
                return result.get() ?: error("访问验证未完成")
            }finally{call.cancel()}
        }
    }
}

/** Local code only; no browsing, media, account access or JavaScript-to-native bridge. */
private class ProofDocument(context:Context,private val cancelled:()->Boolean):Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val ready=CountDownLatch(1)
    private val failure=AtomicReference<Exception>()
    private val deadline=SystemClock.elapsedRealtime()+60_000
    private var web:WebView?=null
    @Volatile private var closed=false
    init {
        val script=context.assets.open("youtube-proof/proof.js").bufferedReader().use{it.readText()}
        main.post {
            if(closed)return@post
            try {
                val view=WebView(context.applicationContext);web=view
                view.apply {
                    check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)){"手机网页组件暂不支持独立访问验证"}
                    WebViewCompat.setProfile(this,"yeting-youtube-proof")
                    WebViewCompat.getProfile(this).cookieManager.setAcceptCookie(false)
                    settings.apply {
                        javaScriptEnabled=true;blockNetworkLoads=true;allowFileAccess=false;allowContentAccess=false
                        domStorageEnabled=false;mediaPlaybackRequiresUserGesture=true
                        mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;userAgentString=YouTubeProof.USER_AGENT
                    }
                    webViewClient=object:WebViewClient(){
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                        override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=
                            WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                        override fun onPageFinished(view:WebView,url:String){ready.countDown()}
                    }
                    loadDataWithBaseURL("https://www.youtube.com/","<!doctype html><html><head><script>$script</script></head><body></body></html>","text/html","UTF-8",null)
                }
            }catch(e:Exception){failure.set(e);ready.countDown()}
        }
    }
    private fun await(done:CountDownLatch) {
        while(!done.await(100,TimeUnit.MILLISECONDS))checkActive()
        checkActive();failure.get()?.let{throw it}
    }
    private fun checkActive(){
        if(cancelled() || Thread.currentThread().isInterrupted)throw InterruptedException()
        check(!closed && SystemClock.elapsedRealtime()<deadline){"访问验证超时"}
    }
    fun evaluate(script:String):JSONObject {
        await(ready)
        val done=CountDownLatch(1);val value=AtomicReference<JSONObject>()
        main.post {
            if(closed){done.countDown();return@post}
            web?.evaluateJavascript(script){raw->
                value.set(runCatching{JSONObject(JSONTokener(raw).nextValue() as String)}.getOrNull());done.countDown()
            }
        }
        await(done)
        return value.get() ?: error("访问验证未完成")
    }
    fun result():JSONObject {
        while(true){
            val state=evaluate("JSON.stringify(window.proofState)")
            when(state.optString("status")){"done"->return state;"error"->throw ProofScriptException(state.optString("reason","unknown"))}
            Thread.sleep(100)
        }
    }
    override fun close(){closed=true;main.post{web?.apply{stopLoading();destroy()};web=null}}
}
