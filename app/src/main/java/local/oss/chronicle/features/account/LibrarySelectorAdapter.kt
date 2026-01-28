package local.oss.chronicle.features.account

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.databinding.ListItemLibrarySelectorHeaderBinding
import local.oss.chronicle.databinding.ListItemLibrarySelectorItemBinding

/**
 * RecyclerView adapter for library selector bottom sheet.
 * 
 * Displays libraries grouped by account using two view types:
 * - TYPE_HEADER: Account name header (non-clickable)
 * - TYPE_LIBRARY_ITEM: Library item (clickable) with active indicator
 */
class LibrarySelectorAdapter(
    private val onLibraryClick: (Library) -> Unit
) : ListAdapter<LibrarySelectorItem, RecyclerView.ViewHolder>(LibrarySelectorItemDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_LIBRARY_ITEM = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is LibrarySelectorItem.Header -> TYPE_HEADER
            is LibrarySelectorItem.LibraryItem -> TYPE_LIBRARY_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder.from(parent)
            TYPE_LIBRARY_ITEM -> LibraryViewHolder.from(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                val item = getItem(position) as LibrarySelectorItem.Header
                holder.bind(item)
            }
            is LibraryViewHolder -> {
                val item = getItem(position) as LibrarySelectorItem.LibraryItem
                holder.bind(item, onLibraryClick)
            }
        }
    }
}

/**
 * ViewHolder for account header items.
 */
class HeaderViewHolder private constructor(
    private val binding: ListItemLibrarySelectorHeaderBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: LibrarySelectorItem.Header) {
        binding.accountName = item.accountName
        binding.executePendingBindings()
    }

    companion object {
        fun from(parent: ViewGroup): HeaderViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ListItemLibrarySelectorHeaderBinding.inflate(inflater, parent, false)
            return HeaderViewHolder(binding)
        }
    }
}

/**
 * ViewHolder for library items.
 */
class LibraryViewHolder private constructor(
    private val binding: ListItemLibrarySelectorItemBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        item: LibrarySelectorItem.LibraryItem,
        onLibraryClick: (Library) -> Unit
    ) {
        binding.library = item.library
        binding.accountName = item.accountName
        binding.isActive = item.isActive
        binding.root.setOnClickListener { onLibraryClick(item.library) }
        binding.executePendingBindings()
    }

    companion object {
        fun from(parent: ViewGroup): LibraryViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ListItemLibrarySelectorItemBinding.inflate(inflater, parent, false)
            return LibraryViewHolder(binding)
        }
    }
}

/**
 * DiffUtil callback for efficient list updates.
 */
class LibrarySelectorItemDiffCallback : DiffUtil.ItemCallback<LibrarySelectorItem>() {
    override fun areItemsTheSame(
        oldItem: LibrarySelectorItem,
        newItem: LibrarySelectorItem
    ): Boolean {
        return when {
            oldItem is LibrarySelectorItem.Header && newItem is LibrarySelectorItem.Header ->
                oldItem.accountName == newItem.accountName
            oldItem is LibrarySelectorItem.LibraryItem && newItem is LibrarySelectorItem.LibraryItem ->
                oldItem.library.id == newItem.library.id
            else -> false
        }
    }

    override fun areContentsTheSame(
        oldItem: LibrarySelectorItem,
        newItem: LibrarySelectorItem
    ): Boolean {
        return oldItem == newItem
    }
}
