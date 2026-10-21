package cn.yeting.app
import org.junit.Assert.*
import org.junit.Test

class ListeningPolicyTest {
    @Test fun endOfCurrentOverridesContinuous() {
        val policy=ListeningPolicy(continuous=true)
        assertTrue(policy.shouldAdvance(0))
        policy.choose("off",0)
        assertTrue(policy.shouldAdvance(0))
        policy.choose("end",0)
        assertFalse(policy.shouldAdvance(0))
    }
    @Test fun minuteTimerUsesRealTimeAndStopsQueue() {
        val policy=ListeningPolicy(continuous=true)
        policy.choose("minutes",1000,15)
        assertEquals(900L,policy.remainingSeconds(1000))
        assertFalse(policy.expired(900999))
        assertTrue(policy.expired(901000))
        assertFalse(policy.shouldAdvance(901000))
        assertEquals(0L,policy.remainingSeconds(999999))
    }
    @Test fun switchingContinuousDoesNotCancelTimer() {
        val policy=ListeningPolicy()
        policy.choose("minutes",10,30)
        val deadline=policy.deadlineMs
        policy.continuous=true
        assertEquals(deadline,policy.deadlineMs)
        assertEquals("minutes",policy.sleep)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidMinutesRejected() {ListeningPolicy().choose("minutes",0,0)}
    @Test fun shareTextExtractsUrlAndAuthor() {
        val link=SharedLink.from("9.28 复制打开抖音，看看【鸡蛋糖水的作品】A股以宽基指数为主角 https://v.douyin.com/v41cbM678Kk/ TYZ:/")
        assertEquals("https://v.douyin.com/v41cbM678Kk/",link.url)
        assertEquals("鸡蛋糖水",link.author)
        assertEquals("抖音",link.platform)
    }
    @Test fun onlyApprovedPlatformsAndPublicLinksAccepted() {
        listOf("http://v.douyin.com/x/","https://v.douyin.com.evil.test/x/","https://user@v.douyin.com/x/","https://127.0.0.1/video/123").forEach {
            assertTrue(runCatching{SharedLink.from(it)}.isFailure)
        }
    }
    @Test fun sourceTrackingDoesNotCreateDuplicateCanonicalUrls() {
        assertEquals("https://www.douyin.com/video/7690901255468133604",SharedLink.from("https://www.douyin.com/video/7690901255468133604?from=copy#part").url)
    }
    @Test fun copiedNonbreakingAndInvisibleSpacesDoNotBreakLink() {
        val link=SharedLink.from("复制打开抖音 https://v.douyin.com/ive30xhfOIg/\u00a0 （这条在抖音里无法听视频）")
        assertEquals("https://v.douyin.com/ive30xhfOIg/",link.url)
        assertEquals("https://v.douyin.com/ive30xhfOIg/",SharedLink.from("https://v.douyin.com/ive30xhfOIg/\u200b").url)
    }
    @Test fun futureShareKeepsOriginalTextForFindingItLater() {
        val source="一个关于历史的长视频 https://b23.tv/abcd"
        val link=SharedLink.from(source)
        assertEquals(source,link.rawText)
        assertEquals("一个关于历史的长视频",link.title)
    }
    @Test fun countStopsAfterNaturalCompletionsAndPauseDoesNotConsume() {
        val p=ListeningPolicy(true);p.choose("count",0,2)
        assertEquals(2,p.remainingTracks);assertTrue(p.completed(1));assertEquals(1,p.remainingTracks)
        assertFalse(p.completed(2));assertEquals(0,p.remainingTracks)
        p.choose("count",3,99);assertEquals(99,p.remainingTracks)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidTrackCountRejected() {ListeningPolicy().choose("count",0,100)}
    @Test fun platformEquivalentUrlsCanonicalizeWithoutLosingBiliPart() {
        assertEquals(SharedLink.from("https://youtu.be/abcdefgh?si=test").url,SharedLink.from("https://www.youtube.com/watch?v=abcdefgh&list=test").url)
        assertEquals("https://www.bilibili.com/video/BV13x41117TL?p=2",SharedLink.from("https://m.bilibili.com/video/BV13x41117TL?p=2&share_source=copy").url)
        assertTrue(runCatching{SharedLink.from("https://www.youtube.com/playlist?list=123")}.isFailure)
    }
    @Test fun futurePlatformsAreRecognizedForLinkCollection() {
        assertEquals("B站",SharedLink.from("https://b23.tv/abcd").platform)
        assertEquals("YouTube",SharedLink.from("https://youtu.be/abcdefgh").platform)
    }
}
