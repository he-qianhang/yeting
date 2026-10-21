package cn.yeting.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class SourceError(val category:String,val substep:String,val reason:String?=null,val httpStatus:Int?=null,val redirects:Int?=null)

internal object SourceErrors {
    fun category(error:Throwable)=detail(error).category
    fun detail(error:Throwable):SourceError {
        val causes=generateSequence(error){it.cause}.take(8).toList()
        if(causes.any{it is InterruptedException || it is com.yausername.youtubedl_android.YoutubeDL.CanceledException})return SourceError("cancelled","unknown")
        val raw=causes.joinToString("\n"){it.message.orEmpty()}.replace(Regex("\\u001B\\[[0-9;]*m"),"")
        // yt-dlp stderr can contain old warnings before its final ERROR. Classify the final failure.
        val fatal=raw.lineSequence().lastOrNull{it.trimStart().startsWith("ERROR:",true)}
        val message=(fatal ?: raw.lineSequence().filterNot{it.trimStart().startsWith("WARNING:",true) || it.trimStart().startsWith("[debug]",true)}.joinToString("\n"))
            .replace(Regex("https?://\\S+"),"[url]").lowercase(Locale.ROOT)
        val category=when {
            "confirm you're not a bot" in message || "confirm you’re not a bot" in message -> "bot_verification"
            "confirm your age" in message || "age-restricted" in message -> "age_restricted"
            "private video" in message || "members-only" in message || "not available in your country" in message -> "restricted"
            "sign in" in message || "login required" in message || "requires authentication" in message -> "login_required"
            "javascript runtime" in message || "js challenge" in message || "challenge solving failed" in message -> "script_runtime"
            causes.any{it is java.security.cert.CertificateException || it is javax.net.ssl.SSLPeerUnverifiedException} || "certificate_verify_failed" in message || "certificate verify failed" in message || "certificateverifyerror" in message || "certpathvalidatorexception" in message || "sslpeerunverifiedexception" in message -> "tls_certificate"
            "connection reset" in message || "connectionreseterror" in message || "errno 104" in message -> "connection_reset"
            "unexpected_eof" in message || "eof occurred in violation" in message || "ssleoferror" in message -> "tls_eof"
            "remote end closed connection" in message || "remotedisconnected" in message -> "connection_closed"
            causes.any{it is java.net.UnknownHostException} || "name resolution" in message || "unable to resolve host" in message || "no address associated" in message || "name or service not known" in message || "getaddrinfo failed" in message -> "dns"
            causes.any{it is java.net.SocketTimeoutException} || "timed out" in message || "timeouterror" in message || "read timeout" in message || "connect timeout" in message -> "timeout"
            Regex("(?:http(?: error)?[ :/]+|status(?: code)?[ :]+)403\\b").containsMatchIn(message) -> "http_403"
            Regex("(?:http(?: error)?[ :/]+|status(?: code)?[ :]+)429\\b").containsMatchIn(message) || "rate limit" in message -> "rate_limit"
            "connection refused" in message -> "connection_refused"
            "network is unreachable" in message || "no route to host" in message -> "network_unreachable"
            "sslerror" in message || "sslhandshakeexception" in message || "tls handshake" in message || "ssl: " in message || "[ssl:" in message -> "tls_handshake"
            "requested format is not available" in message || "no video formats" in message -> "no_formats"
            "unavailable" in message || "not available" in message -> "unavailable"
            "音频时长不完整" in message -> "incomplete_audio"
            "unable to download" in message -> "connection"
            else -> "other"
        }
        val proof=causes.filterIsInstance<YouTubeProofException>().firstOrNull()
        val substep=when {
            proof!=null -> "proof_"+(proof.step.takeIf{it in setOf("document","homepage","challenge","challenge_data","challenge_url","challenge_fetch","snapshot","integrity","mint")} ?: "unknown")
            "webpage" in message -> "webpage"
            "player api" in message || "api page" in message -> "player_api"
            "player javascript" in message || "player js" in message -> "player_script"
            "fragment" in message -> "media_fragment"
            else -> "unknown"
        }
        // Only these fixed codes leave this function; raw text, URLs and arbitrary values never persist.
        val request=causes.filterIsInstance<ProofRequestException>().firstOrNull()
        val known=setOf("challenge_missing","script_url_missing","script_url_invalid","script_url_rejected",
            "script_redirect_limit","script_redirect_missing","script_redirect_rejected","script_http_error","script_empty","http_status")
        return SourceError(if(category=="other" && proof!=null)"proof_generation"else category,substep,
            request?.reason?.takeIf{it in known},request?.httpStatus?.takeIf{it in 100..599},request?.redirects?.takeIf{it in 0..2})
    }
}

