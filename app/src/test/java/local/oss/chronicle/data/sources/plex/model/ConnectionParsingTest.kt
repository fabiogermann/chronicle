package local.oss.chronicle.data.sources.plex.model

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Test

/**
 * Tests Moshi deserialization of the [Connection] data class against payloads representative
 * of Plex's `/api/v2/resources?includeHttps=1&includeRelay=1` response.
 *
 * Fix #4: Plex returns much more than `{ uri, local }` per connection — most importantly
 * `relay: true` for the bandwidth-limited 2 Mbps fallback tunnel through plex.tv. The picker
 * must be able to deprioritize relay behind direct WAN, which means the model has to parse it.
 *
 * This Moshi configuration mirrors the production setup in `AppModule.moshi()` (used by the
 * Retrofit converter): [KotlinJsonAdapterFactory] for reflective Kotlin data-class support.
 */
class ConnectionParsingTest {
    private val moshi: Moshi =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    private val connectionAdapter = moshi.adapter(Connection::class.java)

    @Test
    fun `parses full Plex resources connection payload including relay flag`() {
        val json =
            """
            {
              "protocol": "https",
              "address": "1.2.3.4",
              "port": 8443,
              "uri": "https://1-2-3-4.abcdef.plex.direct:8443",
              "local": false,
              "relay": true,
              "IPv6": false
            }
            """.trimIndent()

        val connection = connectionAdapter.fromJson(json)

        assertThat(connection).isNotNull()
        assertThat(connection!!.uri).isEqualTo("https://1-2-3-4.abcdef.plex.direct:8443")
        assertThat(connection.local).isFalse()
        assertThat(connection.relay).isTrue()
        assertThat(connection.protocol).isEqualTo("https")
        assertThat(connection.address).isEqualTo("1.2.3.4")
        assertThat(connection.port).isEqualTo(8443)
        assertThat(connection.iPv6).isFalse()
    }

    @Test
    fun `parses direct WAN connection payload with relay false`() {
        val json =
            """
            {
              "protocol": "https",
              "address": "5.6.7.8",
              "port": 32400,
              "uri": "https://5-6-7-8.abcdef.plex.direct:32400",
              "local": false,
              "relay": false,
              "IPv6": false
            }
            """.trimIndent()

        val connection = connectionAdapter.fromJson(json)

        assertThat(connection).isNotNull()
        assertThat(connection!!.uri).isEqualTo("https://5-6-7-8.abcdef.plex.direct:32400")
        assertThat(connection.local).isFalse()
        assertThat(connection.relay).isFalse()
        assertThat(connection.protocol).isEqualTo("https")
        assertThat(connection.port).isEqualTo(32400)
    }

    @Test
    fun `parses LAN connection payload`() {
        val json =
            """
            {
              "protocol": "http",
              "address": "192.168.1.100",
              "port": 32400,
              "uri": "http://192.168.1.100:32400",
              "local": true,
              "relay": false,
              "IPv6": false
            }
            """.trimIndent()

        val connection = connectionAdapter.fromJson(json)!!

        assertThat(connection.local).isTrue()
        assertThat(connection.relay).isFalse()
        assertThat(connection.protocol).isEqualTo("http")
        assertThat(connection.address).isEqualTo("192.168.1.100")
    }

    @Test
    fun `parses legacy payload with only uri and local, defaulting new fields`() {
        // This is the shape fix #1 wrote to Room's `connections` JSON column.
        // After fix #4, those persisted rows must still deserialize cleanly.
        val json =
            """
            {
              "uri": "http://192.168.1.100:32400",
              "local": true
            }
            """.trimIndent()

        val connection = connectionAdapter.fromJson(json)

        assertThat(connection).isNotNull()
        assertThat(connection!!.uri).isEqualTo("http://192.168.1.100:32400")
        assertThat(connection.local).isTrue()
        // New fields must default to safe values for legacy persisted rows
        assertThat(connection.relay).isFalse()
        assertThat(connection.protocol).isEmpty()
        assertThat(connection.address).isEmpty()
        assertThat(connection.port).isEqualTo(0)
        assertThat(connection.iPv6).isFalse()
    }

    @Test
    fun `parses empty payload, defaulting every field`() {
        val connection = connectionAdapter.fromJson("{}")

        assertThat(connection).isNotNull()
        assertThat(connection!!.uri).isEmpty()
        assertThat(connection.local).isFalse()
        assertThat(connection.relay).isFalse()
        assertThat(connection.protocol).isEmpty()
        assertThat(connection.address).isEmpty()
        assertThat(connection.port).isEqualTo(0)
        assertThat(connection.iPv6).isFalse()
    }

    @Test
    fun `parses a PlexServer payload with mixed-tier connections including relay`() {
        // Representative slice of `/api/v2/resources` for one server.
        val json =
            """
            {
              "name": "Test Server",
              "provides": "server",
              "clientIdentifier": "deadbeef",
              "accessToken": "tok",
              "owned": true,
              "connections": [
                { "protocol": "http", "address": "192.168.1.100", "port": 32400,
                  "uri": "http://192.168.1.100:32400", "local": true,  "relay": false, "IPv6": false },
                { "protocol": "https", "address": "5.6.7.8", "port": 32400,
                  "uri": "https://5-6-7-8.abcdef.plex.direct:32400", "local": false, "relay": false, "IPv6": false },
                { "protocol": "https", "address": "1.2.3.4", "port": 8443,
                  "uri": "https://1-2-3-4.abcdef.plex.direct:8443", "local": false, "relay": true,  "IPv6": false }
              ]
            }
            """.trimIndent()

        val server = moshi.adapter(PlexServer::class.java).fromJson(json)!!

        assertThat(server.connections).hasSize(3)
        assertThat(server.connections.count { it.local }).isEqualTo(1)
        assertThat(server.connections.count { it.relay }).isEqualTo(1)
        // The relay must be the WAN .plex.direct:8443 one, not the direct WAN.
        val relayConn = server.connections.single { it.relay }
        assertThat(relayConn.uri).isEqualTo("https://1-2-3-4.abcdef.plex.direct:8443")
        assertThat(relayConn.local).isFalse()
    }
}
