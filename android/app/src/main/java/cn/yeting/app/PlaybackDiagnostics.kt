package cn.yeting.app

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.UUID

/** Callers supply fixed state codes/numbers only, never media metadata, accounts or exception text. */
internal class PlaybackDiagnostics(context:Context) {
    private val context=context.applicationContext ?: context
    private val identity=identity()
    private val log=runCatching {
        PlaybackDiagnosticLog(File(this.context.noBackupFilesDir,"playback-diagnostics"),processToken,identity)
    }.getOrNull()

    // One small append per meaningful event; do not call from a progress/frame ticker.
    fun event(name:String,details:String="") {
        val code=name.takeIf{it.matches(Regex("[a-zA-Z0-9_.-]{1,64}"))} ?: "invalid_event"
        log?.append("time_unix_ms=${System.currentTimeMillis()} elapsed_realtime_ms=${SystemClock.elapsedRealtime()} event=$code details=${PlaybackDiagnosticText.field(details)}")
    }

    fun report():String=buildString {
        appendLine("夜听播放诊断 v1")
        appendLine(identity)
        appendLine("reported_unix_ms=${System.currentTimeMillis()}")
        appendLine(exitHistory())
        append(log?.report() ?: "storage=unavailable\n")
    }

    @Suppress("DEPRECATION")
    private fun identity():String {
        val version=runCatching {
            val info=context.packageManager.getPackageInfo(context.packageName,0)
            "${PlaybackDiagnosticText.field(info.versionName.orEmpty())}(${if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()})"
        }.getOrDefault("unknown")
        return "app_package=${PlaybackDiagnosticText.field(context.packageName)} app_version=$version android_api=${Build.VERSION.SDK_INT}"+
            " android_release=${PlaybackDiagnosticText.field(Build.VERSION.RELEASE.orEmpty())}"+
            " manufacturer=${PlaybackDiagnosticText.field(Build.MANUFACTURER.orEmpty())} model=${PlaybackDiagnosticText.field(Build.MODEL.orEmpty())}"+
            " pid=${Process.myPid()} recording_started_unix_ms=$recordingStarted"
    }

    private fun exitHistory():String {
        if(Build.VERSION.SDK_INT<30)return "exit_history=unsupported_android_api reason=unknown"
        return runCatching{readExitHistory()}.getOrDefault("exit_history=unavailable reason=unknown")
    }

    @TargetApi(30)
    private fun readExitHistory():String {
        val manager=context.getSystemService(ActivityManager::class.java)
            ?: return "exit_history=unavailable reason=unknown"
        // Playback runs in the main process. Exclude bound external/sandbox processes.
        val records=manager.getHistoricalProcessExitReasons(context.packageName,0,10)
            .filter{it.processName==context.packageName}.take(5)
        if(records.isEmpty())return "exit_history=no_records reason=unknown"
        return "exit_history=available process_scope=main\n"+records.joinToString("\n") {
            "exit time_unix_ms=${it.timestamp} pid=${it.pid} reason=${PlaybackDiagnosticText.exitReason(it.reason)}"+
                " reason_code=${it.reason} status=${it.status} importance=${it.importance}"
        }
        // No descriptions, traces or process-state summaries: they can contain arbitrary content.
    }

    companion object {
        private val processToken=UUID.randomUUID().toString()
        private val recordingStarted=System.currentTimeMillis()
    }
}

internal object PlaybackDiagnosticText {
    private val links=Regex("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)[^\\s]+")
    private val emails=Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+")
    fun field(value:String)=emails.replace(links.replace(value,"[redacted]"),"[redacted]")
        .map{if(it.isISOControl() || it=='\u2028' || it=='\u2029')' ' else it}.joinToString("").take(256)

    fun exitReason(code:Int):String=when(code) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        else -> "unknown"
    }
}

/** Two bounded private files, shared safely by UI/service instances in the same process. */
internal class PlaybackDiagnosticLog(private val directory:File,token:String,identity:String) {
    private val current=File(directory,"current.txt")
    private val previous=File(directory,"previous.txt")
    private val header="process_token=$token\n$identity\n"
    private var writeOk=true
    private var ready=false

    init {
        synchronized(lock) {
            writeOk=runCatching {
                check(directory.isDirectory || directory.mkdirs())
                val saved=read(current)
                if(saved.lineSequence().firstOrNull()!=header.lineSequence().first()) {
                    if(current.exists())check(current.renameTo(previous))
                    replaceCurrent(header)
                }
            }.isSuccess
            ready=writeOk
        }
    }

    fun append(line:String) {
        synchronized(lock) {
            if(!ready)return
            writeOk=runCatching {
                val row=line+"\n"
                check(row.toByteArray(Charsets.UTF_8).size<=1024)
                if(!current.exists())replaceCurrent(header)
                if(current.length()+row.toByteArray(Charsets.UTF_8).size>MAX_BYTES) {
                    // Compact in batches, keeping both identity lines and recent events.
                    val tail=read(current).lineSequence().drop(2).filter{it.isNotEmpty()}.toList().takeLast(16)
                    replaceCurrent(header+tail.joinToString("\n",postfix=if(tail.isEmpty())"" else "\n"))
                }
                current.appendText(row,Charsets.UTF_8)
            }.isSuccess
        }
    }

    fun report():String=synchronized(lock) {
        "storage_write=${if(writeOk)"ok" else "unavailable"}\n"+
            "[previous_process]\n${safeRead(previous)}\n[current_process]\n${safeRead(current)}\n"
    }

    private fun safeRead(file:File)=runCatching{read(file).ifEmpty{"none"}}.getOrDefault("storage_read=unavailable")
    private fun read(file:File):String {
        if(!file.exists())return ""
        check(file.length()<=MAX_BYTES)
        return file.readText(Charsets.UTF_8)
    }
    private fun replaceCurrent(text:String) {
        check(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        val temp=File(directory,"current.tmp")
        temp.writeText(text,Charsets.UTF_8)
        check(temp.renameTo(current))
    }

    companion object {
        internal const val MAX_BYTES=32*1024
        private val lock=Any()
    }
}
