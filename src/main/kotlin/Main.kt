import com.google.gson.Gson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SearchResult(val title: String, val url: String, val poster: String)
data class MovieDetails(val title: String, val rawLinks: List<String>)
data class VideoLink(val source: String, val url: String, val isM3u8: Boolean, val headers: Map<String, String>? = null)

val client = OkHttpClient.Builder().build()
val gson = Gson()
val headersMap = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
    "Cookie" to "xla=s4t"
)

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowHeader(HttpHeaders.ContentType)
        }
        install(ContentNegotiation) { gson { } }
        
        routing {
            get("/api/search") {
                val query = call.request.queryParameters["q"] ?: return@get call.respond(mapOf("error" to "Query required"))
                val page = call.request.queryParameters["page"] ?: "1"
                
                val url = "https://search.pingora.fyi/collections/post/documents/search?q=$query&query_by=post_title,category&query_by_weights=4,2&sort_by=sort_by_date:desc&limit=15&use_cache=true&page=$page"
                
                val request = Request.Builder().url(url)
                    .header("User-Agent", headersMap["User-Agent"]!!)
                    .header("Cookie", headersMap["Cookie"]!!)
                    .header("Referer", "https://hdhub4u.rehab")
                    .build()
                
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                val jsonBody = response.body?.string() ?: "{}"
                
                call.respondText(jsonBody, io.ktor.http.ContentType.Application.Json)
            }

            get("/api/details") {
                val url = call.request.queryParameters["url"] ?: return@get call.respond(mapOf("error" to "URL required"))
                
                val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*headersMap.flatMap { listOf(it.key, it.value) }.toTypedArray())).build()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                val html = response.body?.string() ?: ""
                val doc = Jsoup.parse(html)
                
                val title = doc.select("h1.page-title span").text()
                val links = doc.select("h3 a:matches(480|720|1080|2160|4K), h4 a:matches(480|720|1080|2160|4K)").map { it.attr("href") } + 
                            doc.select(".page-body > div a").mapNotNull { it.attr("href") }.filter { it.contains("hdstream4u") || it.contains("hubstream") || it.contains("hblinks") }
                
                call.respond(MovieDetails(title, links.distinct()))
            }

            get("/api/extract") {
                val rawUrl = call.request.queryParameters["url"] ?: return@get call.respond(mapOf("error" to "URL required"))
                
                try {
                    val resolvedUrl = if ("?id=" in rawUrl) getRedirectLinks(rawUrl) else rawUrl
                    val lowerUrl = resolvedUrl.lowercase()
                    val results = mutableListOf<VideoLink>()

                    when {
                        "vidstack" in lowerUrl || "hdstream4u" in lowerUrl || "hubstream" in lowerUrl -> {
                            results.addAll(extractVidStack(resolvedUrl))
                        }
                        "hubcdn" in lowerUrl -> {
                            results.addAll(extractHubCdn(resolvedUrl))
                        }
                        "hubcloud" in lowerUrl -> {
                            results.addAll(extractHubCloud(resolvedUrl))
                        }
                        else -> {
                            results.add(VideoLink("Unknown", resolvedUrl, false))
                        }
                    }
                    
                    call.respond(mapOf("original" to rawUrl, "resolved" to resolvedUrl, "links" to results))
                } catch (e: Exception) {
                    call.respond(mapOf("error" to e.message))
                }
            }
        }
    }.start(wait = true)
}

