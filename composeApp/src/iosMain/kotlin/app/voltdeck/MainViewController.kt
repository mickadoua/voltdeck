package app.voltdeck

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController { App(KeychainVictronKeyStore(), UserDefaultsShuntSetupStore()) }
