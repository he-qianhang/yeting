package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SteadyTransitionTest {
    @Test fun lateProfileFadesOnOutputTimeAtEverySpeed(){
        for(step in 5..20){
            val speed=step/10f;val ramp=SteadyTransition(48000)
            ramp.choose(null);repeat(24000){ramp.advance(speed)};assertEquals(0.0,ramp.mix,0.0)
            ramp.choose(SteadyProfile(-6.0,12.0));assertEquals(0.0,ramp.mix,0.0)
            repeat((48000*speed).roundToInt()){ramp.advance(speed)}
            assertEquals(.5,ramp.mix,.00002)
            repeat((48000*speed).roundToInt()+1){ramp.advance(speed)}
            assertEquals(1.0,ramp.mix,.00002)
        }
    }
    @Test fun pauseDoesNotAdvanceAndReversalStartsAtCurrentAmount(){
        val ramp=SteadyTransition(48000);ramp.choose(null);ramp.choose(SteadyProfile(0.0,6.0))
        repeat(24000){ramp.advance(1f)};val before=ramp.mix
        ramp.choose(null);assertEquals(before,ramp.mix,0.0)
        repeat(24001){ramp.advance(1f)};assertEquals(0.0,ramp.mix,0.0)
    }
    @Test fun existingProfileStartsReadyAndOutputUpgradeIsRateLimited(){
        val ramp=SteadyTransition(48000);ramp.choose(SteadyProfile(0.0,12.0))
        assertEquals(1.0,ramp.mix,0.0)
        ramp.choose(SteadyProfile(0.0,18.0));repeat(48000){ramp.advance(1f)}
        assertEquals(15.0,20*log10(ramp.post),.00001)
        assertEquals(1.0,ramp.mix,0.0)
        ramp.choose(null,true);assertEquals(0.0,ramp.mix,0.0)
    }
    @Test fun lateProfileForAnotherEntryDoesNotInheritThePreviousEntryGain(){
        val ramp=SteadyTransition(48000);ramp.choose(SteadyProfile(-20.0,30.0))
        ramp.choose(null,true);ramp.choose(SteadyProfile(12.0,6.0))
        assertEquals(0.0,ramp.mix,0.0)
        assertEquals(12.0,20*log10(ramp.pre),.00001);assertEquals(6.0,20*log10(ramp.post),.00001)
    }
    @Test fun fixedDelayPreservesOriginalFramesAndControlThroughTail(){
        for(length in listOf(1,23,239,480,2500)){
            val delay=SteadyFrameDelay(3,480);val out=FloatArray(3);val collected=ArrayList<FloatArray>()
            for(i in 0 until length){val input=floatArrayOf(i.toFloat(),-i.toFloat(),if(i%2==0)1f else 0f);if(delay.push(input,out))collected.add(out.copyOf())}
            repeat(delay.frames){if(delay.push(FloatArray(3),out))collected.add(out.copyOf())}
            assertEquals(length,collected.size)
            collected.forEachIndexed{i,f->assertArrayEquals(floatArrayOf(i.toFloat(),-i.toFloat(),if(i%2==0)1f else 0f),f,0f)}
        }
    }
}
