package cn.yeting.app

/** One alternative access path, shared by metadata and download within one preparation. */
internal class YouTubeAccess(private val attempts:SourceAttempts,private val network:()->Long,
    private val clock:()->Long,private val create:(String,()->Boolean)->YouTubeProof) {
    private var proof:YouTubeProof?=null
    fun <T> run(phase:String,videoId:String,cancelled:()->Boolean,normal:()->T,verified:(YouTubeProof)->T):T {
        if(proof==null){
            try{return normal()}catch(e:Exception){
                if(cancelled() || SourceErrors.category(e) !in setOf("bot_verification","http_403","no_formats"))throw e
            }
        }
        if(cancelled())throw InterruptedException()
        val current=proof
        if(current==null || current.videoId!=videoId || current.expires<=clock() || current.network!=network())
            proof=attempts.run(phase+"_verification","local_proof",cancelled){create(videoId,cancelled)}
        return verified(proof!!)
    }
}
