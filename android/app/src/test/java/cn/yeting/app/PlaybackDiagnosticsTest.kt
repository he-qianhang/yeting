package cn.yeting.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackDiagnosticsTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun previousProcessSurvivesRestartAndCurrentCompaction() {
        val directory=temp.newFolder()
        PlaybackDiagnosticLog(directory,"old","app_version=old").append("event=playing position_ms=123")
        val log=PlaybackDiagnosticLog(directory,"new","app_version=new")
        repeat(300){log.append("event=buffering index=$it details="+"文".repeat(200))}
        val previous=File(directory,"previous.txt").readText()
        val current=File(directory,"current.txt").readText()
        assertTrue(previous.contains("app_version=old"))
        assertTrue(previous.contains("position_ms=123"))
        assertTrue(current.startsWith("process_token=new\napp_version=new\n"))
        assertTrue(current.contains("index=299"))
        assertFalse(current.contains("index=0 "))
        assertTrue(File(directory,"current.txt").length()<=PlaybackDiagnosticLog.MAX_BYTES)
        assertTrue(File(directory,"previous.txt").length()<=PlaybackDiagnosticLog.MAX_BYTES)
        assertFalse(File(directory,"current.tmp").exists())
    }

    @Test fun reopeningInSameProcessDoesNotRotateOrEraseEvents() {
        val directory=temp.newFolder()
        PlaybackDiagnosticLog(directory,"old","old_identity").append("event=old")
        val first=PlaybackDiagnosticLog(directory,"current","identity")
        first.append("event=service_created")
        val second=PlaybackDiagnosticLog(directory,"current","identity")
        second.append("event=paused")
        val report=second.report()
        assertTrue(report.contains("event=old"))
        assertTrue(report.contains("event=service_created"))
        assertTrue(report.contains("event=paused"))
        assertEquals(1,Regex("event=old").findAll(report).count())
    }

    @Test fun onlyImmediatelyPreviousProcessIsRetained() {
        val directory=temp.newFolder()
        listOf("first","second","third").forEach{PlaybackDiagnosticLog(directory,it,"identity").append("event=$it")}
        val report=PlaybackDiagnosticLog(directory,"third","identity").report()
        assertFalse(report.contains("event=first"))
        assertTrue(report.contains("event=second"))
        assertTrue(report.contains("event=third"))
    }

    @Test fun concurrentInstancesAppendWholeRecords() {
        val directory=temp.newFolder()
        val logs=List(4){PlaybackDiagnosticLog(directory,"same","identity")}
        val workers=Executors.newFixedThreadPool(4)
        try {
            val jobs=logs.mapIndexed{index,log->workers.submit{repeat(25){log.append("event=worker_${index}_$it")}}}
            jobs.forEach{it.get(10,TimeUnit.SECONDS)}
        } finally { workers.shutdownNow() }
        val rows=File(directory,"current.txt").readLines().drop(2)
        assertEquals(100,rows.size)
        assertEquals(100,rows.toSet().size)
        assertTrue(rows.all{it.matches(Regex("event=worker_[0-3]_[0-9]+"))})
    }

    @Test fun unavailableDirectoryDoesNotThrowOrExposeFilePath() {
        val blocked=temp.newFile("private-account-name")
        val log=PlaybackDiagnosticLog(blocked,"process","identity")
        log.append("event=playing")
        val report=log.report()
        assertTrue(report.contains("storage_write=unavailable"))
        assertFalse(report.contains(blocked.path))
    }

    @Test fun oversizedHistoryIsNotReadIntoReport() {
        val directory=temp.newFolder()
        File(directory,"current.txt").writeText("x".repeat(PlaybackDiagnosticLog.MAX_BYTES+1))
        val log=PlaybackDiagnosticLog(directory,"new","identity")
        assertTrue(log.report().contains("storage_read=unavailable"))
        assertTrue(log.report().length<1000)
    }

    @Test fun compactionFailureKeepsExistingCurrentReadable() {
        val directory=temp.newFolder()
        val log=PlaybackDiagnosticLog(directory,"same","identity")
        log.append("event=important")
        File(directory,"current.tmp").mkdir()
        repeat(60){log.append("event=buffering details="+"x".repeat(900))}
        assertTrue(log.report().contains("storage_write=unavailable"))
        assertTrue(File(directory,"current.txt").readText().contains("event=important"))
        assertTrue(File(directory,"current.txt").length()<=PlaybackDiagnosticLog.MAX_BYTES)
    }

    @Test fun failedRotationDoesNotAppendToTheWrongProcess() {
        val directory=temp.newFolder()
        PlaybackDiagnosticLog(directory,"old","old_identity").append("event=old")
        val saved=File(directory,"current.txt").readText()
        File(directory,"previous.txt").mkdir()
        File(directory,"previous.txt/blocked").writeText("blocked")
        val log=PlaybackDiagnosticLog(directory,"new","new_identity")
        log.append("event=new")
        assertTrue(log.report().contains("storage_write=unavailable"))
        assertEquals(saved,File(directory,"current.txt").readText())
    }

    @Test fun fieldsRedactLinksAndEmailsAndCannotForgeRows() {
        val field=PlaybackDiagnosticText.field("state=ready url=https://secret.test/audio?token=private account=person@example.test\nforged\r\tline\u2028next content://private/file")
        assertFalse(field.contains("secret.test"))
        assertFalse(field.contains("person@"))
        assertFalse(field.contains("private"))
        assertFalse(field.contains('\n'))
        assertFalse(field.contains('\r'))
        assertFalse(field.contains('\t'))
        assertFalse(field.contains('\u2028'))
        assertTrue(field.contains("state=ready"))
        assertEquals(256,PlaybackDiagnosticText.field("文".repeat(1000)).length)
    }

    @Test fun unknownAndSignalReasonsAreNotInferredAsSystemCleanup() {
        assertEquals("unknown",PlaybackDiagnosticText.exitReason(0))
        assertEquals("unknown",PlaybackDiagnosticText.exitReason(9876))
        assertEquals("signaled",PlaybackDiagnosticText.exitReason(2))
        assertEquals("low_memory",PlaybackDiagnosticText.exitReason(3))
        assertEquals("other",PlaybackDiagnosticText.exitReason(13))
    }
}
