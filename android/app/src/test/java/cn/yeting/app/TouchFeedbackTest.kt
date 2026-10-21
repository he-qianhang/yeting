package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test

class TouchFeedbackTest {
    @Test fun firstTickWorksAtMonotonicClockOrigin() {
        assertTrue(TouchFeedback.shouldTick(null,1,null,0))
    }

    @Test fun unchangedValueNeverTicksEvenAtAnchorOrAfterWaiting() {
        assertFalse(TouchFeedback.shouldTick(5,5,null,0,true))
        assertFalse(TouchFeedback.shouldTick(5,5,10,10000,true))
    }

    @Test fun normalTickWaitsAtLeast35Milliseconds() {
        assertFalse(TouchFeedback.shouldTick(1,2,100,134))
        assertTrue(TouchFeedback.shouldTick(1,2,100,135))
    }

    @Test fun anchorIsNotLostDuringFastSweepAndRestartsInterval() {
        val ticks=TouchFeedback.Ticks()
        ticks.reset(3)
        assertTrue(ticks.advance(4,100))
        assertTrue(ticks.advance(5,101,true))
        assertFalse(ticks.advance(6,135))
        assertTrue(ticks.advance(7,136))
    }

    @Test fun suppressedTickDoesNotArriveLaterWhileHoldingTheSameValue() {
        val ticks=TouchFeedback.Ticks()
        assertTrue(ticks.advance(1,100))
        assertFalse(ticks.advance(2,110))
        assertFalse(ticks.advance(2,1000))
        assertTrue(ticks.advance(3,1001))
    }

    @Test fun fastSweepThrottlesFromLastTickRatherThanLastObservedChange() {
        val ticks=TouchFeedback.Ticks()
        ticks.reset(0)
        val emitted=(1..8).filter{ticks.advance(it,100L+(it-1)*10)}
        assertEquals(listOf(1,5),emitted)
    }

    @Test fun directionReversalCanTickAfterInterval() {
        val ticks=TouchFeedback.Ticks()
        ticks.reset(4)
        assertTrue(ticks.advance(5,100))
        assertTrue(ticks.advance(4,135))
    }

    @Test fun eachNewGestureUsesCurrentValueAndHasNoStaleThrottle() {
        val ticks=TouchFeedback.Ticks()
        assertTrue(ticks.advance(2,100))
        ticks.reset(7)
        assertFalse(ticks.advance(7,101))
        assertTrue(ticks.advance(6,102))
    }

    @Test fun programmaticRefreshOnlyChangesBaseline() {
        val ticks=TouchFeedback.Ticks()
        assertTrue(ticks.advance(2,100))
        ticks.reset(6)
        assertFalse(ticks.advance(6,200))
        assertTrue(ticks.advance(2,201))
    }

    @Test fun rulersHaveIndependentDuplicateAndThrottleState() {
        val first=TouchFeedback.Ticks();val second=TouchFeedback.Ticks()
        assertTrue(first.advance(5,100))
        assertTrue(second.advance(5,101))
        assertFalse(first.advance(6,101))
        assertFalse(second.advance(6,102))
    }
}
