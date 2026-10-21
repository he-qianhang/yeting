package cn.yeting.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** A phone-local source session. Media is disabled; this screen only handles login/verification. */
class SourceSessionActivity:Activity() {
    private var web:WebView?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val platform=intent.getStringExtra("platform") ?: "抖音"
        val dark=nightDark()
        val colors=Palette(dark)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(colors.bg)}
        fun label(value:String,size:Float)=TextView(this).apply{text=value;textSize=size;setTextColor(colors.text);setPadding(dp(20),dp(12),dp(20),dp(12))}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(label("连接$platform",20f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(label("完成",16f).apply{minimumHeight=dp(48);gravity=Gravity.CENTER;setTextColor(colors.accent);setOnClickListener{CookieManager.getInstance().flush();finish()}})
        root.addView(header)
        if(platform=="YouTube") {
            root.addView(label("公开视频可直接在夜听准备，无需连接账号。Google 不接受此应用内的登录页面；若某条内容要求登录，夜听会显示具体原因。",15f))
            setContentView(root)
            ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
            WindowInsetsControllerCompat(window,root).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
            return
        }
        root.addView(label("请在来源网页自行登录或验证，完成后返回收藏重试。登录状态只保存在这部手机上。此页面不播放视频。",13f).apply{setTextColor(colors.muted)})
        val domains=when(platform){"抖音"->listOf("douyin.com","iesdouyin.com","douyinstatic.com");else->listOf("bilibili.com")}
        val browser=WebView(this).apply {
            setBackgroundColor(colors.bg)
            PhoneBrowser.configure(this)
            if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))WebViewCompat.addDocumentStartJavaScript(this,BLOCK_MEDIA,setOf("*"))
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    val host=request.url.host.orEmpty()
                    return request.url.scheme!="https" || domains.none{host==it || host.endsWith(".$it")}
                }
                override fun onPageFinished(view:WebView,url:String){view.evaluateJavascript(BLOCK_MEDIA,null)}
            }
        }
        web=browser;root.addView(browser,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        WindowInsetsControllerCompat(window,root).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
        browser.loadUrl(if(platform=="抖音")"https://www.douyin.com/"else "https://passport.bilibili.com/login")
    }
    override fun onBackPressed(){val w=web;if(w?.canGoBack()==true)w.goBack()else super.onBackPressed()}
    override fun onPause(){CookieManager.getInstance().flush();web?.onPause();super.onPause()}
    override fun onResume(){super.onResume();web?.onResume()}
    override fun onDestroy(){web?.apply{stopLoading();removeAllViews();destroy()};web=null;super.onDestroy()}
    companion object {
        private val BLOCK_MEDIA="""(()=>{
            if(window.__nightLoginOnly)return;window.__nightLoginOnly=true;
            HTMLMediaElement.prototype.play=function(){this.muted=true;this.pause();return Promise.reject(new DOMException('Media is disabled on this connection page','NotAllowedError'))};
            document.addEventListener('play',e=>{if(e.target instanceof HTMLMediaElement){e.target.muted=true;e.target.pause()}},true);
            const hide=()=>{document.querySelectorAll('video,audio,iframe[src*="player"]').forEach(e=>{e.style.display='none';if(e.pause)e.pause()})};
            new MutationObserver(hide).observe(document,{subtree:true,childList:true});hide();
        })();"""
    }
}
