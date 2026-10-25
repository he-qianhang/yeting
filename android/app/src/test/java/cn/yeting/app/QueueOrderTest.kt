package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test

class QueueOrderTest {
    @Test fun lastItemWrapsAndUnavailableItemsAreSkipped(){
        assertEquals(0,QueueOrder.next(3,2){true})
        assertEquals(1,QueueOrder.next(4,3){it==1 || it==3})
        assertEquals(3,QueueOrder.next(4,1){it==1 || it==3})
    }
    @Test fun onePlayableItemRepeatsEvenInsideALargerQueue(){
        assertEquals(0,QueueOrder.next(1,0){true})
        assertEquals(2,QueueOrder.next(5,2){it==2})
    }
    @Test fun emptyOrUnavailableQueueTerminatesAfterOneRound(){
        assertNull(QueueOrder.next(0,0){error("Empty queue must not be inspected")})
        var calls=0
        assertNull(QueueOrder.next(7,6){calls++;false})
        assertEquals(7,calls)
    }
    @Test fun missingCurrentStartsAtFirstPlayableItem(){
        assertEquals(1,QueueOrder.next(3,-1){it>0})
    }
}
