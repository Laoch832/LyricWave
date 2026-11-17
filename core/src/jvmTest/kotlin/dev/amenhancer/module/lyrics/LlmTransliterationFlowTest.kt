package dev.amenhancer.module.lyrics

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LlmTransliterationFlowTest {
    private val original="""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line" xml:lang="ja"><head/><body><div><p begin="00:00:01.000" end="00:00:02.000">鏡よ</p></div></body></tt>"""
    private val response="""{"lang":"ja-Latn","lines":[{"id":"L1","groups":[["kagami yo"]],"english_units":[[]]}]}"""
    @Test fun `generated pronunciation preserves original timing and composes with translation`() {
        val document=requireNotNull(LlmTransliterationDocument.prepare(original))
        val pronunciation=requireNotNull(document.withResponse(response))
        assertTrue(pronunciation.contains("kagami yo"))
        fun body(source: String): List<List<String>> {
            val factory=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            val xml=factory.newDocumentBuilder().parse(java.io.ByteArrayInputStream(source.toByteArray()))
            val element=xml.getElementsByTagNameNS("*","body").item(0) as org.w3c.dom.Element
            val rows=element.getElementsByTagNameNS("*","p")
            return (0 until rows.length).map { (rows.item(it) as org.w3c.dom.Element).let { p -> listOf(p.getAttribute("begin"),p.getAttribute("end"),p.textContent) } }
        }
        assertEquals(body(original),body(pronunciation))
        val translated=requireNotNull(LlmLyricsDocument.prepare(original)).withTranslations(mapOf("L1" to LlmLyricsLine("镜子啊")))!!
        val combined=requireNotNull(LlmLyricsTrackComposer.compose(original,translated,pronunciation))
        assertTrue(combined.contains("kagami yo"));assertTrue(combined.contains("镜子啊"));assertTrue(combined.contains("鏡よ"))
        assertNull(LlmTransliterationDocument.prepare(combined))
    }
    @Test fun `invalid model grouping and duplicate IDs cannot produce a new track`() {
        val document=requireNotNull(LlmTransliterationDocument.prepare(original))
        for (text in listOf(response.replace("L1","L2"),response.replace("[\"kagami yo\"]","[]"),response.replace("ja-Latn","ja"))) assertNull(document.withResponse(text))
        val answer=JSONObject(response); answer.getJSONArray("lines").put(answer.getJSONArray("lines").getJSONObject(0))
        assertNull(document.withResponse(answer.toString()))
    }
    @Test fun `pronunciation from another timed source cannot replace the selected document`() {
        val document=requireNotNull(LlmTransliterationDocument.prepare(original))
        val pronunciation=requireNotNull(document.withResponse(response))
        assertNull(LlmLyricsTrackComposer.compose(original.replace("鏡よ","私よ"),null,pronunciation))
    }
    @Test fun `both completion orders publish the accepted sibling track rather than overwriting it`() {
        val pronunciation=requireNotNull(LlmTransliterationDocument.prepare(original)).withResponse(response)!!
        val translated=requireNotNull(LlmLyricsDocument.prepare(original)).withTranslations(mapOf("L1" to LlmLyricsLine("镜子啊")))!!
        data class Pointer(val source: String,var id: Long=0)
        for (reverse in listOf(false,true)) {
            val jobs=mutableListOf<Runnable>(); val published=mutableListOf<Long>()
            val runtime=dev.amenhancer.module.hook.LlmLyricsRuntime(
                configuration={ LlmLyricsConfig("https://example.test/v1","key","model") },
                translate={ _,_->translated }, executor=java.util.concurrent.Executor { jobs.add(it) },
                transliterate={ _,_->pronunciation }, transliterationEnabled={ true })
            val session=dev.amenhancer.module.hook.LlmLyricsReplacementSession(runtime,{ Pointer(it) },{ true },{ true },
                { (it as Pointer).id },{ p,id -> (p as Pointer).id=id;true },{ true },{ published.add(it) })
            session.onSongChanged(42);session.onSourceSelected(42)
            assertNull(session.replacementFor(42,original));assertEquals(2,jobs.size)
            jobs.removeAt(if(reverse) 1 else 0).run()
            assertNotNull(session.replacementFor(42,original));jobs.single().run()
            val result=session.replacementFor(42,original) as Pointer
            assertTrue(result.source.contains("kagami yo"));assertTrue(result.source.contains("镜子啊"))
            assertEquals(listOf(42L,42L),published);session.close()
        }
    }

    @Test fun `word pronunciation keeps timed English and background vocals separate`() {
        val source = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word" xml:lang="ja"><head/><body><div><p begin="00:00:01.000" end="00:00:04.000"><span begin="00:00:01.000" end="00:00:02.000">鏡</span> <span begin="00:00:02.000" end="00:00:03.000">Hello!</span><span ttm:role="x-bg"><span begin="00:00:03.000" end="00:00:04.000">よ</span></span></p></div></body></tt>"""
        val document = requireNotNull(LlmTransliterationDocument.prepare(source))
        val answer = """{"lang":"ja-Latn","lines":[{"id":"L1","groups":[["kagami ","Hello!"],["yo"]],"english_units":[[1],[]]}]}"""
        val result = requireNotNull(document.withResponse(answer))
        val body = source.substringAfter("<body>").substringBefore("</body>")
        // Generated keys and their namespace declaration may be added, never timed content.
        assertEquals(body, result.substringAfter("<body>").substringBefore("</body>").replace(Regex(" itunes:key=\"[^\"]+\""), "").replace(" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"", ""))
        assertTrue(result.contains(">kagami</span> ")); assertTrue(result.contains("Hello!")); assertTrue(result.contains("yo"))
        assertNull(document.withResponse(answer.replace("Hello!", "Hello")))
        assertNull(document.withResponse(answer.replace("[[1],[]]", "[[2],[]]")))
    }
    @Test fun `non Latin source cannot bypass pronunciation validation with an English flag`() {
        val document = requireNotNull(LlmTransliterationDocument.prepare(original))
        val invalid = """{"lang":"ja-Latn","lines":[{"id":"L1","groups":[["鏡よ"]],"english_units":[[0]]}]}"""
        assertNull(document.withResponse(invalid))
    }
}
