package local.oss.chronicle.features.account

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import local.oss.chronicle.application.MainActivity
import local.oss.chronicle.data.model.Account
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.databinding.BottomSheetLibrarySelectorBinding
import local.oss.chronicle.navigation.Navigator
import javax.inject.Inject

/**
 * Bottom sheet dialog for quick library switching.
 * 
 * Displays all libraries grouped by account, with the currently active library highlighted.
 * Provides a "Manage Accounts" link to navigate to the full account management screen.
 * 
 * Usage:
 * ```
 * LibrarySelectorBottomSheet.newInstance()
 *     .show(parentFragmentManager, LibrarySelectorBottomSheet.TAG)
 * ```
 */
class LibrarySelectorBottomSheet : BottomSheetDialogFragment() {

    @Inject
    lateinit var accountManager: AccountManager

    @Inject
    lateinit var activeLibraryProvider: ActiveLibraryProvider

    @Inject
    lateinit var navigator: Navigator

    private lateinit var binding: BottomSheetLibrarySelectorBinding
    private lateinit var adapter: LibrarySelectorAdapter

    companion object {
        const val TAG = "LibrarySelectorBottomSheet"
        
        fun newInstance() = LibrarySelectorBottomSheet()
    }

    override fun onAttach(context: Context) {
        (requireActivity() as MainActivity).activityComponent!!.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = BottomSheetLibrarySelectorBinding.inflate(inflater, container, false)
        
        setupRecyclerView()
        observeData()
        setupClickListeners()
        
        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = LibrarySelectorAdapter(
            onLibraryClick = { library ->
                switchToLibrary(library.id)
            }
        )
        binding.libraryList.adapter = adapter
    }

    private fun observeData() {
        // Combine all accounts with their libraries and the active library state
        lifecycleScope.launch {
            combine(
                accountManager.getAllAccounts(),
                activeLibraryProvider.currentLibrary
            ) { accounts: List<Account>, activeLibrary: Library? ->
                buildLibrarySelectorItems(accounts, activeLibrary)
            }.collect { items: List<LibrarySelectorItem> ->
                adapter.submitList(items)
            }
        }
    }

    /**
     * Build a flat list of items for the RecyclerView from accounts and their libraries.
     */
    private suspend fun buildLibrarySelectorItems(
        accounts: List<Account>,
        activeLibrary: Library?
    ): List<LibrarySelectorItem> {
        val items = mutableListOf<LibrarySelectorItem>()

        accounts.forEach { account ->
            val libraries = accountManager.getLibrariesForAccount(account.id).first()

            if (libraries.isNotEmpty()) {
                // Add account header
                items.add(LibrarySelectorItem.Header(account.displayName))

                // Add libraries for this account
                libraries.forEach { library ->
                    items.add(
                        LibrarySelectorItem.LibraryItem(
                            library = library,
                            accountName = account.displayName,
                            isActive = library.id == activeLibrary?.id
                        )
                    )
                }
            }
        }

        return items
    }

    private fun setupClickListeners() {
        binding.manageAccountsLink.setOnClickListener {
            // Navigate to AccountListFragment
            // For now, just dismiss - will be implemented in phase 5.4
            dismiss()
        }
    }

    private fun switchToLibrary(libraryId: String) {
        lifecycleScope.launch {
            try {
                accountManager.switchToLibrary(libraryId)
                dismiss()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Failed to switch library: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/**
 * Sealed class representing items in the library selector RecyclerView.
 * 
 * Two types:
 * - Header: Account name header (non-clickable)
 * - LibraryItem: Clickable library item with active state indicator
 */
sealed class LibrarySelectorItem {
    /**
     * Account name header row.
     */
    data class Header(val accountName: String) : LibrarySelectorItem()

    /**
     * Library item with metadata and active state.
     */
    data class LibraryItem(
        val library: Library,
        val accountName: String,
        val isActive: Boolean
    ) : LibrarySelectorItem()
}
