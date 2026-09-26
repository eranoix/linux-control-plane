package com.vpsmanager.feature.terminal.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vpsmanager.feature.terminal.transport.ConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Renders [ConnectionBanner] in every [ConnectionState] plus the `Live` +
 * stalled combination -- never composed before this, and the one place the
 * "never indistinguishable from frozen" claim gets checked against real
 * rendered text rather than just [bannerContent]'s (private) branching.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a healthy live connection shows no banner text at all`() {
        composeRule.setContent {
            ConnectionBanner(state = ConnectionState.Live, isStalled = false)
        }

        composeRule.onNodeWithText("Conectando…").assertDoesNotExist()
        composeRule.onNodeWithText("Desconectado").assertDoesNotExist()
    }

    @Test
    fun `a stalled live connection is not silently indistinguishable from healthy`() {
        composeRule.setContent {
            ConnectionBanner(state = ConnectionState.Live, isStalled = true)
        }

        composeRule.onNodeWithText("Sem resposta do servidor — a conexão parece travada").assertExists()
    }

    @Test
    fun `connecting shows its own label`() {
        composeRule.setContent {
            ConnectionBanner(state = ConnectionState.Connecting, isStalled = false)
        }

        composeRule.onNodeWithText("Conectando…").assertExists()
    }

    @Test
    fun `reconnecting includes the attempt number`() {
        composeRule.setContent {
            ConnectionBanner(state = ConnectionState.Reconnecting(attempt = 3), isStalled = false)
        }

        composeRule.onNodeWithText("Reconectando (tentativa 3)…").assertExists()
    }

    @Test
    fun `disconnected shows its own label`() {
        composeRule.setContent {
            ConnectionBanner(state = ConnectionState.Disconnected, isStalled = false)
        }

        composeRule.onNodeWithText("Desconectado").assertExists()
    }

    @Test
    fun `a session ended server-side is distinguished from a plain disconnect`() {
        composeRule.setContent {
            ConnectionBanner(state = ConnectionState.SessionEnded, isStalled = false)
        }

        composeRule.onNodeWithText("Esta sessão foi encerrada no servidor").assertExists()
    }

    @Test
    fun `a failure surfaces the underlying reason`() {
        composeRule.setContent {
            ConnectionBanner(state = ConnectionState.Failed(reason = "Ticket expirado"), isStalled = false)
        }

        composeRule.onNodeWithText("Falha na conexão: Ticket expirado").assertExists()
    }
}
