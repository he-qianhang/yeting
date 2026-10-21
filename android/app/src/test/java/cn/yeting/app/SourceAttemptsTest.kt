package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test

class SourceAttemptsTest {
    @Test fun transientFailureRetriesOnceWithDelayAndKeepsBothResults(){
        var calls=0;var slept=0L
        val attempts=SourceAttempts(pause={slept+=it})
        val result=attempts.run("metadata","anonymous",{false}){if(++calls==1)throw java.net.SocketTimeoutException("timed out");"ok"}
        assertEquals("ok",result);assertEquals(2,calls);assertEquals(1500L,slept)
        assertEquals(listOf("timeout",null),attempts.records.map{it.failure?.category})
        assertTrue(attempts.records[0].retry);assertFalse(attempts.records[1].retry)
    }
    @Test fun repeatedFailureIsBoundedAndDoesNotRetryAnotherSessionAgain(){
        var calls=0
        val attempts=SourceAttempts(pause={})
        repeat(2){session->try{attempts.run("download",if(session==0)"anonymous"else "local_session",{false}){calls++;throw Exception("connection reset by peer")}}catch(_:Exception){}}
        assertEquals(3,calls);assertEquals(listOf(1,2,3),attempts.records.map{it.number})
        assertEquals(1,attempts.records.count{it.retry})
    }
    @Test fun platformAndCertificateFailuresNeverRetry(){
        for(message in listOf("Sign in to confirm you're not a bot","HTTP Error 429","HTTP Error 403","certificate verify failed","SSL: handshake failed","Private video","Unable to download webpage")){
            var calls=0;var slept=0L;val attempts=SourceAttempts(pause={slept+=it})
            try{attempts.run("metadata","anonymous",{false}){calls++;throw Exception(message)}}catch(_:Exception){}
            assertEquals(message,1,calls);assertEquals(0L,slept);assertFalse(attempts.records.single().retry)
        }
    }
    @Test fun cancellingDuringBackoffNeverStartsSecondRequest(){
        var cancel=false;var calls=0
        val attempts=SourceAttempts(pause={cancel=true})
        try{attempts.run("metadata","anonymous",{cancel}){calls++;throw Exception("timed out")};fail()}catch(_:InterruptedException){}
        assertEquals(1,calls)
    }
    @Test fun networkChangeIsObservedWithoutRetryingPlatformRejection(){
        var state=SourceNetworkState(identity=1,present=true,validated=true)
        val attempts=SourceAttempts({state},{fail("No retry")})
        try{attempts.run("metadata","anonymous",{false}){state=state.copy(identity=2,vpn=true);throw Exception("confirm you're not a bot")}}catch(_:Exception){}
        val record=attempts.records.single();assertNotEquals(record.before,record.after);assertFalse(record.retry)
    }
}
