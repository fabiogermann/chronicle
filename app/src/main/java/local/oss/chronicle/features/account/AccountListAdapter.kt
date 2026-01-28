package local.oss.chronicle.features.account

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.databinding.ListItemAccountHeaderBinding
import local.oss.chronicle.databinding.ListItemAccountLibraryBinding

/**
 * RecyclerView adapter for displaying accounts with expandable library lists.
 * 
 * Uses two view types:
 * - TYPE_ACCOUNT_HEADER: Account card with expand/collapse
 * - TYPE_LIBRARY: Library item (shown when account is expanded)
 */
class AccountListAdapter(
    private val onAccountClick: (Account) -> Unit,
    private val onLibraryClick: (Library) -> Unit,
    private val onRemoveClick: (Account) -> Unit,
    private val activeLibraryId: () -> String?
) : ListAdapter<AccountListItem, RecyclerView.ViewHolder>(AccountListItemDiffCallback()) {

    companion object {
        private const val TYPE_ACCOUNT_HEADER = 1
        private const val TYPE_LIBRARY = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is AccountListItem.AccountHeader -> TYPE_ACCOUNT_HEADER
            is AccountListItem.LibraryItem -> TYPE_LIBRARY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_ACCOUNT_HEADER -> AccountHeaderViewHolder.from(parent)
            TYPE_LIBRARY -> LibraryItemViewHolder.from(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AccountHeaderViewHolder -> {
                val item = getItem(position) as AccountListItem.AccountHeader
                holder.bind(
                    item,
                    onAccountClick,
                    onRemoveClick
                )
            }
            is LibraryItemViewHolder -> {
                val item = getItem(position) as AccountListItem.LibraryItem
                holder.bind(
                    item,
                    onLibraryClick,
                    activeLibraryId()
                )
            }
        }
    }

    /**
     * Convert AccountWithLibraries list to flat list of items for RecyclerView.
     */
    fun submitAccountList(accounts: List<AccountWithLibraries>) {
        val items = mutableListOf<AccountListItem>()
        accounts.forEach { accountWithLibraries ->
            // Add account header
            items.add(
                AccountListItem.AccountHeader(
                    account = accountWithLibraries.account,
                    libraryCount = accountWithLibraries.libraries.size,
                    isExpanded = accountWithLibraries.isExpanded
                )
            )
            
            // Add libraries if expanded
            if (accountWithLibraries.isExpanded) {
                accountWithLibraries.libraries.forEach { library ->
                    items.add(AccountListItem.LibraryItem(library))
                }
            }
        }
        submitList(items)
    }
}

/**
 * ViewHolder for account header items.
 */
class AccountHeaderViewHolder private constructor(
    private val binding: ListItemAccountHeaderBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        item: AccountListItem.AccountHeader,
        onAccountClick: (Account) -> Unit,
        onRemoveClick: (Account) -> Unit
    ) {
        binding.account = item.account
        binding.libraryCount = item.libraryCount
        binding.isExpanded = item.isExpanded
        binding.root.setOnClickListener { onAccountClick(item.account) }
        binding.removeButton.setOnClickListener { onRemoveClick(item.account) }
        binding.executePendingBindings()
    }

    companion object {
        fun from(parent: ViewGroup): AccountHeaderViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ListItemAccountHeaderBinding.inflate(inflater, parent, false)
            return AccountHeaderViewHolder(binding)
        }
    }
}

/**
 * ViewHolder for library items.
 */
class LibraryItemViewHolder private constructor(
    private val binding: ListItemAccountLibraryBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        item: AccountListItem.LibraryItem,
        onLibraryClick: (Library) -> Unit,
        activeLibraryId: String?
    ) {
        binding.library = item.library
        binding.isActive = item.library.id == activeLibraryId
        binding.root.setOnClickListener { onLibraryClick(item.library) }
        binding.executePendingBindings()
    }

    companion object {
        fun from(parent: ViewGroup): LibraryItemViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ListItemAccountLibraryBinding.inflate(inflater, parent, false)
            return LibraryItemViewHolder(binding)
        }
    }
}

/**
 * Sealed class representing items in the account list RecyclerView.
 */
sealed class AccountListItem {
    data class AccountHeader(
        val account: Account,
        val libraryCount: Int,
        val isExpanded: Boolean
    ) : AccountListItem()

    data class LibraryItem(
        val library: Library
    ) : AccountListItem()
}

/**
 * DiffUtil callback for efficient list updates.
 */
class AccountListItemDiffCallback : DiffUtil.ItemCallback<AccountListItem>() {
    override fun areItemsTheSame(
        oldItem: AccountListItem,
        newItem: AccountListItem
    ): Boolean {
        return when {
            oldItem is AccountListItem.AccountHeader && newItem is AccountListItem.AccountHeader ->
                oldItem.account.id == newItem.account.id
            oldItem is AccountListItem.LibraryItem && newItem is AccountListItem.LibraryItem ->
                oldItem.library.id == newItem.library.id
            else -> false
        }
    }

    override fun areContentsTheSame(
        oldItem: AccountListItem,
        newItem: AccountListItem
    ): Boolean {
        return oldItem == newItem
    }
}
