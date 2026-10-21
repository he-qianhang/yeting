package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test

class YouTubeAccessTest {
    private var now=100L
    private var network=1L
    private var generated=0
    private val attempts=SourceAttempts()
    private val access=YouTubeAccess(attempts,{network},{now}){id,_->
        generated++;YouTubeProof(id,"visitor-$generated","player-$id","stream-$id",now+1000,network)
    }
    @Test fun ordinarySuccessDoesNotGenerateProof(){
        assertEquals("ready",access.run("metadata","video",{false},{"ready"},{error("Unused")}))
        assertEquals(0,generated);assertTrue(attempts.records.isEmpty())
    }
    @Test fun botFailureFallsBackOnceAndSharesProofWithDownload(){
        val first=access.run("metadata","video",{false},{throw Exception("ERROR: confirm you're not a bot")},{it})
        val second=access.run("download","video",{false},{error("Do not return to anonymous")},{it})
        assertSame(first,second);assertEquals(1,generated);assertEquals(1,attempts.records.size)
    }
    @Test fun verifiedBotFailureDoesNotLoop(){
        var calls=0
        try{access.run("metadata","video",{false},{throw Exception("ERROR: confirm you're not a bot")},{calls++;throw Exception("ERROR: confirm you're not a bot")});fail()}catch(e:Exception){assertEquals("bot_verification",SourceErrors.category(e))}
        assertEquals(1,calls);assertEquals(1,generated)
    }
    @Test fun connectionCertificateAndPermissionFailuresDoNotTriggerProof(){
        for(message in listOf("ERROR: timed out","ERROR: certificate verify failed","ERROR: private video","ERROR: HTTP Error 429")){
            try{access.run("metadata","video",{false},{throw Exception(message)},{error("Unexpected proof")});fail()}catch(_:Exception){}
        }
        assertEquals(0,generated)
    }
    @Test fun expiredOrChangedNetworkAndDifferentVideoGetNewProof(){
        fun run(id:String="video")=access.run("metadata",id,{false},{throw Exception("ERROR: HTTP Error 403")},{it})
        val first=run();now+=1001;assertNotSame(first,run());network=2;run();run("other")
        assertEquals(4,generated)
    }
    @Test fun cancelledPreparationDoesNotStartProof(){
        try{access.run("metadata","video",{true},{throw InterruptedException()},{error("Unexpected proof")});fail()}catch(_:InterruptedException){}
        assertEquals(0,generated)
    }
    @Test fun proofFailurePreservesSafeCategoryAndStep(){
        val detail=SourceErrors.detail(YouTubeProofException("snapshot",IllegalStateException("secret-value")))
        assertEquals("proof_generation",detail.category);assertEquals("proof_snapshot",detail.substep)
    }
}
