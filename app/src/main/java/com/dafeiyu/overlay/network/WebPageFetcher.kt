package com.dafeiyu.overlay.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ExtractedWebPage(
    val url: String,
    val title: String,
    val paragraphs: List<String>
)

object WebPageFetcher {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Extract first URL if shared text contains title + URL
        val urlRegex = Regex("https?://[^\\s<>\"'\\)\\]]+", RegexOption.IGNORE_CASE)
        val matched = urlRegex.find(trimmed)?.value
        if (matched != null) return matched

        // Bare domain like "en.wikipedia.org/wiki/..."
        if (Regex("^[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}(/.*)?$").matches(trimmed)) {
            return "https://$trimmed"
        }
        return null
    }

    suspend fun fetchReadablePage(rawUrl: String): ApiResult<ExtractedWebPage> =
        withContext(Dispatchers.IO) {
            val url = normalizeUrl(rawUrl)
                ?: return@withContext ApiResult.Error("無效的網頁網址")

            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,ja;q=0.8,zh-TW;q=0.7")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ApiResult.Error("無法讀取網頁 (HTTP ${resp.code})", resp.code)
                    }
                    val html = resp.body?.string().orEmpty()
                    if (html.isBlank()) {
                        return@withContext ApiResult.Error("網頁內容為空")
                    }
                    val extracted = parseHtmlToArticle(url, html)
                    if (extracted.paragraphs.isEmpty()) {
                        ApiResult.Error("此網頁需要動態登入或無法提取正文（建議改用「原位翻譯」直接翻譯畫面）")
                    } else {
                        ApiResult.Success(extracted)
                    }
                }
            } catch (e: Exception) {
                ApiResult.Error("抓取網頁失敗：${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }

    fun parseHtmlToArticle(url: String, html: String): ExtractedWebPage {
        val titleMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        val title = decodeEntities(titleMatch?.groupValues?.getOrNull(1).orEmpty())
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { url }

        // Prefer <article> or <main> block when substantial
        var body = Regex("<article[^>]*>(.*?)</article>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)
            ?.takeIf { it.length > 400 }
            ?: Regex("<main[^>]*>(.*?)</main>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)
                ?.takeIf { it.length > 400 }
            ?: Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)
            ?: html

        // Strip non-content tags
        val removeTags = listOf("script", "style", "noscript", "svg", "iframe", "nav", "footer", "aside", "form", "button")
        removeTags.forEach { tag ->
            body = body.replace(
                Regex("<$tag\\b[^>]*>.*?</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                "\n"
            )
        }
        // Strip HTML comments
        body = body.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

        // Replace block boundaries with newline markers
        body = body.replace(
            Regex("</?(p|div|h1|h2|h3|h4|h5|h6|li|blockquote|tr|section|br|hr)\\b[^>]*>", RegexOption.IGNORE_CASE),
            "\n"
        )
        // Strip remaining inline tags
        body = body.replace(Regex("<[^>]+>"), " ")
        body = decodeEntities(body)

        val seen = HashSet<String>()
        val paragraphs = body.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { line ->
                line.length >= 12 &&
                    !line.startsWith("http://") &&
                    !line.startsWith("https://") &&
                    seen.add(line)
            }
            .take(80)

        return ExtractedWebPage(
            url = url,
            title = title,
            paragraphs = paragraphs
        )
    }

    fun chunkParagraphs(paragraphs: List<String>, maxCharsPerChunk: Int = 1500, maxItemsPerChunk: Int = 8): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentLen = 0

        for (p in paragraphs) {
            if (current.isNotEmpty() && (currentLen + p.length > maxCharsPerChunk || current.size >= maxItemsPerChunk)) {
                chunks.add(current)
                current = mutableListOf()
                currentLen = 0
            }
            current.add(p)
            currentLen += p.length
        }
        if (current.isNotEmpty()) {
            chunks.add(current)
        }
        return chunks
    }

    private fun decodeEntities(input: String): String {
        var text = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&rdquo;", "”")
            .replace("&ldquo;", "“")

        // Numeric decimal entities &#1234;
        text = Regex("&#(\\d+);").replace(text) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 32..0x10FFFF) {
                String(Character.toChars(code))
            } else ""
        }
        // Numeric hex entities &#x1F600;
        text = Regex("&#[xX]([0-9a-fA-F]+);").replace(text) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 32..0x10FFFF) {
                String(Character.toChars(code))
            } else ""
        }
        return text
    }
}
