package com.lambliver.appforsale.ui

import com.lambliver.appforsale.data.CatalogPersistPlan
import com.lambliver.appforsale.domain.*

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

internal fun PosViewModel.addProduct(name: String, price: Long, categoryId: String, stock: Long?) {
    val newProduct = Product(UUID.randomUUID().toString(), name, price, categoryId, stock)
    val updated = posUiState.value.products + newProduct
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(products = updated))
            posUiState.update { it.copy(dialogState = DialogState.None) }
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "addProduct failed", e)
            emitToast("新增商品失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.updateProduct(id: String, name: String, price: Long, categoryId: String, stock: Long?) {
    val updated = posUiState.value.products.map {
        if (it.id == id) it.copy(name = name, price = price, categoryId = categoryId, stock = stock) else it
    }
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(products = updated))
            posUiState.update { it.copy(dialogState = DialogState.None) }
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "updateProduct failed", e)
            emitToast("編輯商品失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.setProductStock(productId: String, stock: Long?) {
    val normalized = stock?.coerceAtLeast(0L)
    val updated = posUiState.value.products.map {
        if (it.id == productId) it.copy(stock = normalized) else it
    }
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(products = updated))
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "setProductStock failed", e)
            emitToast("更新庫存失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.deleteProduct(productId: String) {
    val ui = posUiState.value
    when (
        val r = PosCatalogCoordinator.execute(
            state = PosCatalogCoordinator.CatalogState(
                products = ui.products,
                bundles = ui.bundles,
                cart = posCartMemory.value,
            ),
            command = PosCatalogCoordinator.CatalogCommand.DeleteProduct(productId),
        )
    ) {
        is PosCatalogCoordinator.CatalogResult.Ignored -> return
        is PosCatalogCoordinator.CatalogResult.UserMessage -> {
            emitToastAsync(r.message, PosToastSeverity.Error)
            return
        }
        is PosCatalogCoordinator.CatalogResult.Ready -> {
            posCartDebounceJob?.cancel()
            r.plan.cart?.let { posCartMemory.value = it }
            viewModelScope.launch {
                try {
                    posStore.applyCatalog(
                        CatalogPersistPlan(
                            products = r.plan.products,
                            bundles = r.plan.bundles,
                            cart = r.plan.cart,
                        ),
                    )
                    posUiState.update { it.copy(dialogState = DialogState.None) }
                } catch (e: Throwable) {
                    Log.e(PosViewModel.LOG_TAG, "deleteProduct failed", e)
                    emitToast("刪除商品失敗：${e.message}", PosToastSeverity.Error)
                }
            }
        }
    }
}