/** Only fixed diagnostic fields are persisted; no exception text, URLs or sessions. */
internal class SourceDiagnostics(private val context:Context) {
    private fun file(id:String)=File(context.noBackupFilesDir,"source-diagnostics").apply{mkdirs()}.resolve(
        java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}+".json")
    fun failed(id:String,platform:String,phase:String,error:Exception,attempts:SourceAttempts?=null) {
        save(id,platform,phase,"failed",error,attempts)
    }
    fun succeeded(id:String,platform:String,phase:String,status:String,attempts:SourceAttempts) {
        save(id,platform,phase,status,null,attempts)
    }
    private fun snapshot():SourceNetworkState {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val active=manager.activeNetwork
        val network=manager.getNetworkCapabilities(active)
        val proxy=manager.defaultProxy
        return SourceNetworkState(active?.networkHandle ?: 0,network!=null,
            network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true,
            network?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true,
            network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true,
            network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true,
            if(proxy==null)"none"else if(proxy.pacFileUrl!=android.net.Uri.EMPTY)"pac"else "static")
    }
    fun networkState()=runCatching{snapshot()}.getOrDefault(SourceNetworkState())
    private fun networkJson(state:SourceNetworkState)=JSONObject().put("network_present",state.present)
        .put("network_validated",state.validated).put("vpn_visible",state.vpn).put("wifi",state.wifi)
        .put("cellular",state.cellular).put("system_proxy_mode",state.proxy)
    private fun save(id:String,platform:String,phase:String,status:String,error:Exception?,attempts:SourceAttempts?) {
        val network=networkState()
        val stamp=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
        val report=JSONObject().put("time_utc",stamp).put("platform",platform).put("phase",phase)
            .put("diagnostic_version",2).put("status",status).put("category",error?.let{SourceErrors.category(it)} ?: "success")
            .put("app_version",context.packageManager.getPackageInfo(context.packageName,0).versionName)
            .put("android_api",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("extractor","2026.08.19").put("runtime","QuickJS").put("runtime_present",File(context.applicationInfo.nativeLibraryDir,"libqjs.so").canExecute())
            .put("network",networkJson(network)).put("request_proxy_policy","system_routing_no_explicit_override")
            .put("proxy_environment_present",listOf("http_proxy","https_proxy","all_proxy","HTTP_PROXY","HTTPS_PROXY","ALL_PROXY").any{!System.getenv(it).isNullOrBlank()})
            .put("engine_retry_count",if(platform=="YouTube")0 else 1).put("delayed_retries_per_phase",if(platform=="YouTube")1 else 0)
        val rows=org.json.JSONArray()
        attempts?.records?.forEach{r->rows.put(JSONObject().put("phase",r.phase).put("session",r.session)
            .put("attempt",r.number).put("started_unix_ms",r.started).put("elapsed_ms",r.elapsed)
            .put("status",if(r.failure==null)"success"else "failed").put("category",r.failure?.category ?: "success")
            .put("substep",r.failure?.substep ?: "unknown").put("will_retry",r.retry)
            .put("network_before",networkJson(r.before)).put("network_after",networkJson(r.after))
            .put("network_changed",r.before!=r.after)
            .also{row->r.failure?.let{error->
                error.reason?.let{row.put("reason",it)};error.httpStatus?.let{row.put("http_status",it)}
                error.redirects?.let{row.put("redirect_count",it)}
            }})}
        report.put("attempts",rows)
        file(id).writeText(report.toString(2))
    }
    fun clear(id:String){file(id).delete()}
    fun report(id:String):String?=file(id).takeIf{it.isFile}?.readText()
}
