package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopPdfCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `開き直しても訳を使い品質と言語を区別する`() = runBlocking {
        val directory = folder.newFolder().toPath()
        val scope = TranslationStream.Options(source = null, target = Language.JA).cacheScope
        val first = DesktopPdfCache(directory)
        val source = "Hello!"
        first.store(scope, ModelOrigin("small", 1), first.generation, mapOf(source to "こんにちは"))
        val reopened = DesktopPdfCache(directory)
        assertEquals("こんにちは", reopened.lookup(scope, 1, listOf(source)).getValue(source).translation)
        assertTrue(reopened.lookup(scope, 2, listOf(source)).isEmpty())
        assertTrue(reopened.lookup(scope.copy(target = Language.KO), 1, listOf(source)).isEmpty())

        reopened.store(scope, ModelOrigin("large", 2), reopened.generation, mapOf(source to "ごきげんよう"))
        first.store(scope, ModelOrigin("small", 1), first.generation, mapOf(source to "こんにちは"))
        assertEquals("ごきげんよう", first.lookup(scope, 2, listOf(source)).getValue(source).translation)
    }

    @Test fun `消去前に始まった翻訳はキャッシュに戻らない`() = runBlocking {
        val directory = folder.newFolder().toPath()
        val cache = DesktopPdfCache(directory)
        val scope = TranslationStream.Options(source = null, target = Language.JA).cacheScope
        val origin = ModelOrigin("small", 1)
        val old = cache.generation
        val pair = mapOf("Hello!" to "こんにちは")
        cache.store(scope, origin, old, pair)
        assertEquals(1L, cache.count())
        DesktopPdfCache(directory).clear()
        assertEquals(0L, cache.count())
        cache.store(scope, origin, old, pair)
        assertTrue(DesktopPdfCache(directory).lookup(scope, 1, pair.keys).isEmpty())
        cache.store(scope, origin, cache.generation, pair)
        assertEquals("こんにちは", cache.lookup(scope, 1, pair.keys).getValue("Hello!").translation)
    }
}
