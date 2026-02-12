package com.example.miniapp.quickadd

import com.example.miniapp.api.LinkResolveResponse
import com.example.miniapp.api.LinkResolveVariant

enum class QuickAddStateKind {
    AUTO_ADD,
    NEED_VARIANT,
    ERROR
}

data class QuickAddState(
    val kind: QuickAddStateKind,
    val selectedVariantId: String? = null,
    val variants: List<LinkResolveVariant> = emptyList(),
    val errorMessage: String? = null
)

fun evaluateQuickAddState(resolve: LinkResolveResponse): QuickAddState {
    val available = resolve.availableVariants.filter { it.active && it.available }
    val required = resolve.requiredOptions.variantRequired
    val explicitAuto = resolve.requiredOptions.autoVariantId

    return when {
        !required -> QuickAddState(
            kind = QuickAddStateKind.AUTO_ADD,
            selectedVariantId = explicitAuto ?: available.singleOrNull()?.id
        )

        explicitAuto != null -> QuickAddState(
            kind = QuickAddStateKind.AUTO_ADD,
            selectedVariantId = explicitAuto
        )

        available.isEmpty() -> QuickAddState(
            kind = QuickAddStateKind.ERROR,
            errorMessage = "Нет доступных вариантов"
        )

        available.size == 1 -> QuickAddState(
            kind = QuickAddStateKind.AUTO_ADD,
            selectedVariantId = available.single().id
        )

        else -> QuickAddState(
            kind = QuickAddStateKind.NEED_VARIANT,
            variants = resolve.availableVariants
        )
    }
}
