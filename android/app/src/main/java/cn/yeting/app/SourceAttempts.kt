package cn.yeting.app

internal data class SourceNetworkState(val identity:Long=0,val present:Boolean=false,val validated:Boolean=false,
    val vpn:Boolean=false,val wifi:Boolean=false,val cellular:Boolean=false,val proxy:String="none")

internal data class SourceAttempt(val phase:String,val session:String,val number:Int,val started:Long,val elapsed:Long,
    val before:SourceNetworkState,val after:SourceNetworkState,val failure:SourceError?,val retry:Boolean)

/** One delayed retry per phase; never retry platform verification or certificate failures. */
internal class SourceAttempts(private val network:()->SourceNetworkState={SourceNetworkState()},
    private val pause:(Long)->Unit={Thread.sleep(it)}) {
    val records=mutableListOf<SourceAttempt>()
    private val retried=mutableSetOf<String>()
    fun <T> run(phase:String,session:String,cancelled:()->Boolean,action:()->T):T {
        while(true) {
            if(cancelled() || Thread.currentThread().isInterrupted)throw InterruptedException()
            val before=network();val started=System.currentTimeMillis();val tick=System.nanoTime()
            try {
                val result=action()
                if(cancelled() || Thread.currentThread().isInterrupted)throw InterruptedException()
                records.add(SourceAttempt(phase,session,records.count{it.phase==phase}+1,started,
                    (System.nanoTime()-tick)/1_000_000,before,network(),null,false))
                return result
            }catch(e:Exception) {
                val error=if(cancelled())SourceErrors.detail(InterruptedException())else SourceErrors.detail(e)
                val retry=error.category in setOf("dns","timeout","connection_reset","connection_closed","connection_refused","tls_eof","network_unreachable") && retried.add(phase)
                records.add(SourceAttempt(phase,session,records.count{it.phase==phase}+1,started,
                    (System.nanoTime()-tick)/1_000_000,before,network(),error,retry))
                if(error.category=="cancelled")throw InterruptedException()
                if(!retry)throw e
            }
            // Small slices let cancellation stop the backoff without making another request.
            repeat(15){if(cancelled())throw InterruptedException();pause(100)}
        }
    }
}
