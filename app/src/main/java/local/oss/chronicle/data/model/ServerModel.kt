package local.oss.chronicle.data.model

import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.data.sources.plex.model.PlexServer

data class ServerModel(
    val name: String,
    val connections: List<Connection>,
    val serverId: String,
    // Access token for the server, needed for accessing shared servers
    val accessToken: String = "",
    val owned: Boolean = true,
)

fun PlexServer.asServer(): ServerModel {
    return ServerModel(
        name = this.name,
        connections = this.connections,
        serverId = this.clientIdentifier,
        accessToken = this.accessToken ?: "",
        owned = this.owned,
    )
}
