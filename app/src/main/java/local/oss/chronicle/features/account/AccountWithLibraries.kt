package local.oss.chronicle.features.account

import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.Library

/**
 * UI model combining an Account with its associated Libraries.
 * Used by AccountListAdapter for expandable list display.
 */
data class AccountWithLibraries(
    val account: Account,
    val libraries: List<Library>,
    val isExpanded: Boolean = false,
)
