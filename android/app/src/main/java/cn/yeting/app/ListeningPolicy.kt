package cn.yeting.app

/** Countdown uses elapsed wall time, independent of media position and playback speed. */
class ListeningPolicy(var continuous:Boolean=false,var sleep:String="off") {
    data class Snapshot(val continuous:Boolean,val sleep:String,val deadlineMs:Long,val remainingTracks:Int,val finishCurrent:Boolean,val finishingCurrent:Boolean)
    var deadlineMs:Long=0; private set
    var remainingTracks:Int=if(sleep=="end")1 else 0; private set
    var finishCurrent:Boolean=false; private set
    var finishingCurrent:Boolean=false; private set
    fun choose(mode:String,nowMs:Long,minutes:Int=0,finishCurrent:Boolean=false) {
        require(mode in listOf("end","off","minutes","count"))
        if(mode=="minutes") require(minutes in 1..240)
        if(mode=="count") require(minutes in 1..99)
        sleep=mode;deadlineMs=if(mode=="minutes") nowMs+minutes*60_000L else 0
        remainingTracks=if(mode=="count")minutes else if(mode=="end")1 else 0
        this.finishCurrent=mode=="minutes" && finishCurrent;finishingCurrent=false
    }
    fun expired(nowMs:Long)=sleep=="minutes" && deadlineMs>0 && nowMs>=deadlineMs
    /** Returns true only when the deadline requires an immediate pause. */
    fun reachDeadline(nowMs:Long,playing:Boolean):Boolean {
        if(!expired(nowMs))return false
        val wait=finishCurrent && playing
        choose(if(wait)"end"else "off",nowMs);finishingCurrent=wait
        return !wait
    }
    fun shouldAdvance(nowMs:Long)=continuous && sleep!="end" && !expired(nowMs)
    fun completed(nowMs:Long):Boolean {
        if(sleep in listOf("end","count")){remainingTracks=(remainingTracks-1).coerceAtLeast(0);if(remainingTracks==0)return false}
        return continuous && !expired(nowMs)
    }
    fun remainingSeconds(nowMs:Long)=if(deadlineMs>0) ((deadlineMs-nowMs).coerceAtLeast(0)+999)/1000 else 0
    fun snapshot()=Snapshot(continuous,sleep,deadlineMs,remainingTracks,finishCurrent,finishingCurrent)
    /** Restore a still-active limit without starting a fresh countdown or consuming a track. */
    fun restore(saved:Snapshot,nowMs:Long):Boolean {
        if(saved.finishingCurrent)return false
        when(saved.sleep){
            "minutes"->if(saved.deadlineMs<=nowMs || saved.deadlineMs-nowMs>240*60_000L)return false
            "end","count"->if(saved.remainingTracks !in 1..99)return false
            else->return false
        }
        continuous=saved.continuous;sleep=saved.sleep;deadlineMs=if(sleep=="minutes")saved.deadlineMs else 0
        remainingTracks=if(sleep=="minutes")0 else saved.remainingTracks
        finishCurrent=sleep=="minutes" && saved.finishCurrent;finishingCurrent=false
        return true
    }
}
