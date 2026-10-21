package cn.yeting.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceErrorsTest {
    @Test fun cookieHelpDoesNotTurnNetworkFailureIntoLoginRequest(){
        assertEquals("timeout",SourceErrors.category(Exception("Unable to download webpage: timed out. See FAQ about cookies")))
        assertEquals("http_403",SourceErrors.category(Exception("Unable to download webpage: HTTP Error 403: Forbidden")))
        assertEquals("bot_verification",SourceErrors.category(Exception("Sign in to confirm you're not a bot. Use cookies")))
    }
    @Test fun runtimeContentAndAccountErrorsRemainDistinct(){
        assertEquals("script_runtime",SourceErrors.category(Exception("No supported JavaScript runtime could be found")))
        assertEquals("age_restricted",SourceErrors.category(Exception("Sign in to confirm your age")))
        assertEquals("no_formats",SourceErrors.category(Exception("Requested format is not available")))
        assertEquals("unavailable",SourceErrors.category(Exception("Video unavailable")))
        assertEquals("cancelled",SourceErrors.category(InterruptedException()))
    }
    @Test fun certificateResetAndEofAreNotTheSameFailure(){
        assertEquals("tls_certificate",SourceErrors.category(Exception("[SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed")))
        assertEquals("tls_eof",SourceErrors.category(Exception("[SSL: UNEXPECTED_EOF_WHILE_READING] EOF occurred in violation of protocol")))
        assertEquals("connection_reset",SourceErrors.category(Exception("SSL connection reset by peer (errno 104)")))
        assertEquals("tls_handshake",SourceErrors.category(Exception("[SSL: WRONG_VERSION_NUMBER] wrong version number")))
        assertFalse(PhoneSource.friendlyError(Exception("[SSL: UNEXPECTED_EOF_WHILE_READING]"),"YouTube").contains("手机时间"))
    }
    @Test fun finalFailureWinsOverOldWarningsAndHelpUrls(){
        assertEquals("timeout",SourceErrors.category(Exception("WARNING: Sign in to confirm you're not a bot\nERROR: Unable to download webpage: timed out")))
        assertEquals("bot_verification",SourceErrors.category(Exception("WARNING: SSL handshake failed\nERROR: Sign in to confirm you're not a bot")))
        assertEquals("connection",SourceErrors.category(Exception("ERROR: Unable to download webpage https://example.org/ssl/403?key=tls")))
        assertEquals("restricted",SourceErrors.category(Exception("Private video. Sign in if you've been granted access")))
    }
    @Test fun wrappedTypedNetworkErrorsStillClassify(){
        assertEquals("dns",SourceErrors.category(Exception("Request failed",java.net.UnknownHostException("example"))))
        assertEquals("timeout",SourceErrors.category(Exception("Request failed",java.net.SocketTimeoutException())))
        assertEquals("tls_certificate",SourceErrors.category(Exception("Request failed",java.security.cert.CertificateException())))
        assertEquals("webpage",SourceErrors.detail(Exception("ERROR: Unable to download webpage: timed out")).substep)
    }
}
