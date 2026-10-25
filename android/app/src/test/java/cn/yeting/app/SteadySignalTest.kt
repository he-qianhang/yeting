package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SteadySignalTest {
    private fun compress(frames:Array<FloatArray>,rate:Int=48000,pre:(Int)->Double={1.0},post:(Int)->Double={1.0}):Array<FloatArray>{
        val core=SteadyCompressor(rate,frames[0].size)
        val output=FloatArray(frames[0].size);val result=ArrayList<FloatArray>()
        frames.forEachIndexed{i,frame->if(core.push(frame,pre(i),post(i),output))result.add(output.copyOf())}
        repeat(core.delayFrames){if(core.push(FloatArray(output.size),1.0,1.0,output))result.add(output.copyOf())}
        assertEquals(frames.size,result.size)
        return result.toTypedArray()
    }
    private fun limited(frames:Array<FloatArray>,rate:Int=48000):Array<FloatArray>{
        val core=SteadyOversampledLimiter(rate,frames[0].size)
        val output=FloatArray(frames[0].size);val result=ArrayList<FloatArray>()
        for(frame in frames)if(core.push(frame,output))result.add(output.copyOf())
        repeat(core.tailFrames){if(core.push(FloatArray(output.size),output))result.add(output.copyOf())}
        assertEquals(frames.size,result.size)
        return result.toTypedArray()
    }

    @Test fun pendingFramesKeepTheirOwnEntryMakeup(){
        val frames=Array(9600){floatArrayOf(.001f,-.001f)}
        val result=compress(frames,post={if(it<4800)1.0 else .1})
        for(i in result.indices){val expected=if(i<4800).001f else .0001f
            assertEquals(expected,result[i][0],1e-8f);assertEquals(-expected,result[i][1],1e-8f)}
    }
    @Test fun compressorReducesTwelveDbRiseWithoutChangingTiming(){
        val rate=48000
        val frames=Array(rate*23){i->
            val gain=10.0.pow((if(i in rate*10 until rate*13)-16 else -28)/20.0)
            val value=(sin(2*PI*880*i/rate)*gain).toFloat();floatArrayOf(value,value)
        }
        val output=compress(frames,pre={10.0.pow(6.0/20)})
        fun level(start:Int)=10*log10((start*rate until (start+1)*rate).sumOf{output[it][0].toDouble().pow(2)}/rate)
        val difference=level(11)-level(8)
        assertTrue("12 dB rise remained $difference dB",difference in 0.0..3.0)
    }
    @Test fun digitalSilenceStaysExactlySilent(){
        val output=limited(compress(Array(10000){FloatArray(2)},pre={2.0},post={2.0}))
        for(frame in output)for(value in frame)assertEquals(0f,value,0f)
    }
    @Test fun oversamplingPreservesFrameCountEvenForShortStreams(){
        for(rate in listOf(44100,48000))for(length in listOf(1,2,23,240,1025)){
            val result=limited(Array(length){floatArrayOf(.01f)},rate)
            assertEquals(length,result.size);assertTrue(result.all{it.all{v->v.isFinite()}})
        }
    }
    @Test fun limiterUsesLinkedStereoGainAndControlsLargeTransients(){
        val frames=Array(48000){i->val x=(sin(2*PI*997*i/48000)*(if(i in 20000..20480)2.0 else .1)).toFloat();floatArrayOf(x,x*.25f)}
        val result=limited(frames)
        assertTrue(result.maxOf{abs(it[0])}<.8f)
        for(frame in result)assertEquals(frame[0]*.25f,frame[1],1e-6f)
    }
    @Test fun ordinarySignalIsNotIncreasedByLimiter(){
        val result=limited(Array(10000){floatArrayOf(.01f)})
        assertEquals(.01f,result[5000][0],.00001f)
    }
    @Test fun profileRejectsUnboundedBoostAndNonFiniteValues(){
        for(pair in listOf(19.0 to 0.0,0.0 to 19.0,Double.NaN to 0.0,0.0 to Double.POSITIVE_INFINITY)){
            try{SteadyProfile(pair.first,pair.second);fail("Invalid profile accepted")}catch(_:IllegalArgumentException){}
        }
        assertEquals(6.0,SteadyProfile(-3.0,9.0).let{it.preDb+it.postDb},0.0)
        assertEquals(12.0,SteadyProfile(3.0,9.0).let{it.preDb+it.postDb},0.0)
        assertEquals(18.0,SteadyProfile(3.0,15.0).let{it.preDb+it.postDb},0.0)
    }
}
