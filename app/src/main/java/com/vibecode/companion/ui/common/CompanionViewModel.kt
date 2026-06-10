package com.vibecode.companion.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vibecode.companion.AppContainer
import com.vibecode.companion.CompanionApp

/**
 * Obtains a ViewModel wired to the app's [AppContainer].
 * Usage: `val vm = companionViewModel { container -> MyViewModel(container.apiClient) }`
 */
@Composable
inline fun <reified VM : ViewModel> companionViewModel(crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as CompanionApp
    return viewModel(factory = viewModelFactory { initializer { create(app.container) } })
}