internal fun PosViewModel.addBundle(name: String, price: Long, categoryId: String, components: List<BundleComponent>) {
    if (posUiState.value.products.isEmpty()) {
        emitToastAsync("請先新增一般商品，才能建立套組", PosToastSeverity.Error)
        return
    }
    val products = posUiState.value.products
    val cleaned = posNormalizeComponents(products, components) ?: run {
        emitToastAsync("成分須指向現有商品且每套數量至少 1", PosToastSeverity.Error)
        return
    }
    val bundle = Bundle(UUID.randomUUID().toString(), name.trim(), price.coerceAtLeast(0L), categoryId, cleaned)
    val updated = posUiState.value.bundles + bundle
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(bundles = updated))
            posUiState.update { it.copy(dialogState = DialogState.None) }
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "addBundle failed", e)
            emitToast("新增套組失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.updateBundle(id: String, name: String, price: Long, categoryId: String, components: List<BundleComponent>) {
    if (posUiState.value.products.isEmpty()) {
        emitToastAsync("請先新增一般商品", PosToastSeverity.Error)
        return
    }
    val products = posUiState.value.products
    val cleaned = posNormalizeComponents(products, components) ?: run {
        emitToastAsync("成分須指向現有商品且每套數量至少 1", PosToastSeverity.Error)
        return
    }
    val updated = posUiState.value.bundles.map {
        if (it.id == id) it.copy(name = name.trim(), price = price.coerceAtLeast(0L), categoryId = categoryId, components = cleaned) else it
    }
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(bundles = updated))
            posUiState.update { it.copy(dialogState = DialogState.None) }
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "updateBundle failed", e)
            emitToast("更新套組失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.deleteBundle(bundleId: String) {
    val ui = posUiState.value
    when (
        val r = PosCatalogCoordinator.execute(
            state = PosCatalogCoordinator.CatalogState(
                products = ui.products,
                bundles = ui.bundles,
                cart = posCartMemory.value,
            ),
            command = PosCatalogCoordinator.CatalogCommand.DeleteBundle(bundleId),
        )
    ) {
        is PosCatalogCoordinator.CatalogResult.Ignored -> return
        is PosCatalogCoordinator.CatalogResult.UserMessage -> {
            emitToastAsync(r.message, PosToastSeverity.Error)
            return
        }
        is PosCatalogCoordinator.CatalogResult.Ready -> {
            posCartDebounceJob?.cancel()
            r.plan.cart?.let { posCartMemory.value = it }
            viewModelScope.launch {
                try {
                    posStore.applyCatalog(
                        CatalogPersistPlan(
                            products = r.plan.products,
                            bundles = r.plan.bundles,
                            cart = r.plan.cart,
                        ),
                    )
                    posUiState.update { it.copy(dialogState = DialogState.None) }
                } catch (e: Throwable) {
                    Log.e(PosViewModel.LOG_TAG, "deleteBundle failed", e)
                    emitToast("刪除套組失敗：${e.message}", PosToastSeverity.Error)
                }
            }
        }
    }
}

internal fun PosViewModel.addBundleCategory(name: String) {
    val newCat = BundleCategory(UUID.randomUUID().toString(), name.trim())
    val updated = posUiState.value.bundleCategories + newCat
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(bundleCategories = updated))
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "addBundleCategory failed", e)
            emitToast("新增套組分類失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.updateBundleCategory(id: String, name: String) {
    val updated = posUiState.value.bundleCategories.map {
        if (it.id == id) it.copy(name = name.trim()) else it
    }
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(bundleCategories = updated))
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "updateBundleCategory failed", e)
            emitToast("編輯套組分類失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.deleteBundleCategory(id: String) {
    val updatedCats = posUiState.value.bundleCategories.filter { it.id != id }
    val updatedBundles = posUiState.value.bundles.map {
        if (it.categoryId == id) it.copy(categoryId = "") else it
    }
    viewModelScope.launch {
        try {
            posStore.applyCatalog(
                CatalogPersistPlan(
                    bundleCategories = updatedCats,
                    bundles = updatedBundles,
                ),
            )
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "deleteBundleCategory failed", e)
            emitToast("刪除套組分類失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.addCategory(name: String) {
    val newCat = Category(UUID.randomUUID().toString(), name.trim())
    val updated = posUiState.value.categories + newCat
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(categories = updated))
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "addCategory failed", e)
            emitToast("新增分類失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.updateCategory(id: String, name: String) {
    val updated = posUiState.value.categories.map {
        if (it.id == id) it.copy(name = name.trim()) else it
    }
    viewModelScope.launch {
        try {
            posStore.applyCatalog(CatalogPersistPlan(categories = updated))
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "updateCategory failed", e)
            emitToast("編輯分類失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.deleteCategory(id: String) {
    val updatedCats = posUiState.value.categories.filter { it.id != id }
    val updatedProds = posUiState.value.products.map {
        if (it.categoryId == id) it.copy(categoryId = "") else it
    }
    viewModelScope.launch {
        try {
            posStore.applyCatalog(
                CatalogPersistPlan(
                    categories = updatedCats,
                    products = updatedProds,
                ),
            )
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "deleteCategory failed", e)
            emitToast("刪除分類失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}
