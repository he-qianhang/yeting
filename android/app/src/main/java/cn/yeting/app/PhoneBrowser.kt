package cn.yeting.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses only this app's own browser session to read the requested public work. */
class PhoneBrowser(private val context:Context) {
    fun resolve(url:String,id:String,platform:String,cancelled:()->Boolean):JSONObject {
        check(Looper.myLooper()!=Looper.getMainLooper())
        val main=Handler(Looper.getMainLooper())
        val done=CountDownLatch(1)
        val value=AtomicReference<JSONObject>()
        val failure=AtomicReference<Exception>()
        var browser:WebView?=null
        val deadline=SystemClock.elapsedRealtime()+50_000
        val inspect=object:Runnable {
            override fun run() {
                val web=browser ?: return
                if(done.count==0L)return
                if(cancelled() || SystemClock.elapsedRealtime()>deadline){failure.set(IllegalStateException("请在来源连接中完成${platform}登录或验证，然后重试"));done.countDown();return}
                web.evaluateJavascript(snapshot(id,platform)){raw->
                    val result=runCatching{JSONObject(JSONTokener(raw).nextValue() as String)}.getOrNull()
                    if(result!=null && result.optString("title").isNotBlank() && result.optString("id").isNotBlank()) {value.set(result);done.countDown()}
                    else main.postDelayed(this,750)
                }
            }
        }
        main.post {
            try {
                browser=WebView(context.applicationContext).apply {
                    configure(this)
                    webViewClient=object:WebViewClient(){
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                            val host=request.url.host.orEmpty()
                            return request.url.scheme!="https" || !(host=="douyin.com" || host.endsWith(".douyin.com") || host=="bilibili.com" || host.endsWith(".bilibili.com"))
                        }
                    }
                    measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY))
                    layout(0,0,1080,1920)
                    loadUrl(url)
                }
                main.postDelayed(inspect,750)
            } catch(e:Exception){failure.set(e);done.countDown()}
        }
        try {
            while(!done.await(300,TimeUnit.MILLISECONDS))if(cancelled())throw InterruptedException()
            failure.get()?.let{throw it}
            return value.get() ?: error("作品信息暂时无法读取，请重试")
        } finally {main.post {main.removeCallbacks(inspect);browser?.apply{stopLoading();destroy()};browser=null}}
    }
    companion object {
        const val USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        fun configure(web:WebView) {
            web.settings.apply {
                javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=true
                userAgentString=USER_AGENT;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))WebViewCompat.addDocumentStartJavaScript(web,CAPTURE,setOf("https://*.douyin.com","https://*.bilibili.com"))
        }
        private val CAPTURE="""(()=>{
            window.__nightResponses=[];
            function accept(url,d){
                if(!/aweme\/detail|iteminfo|playurl|web-interface\/view/.test(url))return;
                if(window.__nightResponses.length<12)window.__nightResponses.push(d);
            }
            const fetch=window.fetch;
            window.fetch=function(...a){return fetch.apply(this,a).then(r=>{try{r.clone().json().then(d=>accept(r.url,d)).catch(()=>{})}catch(e){}return r})};
            const send=XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send=function(...a){this.addEventListener('load',()=>{try{accept(this.responseURL,this.responseType==='json'?this.response:JSON.parse(this.responseText))}catch(e){}});return send.apply(this,a)};
            document.addEventListener('play',e=>{if(e.target instanceof HTMLMediaElement){e.target.muted=true;e.target.pause()}},true);
        })();"""
        private fun snapshot(id:String,platform:String):String {
            val expected=JSONObject.quote(id)
            return if(platform=="抖音") """(()=>{
                const id=$expected;
                const values=window.__nightResponses||[];
                for(let i=values.length-1;i>=0;i--){const d=values[i]?.aweme_detail;if(!d||String(d.aweme_id)!==id)continue;
                    const v=d.video||{};const u=v.play_addr?.url_list?.find(u=>u.startsWith('https://'));
                    if(u)return JSON.stringify({id,title:d.desc||document.title,author:d.author?.nickname||'',duration_ms:v.duration||0,media_url:u});}
                return '{}';
            })()""" else """(()=>{
                const s=window.__INITIAL_STATE__;const v=s?.videoData;const id=$expected;
                const p=Math.max(1,parseInt(new URL(location.href).searchParams.get('p')||'1'));
                const part=v?.pages?.find(x=>x.page===p);
                if(v&&v.bvid===id)return JSON.stringify({id,title:(v.title||document.title)+(v.pages?.length>1&&part?' · '+part.part:''),author:v.owner?.name||s.upData?.name||'',duration_ms:(part?.duration||v.duration||0)*1000,cid:part?.cid||s.cid||v.cid||0});
                return '{}';
            })()"""
        }
    }
}
