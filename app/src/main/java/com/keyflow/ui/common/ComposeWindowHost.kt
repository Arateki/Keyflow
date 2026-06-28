package com.keyflow.ui.common

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Provedor mínimo dos três *tree owners* exigidos por um `ComposeView` que vive fora de uma
 * Activity — janelas do `WindowManager` (overlay) e `onCreateInputView` do IME (C3, OVL-01/KBD-01).
 *
 * Sem isto, o `ComposeView` não compõe (não acha `ViewTreeLifecycleOwner`/`SavedStateRegistryOwner`/
 * `ViewModelStoreOwner`) e/ou vaza estado. Reutilizável pelo `OverlayService` (T22) e pelo
 * `KeyFlowKeyboardService` (T23).
 *
 * Uso: `host.attach(rootView)` antes de `windowManager.addView(...)`; `host.detach()` após `removeView`.
 */
class ComposeWindowHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    var isAttached: Boolean = false
        private set

    /**
     * Liga os três owners à `root`, restaura o `SavedState` e move o ciclo de vida para RESUMED —
     * o que dispara a composição quando a view entra no `WindowManager`.
     */
    fun attach(root: View) {
        check(!isAttached) { "ComposeWindowHost já está anexado" }
        // performRestore exige o ciclo em INITIALIZED (estado inicial do registry).
        savedStateController.performRestore(null)
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        isAttached = true
    }

    /** Move o ciclo para DESTROYED e limpa o `ViewModelStore` — evita vazamento ao remover a janela. */
    fun detach() {
        if (!isAttached) return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        isAttached = false
    }
}
