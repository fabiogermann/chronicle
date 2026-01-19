package local.oss.chronicle.features.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import local.oss.chronicle.R
import local.oss.chronicle.application.ChronicleApplication
import local.oss.chronicle.databinding.FragmentPlexOauthBinding
import timber.log.Timber
import javax.inject.Inject

class PlexOAuthDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_OAUTH_URL = "oauth_url"
        private const val ARG_PIN_ID = "pin_id"
        const val TAG = "PlexOAuthDialogFragment"

        fun newInstance(oauthUrl: String, pinId: Long): PlexOAuthDialogFragment {
            return PlexOAuthDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_OAUTH_URL, oauthUrl)
                    putLong(ARG_PIN_ID, pinId)
                }
            }
        }
    }

    @Inject
    lateinit var viewModelFactory: PlexOAuthViewModel.Factory

    private lateinit var viewModel: PlexOAuthViewModel
    
    private var _binding: FragmentPlexOauthBinding? = null
    private val binding get() = _binding!!

    private var onAuthSuccessListener: (() -> Unit)? = null
    private var onAuthCancelledListener: (() -> Unit)? = null

    override fun onAttach(context: Context) {
        (requireActivity().application as ChronicleApplication)
            .appComponent
            .inject(this)
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Chronicle_FullScreenDialog)
        viewModel = ViewModelProvider(this, viewModelFactory).get(PlexOAuthViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlexOauthBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set soft input mode to resize the window when keyboard appears
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setupToolbar()
        setupWebView()
        observeAuthState()

        val oauthUrl = requireArguments().getString(ARG_OAUTH_URL)
            ?: throw IllegalStateException("OAuth URL is required")
        val pinId = requireArguments().getLong(ARG_PIN_ID, -1L)
        
        if (pinId == -1L) {
            throw IllegalStateException("PIN ID is required")
        }

        binding.webview.loadUrl(oauthUrl)
        viewModel.startPolling(pinId)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onAuthCancelledListener?.invoke()
            dismiss()
        }
        binding.toolbar.title = getString(R.string.plex_login_title)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webview.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = buildUserAgent()
            
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                }
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    binding.progressBar.progress = newProgress
                }
            }
        }
    }

    private fun buildUserAgent(): String {
        val defaultAgent = WebView(requireContext()).settings.userAgentString
        // Append Chronicle identifier but keep browser-like agent to avoid blocking
        return "$defaultAgent Chronicle/Android"
    }

    private fun observeAuthState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authState.collect { state ->
                    when (state) {
                        is PlexOAuthViewModel.AuthState.Polling -> {
                            // Continue showing WebView
                        }
                        is PlexOAuthViewModel.AuthState.Success -> {
                            Timber.i("OAuth authentication successful, dismissing dialog")
                            onAuthSuccessListener?.invoke()
                            dismiss()
                        }
                        is PlexOAuthViewModel.AuthState.Error -> {
                            Timber.e("OAuth authentication error: ${state.message}")
                            onAuthCancelledListener?.invoke()
                            dismiss()
                        }
                        is PlexOAuthViewModel.AuthState.Timeout -> {
                            Timber.w("OAuth authentication timed out")
                            onAuthCancelledListener?.invoke()
                            dismiss()
                        }
                        PlexOAuthViewModel.AuthState.Idle -> {
                            // Initial state, do nothing
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        viewModel.stopPolling()
        binding.webview.destroy()
        _binding = null
        super.onDestroyView()
    }

    fun setOnAuthSuccessListener(listener: () -> Unit) {
        onAuthSuccessListener = listener
    }

    fun setOnAuthCancelledListener(listener: () -> Unit) {
        onAuthCancelledListener = listener
    }
}
