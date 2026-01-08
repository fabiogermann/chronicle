package local.oss.chronicle.data.model

import local.oss.chronicle.data.sources.plex.model.MediaType

data class PlexLibrary(val name: String, val type: MediaType, val id: String)
