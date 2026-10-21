package cn.yeting.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri

object SourceLinks {
    fun open(activity:Activity,entry:Entry) {
        val candidates=when(entry.platform){
            "抖音"->listOf("com.ss.android.ugc.aweme","com.ss.android.ugc.aweme.lite")
            "B站"->listOf("tv.danmaku.bili")
            else->listOf("com.google.android.youtube")
        }
        val installed=candidates.filter{activity.packageManager.getLaunchIntentForPackage(it)!=null}
        val prefs=NightApp.store.prefs
        val previous=prefs.getString("open-${entry.platform}",null)
        fun launch(pkg:String?) {
            val source=entry.appUri.ifEmpty{if(entry.platform=="抖音")Regex("/video/(\\d+)").find(entry.url)?.groupValues?.get(1)?.let{"snssdk1128://aweme/detail/$it"}.orEmpty()else ""}
            val scheme=source.let{if(pkg=="com.ss.android.ugc.aweme.lite")it.replace("snssdk1128:","snssdk2329:")else it}
            for(url in (if(pkg!=null)listOf(scheme,entry.url)else listOf(entry.url)).filter{it.isNotBlank()}) {
                try{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).apply{if(pkg!=null)setPackage(pkg)});return}catch(_:android.content.ActivityNotFoundException){}
            }
            if(pkg!=null)launch(null)
        }
        if(installed.size>1 && previous !in installed){
            val labels=installed.map{activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(it,0)).toString()}.toTypedArray()
            AlertDialog.Builder(activity,if(activity.nightDark())R.style.NightDialog else R.style.DayDialog).setTitle("使用哪个App打开").setItems(labels){_,i->prefs.edit().putString("open-${entry.platform}",installed[i]).apply();launch(installed[i])}.show()
        }else launch(previous?.takeIf{it in installed} ?: installed.firstOrNull())
    }
}
