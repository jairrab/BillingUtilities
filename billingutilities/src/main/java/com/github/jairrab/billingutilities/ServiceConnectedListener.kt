package com.github.jairrab.billingutilities

import com.android.billingclient.api.BillingClient

/** Listener for the Billing client state to become connected */
interface ServiceConnectedListener {
    fun onServiceConnected(@BillingClient.BillingResponseCode resultCode: Int)
}