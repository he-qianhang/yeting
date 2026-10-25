package cn.yeting.app

import kotlin.math.*

/** Runs on the audio thread. Source-frame steps account for the current playback speed. */
internal class SteadyTransition(private val rate:Int) {
    var mix=0.0;private set
    private var target=0.0
    private var preDb=0.0
    private var postDb=0.0
    private var wanted:SteadyProfile?=null
    private var initialized=false
    val pre get()=10.0.pow(preDb/20)
    val post get()=10.0.pow(postDb/20)
    fun choose(profile:SteadyProfile?,boundary:Boolean=false){
        target=if(profile==null)0.0 else 1.0
        if(!initialized || boundary){mix=target;initialized=true}
        if(boundary && profile==null){wanted=null;preDb=0.0;postDb=0.0}
        if(profile!=null){
            if(wanted==null || boundary){preDb=profile.preDb;postDb=profile.postDb}
            wanted=profile
        }
    }
    fun advance(speed:Float){
        val framesPerSecond=rate*speed.toDouble()
        val step=1.0/(2*framesPerSecond)
        mix=when{mix<target->min(target,mix+step);mix>target->max(target,mix-step);else->mix}
        wanted?.let{p->
            val dbStep=3.0/framesPerSecond
            preDb+= (p.preDb-preDb).coerceIn(-dbStep,dbStep)
            postDb+=(p.postDb-postDb).coerceIn(-dbStep,dbStep)
        }
    }
}

/** Fixed delay shared by the original samples and their transition coefficient. */
internal class SteadyFrameDelay(private val channels:Int,val frames:Int) {
    private val data=FloatArray(channels*frames)
    private var cursor=0
    private var count=0
    fun push(input:FloatArray,output:FloatArray):Boolean {
        val ready=count>=frames
        for(c in 0 until channels){val i=cursor*channels+c;if(ready)output[c]=data[i];data[i]=input[c]}
        cursor=(cursor+1)%frames
        if(!ready)count++
        return ready
    }
}
