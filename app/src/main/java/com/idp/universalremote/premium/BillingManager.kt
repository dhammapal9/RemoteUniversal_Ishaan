package com.idp.universalremote.premium

import android.app.Activity

interface BillingManager {
    suspend fun queryProducts(): List<Product>
    suspend fun purchase(activity: Activity, productId: String): PurchaseResult
    suspend fun restorePurchases(): List<String>
    suspend fun isPremium(): Boolean
}

data class Product(
    val id: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val isLifetime: Boolean
)

sealed class PurchaseResult {
    data object Success : PurchaseResult()
    data object UserCancelled : PurchaseResult()
    data class Failed(val message: String) : PurchaseResult()
}

object NoopBillingManager : BillingManager {
    override suspend fun queryProducts(): List<Product> = listOf(
        Product(
            id = "remote_lifetime",
            title = "Lifetime Premium",
            description = "Pay once. Use forever.",
            formattedPrice = "$14.99",
            isLifetime = true
        )
    )
    override suspend fun purchase(activity: Activity, productId: String) = PurchaseResult.Failed("Billing not configured")
    override suspend fun restorePurchases() = emptyList<String>()
    override suspend fun isPremium() = false
}
