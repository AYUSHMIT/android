package io.homeassistant.companion.android.common.data.authentication.impl

import io.homeassistant.companion.android.common.data.LocalStorage
import io.homeassistant.companion.android.common.data.authentication.AuthorizationException
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * Tests for AuthenticationRepositoryImpl focusing on concurrency control for token refresh.
 */
class AuthenticationRepositoryImplTest {

    private lateinit var authenticationService: AuthenticationService
    private lateinit var serverManager: ServerManager
    private lateinit var localStorage: LocalStorage
    private lateinit var installId: SuspendProvider<String>
    private lateinit var repository: AuthenticationRepositoryImpl

    private val testServerId = 1
    private val testInstallId = "test-install-id"
    private val testRefreshToken = "test-refresh-token"
    private val testAccessToken = "test-access-token"
    private val testNewAccessToken = "new-access-token"

    @BeforeEach
    fun setup() {
        authenticationService = mockk(relaxed = true)
        serverManager = mockk(relaxed = true)
        localStorage = mockk(relaxed = true)
        installId = mockk {
            coEvery { invoke() } returns testInstallId
        }

        val testServer = Server(
            id = testServerId,
            friendlyName = "Test Server",
            connection = ServerConnectionInfo(
                externalUrl = "https://test.com",
                internalUrl = null,
                cloudhookUrl = null,
                webhookId = "test-webhook",
                hasAtLeastOneUrl = true,
            ),
            session = ServerSessionInfo(
                accessToken = testAccessToken,
                refreshToken = testRefreshToken,
                tokenExpiration = System.currentTimeMillis() / 1000 - 100, // Expired
                tokenType = "Bearer",
                installId = testInstallId,
            ),
        )

        coEvery { serverManager.getServer(testServerId) } returns testServer
        coEvery { serverManager.connectionStateProvider(testServerId).urlFlow().firstUrlOrNull(any()) } returns "https://test.com"
        coEvery { serverManager.updateServer(any()) } returns Unit

        repository = AuthenticationRepositoryImpl(
            authenticationService = authenticationService,
            serverManager = serverManager,
            serverId = testServerId,
            localStorage = localStorage,
            installId = installId,
        )
    }

    /**
     * Test: Concurrent token refresh attempts are serialized.
     *
     * Uses a barrier (CompletableDeferred) to ensure deterministic concurrent execution
     * without sleep delays.
     */
    @Test
    fun `concurrent token refresh attempts are serialized`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        var callCount = 0

        coEvery {
            authenticationService.refreshToken(any(), any(), any(), any())
        } coAnswers {
            callCount++
            if (callCount == 1) {
                // First call waits for barrier
                barrier.await()
            }
            Response.success(
                TokenResponse(
                    accessToken = testNewAccessToken,
                    expiresIn = 3600,
                    tokenType = "Bearer",
                ),
            )
        }

        // Launch multiple concurrent refresh attempts
        val job1 = async { repository.retrieveAccessToken() }
        val job2 = async { repository.retrieveAccessToken() }
        val job3 = async { repository.retrieveAccessToken() }

        // Wait for first call to start
        testScheduler.advanceUntilIdle()

        // Complete the barrier to allow first call to finish
        barrier.complete(Unit)

        // Wait for all jobs to complete
        job1.await()
        job2.await()
        job3.await()

        // Verify: Only one actual HTTP refresh call was made due to mutex serialization
        coVerify(exactly = 1) {
            authenticationService.refreshToken(any(), any(), testRefreshToken, any())
        }
    }

    /**
     * Test: Failed token refresh releases mutex for subsequent attempts.
     *
     * Verifies mutex is properly released on exception.
     */
    @Test
    fun `failed token refresh releases mutex for subsequent attempts`() = runTest {
        val errorResponse = Response.error<TokenResponse>(
            401,
            "Unauthorized".toResponseBody(),
        )
        val successResponse = Response.success(
            TokenResponse(
                accessToken = testNewAccessToken,
                expiresIn = 3600,
                tokenType = "Bearer",
            ),
        )

        coEvery {
            authenticationService.refreshToken(any(), any(), any(), any())
        } returnsMany listOf(errorResponse, successResponse)

        // First attempt should fail
        var exception: Exception? = null
        try {
            repository.retrieveAccessToken()
        } catch (e: Exception) {
            exception = e
        }

        // Verify first attempt failed
        assertEquals(AuthorizationException::class.java, exception?.javaClass)

        // Second attempt should succeed (mutex was released)
        val result = repository.retrieveAccessToken()
        assertEquals(testNewAccessToken, result)

        // Verify two refresh attempts were made
        coVerify(exactly = 2) {
            authenticationService.refreshToken(any(), any(), testRefreshToken, any())
        }
    }

    /**
     * Test: Mutex properly handles coroutine cancellation.
     *
     * Verifies that cancelled refresh doesn't leave mutex in locked state.
     */
    @Test
    fun `mutex handles cancellation without deadlock`() = runTest {
        val barrier = CompletableDeferred<Unit>()

        coEvery {
            authenticationService.refreshToken(any(), any(), any(), any())
        } coAnswers {
            barrier.await()
            Response.success(
                TokenResponse(
                    accessToken = testNewAccessToken,
                    expiresIn = 3600,
                    tokenType = "Bearer",
                ),
            )
        }

        // Start first refresh
        val job1 = async { repository.retrieveAccessToken() }

        // Advance to let it start
        testScheduler.advanceUntilIdle()

        // Cancel the job
        job1.cancel()

        // Complete barrier (cleanup)
        barrier.complete(Unit)

        // Advance to process cancellation
        testScheduler.advanceUntilIdle()

        // Start second refresh - should not hang (mutex was released)
        coEvery {
            authenticationService.refreshToken(any(), any(), any(), any())
        } returns Response.success(
            TokenResponse(
                accessToken = testNewAccessToken,
                expiresIn = 3600,
                tokenType = "Bearer",
            ),
        )

        val result = repository.retrieveAccessToken()
        assertEquals(testNewAccessToken, result)
    }
}

/**
 * Mock response model for token refresh.
 */
private data class TokenResponse(
    val accessToken: String,
    val expiresIn: Long,
    val tokenType: String,
)
