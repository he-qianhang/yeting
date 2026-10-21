package cn.yeting.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SourceReliabilityTest {
    @org.junit.Before fun qaOnly(){DeviceFixture.requireQaEmulator()}

    private val context get()=DeviceFixture.context

    @Test fun nativeDownloadRecoversFromOneResetAndRecordsBothAttempts(){
        val bytes=DeviceFixture.inst.context.assets.open("six-seconds.m4a").use{it.readBytes()}
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val requests=AtomicInteger()
        val worker=Thread {
            try{while(!server.isClosed){server.accept().use{socket->
                if(requests.incrementAndGet()==1){socket.setSoLinger(true,0);return@use}
                val input=socket.getInputStream().bufferedReader();val first=input.readLine().orEmpty()
                while(!input.readLine().isNullOrEmpty()){}
                val out=socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                if(!first.startsWith("HEAD "))out.write(bytes)
                out.flush()
            }}}catch(_:Exception){}
        }.apply{isDaemon=true;start()}
        val directory=File(context.cacheDir,"source-reset-qa").apply{deleteRecursively();mkdirs()}
        try {
            val source=PhoneSource(context)
            val url="http://127.0.0.1:${server.localPort}/audio.m4a"
            val audio=source.download(PhoneWork("qa-reset","YouTube","QA","QA",6000,url,url),directory,"qa-reset")
            assertArrayEquals(bytes,audio.readBytes())
            assertEquals(2,source.attempts.records.size)
            assertTrue(source.attempts.records.first().retry)
            assertNull(source.attempts.records.last().failure)
            assertTrue(source.attempts.records.last().started-source.attempts.records.first().started>=1500)
        }finally{server.close();worker.join(1000);directory.deleteRecursively()}
    }

    @Test fun nativeSilentMetadataRequestCancelsWithoutWaitingForSocketTimeout(){
        PhoneSource.initialize(context)
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val accepted=CountDownLatch(1);val release=CountDownLatch(1)
        val serving=Thread {try{server.accept().use{accepted.countDown();release.await(15,TimeUnit.SECONDS)}}catch(_:Exception){}}.apply{isDaemon=true;start()}
        val cancel=AtomicBoolean(false);val error=AtomicReference<Throwable>();val done=CountDownLatch(1)
        val source=PhoneSource(context)
        val worker=Thread {
            try {
                val req=YoutubeDLRequest("http://127.0.0.1:${server.localPort}/slow").addOption("--socket-timeout","15").addOption("--extractor-retries","0").addOption("--retries","0").addOption("--dump-single-json")
                source.attempts.run("metadata","anonymous",{cancel.get()}){source.execute(req,"qa-silent-metadata",{cancel.get()})}
            }catch(e:Throwable){error.set(e)}finally{done.countDown()}
        }.apply{start()}
        try {
            assertTrue(accepted.await(12,TimeUnit.SECONDS));val tick=System.nanoTime();cancel.set(true)
            assertTrue("Cancellation must stop a silent native request",done.await(4,TimeUnit.SECONDS))
            assertTrue((System.nanoTime()-tick)/1_000_000<4000)
            assertEquals("cancelled",SourceErrors.category(error.get()!!));assertEquals(1,source.attempts.records.size)
        }finally{cancel.set(true);release.countDown();server.close();worker.join(5000);serving.join(1000)}
    }

    @Test fun nativeConnectionRefusedStopsAfterTwoAttempts(){
        val port=ServerSocket(0).use{it.localPort}
        val source=PhoneSource(context);val directory=File(context.cacheDir,"source-refused-qa").apply{mkdirs()}
        try {
            val url="http://127.0.0.1:$port/audio.m4a"
            try{source.download(PhoneWork("qa-refused","YouTube","QA","QA",6000,url,url),directory,"qa-refused");fail("Expected connection refusal")}catch(e:Exception){assertEquals("connection_refused",SourceErrors.category(e))}
            assertEquals(2,source.attempts.records.size);assertFalse(source.attempts.records.last().retry)
        }finally{directory.deleteRecursively()}
    }

    @Test fun nativeTlsEofIsBoundedAndDoesNotBlameDeviceTime(){
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val connections=AtomicInteger()
        val serving=Thread {try{while(!server.isClosed){server.accept().use{socket->connections.incrementAndGet();socket.soTimeout=3000;socket.getInputStream().read(ByteArray(2048))}}}catch(_:Exception){}}.apply{isDaemon=true;start()}
        val source=PhoneSource(context);val directory=File(context.cacheDir,"source-tls-eof-qa").apply{mkdirs()}
        try {
            val url="https://127.0.0.1:${server.localPort}/audio.m4a"
            try{source.download(PhoneWork("qa-tls-eof","YouTube","QA","QA",6000,url,url),directory,"qa-tls-eof");fail("Expected TLS EOF")}catch(e:Exception){
                assertEquals("tls_eof",SourceErrors.category(e));assertFalse(PhoneSource.friendlyError(e,"YouTube").contains("手机时间"))
            }
            assertEquals(2,source.attempts.records.size);assertFalse(source.attempts.records.last().retry)
            assertEquals(2,connections.get())
        }finally{server.close();serving.join(1000);directory.deleteRecursively()}
    }

    @Test fun diagnosticKeepsSuccessAndFailureHistoryWithoutPrivateErrorText(){
        val diagnostics=SourceDiagnostics(context);val id="qa-source-history"
        var state=SourceNetworkState(11,true,true,true,true,false,"static");var calls=0
        val attempts=SourceAttempts({state},{})
        attempts.run("metadata","anonymous",{false}){
            if(++calls==1){state=state.copy(identity=12);throw Exception("ERROR: Unable to download webpage: timed out Cookie=qa-secret https://private.example/?token=qa-token")}
        }
        try {
            diagnostics.succeeded(id,"YouTube","读取信息","metadata_ready",attempts)
            val raw=diagnostics.report(id)!!;val report=JSONObject(raw);val rows=report.getJSONArray("attempts")
            assertEquals("metadata_ready",report.getString("status"));assertEquals(2,rows.length())
            assertEquals("timeout",rows.getJSONObject(0).getString("category"));assertTrue(rows.getJSONObject(0).getBoolean("network_changed"))
            assertEquals("success",rows.getJSONObject(1).getString("status"))
            for(secret in listOf("qa-secret","qa-token","private.example","Cookie=","identity"))assertFalse(raw.contains(secret))
            diagnostics.failed(id,"YouTube","准备音频",Exception("ERROR: confirm you're not a bot token=qa-secret"),attempts)
            val failed=JSONObject(diagnostics.report(id)!!);assertEquals("bot_verification",failed.getString("category"));assertEquals(2,failed.getJSONArray("attempts").length())
        }finally{diagnostics.clear(id)}
    }
}
