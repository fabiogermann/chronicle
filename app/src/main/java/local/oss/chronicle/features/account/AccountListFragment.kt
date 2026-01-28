package local.oss.chronicle.features.account

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import local.oss.chronicle.R
import local.oss.chronicle.application.MainActivity
import local.oss.chronicle.databinding.FragmentAccountListBinding
import javax.inject.Inject

/**
 * Fragment for managing multiple accounts and libraries.
 *
 * Displays accounts in an expandable list with their associated libraries.
 * Users can add accounts (via OAuth), remove accounts, and switch between libraries.
 */
class AccountListFragment : Fragment() {
    @Inject
    lateinit var viewModelFactory: AccountListViewModel.Factory

    private lateinit var viewModel: AccountListViewModel
    private var _binding: FragmentAccountListBinding? = null
    private val binding get() = _binding!!
    private lateinit var accountAdapter: AccountListAdapter

    companion object {
        const val TAG = "AccountListFragment"

        fun newInstance() = AccountListFragment()
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
        _binding = FragmentAccountListBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner

        viewModel =
            ViewModelProvider(this, viewModelFactory)
                .get(AccountListViewModel::class.java)
        binding.viewModel = viewModel

        setupToolbar()
        setupRecyclerView()
        observeViewModel()

        return binding.root
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.accounts_title)
        }
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        accountAdapter =
            AccountListAdapter(
                onAccountClick = { account ->
                    viewModel.onToggleExpand(account.id)
                },
                onLibraryClick = { library ->
                    viewModel.onSwitchToLibrary(library.id)
                },
                onRemoveClick = { account ->
                    viewModel.onRemoveAccount(account.id)
                },
                activeLibraryId = { viewModel.activeLibraryId.value },
            )

        binding.accountList.apply {
            adapter = accountAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun observeViewModel() {
        viewModel.accounts.observe(viewLifecycleOwner) { accounts ->
            accountAdapter.submitAccountList(accounts)
        }

        viewModel.userMessage.observe(viewLifecycleOwner) { event ->
            if (!event.hasBeenHandled) {
                val message = event.getContentIfNotHandled()
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.confirmRemovalAccountId.observe(viewLifecycleOwner) { event ->
            if (!event.hasBeenHandled) {
                val accountId = event.getContentIfNotHandled()
                if (accountId != null) {
                    showRemovalConfirmationDialog(accountId)
                }
            }
        }

        viewModel.activeLibraryId.observe(viewLifecycleOwner) {
            // Trigger adapter refresh to update active indicator
            accountAdapter.notifyDataSetChanged()
        }
    }

    private fun showRemovalConfirmationDialog(accountId: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_account_title)
            .setMessage(R.string.remove_account_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                viewModel.confirmRemoveAccount(accountId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
