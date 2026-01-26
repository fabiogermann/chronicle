package local.oss.chronicle.features.settings

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
            binding.serverNameText.text = info.serverName ?: "Not selected"
            binding.connectionStateText.text = formatConnectionState(info.connectionState)
            binding.activeUrlText.text = info.activeUrl ?: "Not connected"
        }

        viewModel.connectionResults.observe(viewLifecycleOwner) { connections ->
            connectionsAdapter.submitList(connections)
        }
    }

    private fun setupButtons() {
        binding.testAllButton.setOnClickListener {
            viewModel.testAllConnections()
        }
    }

    private fun formatConnectionState(state: PlexConfig.ConnectionState): String {
        return when (state) {
            PlexConfig.ConnectionState.CONNECTED -> "● Connected"
            PlexConfig.ConnectionState.CONNECTING -> "⟳ Connecting..."
            PlexConfig.ConnectionState.NOT_CONNECTED -> "○ Not Connected"
            PlexConfig.ConnectionState.CONNECTION_FAILED -> "✗ Connection Failed"
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
                connectionUrl.text = connection.uri
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
                    ConnectionStatus.TESTING -> {
                        statusIndicator.text = "⟳"
                        statusIndicator.setTextColor(Color.BLUE)
                    }
                }
            }
        }
    }
}
