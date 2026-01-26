package local.oss.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ErrorHandlingTest {
    @Test
    fun `toChronicleError converts UnknownHostException to NetworkError`() {
        val exception = UnknownHostException("Host not found")
        val error = exception.toChronicleError()

        assertTrue(error is ChronicleError.NetworkError)
        assertEquals("Host not found", error.message)
        assertEquals(exception, error.cause)
        assertTrue((error as ChronicleError.NetworkError).isRecoverable)
    }

    @Test
    fun `toChronicleError converts SocketTimeoutException to NetworkError`() {
        val exception = SocketTimeoutException("Connection timed out")
        val error = exception.toChronicleError()

        assertTrue(error is ChronicleError.NetworkError)
        assertEquals("Connection timed out", error.message)
        assertEquals(exception, error.cause)
        assertTrue((error as ChronicleError.NetworkError).isRecoverable)
    }

    @Test
    fun `toChronicleError converts ConnectException to NetworkError`() {
        val exception = ConnectException("Connection refused")
        val error = exception.toChronicleError()

        assertTrue(error is ChronicleError.NetworkError)
        assertEquals("Connection refused", error.message)
        assertEquals(exception, error.cause)
    }

    @Test
    fun `toChronicleError converts SSLException to NetworkError`() {
        val exception = SSLException("SSL handshake failed")
        val error = exception.toChronicleError()

        assertTrue(error is ChronicleError.NetworkError)
        assertEquals("SSL handshake failed", error.message)
        assertEquals(exception, error.cause)
    }

    @Test
    fun `toChronicleError converts IOException to StorageError`() {
        val exception = IOException("Disk full")
        val error = exception.toChronicleError()

        assertTrue(error is ChronicleError.StorageError)
        assertEquals("Disk full", error.message)
        assertEquals(exception, error.cause)
    }

    @Test
    fun `toChronicleError converts unknown exception to UnknownError`() {
        val exception = RuntimeException("Unexpected error")
        val error = exception.toChronicleError()

        assertTrue(error is ChronicleError.UnknownError)
        assertEquals("Unexpected error", error.message)
        assertEquals(exception, error.cause)
    }

    @Test
    fun `toChronicleError handles null message in exception`() {
        val exception = RuntimeException(null as String?)
        val error = exception.toChronicleError()

        assertTrue(error is ChronicleError.UnknownError)
        assertEquals("Unknown error", error.message)
        assertEquals(exception, error.cause)
    }

    @Test
    fun `NetworkError can be created with custom properties`() {
        val error =
            ChronicleError.NetworkError(
                message = "Custom network error",
                cause = null,
                isRecoverable = false,
            )

        assertEquals("Custom network error", error.message)
        assertEquals(false, error.isRecoverable)
    }

    @Test
    fun `PlaybackError can include trackKey`() {
        val error =
            ChronicleError.PlaybackError(
                message = "Playback failed",
                cause = null,
                trackKey = "/library/metadata/12345",
            )

        assertEquals("Playback failed", error.message)
        assertEquals("/library/metadata/12345", error.trackKey)
    }

    @Test
    fun `onChronicleError is invoked for Result failure`() {
        var capturedError: ChronicleError? = null

        val result: Result<String> = Result.failure(IOException("Test error"))
        result.onChronicleError { error ->
            capturedError = error
        }

        assertNotNull(capturedError)
        assertTrue(capturedError is ChronicleError.StorageError)
        assertEquals("Test error", capturedError?.message)
    }

    @Test
    fun `onChronicleError returns original Result`() {
        val result: Result<String> = Result.success("test value")

        val returnedResult = result.onChronicleError { }

        assertEquals(result, returnedResult)
        assertTrue(returnedResult.isSuccess)
        assertEquals("test value", returnedResult.getOrNull())
    }

    @Test
    fun `onChronicleError is not invoked for Result success`() {
        var errorCallbackInvoked = false

        val result: Result<String> = Result.success("test value")
        result.onChronicleError {
            errorCallbackInvoked = true
        }

        assertEquals(false, errorCallbackInvoked)
    }

    @Test
    fun `ChronicleError subtypes are data classes with proper equality`() {
        val error1 = ChronicleError.NetworkError("test", null, true)
        val error2 = ChronicleError.NetworkError("test", null, true)
        val error3 = ChronicleError.NetworkError("different", null, true)

        assertEquals(error1, error2)
        assertTrue(error1 != error3)
    }
}
