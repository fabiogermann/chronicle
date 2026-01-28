package local.oss.chronicle.features.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.account.AccountTestFixtures
import local.oss.chronicle.data.local.LibraryRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class ActiveLibraryProviderTest {

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    private lateinit var libraryRepository: LibraryRepository

    private lateinit var provider: ActiveLibraryProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        provider = ActiveLibraryProvider(libraryRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== Initialization Tests =====

    @Test
    fun `initialize subscribes to library repository flow`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123", isActive = true)
        val flow = MutableStateFlow(library)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        assertThat(provider.currentLibrary.value).isEqualTo(library)
    }

    @Test
    fun `initialize with no active library sets null`() = runTest {
        val flow = MutableStateFlow<local.oss.chronicle.data.model.Library?>(null)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        assertThat(provider.currentLibrary.value).isNull()
    }

    @Test
    fun `currentLibrary updates when repository flow emits`() = runTest {
        val library1 = AccountTestFixtures.createPlexLibrary(accountId = "account-123", name = "Library 1")
        val library2 = AccountTestFixtures.createPlexLibrary(accountId = "account-123", name = "Library 2")
        val flow = MutableStateFlow(library1)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()
        assertThat(provider.currentLibrary.value).isEqualTo(library1)

        // Emit new value
        flow.value = library2
        advanceUntilIdle()
        assertThat(provider.currentLibrary.value).isEqualTo(library2)
    }

    // ===== Current Library Tests =====

    @Test
    fun `currentLibraryId returns library id when library is active`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123", isActive = true)
        val flow = MutableStateFlow(library)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        assertThat(provider.currentLibraryId).isEqualTo(library.id)
    }

    @Test
    fun `currentLibraryId returns null when no library is active`() = runTest {
        val flow = MutableStateFlow<local.oss.chronicle.data.model.Library?>(null)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        assertThat(provider.currentLibraryId).isNull()
    }

    // ===== Switch Library Tests =====

    @Test
    fun `switchToLibrary delegates to repository setActiveLibrary`() = runTest {
        val libraryId = "library-123"

        provider.switchToLibrary(libraryId)

        verify(libraryRepository).setActiveLibrary(libraryId)
    }

    @Test
    fun `clearLibrary delegates to repository clearActiveLibrary`() = runTest {
        provider.clearLibrary()

        verify(libraryRepository).clearActiveLibrary()
    }

    // ===== hasActiveLibrary Tests =====

    @Test
    fun `hasActiveLibrary returns true when library is set`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123", isActive = true)
        val flow = MutableStateFlow(library)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        assertThat(provider.hasActiveLibrary()).isTrue()
    }

    @Test
    fun `hasActiveLibrary returns false when no library is set`() = runTest {
        val flow = MutableStateFlow<local.oss.chronicle.data.model.Library?>(null)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        assertThat(provider.hasActiveLibrary()).isFalse()
    }

    // ===== requireLibraryId Tests =====

    @Test
    fun `requireLibraryId returns library id when library is active`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123", isActive = true)
        val flow = MutableStateFlow(library)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        assertThat(provider.requireLibraryId()).isEqualTo(library.id)
    }

    @Test
    fun `requireLibraryId throws exception when no library is active`() = runTest {
        val flow = MutableStateFlow<local.oss.chronicle.data.model.Library?>(null)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        try {
            provider.requireLibraryId()
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("No active library selected")
        }
    }

    // ===== Flow Behavior Tests =====

    @Test
    fun `currentLibrary is a StateFlow that exposes current value`() = runTest {
        val library = AccountTestFixtures.createPlexLibrary(accountId = "account-123", isActive = true)
        val flow = MutableStateFlow(library)
        whenever(libraryRepository.getActiveLibraryFlow()).thenReturn(flow)

        provider.initialize()
        advanceUntilIdle()

        // StateFlow.value gives us the current value without suspending
        assertThat(provider.currentLibrary.value).isEqualTo(library)
    }
}
