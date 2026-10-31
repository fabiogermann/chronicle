package local.oss.chronicle.data.sources.plex

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Pins the real outgoing HTTP request for OAuth PIN creation.
 *
 * The manual-linking defect (B1) was that the app displayed a *strong* PIN's
 * 25-character code for manual entry at plex.tv/link, which only accepts the
 * 4-character code of a NON-strong PIN. These tests stand up the real Retrofit
 * service against an in-process OkHttp interceptor (no network, no plex.tv)
 * and assert the actual request line: the OAuth/browser path must send
 * `strong=true`, the manual-linking path `strong=false`, both to
 * `/api/v2/pins.json`.
 */
class PlexLoginServicePinRequestTest {
    private class RecordingInterceptor(
        private val responseBody: String,
    ) : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            requests.add(request)
            return okhttp3.Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun serviceWith(interceptor: RecordingInterceptor): PlexLoginService {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return Retrofit
            .Builder()
            // The interface methods carry absolute https://plex.tv URLs; the base
            // URL is never hit but Retrofit requires one.
            .baseUrl(PLEX_LOGIN_SERVICE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PlexLoginService::class.java)
    }

    @Test
    fun `browser OAuth path requests a strong PIN over the wire`() =
        runTest {
            val interceptor =
                RecordingInterceptor(
                    """{"id": 1001, "clientIdentifier": "client-x", "code": "LONGSTRONGPIN25CHARACTERSAB", "authToken": null}""",
                )
            val service = serviceWith(interceptor)

            service.postAuthPin(strong = true)

            assertEquals(1, interceptor.requests.size)
            val url = interceptor.requests.single().url
            assertEquals("POST", interceptor.requests.single().method)
            assertEquals("plex.tv", url.host)
            assertEquals("/api/v2/pins.json", url.encodedPath)
            assertEquals(
                "the OAuth/browser flow must keep requesting a strong PIN",
                "true",
                url.queryParameter("strong"),
            )
        }

    @Test
    fun `manual linking path requests a non-strong PIN over the wire and gets a 4-char code`() =
        runTest {
            val interceptor =
                RecordingInterceptor(
                    """{"id": 2002, "clientIdentifier": "client-x", "code": "K7XW", "authToken": null}""",
                )
            val service = serviceWith(interceptor)

            val pin = service.postAuthPin(strong = false)

            assertEquals(1, interceptor.requests.size)
            val url = interceptor.requests.single().url
            assertEquals("/api/v2/pins.json", url.encodedPath)
            assertEquals(
                "plex.tv/link only accepts non-strong PIN codes — the manual path must send strong=false",
                "false",
                url.queryParameter("strong"),
            )
            assertNotNull(pin)
            assertEquals("K7XW", pin!!.code)
            assertTrue(
                "manual-linking code must be the short code plex.tv/link accepts, was '${pin.code}'",
                pin.code.length <= 6,
            )
        }
}
