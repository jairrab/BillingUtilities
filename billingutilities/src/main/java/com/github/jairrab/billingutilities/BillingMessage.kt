package com.github.jairrab.billingutilities

import com.android.billingclient.api.BillingClient.BillingResponseCode

/**
 * int SERVICE_TIMEOUT = -3;
 * int FEATURE_NOT_SUPPORTED = -2;
 * int SERVICE_DISCONNECTED = -1;
 * int OK = 0;
 * int USER_CANCELED = 1;
 * int SERVICE_UNAVAILABLE = 2;
 * int BILLING_UNAVAILABLE = 3;
 * int ITEM_UNAVAILABLE = 4;
 * int DEVELOPER_ERROR = 5;
 * int ERROR = 6;
 * int ITEM_ALREADY_OWNED = 7;
 * int ITEM_NOT_OWNED = 8;
 */
object BillingMessage {
    fun getBillingMessage(code: Int): String {
        return when (code) {
            BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
            BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
            BillingResponseCode.ERROR -> "ERROR"
            BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
            BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
            BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
            BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
            BillingResponseCode.OK -> "RESULT_OK"
            BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
            BillingResponseCode.SERVICE_TIMEOUT -> "SERVICE_TIMEOUT"
            BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
            else -> "OTHER:$code"
        }
    }
}
