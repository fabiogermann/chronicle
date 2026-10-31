package local.oss.chronicle.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import local.oss.chronicle.R
import local.oss.chronicle.application.MainActivity
import local.oss.chronicle.data.sources.plex.PlexConfig
import local.oss.chronicle.databinding.DialogDebugInfoBinding
import javax.inject.Inject

class DebugInfoDialogFragment : DialogFragment() {
    companion object {
        const val TAG = "DebugInfoDialogFragment"

        fun newInstance(): DebugInfoDialogFragment {
            return DebugInfoDialogFragment()
        }
    }

    @Inject
    lateinit var viewModelFactory: DebugInfoViewModel.Factory

    private lateinit var viewModel: DebugInfoViewModel

    private var _binding: DialogDebugInfoBinding? = null
    private val binding get() = _binding!!

    private val connectionsAdapter = ConnectionsAdapter()

    override fun onAttach(context: Context) {
        (requireActivity() as MainActivity).activityComponent!!.inject(this)
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Chronicle_FullScreenDialog)
        viewModel = ViewModelProvider(this, viewModelFactory).get(DebugInfoViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogDebugInfoBinding.inflate(inflater, container, false)
        binding.viewModel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        observeViewModel()
        setupButtons()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            dismiss()
        }
    }

    private fun setupRecyclerView() {
        binding.connectionsRecyclerView.adapter = connectionsAdapter
    }

    private fun observeViewModel() {
        viewModel.debugInfo.observe(viewLifecycleOwner) { info ->
            binding.appVersionText.text = "${info.appVersion} (Build ${info.buildNumber})"
            binding.serverNameText.text = info.serverName ?: getString(R.string.debug_info_server_not_selected)
            binding.connectionStateText.text = formatConnectionState(info.connectionState)
            binding.activeUrlText.text = info.activeUrl ?: getString(R.string.debug_info_not_connected)
        }

        viewModel.connectionResults.observe(viewLifecycleOwner) { connections ->
            connectionsAdapter.submitList(connections)
        }
    }

    private fun setupButtons() {
        binding.testAllButton.setOnClickListener {
            viewModel.testAllConnections()
        }
        binding.copyDiagnosticsButton.setOnClickListener {
            copyDiagnosticsToClipboard()
        }
        binding.shareDiagnosticsButton.setOnClickListener {
            shareDiagnostics()
        }
    }

    /**
     * Copies the secret-safe diagnostics report to the clipboard. The report is built by
     * [DebugInfoViewModel.buildDiagnosticsReport], which redacts Plex tokens by construction.
     */
    private fun copyDiagnosticsToClipboard() {
        val report = viewModel.buildDiagnosticsReport()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_copy), report))
        Toast.makeText(requireContext(), R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    /** Shares the secret-safe diagnostics report via the system share sheet. */
    private fun shareDiagnostics() {
        val report = viewModel.buildDiagnosticsReport()
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.diagnostics_share)))
    }

    private fun formatConnectionState(state: PlexConfig.ConnectionState): String {
        return when (state) {
            PlexConfig.ConnectionState.CONNECTED -> getString(R.string.debug_info_state_connected)
            PlexConfig.ConnectionState.CONNECTING -> getString(R.string.debug_info_state_connecting)
            PlexConfig.ConnectionState.NOT_CONNECTED -> getString(R.string.debug_info_state_not_connected)
            PlexConfig.ConnectionState.CONNECTION_FAILED -> getString(R.string.debug_info_state_connection_failed)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * Adapter for displaying connection test results
     */
    private class ConnectionsAdapter : RecyclerView.Adapter<ConnectionsAdapter.ViewHolder>() {
        private var connections: List<ConnectionTestResult> = emptyList()

        fun submitList(newConnections: List<ConnectionTestResult>) {
            connections = newConnections
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val view =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_debug_connection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            holder.bind(connections[position])
        }

        override fun getItemCount(): Int = connections.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val statusIndicator: TextView = itemView.findViewById(R.id.statusIndicator)
            private val connectionUrl: TextView = itemView.findViewById(R.id.connectionUrl)
            private val connectionType: TextView = itemView.findViewById(R.id.connectionType)

            fun bind(connection: ConnectionTestResult) {
                val displayUrl =
                    when (connection.status) {
                        ConnectionStatus.BLOCKED -> "${connection.uri} (potentially blocked by WAF or protection software)"
                        else -> connection.uri
                    }
                connectionUrl.text = displayUrl
                connectionType.text = if (connection.isLocal) "L" else "R"

                when (connection.status) {
                    ConnectionStatus.UNTESTED -> {
                        statusIndicator.text = "○"
                        statusIndicator.setTextColor(Color.GRAY)
                    }
                    ConnectionStatus.CONNECTED -> {
                        statusIndicator.text = "●"
                        statusIndicator.setTextColor(Color.GREEN)
                    }
                    ConnectionStatus.SUCCESSFUL -> {
                        statusIndicator.text = "✓"
                        statusIndicator.setTextColor(Color.GREEN)
                    }
                    ConnectionStatus.FAILED -> {
                        statusIndicator.text = "✗"
                        statusIndicator.setTextColor(Color.RED)
                    }
                    ConnectionStatus.BLOCKED -> {
                        statusIndicator.text = "⚠"
                        statusIndicator.setTextColor(Color.parseColor("#FFA500")) // Orange color
                    }
                    ConnectionStatus.TESTING -> {
                        statusIndicator.text = "⟳"
                        statusIndicator.setTextColor(Color.BLUE)
                    }
                }
            }
        }
    }
}
