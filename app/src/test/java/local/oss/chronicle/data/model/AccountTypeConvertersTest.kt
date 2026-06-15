package local.oss.chronicle.data.model

import com.google.common.truth.Truth.assertThat
import local.oss.chronicle.data.sources.plex.model.Connection
import org.junit.Test

/**
 * Round-trip tests for [AccountTypeConverters] — the Room TypeConverter that JSON-encodes
 * the per-library `List<Connection>` column added in fix #1.
 *
 * Fix #4 added new fields (`relay`, `protocol`, `address`, `port`, `iPv6`) to [Connection]
 * with sensible defaults. These tests verify:
 *
 *  1. The full enriched model round-trips losslessly (so newly-written rows preserve relay).
 *  2. Legacy JSON written by fix #1 (only `uri` + `local`) still deserializes — defaults
 *     fill in for the missing fields.
 *  3. The Moshi instance inside [AccountTypeConverters] (built without an explicit
 *     [com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory]) correctly handles
 *     `@JsonClass`-annotated Kotlin data classes.
 */
class AccountTypeConvertersTest {
    private val converters = AccountTypeConverters()

    @Test
    fun `round-trips a Connection list with full Plex metadata including relay`() {
        val original =
            listOf(
                Connection(
                    uri = "http://192.168.1.100:32400",
                    local = true,
                    relay = false,
                    protocol = "http",
                    address = "192.168.1.100",
                    port = 32400,
                    iPv6 = false,
                ),
                Connection(
                    uri = "https://5-6-7-8.abcdef.plex.direct:32400",
                    local = false,
                    relay = false,
                    protocol = "https",
                    address = "5.6.7.8",
                    port = 32400,
                    iPv6 = false,
                ),
                Connection(
                    uri = "https://1-2-3-4.abcdef.plex.direct:8443",
                    local = false,
                    relay = true,
                    protocol = "https",
                    address = "1.2.3.4",
                    port = 8443,
                    iPv6 = false,
                ),
            )

        val json = converters.fromConnectionList(original)
        assertThat(json).isNotNull()

        val restored = converters.toConnectionList(json)
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `deserializes legacy fix-1 JSON with only uri and local, defaulting new fields`() {
        // This is exactly what fix #1 wrote to the `connections` column on schema v3 rows
        // before fix #4 enriched the Connection model.
        val legacyJson =
            """[
              {"uri":"http://192.168.1.100:32400","local":true},
              {"uri":"https://wan.plex.direct:32400","local":false}
            ]""".trimIndent()

        val restored = converters.toConnectionList(legacyJson)

        assertThat(restored).isNotNull()
        assertThat(restored).hasSize(2)
        val lan = restored!![0]
        val wan = restored[1]
        assertThat(lan.uri).isEqualTo("http://192.168.1.100:32400")
        assertThat(lan.local).isTrue()
        assertThat(lan.relay).isFalse()
        assertThat(lan.protocol).isEmpty()
        assertThat(lan.address).isEmpty()
        assertThat(lan.port).isEqualTo(0)
        assertThat(lan.iPv6).isFalse()
        assertThat(wan.local).isFalse()
        assertThat(wan.relay).isFalse()
    }

    @Test
    fun `fromConnectionList serializes IPv6 with its JSON-side name`() {
        val list = listOf(Connection(uri = "https://x", iPv6 = true))
        val json = converters.fromConnectionList(list)!!
        // The persisted JSON must use the wire-side name `IPv6` so round-tripping with any
        // adapter (production or test) sees the same field name Plex emits.
        assertThat(json).contains("\"IPv6\":true")
    }

    @Test
    fun `null and blank input maps to null`() {
        assertThat(converters.toConnectionList(null)).isNull()
        assertThat(converters.toConnectionList("")).isNull()
        assertThat(converters.toConnectionList("   ")).isNull()
        assertThat(converters.fromConnectionList(null)).isNull()
    }

    @Test
    fun `empty list round-trips as empty list`() {
        val json = converters.fromConnectionList(emptyList())
        assertThat(json).isNotNull()
        val restored = converters.toConnectionList(json)
        assertThat(restored).isEmpty()
    }
}
