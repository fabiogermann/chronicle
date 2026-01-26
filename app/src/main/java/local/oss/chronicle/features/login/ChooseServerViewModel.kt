package local.oss.chronicle.features.login

import androidx.lifecycle.*
import kotlinx.coroutines.launch
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.model.LoadingStatus
import local.oss.chronicle.data.model.ServerModel
import local.oss.chronicle.data.model.asServer
import local.oss.chronicle.data.sources.plex.PlexLoginRepo
import local.oss.chronicle.data.sources.plex.PlexLoginService
import local.oss.chronicle.util.Event
import local.oss.chronicle.util.postEvent
import timber.log.Timber
import javax.inject.Inject

class ChooseServerViewModel
    @Inject
    constructor(
        private val plexLoginService: PlexLoginService,
        private val plexLoginRepo: PlexLoginRepo,
    ) : ViewModel() {
        class Factory
            @Inject
            constructor(
                private val plexLoginService: PlexLoginService,
                private val plexLoginRepo: PlexLoginRepo,
            ) : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChooseServerViewModel::class.java)) {
                        return ChooseServerViewModel(plexLoginService, plexLoginRepo) as T
                    }
                    throw IllegalArgumentException("Unknown ViewHolder class")
                }
            }

        private val _userMessage = MutableLiveData<Event<String>>()
        val userMessage: LiveData<Event<String>>
            get() = _userMessage

        private var _servers = MutableLiveData(emptyList<ServerModel>())
        val servers: LiveData<List<ServerModel>>
            get() = _servers

        private var _loadingStatus = MutableLiveData(LoadingStatus.LOADING)
        val loadingStatus: LiveData<LoadingStatus>
            get() = _loadingStatus

        init {
            loadServers()
        }

        private fun loadServers() {
            viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
                try {
                    _loadingStatus.value = LoadingStatus.LOADING
                    val serverContainer = plexLoginService.resources()
                    Timber.i("Server: $serverContainer")
                    _loadingStatus.value = LoadingStatus.DONE
                    val servers =
                        serverContainer
                            .filter { it.provides.contains("server") }
                            .map { it.asServer() }

                    Timber.d("URL_DEBUG: Discovered ${servers.size} servers from plex.tv:")
                    servers.forEach { server ->
                        Timber.d(
                            "URL_DEBUG:   Server '${server.name}' has ${server.connections.size} connections: ${server.connections.map { "${it.uri} (local=${it.local})" }}",
                        )
                    }

                    _servers.postValue(servers)
                } catch (e: Throwable) {
                    Timber.e(e, "Failed to get servers")
                    _userMessage.postEvent("Failed to load servers: ${e.message}")
                    _loadingStatus.value = LoadingStatus.ERROR
                }
            }
        }

        fun refresh() {
            loadServers()
        }

        fun chooseServer(serverModel: ServerModel) {
            plexLoginRepo.chooseServer(serverModel)
        }
    }
