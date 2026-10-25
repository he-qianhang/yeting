package cn.yeting.app

/** Search at most one complete round, including the current item as the last fallback. */
internal object QueueOrder {
    fun next(size:Int,current:Int,playable:(Int)->Boolean):Int? {
        if(size==0)return null
        val start=if(current in 0 until size)current else -1
        for(step in 1..size){val index=(start+step)%size;if(playable(index))return index}
        return null
    }
}
