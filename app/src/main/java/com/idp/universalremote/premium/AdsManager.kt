package com.idp.universalremote.premium

import android.app.Activity
import android.view.ViewGroup

interface AdsManager {
    fun isPremium(): Boolean
    fun loadAppOpenAd(activity: Activity)
    fun showAppOpenAd(activity: Activity, onDismissed: () -> Unit)
    fun loadBanner(container: ViewGroup, adUnitId: String? = null)
    fun loadInterstitial(adUnitId: String? = null)
    fun showInterstitial(activity: Activity, onClosed: () -> Unit)
    fun loadRewarded(adUnitId: String? = null)
    fun showRewarded(activity: Activity, onReward: () -> Unit)
}

object NoopAdsManager : AdsManager {
    override fun isPremium() = true
    override fun loadAppOpenAd(activity: Activity) = Unit
    override fun showAppOpenAd(activity: Activity, onDismissed: () -> Unit) = onDismissed()
    override fun loadBanner(container: ViewGroup, adUnitId: String?) = Unit
    override fun loadInterstitial(adUnitId: String?) = Unit
    override fun showInterstitial(activity: Activity, onClosed: () -> Unit) = onClosed()
    override fun loadRewarded(adUnitId: String?) = Unit
    override fun showRewarded(activity: Activity, onReward: () -> Unit) = onReward()
}
