package com.spratap.taptrapanimal

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class AdManager(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "AdManager"
    }

    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null

    private var interstitialLoading = false
    private var rewardedLoading = false

    // ── Initialise SDK + pre-load both formats ─────────────────────────────
    fun init() {
        MobileAds.initialize(activity) {
            Log.d(TAG, "AdMob SDK initialised")
            loadInterstitial()
            loadRewarded()
        }
    }

    // ── Interstitial ──────────────────────────────────────────────────────
    fun loadInterstitial() {
        if (interstitialLoading || interstitialAd != null) return
        interstitialLoading = true
        InterstitialAd.load(
            activity,
            BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    interstitialLoading = false
                    Log.d(TAG, "Interstitial loaded")
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    interstitialAd = null
                    interstitialLoading = false
                    Log.w(TAG, "Interstitial failed: ${err.message}")
                }
            }
        )
    }

    /**
     * Shows the interstitial if ready, then calls [onDismissed].
     * If the ad isn't ready yet, [onDismissed] is called immediately so the
     * game flow is never blocked.
     */
    fun showInterstitial(onDismissed: () -> Unit) {
        val ad = interstitialAd
        if (ad == null) {
            Log.d(TAG, "Interstitial not ready — skipping")
            loadInterstitial()   // pre-load for next time
            onDismissed()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitial()   // pre-load next one
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(err: AdError) {
                interstitialAd = null
                loadInterstitial()
                onDismissed()
            }
        }
        ad.show(activity)
    }

    // ── Rewarded ──────────────────────────────────────────────────────────
    fun loadRewarded() {
        if (rewardedLoading || rewardedAd != null) return
        rewardedLoading = true
        RewardedAd.load(
            activity,
            BuildConfig.ADMOB_REWARDED_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    rewardedLoading = false
                    Log.d(TAG, "Rewarded ad loaded")
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    rewardedAd = null
                    rewardedLoading = false
                    Log.w(TAG, "Rewarded failed: ${err.message}")
                }
            }
        )
    }

    val isRewardedReady: Boolean get() = rewardedAd != null

    /**
     * Shows the rewarded ad. [onRewarded] is called only when the user
     * earns the reward (watches the full ad). [onDismissed] is always called
     * when the ad closes regardless of reward.
     */
    fun showRewardedAd(onRewarded: () -> Unit, onDismissed: () -> Unit = {}) {
        val ad = rewardedAd
        if (ad == null) {
            Log.d(TAG, "Rewarded ad not ready")
            loadRewarded()
            onDismissed()
            return
        }
        var rewarded = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewarded()
                if (rewarded) onRewarded()
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(err: AdError) {
                rewardedAd = null
                loadRewarded()
                onDismissed()
            }
        }
        ad.show(activity) { rewarded = true }
    }

    fun destroy() {
        interstitialAd = null
        rewardedAd = null
    }
}
