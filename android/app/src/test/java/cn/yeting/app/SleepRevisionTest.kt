package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test

class SleepRevisionTest {
    @Test fun finishingCurrentRemovesDeadlineWithoutAllowingNextTrack(){
        val p=ListeningPolicy(true);p.choose("minutes",1000,1,true)
        assertFalse(p.reachDeadline(60999,true));assertTrue(p.shouldAdvance(60999))
        assertFalse(p.reachDeadline(61000,true));assertTrue(p.finishingCurrent)
        assertEquals("end",p.sleep);assertFalse(p.shouldAdvance(61000));assertFalse(p.completed(62000))
        assertFalse(p.reachDeadline(63000,true))
    }
    @Test fun pausedAtDeadlineDoesNotResumeOrLeaveTailPending(){
        val p=ListeningPolicy(true);p.choose("minutes",0,1,true)
        assertTrue(p.reachDeadline(60000,false));assertFalse(p.finishingCurrent);assertEquals("off",p.sleep)
    }
    @Test fun ordinaryTimerStillStopsImmediately(){
        val p=ListeningPolicy(true);p.choose("minutes",0,1)
        assertTrue(p.reachDeadline(60000,true));assertFalse(p.finishingCurrent)
    }
    @Test fun naturalCompletionAtTheDeadlineCannotAdvanceEvenBeforeTimerTick(){
        val p=ListeningPolicy(true);p.choose("minutes",0,1,true)
        assertTrue(p.completed(59999));assertFalse(p.completed(60000));assertFalse(p.shouldAdvance(60000))
    }
    @Test fun selectingNewRuleClearsFinishingStateAndTimingOption(){
        val p=ListeningPolicy(true);p.choose("minutes",0,1,true);p.reachDeadline(60000,true)
        p.choose("count",61000,3);assertFalse(p.finishingCurrent);assertFalse(p.finishCurrent)
        assertEquals(0L,p.deadlineMs);assertEquals(3,p.remainingTracks)
        p.choose("end",62000);assertEquals(1,p.remainingTracks);assertFalse(p.shouldAdvance(62000))
    }
    @Test fun allSixteenSpeedsAndLegacyValuesNormalizePredictably(){
        (5..20).forEach{assertEquals(it/10f,PlaybackSpeed.normalize(it/10f),.0001f)}
        assertEquals(.8f,PlaybackSpeed.normalize(.75f),.0001f)
        assertEquals(1.3f,PlaybackSpeed.normalize(1.25f),.0001f)
        assertEquals("1.0×",PlaybackSpeed.label(1f))
    }
}

class ProgressTicksTest {
    @org.junit.Test fun patternsAreStableDistinctAndMostlyNearTheMean(){
        val first=ProgressTicks.heights("one",1000)
        org.junit.Assert.assertArrayEquals(first,ProgressTicks.heights("one",1000))
        org.junit.Assert.assertFalse(first.contentEquals(ProgressTicks.heights("two",1000)))
        org.junit.Assert.assertTrue(first.count{it in 18..25} in 850..950)
        org.junit.Assert.assertTrue(first.any{it>=34} && first.any{it<=10})
        org.junit.Assert.assertArrayEquals(first.take(40).toIntArray(),ProgressTicks.heights("one",40))
    }
    @org.junit.Test fun unlimitedClearsEveryTimerAndTailField(){
        val p=ListeningPolicy(true)
        org.junit.Assert.assertEquals("off",p.sleep);org.junit.Assert.assertTrue(p.shouldAdvance(0))
        p.choose("minutes",0,1,true);p.reachDeadline(60000,true);p.choose("off",61000)
        org.junit.Assert.assertFalse(p.finishCurrent || p.finishingCurrent)
        org.junit.Assert.assertEquals(0,p.remainingTracks);org.junit.Assert.assertEquals(0L,p.deadlineMs)
        org.junit.Assert.assertTrue(p.completed(999999))
    }
}
