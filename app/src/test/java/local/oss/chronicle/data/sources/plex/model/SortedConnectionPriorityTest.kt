package local.oss.chronicle.data.sources.plex.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests the connection priority ordering established by fix #4.
 *
 * Plex advertises three classes of connection per server. The client must prefer them in this
 * order:
 *
 *   1. **LAN** ([local] = true) — fastest, always preferred when reachable.
 *   2. **Direct WAN** ([local] = false, [relay] = false) — direct .plex.direct address.
 *   3. **Plex Relay** ([local] = false, [relay] = true) — bandwidth-limited ≈2 Mbps tunnel
 *      through plex.tv; last resort, otherwise high-bitrate audio stutters.
 *
 * This priority is encoded in [Connection.priority] and [Connection.PRIORITY_COMPARATOR] and
 * consumed by both `ConnectionProber` and `PlexConfig.chooseViableConnections`.
 */
class SortedConnectionPriorityTest {
    private val lan = Connection(uri = "http://192.168.1.100:32400", local = true, relay = false)
    private val wan = Connection(uri = "https://wan.plex.direct:32400", local = false, relay = false)
    private val relay = Connection(uri = "https://relay.plex.tv:443", local = false, relay = true)

    @Test
    fun `priority values are LAN 0, direct WAN 1, relay 2`() {
        assertThat(lan.priority).isEqualTo(Connection.PRIORITY_LAN)
        assertThat(wan.priority).isEqualTo(Connection.PRIORITY_DIRECT_WAN)
        assertThat(relay.priority).isEqualTo(Connection.PRIORITY_RELAY)
        assertThat(lan.priority).isLessThan(wan.priority)
        assertThat(wan.priority).isLessThan(relay.priority)
    }

    @Test
    fun `comparator sorts shuffled connections LAN then WAN then relay`() {
        val shuffled = listOf(relay, wan, lan)
        val sorted = shuffled.sortedWith(Connection.PRIORITY_COMPARATOR)
        assertThat(sorted).containsExactly(lan, wan, relay).inOrder()
    }

    @Test
    fun `comparator is stable for two connections at the same tier`() {
        val lan2 = Connection(uri = "http://10.0.0.1:32400", local = true, relay = false)
        val wan2 = Connection(uri = "https://other.plex.direct:32400", local = false, relay = false)
        val shuffled = listOf(wan, relay, lan, lan2, wan2)

        val sorted = shuffled.sortedWith(Connection.PRIORITY_COMPARATOR)

        // Both LANs first (any order), then both WANs, then relay.
        assertThat(sorted.subList(0, 2)).containsExactly(lan, lan2)
        assertThat(sorted.subList(2, 4)).containsExactly(wan, wan2)
        assertThat(sorted.last()).isEqualTo(relay)
    }

    @Test
    fun `a server with only LAN connections still sorts cleanly`() {
        val sorted = listOf(lan).sortedWith(Connection.PRIORITY_COMPARATOR)
        assertThat(sorted).containsExactly(lan)
    }

    @Test
    fun `a server with only relay connection is acceptable but last-priority`() {
        val sorted = listOf(relay).sortedWith(Connection.PRIORITY_COMPARATOR)
        assertThat(sorted).containsExactly(relay)
        assertThat(sorted.first().priority).isEqualTo(Connection.PRIORITY_RELAY)
    }
}
