package cn.yeting.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/** One checkpoint for an unfinished limit; an absent checkpoint means a new unlimited session. */
internal class SleepRecovery(private val context:Context) {
    private val prefs get()=NightApp.store.prefs
    private fun boot()=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
    data class Restored(val policy:ListeningPolicy,val entryId:String?,val notice:String)
    fun load():Restored {
        val fresh=ListeningPolicy(true,"off")
        val raw=prefs.getString("sleepCheckpoint",null) ?: return Restored(fresh,null,"")
        val saved=runCatching{JSONObject(raw)}.getOrNull()
        if(saved==null || boot()<0 || saved.optInt("boot",-2)!=boot()) {
            clear();return Restored(fresh,null,"上次睡眠限制需重新设定，播放保持暂停")
        }
        val snapshot=ListeningPolicy.Snapshot(saved.optBoolean("continuous",true),saved.optString("sleep"),saved.optLong("deadline"),saved.optInt("tracks"),saved.optBoolean("finish"),saved.optBoolean("finishing"))
        if(!fresh.restore(snapshot,SystemClock.elapsedRealtime())) {
            clear();return Restored(fresh,null,"上次睡眠限制已结束，播放保持暂停")
        }
        return Restored(fresh,saved.optString("entry").takeIf{it.isNotEmpty()},"已保留上次睡眠限制，点播放继续")
    }
    fun save(policy:ListeningPolicy,entryId:String?) {
        if(policy.sleep=="off"){clear();return}
        val s=policy.snapshot()
        val value=JSONObject().put("boot",boot()).put("continuous",s.continuous).put("sleep",s.sleep)
            .put("deadline",s.deadlineMs).put("tracks",s.remainingTracks).put("finish",s.finishCurrent)
            .put("finishing",s.finishingCurrent).put("entry",entryId.orEmpty()).toString()
        // Persist only policy/identity changes, not every progress tick; synchronous for abrupt process loss.
        if(prefs.getString("sleepCheckpoint",null)!=value)prefs.edit().putString("sleepCheckpoint",value).commit()
    }
    private fun clear(){if(prefs.contains("sleepCheckpoint"))prefs.edit().remove("sleepCheckpoint").commit()}
}
