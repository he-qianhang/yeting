package cn.yeting.app

import org.junit.Assert.*
import org.junit.Test

class YouTubeChallengeTest {
    private val first=YouTubeChallenge.url("https://www.google.com/js/th/first.js")
    private fun failure(action:()->Unit):ProofRequestException {
        try{action();fail("Expected failure")}catch(e:ProofRequestException){return e}
        error("Unreachable")
    }
    @Test fun ordinaryScriptNeedsOneRequest(){
        var calls=0
        assertEquals("script",YouTubeChallenge.load(first){calls++;YouTubeChallenge.Response(200,"script")})
        assertEquals(1,calls)
    }
    @Test fun relativeAndAbsoluteTrustedRedirectsFinishSuccessfully(){
        val seen=mutableListOf<String>()
        val script=YouTubeChallenge.load(first){url->
            seen+=url.encodedPath
            when(seen.size){
                1->YouTubeChallenge.Response(302,location="next.js")
                2->YouTubeChallenge.Response(307,location="https://www.google.com/js/bg/final.js")
                else->YouTubeChallenge.Response(200,"verified-script")
            }
        }
        assertEquals("verified-script",script)
        assertEquals(listOf("/js/th/first.js","/js/th/next.js","/js/bg/final.js"),seen)
    }
    @Test fun allSupportedRedirectCodesAreHandled(){
        for(status in listOf(301,302,303,307,308)){
            var calls=0
            assertEquals("script",YouTubeChallenge.load(first){
                if(calls++==0)YouTubeChallenge.Response(status,location="/js/th/next.js")else YouTubeChallenge.Response(200,"script")
            });assertEquals(2,calls)
        }
    }
    @Test fun redirectLoopStopsAfterTwoHops(){
        var calls=0
        val error=failure{YouTubeChallenge.load(first){calls++;YouTubeChallenge.Response(302,location="/js/th/loop.js")}}
        assertEquals(3,calls);assertEquals("script_redirect_limit",error.reason);assertEquals(2,error.redirects)
    }
    @Test fun untrustedDestinationsAreNeverRequested(){
        for(destination in listOf("http://www.google.com/js/th/a.js","https://example.org/js/th/a.js",
            "https://www.google.com.evil.example/js/th/a.js","https://www.google.com:444/js/th/a.js",
            "https://user:password@www.google.com/js/th/a.js","https://www.google.com/search?q=secret",
            "https://www.google.com/js/th/../../../search","file:///js/th/a.js")){
            var calls=0
            val error=failure{YouTubeChallenge.load(first){calls++;YouTubeChallenge.Response(302,location=destination)}}
            assertEquals(1,calls);assertEquals("script_redirect_rejected",error.reason)
            assertFalse(error.message.orEmpty().contains(destination))
        }
    }
    @Test fun schemeRelativeAndDefaultTlsPortAreAccepted(){
        assertEquals(first,YouTubeChallenge.url("//www.google.com:443/js/th/first.js"))
        assertEquals("/js/bg/a.js",YouTubeChallenge.url("https://www.google.com/js/bg/a.js").encodedPath)
    }
    @Test fun missingLocationAndEmptyBodyHaveDistinctReasons(){
        assertEquals("script_redirect_missing",failure{YouTubeChallenge.load(first){YouTubeChallenge.Response(302)}}.reason)
        assertEquals("script_empty",failure{YouTubeChallenge.load(first){YouTubeChallenge.Response(200,"  ")}}.reason)
    }
    @Test fun scriptStatusAndRedirectCountSurviveInSafeDiagnostics(){
        var calls=0
        val error=failure{YouTubeChallenge.load(first){
            if(calls++==0)YouTubeChallenge.Response(302,location="next.js")else YouTubeChallenge.Response(404)
        }}
        val detail=SourceErrors.detail(YouTubeProofException("challenge_fetch",error))
        assertEquals("proof_generation",detail.category);assertEquals("proof_challenge_fetch",detail.substep)
        assertEquals("script_http_error",detail.reason);assertEquals(404,detail.httpStatus);assertEquals(1,detail.redirects)
    }
    @Test fun securityAndRateLimitCategoriesStillTakePriority(){
        for((status,category) in listOf(403 to "http_403",429 to "rate_limit")){
            val error=failure{YouTubeChallenge.load(first){YouTubeChallenge.Response(status)}}
            assertEquals(category,SourceErrors.detail(YouTubeProofException("challenge_fetch",error)).category)
        }
        val unknown=SourceErrors.detail(YouTubeProofException("untrusted-step",ProofRequestException("secret-url-or-token",999,999)))
        assertEquals("proof_unknown",unknown.substep);assertNull(unknown.reason);assertNull(unknown.httpStatus);assertNull(unknown.redirects)
    }
}