suspend fun extractVidStack(url: String): List<VideoLink> = withContext(Dispatchers.IO) {
    val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
    val hash = url.substringAfterLast("#").substringAfter("/")
    val baseurl = try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { "https://vidstack.io" }

    val request = Request.Builder().url("$baseurl/api/v1/video?id=$hash").header("User-Agent", headers["User-Agent"]!!).build()
    val encoded = client.newCall(request).execute().body?.string()?.trim() ?: throw Exception("Empty response from VidStack")

    val key = "kiemtienmua911ca"
    val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

    val decryptedText = ivList.firstNotNullOfOrNull { iv ->
        try { AesHelper.decryptAES(encoded, key, iv) } catch (_: Exception) { null }
    } ?: throw Exception("Failed to decrypt with all IVs")

    val m3u8 = Regex("\"source\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
    
    listOf(VideoLink(
        source = "Vidstack",
        url = m3u8.replace("https", "http"),
        isM3u8 = true,
        headers = mapOf("referer" to url, "Origin" to url.substringAfterLast("/"))
    ))
}

suspend fun extractHubCdn(url: String): List<VideoLink> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    val html = client.newCall(request).execute().body?.string() ?: ""
    
    val encoded = Regex("r=([A-Za-z0-9+/=]+)").find(html)?.groups?.get(1)?.value
    if (!encoded.isNullOrEmpty()) {
        val decodedUrl = base64DecodeCustom(encoded).substringAfterLast("link=")
        return@withContext listOf(VideoLink("Hubcdn", decodedUrl, true))
    }
    
    val scriptText = Jsoup.parse(html).selectFirst("script:containsData(var reurl)")?.data()
    val encodedUrl2 = Regex("reurl\\s*=\\s*\"([^\"]+)\"").find(scriptText ?: "")?.groupValues?.get(1)?.substringAfter("?r=")
    val decodedUrl2 = encodedUrl2?.let { base64DecodeCustom(it) }?.substringAfterLast("link=")
    
    if (decodedUrl2 != null) {
        return@withContext listOf(VideoLink("HUBCDN", decodedUrl2, false))
    }
    
    emptyList()
}

suspend fun extractHubCloud(url: String): List<VideoLink> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    val doc = Jsoup.parse(client.newCall(request).execute().body?.string() ?: "")
    val results = mutableListOf<VideoLink>()
    
    doc.select("a.btn").forEach { element ->
        val link = element.attr("href")
        val label = element.ownText().lowercase()
        
        when {
            "buzzserver" in label -> {
                val req2 = Request.Builder().url("$link/download").header("Referer", link).build()
                val resp = client.newCall(req2).execute()
                val dlink = resp.header("hx-redirect") ?: resp.header("HX-Redirect") ?: ""
                if (dlink.isNotBlank()) results.add(VideoLink("BuzzServer", dlink, false))
            }
            "pixeldra" in label || "pixelserver" in label -> {
                val finalUrl = if ("download" in link) link else "https://pixeldrain.com/api/file/${link.substringAfterLast("/")}?download"
                results.add(VideoLink("Pixeldrain", finalUrl, false))
            }
            "fsl server" in label || "s3 server" in label -> {
                results.add(VideoLink("FSL/S3 Server", link, false))
            }
        }
    }
    results
}

suspend fun getRedirectLinks(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    val doc = client.newCall(request).execute().body?.string() ?: return@withContext ""
    
    val regex = "s\\('o','([A-Za-z0-9+/=]+)'|ck\\('_wp_http_\\d+','([^']+)'".toRegex()
    val combinedString = buildString {
        regex.findAll(doc).forEach { matchResult ->
            val extractedValue = matchResult.groups[1]?.value ?: matchResult.groups[2]?.value
            if (!extractedValue.isNullOrEmpty()) append(extractedValue)
        }
    }
    try {
        val decodedString = base64DecodeCustom(pen(base64DecodeCustom(base64DecodeCustom(combinedString))))
        val jsonObject = gson.fromJson(decodedString, Map::class.java) as Map<String, String>
        
        val encodedurl = base64DecodeCustom(jsonObject["o"] ?: "").trim()
        val data = encodeCustom(jsonObject["data"] ?: "").trim()
        val wphttp1 = (jsonObject["blog_url"] ?: "").trim()
        
        val directlink = runCatching {
            val req2 = Request.Builder().url("$wphttp1?re=$data").build()
            Jsoup.parse(client.newCall(req2).execute().body?.string() ?: "").select("body").text().trim()
        }.getOrDefault("").trim()

        if (encodedurl.isEmpty()) directlink else encodedurl
    } catch (e: Exception) {
        ""
    }
}

fun encodeCustom(value: String): String {
    return String(Base64.getDecoder().decode(value))
}

fun base64DecodeCustom(value: String): String {
    return String(Base64.getDecoder().decode(value))
}

fun pen(value: String): String {
    return value.map {
        when (it) {
            in 'A'..'Z' -> ((it - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((it - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> it
        }
    }.joinToString("")
}

object AesHelper {
    private const val TRANSFORMATION = "AES/CBC/PKCS5PADDING"
    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedBytes = cipher.doFinal(inputHex.hexToByteArray())
        return String(decryptedBytes, Charsets.UTF_8)
    }
    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
