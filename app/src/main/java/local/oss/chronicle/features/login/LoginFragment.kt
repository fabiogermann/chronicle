package local.oss.chronicle.features.login

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import local.oss.chronicle.application.ChronicleApplication
import local.oss.chronicle.data.local.PrefsRepo
import local.oss.chronicle.databinding.OnboardingLoginBinding
import timber.log.Timber
import javax.inject.Inject

class LoginFragment : Fragment() {
    companion object {
        @JvmStatic
        fun newInstance() = LoginFragment()

        const val TAG: String = "Login tag"
    }

    @Inject
    lateinit var prefsRepo: PrefsRepo

    @Inject
    lateinit var viewModelFactory: LoginViewModel.Factory

    private lateinit var loginViewModel: LoginViewModel

    override fun onAttach(context: Context) {
        ((activity as Activity).application as ChronicleApplication)
            .appComponent
            .inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        loginViewModel =
            ViewModelProvider(
                this,
                viewModelFactory,
            ).get(LoginViewModel::class.java)

        val binding = OnboardingLoginBinding.inflate(inflater, container, false)

        loginViewModel.isLoading.observe(
            viewLifecycleOwner,
            Observer { isLoading: Boolean ->
                if (isLoading) {
                    binding.loading.visibility = View.VISIBLE
                } else {
                    binding.loading.visibility = View.GONE
                }
            },
        )

        binding.oauthLogin.setOnClickListener {
            loginViewModel.loginWithOAuth()
        }

        loginViewModel.authEvent.observe(
            viewLifecycleOwner,
            Observer { authRequestEvent ->
                val oAuthPin = authRequestEvent.getContentIfNotHandled()
                if (oAuthPin != null) {
                    // Build OAuth URL
                    val url =
                        loginViewModel.makeOAuthLoginUrl(
                            oAuthPin.clientIdentifier,
                            oAuthPin.code,
                        )

                    // Get the PIN ID for polling
                    val pinId = oAuthPin.id

                    // Show WebView dialog instead of Chrome Custom Tab
                    val dialog =
                        PlexOAuthDialogFragment.newInstance(
                            oauthUrl = url.toString(),
                            pinId = pinId,
                        )

                    dialog.setOnAuthSuccessListener {
                        Timber.i("OAuth success callback received")
                        // The loginEvent observer in MainActivity handles navigation
                    }

                    dialog.setOnAuthCancelledListener {
                        Timber.i("OAuth cancelled by user")
                        // User can retry by pressing login button again
                    }

                    dialog.show(childFragmentManager, PlexOAuthDialogFragment.TAG)

                    loginViewModel.setLaunched(true)
                }
            },
        )

        loginViewModel.errorEvent.observe(
            viewLifecycleOwner,
            Observer { errorEvent ->
                val error = errorEvent.getContentIfNotHandled()
                if (error != null) {
                    android.widget.Toast.makeText(requireContext(), error, android.widget.Toast.LENGTH_LONG).show()
                    Timber.e("Login error: $error")
                }
            },
        )

        return binding.root
    }

    override fun onResume() {
        Timber.i("RESUMING LoginFragment")
        loginViewModel.checkForAccess()
        super.onResume()
    }
}
