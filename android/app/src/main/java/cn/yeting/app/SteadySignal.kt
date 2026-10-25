package cn.yeting.app

import kotlin.math.*

/** DSP state belongs to the playback thread. Static gains are attached to incoming audio frames. */
internal class SteadyCompressor(val rate:Int,val channels:Int) {
    val delayFrames=ceil(rate*.005).toInt()
    private val delayed=FloatArray(delayFrames*channels)
    private val makeup=DoubleArray(delayFrames)
    private var cursor=0
    private var filled=0
    private var envelope=0.0
    private var slowEnvelope=0.0
    private val attack=min(1.0,4000.0/(SteadyTuning.FAST_ATTACK_MS*rate))
    private val release=min(1.0,4000.0/(SteadyTuning.FAST_RELEASE_MS*rate))
    private val slowAttack=min(1.0,4000.0/(SteadyTuning.SLOW_ATTACK_MS*rate))
    private val slowRelease=min(1.0,4000.0/(SteadyTuning.SLOW_RELEASE_MS*rate))

    // Same downward RMS / maximum-channel convention and knee as the reviewed FFmpeg prototype.
    private fun gain(power:Double,thresholdLinear:Double,ratio:Double):Double {
        if(power<=0)return 1.0
        val level=10*log10(power)
        val threshold=20*log10(thresholdLinear)
        val width=20*log10(SteadyTuning.KNEE)
        val difference=level-threshold
        val reduction=when {
            difference<= -width/2->0.0
            difference>=width/2->difference*(1.0/ratio-1)
            else->(1.0/ratio-1)*(difference+width/2).pow(2)/(2*width)
        }
        return 10.0.pow(reduction/20)
    }

    fun push(input:FloatArray,pre:Double,post:Double,output:FloatArray):Boolean {
        var peak=0.0
        for(c in 0 until channels)peak=max(peak,abs(input[c]*pre))
        val square=peak*peak
        slowEnvelope+=(square-slowEnvelope)*(if(square>slowEnvelope)slowAttack else slowRelease)
        val slowGain=gain(slowEnvelope,SteadyTuning.SLOW_THRESHOLD,SteadyTuning.SLOW_RATIO)
        val controlled=square*slowGain*slowGain
        envelope+=(controlled-envelope)*(if(controlled>envelope)attack else release)
        val ready=filled==delayFrames
        val factor=gain(envelope,SteadyTuning.FAST_THRESHOLD,SteadyTuning.FAST_RATIO)*makeup[cursor]
        for(c in 0 until channels){
            val index=cursor*channels+c
            if(ready)output[c]=(delayed[index]*factor).toFloat()
            delayed[index]=(input[c]*pre*slowGain).toFloat()
        }
        makeup[cursor]=post
        cursor=(cursor+1)%delayFrames
        if(!ready)filled++
        return ready
    }
}

/** Sliding-window lookahead peak control, linked across channels, without automatic makeup gain. */
internal class SteadyPeakLimiter(rate:Int,private val channels:Int,private val ceiling:Float,delay:Int=ceil(rate*.005).toInt()) {
    val delayFrames=delay
    private val frames=FloatArray(delay*channels)
    private val peaks=FloatArray(delay+2)
    private val indexes=LongArray(delay+2)
    private var head=0
    private var tail=0
    private var cursor=0
    private var count=0L
    private var gain=1.0
    private val release=1-exp(-1.0/(rate*.120))

    fun push(input:FloatArray,output:FloatArray):Boolean {
        var peak=0f
        for(c in 0 until channels)peak=max(peak,abs(input[c]))
        val size=peaks.size
        while(head!=tail && indexes[head]<count-delayFrames)head=(head+1)%size
        while(head!=tail){val previous=(tail+size-1)%size;if(peaks[previous]>peak)break;tail=previous}
        peaks[tail]=peak;indexes[tail]=count;tail=(tail+1)%size
        val desired=if(peaks[head]>ceiling)ceiling/peaks[head].toDouble()else 1.0
        gain=if(desired<gain)desired else gain+(desired-gain)*release
        val ready=count>=delayFrames
        for(c in 0 until channels){
            val index=cursor*channels+c
            if(ready)output[c]=(frames[index]*gain).toFloat()
            frames[index]=input[c]
        }
        cursor=(cursor+1)%delayFrames;count++
        return ready
    }
}

/** Fourfold windowed-sinc interpolation, limiting, and anti-alias filtering after time stretching. */
internal class SteadyOversampledLimiter(rate:Int,private val channels:Int) {
    private val taps=24
    private val up=Array(4){phase->
        DoubleArray(taps){i->sinc(i-(taps-1)/2.0+phase/4.0)*window(i,taps)}.also{a->val sum=a.sum();for(i in a.indices)a[i]/=sum}
    }
    private val down=DoubleArray(taps*4){i->.25*.95*sinc((i-(taps*4-1)/2.0)*.25*.95)*window(i,taps*4)}.also{a->val sum=a.sum();for(i in a.indices)a[i]/=sum}
    private val history=FloatArray(taps*channels)
    private val filtered=FloatArray(down.size*channels)
    private val high=FloatArray(channels)
    private val limited=FloatArray(channels)
    private val limiter=SteadyPeakLimiter(rate*4,channels,10.0.pow(-2.5/20).toFloat(),ceil(rate*.005).toInt()*4)
    private var upCursor=0
    private var downCursor=0
    private var phaseCount=0
    private var discard=23
    val tailFrames=ceil(rate*.005).toInt()+23

    fun push(input:FloatArray,output:FloatArray):Boolean {
        for(c in 0 until channels)history[upCursor*channels+c]=input[c]
        var ready=false
        for(phase in 0..3){
            for(c in 0 until channels){
                var value=0.0
                for(i in 0 until taps)value+=history[((upCursor-i+taps)%taps)*channels+c]*up[phase][i]
                high[c]=value.toFloat()
            }
            if(!limiter.push(high,limited))continue
            for(c in 0 until channels)filtered[downCursor*channels+c]=limited[c]
            if(++phaseCount==4){
                phaseCount=0
                if(discard>0)discard-- else {
                    for(c in 0 until channels){
                        var value=0.0
                        for(i in down.indices)value+=filtered[((downCursor-i+down.size)%down.size)*channels+c]*down[i]
                        output[c]=value.toFloat()
                    }
                    ready=true
                }
            }
            downCursor=(downCursor+1)%down.size
        }
        upCursor=(upCursor+1)%taps
        return ready
    }

    private fun sinc(value:Double)=if(abs(value)<1e-10)1.0 else sin(PI*value)/(PI*value)
    private fun window(index:Int,size:Int)=.42-.5*cos(2*PI*index/(size-1))+.08*cos(4*PI*index/(size-1))
}
