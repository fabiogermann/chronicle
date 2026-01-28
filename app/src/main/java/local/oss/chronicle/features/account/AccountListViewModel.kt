package local.oss.chronicle.features.account

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map as mapFlow
import kotlinx.coroutines.launch
import local.oss.chronicle.navigation.Navigator
import local.oss.chronicle.util.Event
import javax.inject.Inject

/**
 * ViewModel for AccountListFragment.
 * 
 * Manages the display and interaction of accounts and their libraries
 * in an expandable list format.
 */
class AccountListViewModel(
    private val accountManager: AccountManager,
    private val activeLibraryProvider: ActiveLibraryProvider,
    private val navigator: Navigator
) : ViewModel() {

    // ===== Private State =====

    /**
     * Set of expanded account IDs for UI state.
     */
    private val _expandedAccountIds = MutableStateFlow<Set<String>>(emptySet())

    // ===== Exposed State =====

    /**
     * All accounts with their libraries, ordered by last used.
     * Includes expanded state for UI.
     */
    val accounts: LiveData<List<AccountWithLibraries>> = combine(
        accountManager.getAccountsOrderedByLastUsed(),
        _expandedAccountIds
    ) { accountList, expandedIds ->
        accountList.map { account ->
            val libraries = accountManager.getLibrariesForAccount(account.id).first()
            AccountWithLibraries(
                account = account,
                libraries = libraries,
                isExpanded = account.id in expandedIds
            )
        }
    }.asLiveData()

    /**
     * Currently active library ID for highlighting in the list.
     */
    val activeLibraryId: LiveData<String?> =
        activeLibraryProvider.currentLibrary
            .mapFlow { it?.id }
            .asLiveData()

    /**
     * Whether any accounts exist.
     */
    val hasAccounts: LiveData<Boolean> = accounts.map { it.isNotEmpty() }

    /**
     * User messages (errors, success confirmations).
     */
    private val _userMessage = MutableLiveData<Event<String>>()
    val userMessage: LiveData<Event<String>> = _userMessage

    /**
     * Account ID for removal confirmation dialog.
     */
    private val _confirmRemovalAccountId = MutableLiveData<Event<String>>()
    val confirmRemovalAccountId: LiveData<Event<String>> = _confirmRemovalAccountId

    // ===== Actions =====

    /**
     * Navigate to OAuth login flow to add a new account.
     */
    fun onAddAccount() {
        navigator.showLogin()
    }

    /**
     * Toggle expand/collapse state for an account.
     */
    fun onToggleExpand(accountId: String) {
        val current = _expandedAccountIds.value
        _expandedAccountIds.value = if (accountId in current) {
            current - accountId
        } else {
            current + accountId
        }
    }

    /**
     * Switch to a different library.
     */
    fun onSwitchToLibrary(libraryId: String) {
        viewModelScope.launch {
            try {
                accountManager.switchToLibrary(libraryId)
                _userMessage.value = Event("Switched library")
            } catch (e: Exception) {
                _userMessage.value = Event("Failed to switch library: ${e.message}")
            }
        }
    }

    /**
     * Request removal of an account (shows confirmation dialog).
     */
    fun onRemoveAccount(accountId: String) {
        _confirmRemovalAccountId.value = Event(accountId)
    }

    /**
     * Confirm and execute account removal.
     */
    fun confirmRemoveAccount(accountId: String) {
        viewModelScope.launch {
            try {
                accountManager.removeAccount(accountId)
                _userMessage.value = Event("Account removed")
            } catch (e: Exception) {
                _userMessage.value = Event("Failed to remove account: ${e.message}")
            }
        }
    }

    // ===== Factory =====

    class Factory @Inject constructor(
        private val accountManager: AccountManager,
        private val activeLibraryProvider: ActiveLibraryProvider,
        private val navigator: Navigator
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AccountListViewModel(
                accountManager,
                activeLibraryProvider,
                navigator
            ) as T
        }
    }
}
