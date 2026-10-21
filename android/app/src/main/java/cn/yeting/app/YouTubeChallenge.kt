package cn.yeting.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Fixed diagnostic codes only. Never retain a URL, response body or credentials in errors. */
internal class ProofRequestException(val reason:String,val httpStatus:Int=0,val redirects:Int=0):
    IOException(if(httpStatus>0)"HTTP Error $httpStatus"else "访问验证未完成")

internal object YouTubeChallenge {
    data class Response(val status:Int,val body:String="",val location:String?=null)
    fun url(value:String):HttpUrl {
        val url=(if(value.startsWith("//"))"https:$value"else value).toHttpUrlOrNull()
            ?: throw ProofRequestException("script_url_invalid")
        if(url.scheme!="https" || url.host!="www.google.com" || url.port!=443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            listOf("/js/bg/","/js/th/").none{url.encodedPath.startsWith(it)})
            throw ProofRequestException("script_url_rejected")
        return url
    }
    fun load(initial:HttpUrl,fetch:(HttpUrl)->Response):String {
        var current=url(initial.toString());var redirects=0
        while(true) {
            val response=fetch(current)
            if(response.status in setOf(301,302,303,307,308)) {
                if(redirects>=2)throw ProofRequestException("script_redirect_limit",response.status,redirects)
                val location=response.location?.takeIf{it.isNotBlank()}
                    ?: throw ProofRequestException("script_redirect_missing",response.status,redirects)
                val next=current.resolve(location)
                    ?: throw ProofRequestException("script_redirect_rejected",response.status,redirects)
                current=try{url(next.toString())}catch(_:ProofRequestException){
                    throw ProofRequestException("script_redirect_rejected",response.status,redirects)
                }
                redirects++;continue
            }
            if(response.status !in 200..299)throw ProofRequestException("script_http_error",response.status,redirects)
            if(response.body.isBlank())throw ProofRequestException("script_empty",response.status,redirects)
            return response.body
        }
    }
}
