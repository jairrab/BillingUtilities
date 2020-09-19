package com.github.jairrab.billingutilities

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase

/**
 * Listener to the updates that happen when purchases list was updated or consumption of the
 * item was finished
 */
interface BillingManagerListener {
    fun onBillingClientSetupFinished()
    fun onBillingClientDisconnected(@BillingClient.BillingResponseCode responseCode: Int)
    fun failedToLaunchPurchaseFlow(@BillingClient.BillingResponseCode responseCode: Int)
    fun onConsumeFinished(@BillingClient.BillingResponseCode responseCode: Int, purchase: Purchase)
    fun onPurchasesUpdated(@BillingClient.BillingResponseCode responseCode: Int, purchases: List<Purchase>?)
    fun onQueryCompleted(@BillingClient.BillingResponseCode responseCode: Int, purchases: List<Purchase>?)
    fun onPurchaseAcknowledged(@BillingClient.BillingResponseCode responseCode: Int, purchase: Purchase)
}