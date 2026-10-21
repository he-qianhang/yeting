package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test

class SleepRecoveryTest {
    @Test fun countdownRestoresOriginalDeadlineAndCountsInterruptedTime(){
        val original=ListeningPolicy(true).apply{choose("minutes",1000,30,true)}
        val restored=ListeningPolicy(true)
        assertTrue(restored.restore(original.snapshot(),301000))
        assertEquals(1801000L,restored.deadlineMs);assertEquals(1500L,restored.remainingSeconds(301000))
        assertTrue(restored.finishCurrent)
    }
    @Test fun countsRemainAndCompletionConsumesOnlyOncePerCall(){
        val original=ListeningPolicy(true).apply{choose("count",1000,3);completed(2000)}
        val restored=ListeningPolicy(true)
        assertTrue(restored.restore(original.snapshot(),3000));assertEquals(2,restored.remainingTracks)
        assertTrue(restored.completed(4000));assertEquals(1,restored.remainingTracks)
    }
    @Test fun expiredAndTailLimitsDoNotResumeAfterAnInterruption(){
        val original=ListeningPolicy(true).apply{choose("minutes",1000,1,true)}
        assertFalse(ListeningPolicy(true).restore(original.snapshot(),61000))
        original.reachDeadline(61000,true)
        assertFalse(ListeningPolicy(true).restore(original.snapshot(),62000))
    }
    @Test fun unlimitedCompletedOrMalformedCheckpointDoesNotRestore(){
        val original=ListeningPolicy(true)
        assertFalse(ListeningPolicy(true).restore(original.snapshot(),0))
        original.choose("count",0,1);original.completed(1)
        assertFalse(ListeningPolicy(true).restore(original.snapshot(),2))
        assertFalse(ListeningPolicy(true).restore(ListeningPolicy.Snapshot(true,"minutes",Long.MAX_VALUE,0,false,false),10))
    }
}
