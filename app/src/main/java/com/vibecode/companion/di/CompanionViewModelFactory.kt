package com.vibecode.companion.di

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * The concrete [MetroViewModelFactory] that backs `metroViewModelFactory` on [AppGraph].
 *
 * `@ContributesBinding(AppScope::class)` binds this to `MetroViewModelFactory`, so the
 * `ViewModelGraph.metroViewModelFactory` accessor resolves here. The three maps are the
 * MetroX ViewModel multibindings, populated by `@ContributesIntoMap(AppScope::class)`:
 * standard `@ViewModelKey` ViewModels land in [viewModelProviders]; manual assisted
 * factories (`@ManualViewModelAssistedFactoryKey`) land in [manualAssistedFactoryProviders].
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class CompanionViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders:
        Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
