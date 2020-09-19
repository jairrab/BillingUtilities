package com.github.jairrab.billingutilities

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.util.Log
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.*
import com.android.billingclient.api.BillingClient.FeatureType.SUBSCRIPTIONS
import com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
import com.android.billingclient.api.Purchase.PurchasesResult
import java.io.IOException
import java.util.*

/**
 * Handles all the interactions with Play Store (via Billing library), maintains connection to
 * it through BillingClient and caches temporary states/data if needed
 */
class BillingManager(
    context: Context,
    private val key: String,
    private val listener: BillingManagerListener
) : PurchasesUpdatedListener {

    /* BASE_64_ENCODED_PUBLIC_KEY should be YOUR APPLICATION'S PUBLIC KEY
     * (that you got from the Google Play developer console). This is not your
     * developer public key, it's the *app-specific* public key.
     *
     * Instead of just storing the entire literal string here embedded in the
     * program,  construct the key at runtime from pieces or
     * use bit manipulation (for example, XOR with some other string) to hide
     * the actual key.  The key itself is not secret information, but we don't
     * want to make it easy for an attacker to replace the public key with one
     * of their own and then fake messages from the server.
     */
    private val purchases: MutableList<Purchase> = ArrayList()
    private var serviceConnected = false
    private var tokensToBeConsumed: MutableSet<String>? = null
    private var purchaseFlowRequest: Runnable? = null
    private var retryCount = 0

    private var billingClient: BillingClient? = newBuilder(context)
        .enablePendingPurchases()
        .setListener(this)
        .build()

    /**
     * Returns the value Billing client response code or BILLING_MANAGER_NOT_INITIALIZED if the
     * clien connection response was not received yet.
     */
    var billingClientResponseCode = BILLING_MANAGER_NOT_INITIALIZED
        private set

    /**
     * Query purchases across various use cases and deliver the result in a formalized way through
     * a iLinkResults
     */
    fun queryPurchases() {
        Log.d(TAG, "Starting setup.")
        // Start setup. This is asynchronous and the specified iLinkResults will be called
        // once setup completes.
        // It also starts to report all the new purchases through onPurchasesUpdated() callback.
        startServiceConnection {
            // Notifying the iLinkResults that billing client is ready
            listener.onBillingClientSetupFinished()

            // IAB is fully set up. Now, let's get an inventory of stuff we own.
            Log.d(TAG, "Setup successful. Querying inventory.")

            val purchasesQuery = Runnable {
                val client = billingClient ?: return@Runnable

                val time = System.currentTimeMillis()
                val purchasesResult = client.queryPurchases(SkuType.INAPP)

                Log.i(
                    TAG, "Querying purchases elapsed time: " +
                    (System.currentTimeMillis() - time) + "ms"
                )

                // If there are subscriptions supported, we add subscription rows as well
                when {
                    areSubscriptionsSupported() -> {
                        try { //added by Jay
                            val subscriptionResult = client.queryPurchases(SkuType.SUBS)

                            Log.i(
                                TAG, "Querying purchases and subscriptions elapsed time: " +
                                (System.currentTimeMillis() - time) + "ms"
                            )

                            Log.i(
                                TAG, "Querying subscriptions result code: " +
                                subscriptionResult.responseCode + " res: " +
                                subscriptionResult.purchasesList?.size
                            )

                            if (subscriptionResult.responseCode == BillingResponseCode.OK) {
                                subscriptionResult.purchasesList
                                    ?.let { purchasesResult.purchasesList?.addAll(it) }

                            } else {
                                Log.e(
                                    TAG, "Got an error response trying to " +
                                    "query subscription purchases"
                                )
                            }
                        } catch (e: NullPointerException) {
                            e.printStackTrace()
                        }
                    }
                    purchasesResult.responseCode == BillingResponseCode.OK -> {
                        Log.i(
                            TAG, "Skipped subscription purchases query " +
                            "since they are not supported"
                        )
                    }
                    else -> {
                        Log.w(
                            TAG, "queryPurchases() got an error " +
                            "response code: " + purchasesResult.responseCode
                        )
                    }
                }

                onQueryPurchasesFinished(purchasesResult)
            }

            executeServiceRequest(purchasesQuery)
        }
    }

    /** Handle a callback that purchases were updated from the Billing library */
    override fun onPurchasesUpdated(billingResult: BillingResult, list: List<Purchase>?) {
        when (val resultCode = billingResult.responseCode) {
            BillingResponseCode.OK -> {
                list?.forEach { handlePurchase(it) }
                listener.onPurchasesUpdated(resultCode, purchases)
            }
            BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "onPurchasesUpdated() - user cancelled the purchase flow - skipping")
            }
            else -> {
                Log.w(TAG, "onPurchasesUpdated() got unknown resultCode: $resultCode")
            }
        }
    }

    fun querySkuDetailsAsync(
        @SkuType itemType: String,
        skuList: List<String?>,
        listener: SkuDetailsResponseListener
    ) {
        // Creating a runnable from the request to use it inside our connection retry policy below
        val queryRequest = Runnable {
            // Query the purchase async
            val params = SkuDetailsParams.newBuilder()
                .setSkusList(skuList)
                .setType(itemType)
                .build()
            billingClient?.querySkuDetailsAsync(params, listener)
        }
        executeServiceRequest(queryRequest)
    }

    fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.purchaseState == PURCHASED) {
            // Grant entitlement to the user.
            // Acknowledge the purchase if it hasn't already been acknowledged.
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams
                    .newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient?.acknowledgePurchase(acknowledgePurchaseParams) { result ->
                    /*Toast.makeText(activity, "Acknowledging purchase: response OK is " +
                        if (result.responseCode == BillingResponseCode.OK) "true" else "false",
                        Toast.LENGTH_LONG).show()*/

                    listener.onPurchaseAcknowledged(result.responseCode, purchase)
                }
            }
        }
    }

    /** Start a purchase flow */
    fun initiatePurchaseFlow(activity: Activity, skuDetails: SkuDetails) {
        purchaseFlowRequest = Runnable {
            val purchaseParams = BillingFlowParams.newBuilder()
                .setSkuDetails(skuDetails)
                .build()

            billingClient?.launchBillingFlow(activity, purchaseParams)
        }
        executeServiceRequest(purchaseFlowRequest)
    }

    fun consumeAsync(purchase: Purchase) {
        // If we've already scheduled to consume this token - no action is needed (this could happen
        // if you received the token when querying purchases inside onReceive() and later from
        // onActivityResult()
        if (tokensToBeConsumed == null) {
            tokensToBeConsumed = HashSet()
        } else if (tokensToBeConsumed?.contains(purchase.purchaseToken) == true) {
            Log.i(TAG, "Token was already scheduled to be consumed - skipping...")
            return
        }

        tokensToBeConsumed?.add(purchase.purchaseToken)

        // Generating Consume Response iLinkResults
        val onConsumeListener = ConsumeResponseListener { billingResult: BillingResult, s: String? ->
            // If billing service was disconnected, we try to reconnect 1 time
            // (feel free to introduce your retry policy here).
            listener.onConsumeFinished(billingResult.responseCode, purchase)
        }

        // Creating a runnable from the request to use it inside our connection retry policy below
        val consumeRequest = Runnable {
            // Consume the purchase async
            val consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken).build()
            billingClient?.consumeAsync(consumeParams, onConsumeListener)
        }

        executeServiceRequest(consumeRequest)
    }

    /**
     * Checks if subscriptions are supported for current client
     *
     * Note: This method does not automatically retry for RESULT_SERVICE_DISCONNECTED.
     * It is only used in unit tests and after queryPurchases execution, which already has
     * a retry-mechanism implemented.
     *
     */
    private fun areSubscriptionsSupported(): Boolean {
        val responseCode = billingClient?.isFeatureSupported(SUBSCRIPTIONS)?.responseCode
        if (responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "areSubscriptionsSupported() got an error response: $responseCode")
        }
        return responseCode == BillingResponseCode.OK
    }

    private fun doRetryPolicy(executeOnSuccess: Runnable?) {
        try {
            if (retryCount < 3) {
                val handler = Handler()
                handler.postDelayed({
                    ++retryCount
                    executeServiceRequest(executeOnSuccess)
                }, 500)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun executeServiceRequest(runnable: Runnable?) {
        if (serviceConnected) {
            runnable?.run()
        } else {
            // If billing service was disconnected, we try to reconnect 1 time.
            // (feel free to introduce your retry policy here).
            startServiceConnection(runnable)
        }
    }

    /**
     * Handles the purchase
     *
     * Note: Notice that for each purchase, we check if signature is valid on the client.
     * It's recommended to move this check into your backend.
     * See [Security.verifyPurchase]
     *
     *
     * @param purchase Purchase to be handled
     */
    private fun handlePurchase(purchase: Purchase) {
        if (!verifyValidSignature(purchase.originalJson, purchase.signature)) {
            Log.i(TAG, "Got a purchase: $purchase; but signature is bad. Skipping...")
            return
        }
        Log.d(TAG, "Got a verified purchase: $purchase")
        purchases.add(purchase)
    }

    /**Handle a result from querying of purchases and report an updated list to the iLinkResults*/
    private fun onQueryPurchasesFinished(result: PurchasesResult?) {
        if (result == null) return

        // Have we been disposed of in the meantime? If so, or bad result code, then quit
        if (billingClient == null) {
            Log.w(TAG, "Billing client was null  - quitting")
            return
        }

        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Result code (" + result.responseCode + ")")
            listener.onQueryCompleted(result.responseCode, result.purchasesList)
            return
        }

        Log.d(TAG, "Query inventory was successful.")

        // Update the UI and purchases inventory with new list of purchases
        purchases.clear()

        listener.onQueryCompleted(BillingResponseCode.OK, result.purchasesList)
    }

    private fun startServiceConnection(executeOnSuccess: Runnable?) {
        //this could be null during retries and when activity has been destroyed
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingClient == null) return

                val responseCode = billingResult.responseCode

                Log.d(TAG, "Setup finished. Response code: $responseCode")

                retryCount = 0

                if (responseCode == BillingResponseCode.OK) {
                    serviceConnected = true
                    executeOnSuccess?.run()
                } else {
                    if (executeOnSuccess === purchaseFlowRequest) {
                        listener.failedToLaunchPurchaseFlow(responseCode)
                    } else {
                        listener.onBillingClientDisconnected(responseCode)
                    }
                }

                billingClientResponseCode = responseCode
            }

            override fun onBillingServiceDisconnected() {
                serviceConnected = false
                doRetryPolicy(executeOnSuccess)
            }
        })
    }

    /**
     * Verifies that the purchase was signed correctly for this developer's public key.
     *
     * Note: It's strongly recommended to perform such check on your backend since hackers can
     * replace this method with "constant true" if they decompile/rebuild your app.
     *
     */
    private fun verifyValidSignature(
        signedData: String,
        signature: String
    ): Boolean {
        return try {
            Security.verifyPurchase(key, signedData, signature)
        } catch (e: IOException) {
            Log.e(TAG, "Got an exception trying to validate a purchase: $e")
            false
        }
    }

    /** Clear the resources */
    fun destroy() {
        Log.d(TAG, "Destroying the manager.")
        if (billingClient?.isReady == true) {
            billingClient?.endConnection()
            billingClient = null
        }
    }

    companion object {
        // Default value of billingClientResponseCode until BillingManager was not yeat initialized
        const val BILLING_MANAGER_NOT_INITIALIZED = -1
        private const val TAG = "BillingManager"
    }
}