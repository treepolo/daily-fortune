package com.treepolo.dailyfortune.ads

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.treepolo.dailyfortune.BuildConfig
import com.treepolo.dailyfortune.model.AdsConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Process-level rewarded-ad runtime. It holds only a weak Activity reference, never blocks app
 * startup, and does no SDK/privacy work while Remote Config keeps ads disabled.
 */
object AdMobRewardedController {
    private const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    private const val CACHE_MAX_AGE_MILLIS = 55L * 60L * 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activityRef: WeakReference<Activity>? = null
    private var mobileAdsInitialized = false
    private var consentUpdated = false
    private var rewardedAd: RewardedAd? = null
    private var loadedUnitId: String? = null
    private var loadedAtMillis: Long = 0L
    private var loadInFlight = false

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        consentUpdated = false
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    fun preload(
        config: AdsConfig,
        report: (String, Map<String, Any?>) -> Unit,
    ) {
        if (!config.enabled || !config.preload) return
        val activity = activityRef?.get() ?: return
        scope.launch {
            prepareAndLoad(activity, config, report)
        }
    }

    suspend fun showForReroll(
        config: AdsConfig,
        report: (String, Map<String, Any?>) -> Unit,
    ): RewardedResult = withContext(Dispatchers.Main.immediate) {
        val activity = activityRef?.get() ?: return@withContext RewardedResult.RUNTIME_UNAVAILABLE
        val ready = withTimeoutOrNull(config.loadTimeoutMillis) {
            prepareAndLoad(activity, config, report)
        } ?: false
        if (!ready) return@withContext RewardedResult.LOAD_FAILED

        val ad = rewardedAd ?: return@withContext RewardedResult.LOAD_FAILED
        rewardedAd = null
        loadedUnitId = null
        loadedAtMillis = 0L

        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            var earned = false
            fun finish(result: RewardedResult) {
                if (finished.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(result)
                }
            }

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    report("ad_show", mapOf("placement" to "reroll_rewarded", "outcome" to "shown"))
                }

                override fun onAdImpression() {
                    report("ad_show", mapOf("placement" to "reroll_rewarded", "outcome" to "impression"))
                }

                override fun onAdClicked() {
                    report("ad_show", mapOf("placement" to "reroll_rewarded", "outcome" to "clicked"))
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    report(
                        "ad_failed",
                        mapOf(
                            "placement" to "reroll_rewarded",
                            "stage" to "show",
                            "code" to error.code,
                            "domain" to error.domain,
                        ),
                    )
                    finish(RewardedResult.SHOW_FAILED)
                    preload(config, report)
                }

                override fun onAdDismissedFullScreenContent() {
                    report(
                        "ad_show",
                        mapOf("placement" to "reroll_rewarded", "outcome" to "dismissed", "earned" to earned),
                    )
                    finish(if (earned) RewardedResult.REWARDED else RewardedResult.DISMISSED_WITHOUT_REWARD)
                    preload(config, report)
                }
            }

            runCatching {
                ad.show(activity) { reward ->
                    earned = true
                    report(
                        "ad_complete",
                        mapOf(
                            "placement" to "reroll_rewarded",
                            "reward_amount" to reward.amount,
                            "reward_type" to reward.type,
                        ),
                    )
                }
            }.onFailure {
                report(
                    "ad_failed",
                    mapOf("placement" to "reroll_rewarded", "stage" to "show_exception"),
                )
                finish(RewardedResult.SHOW_FAILED)
            }
        }
    }

    private suspend fun prepareAndLoad(
        activity: Activity,
        config: AdsConfig,
        report: (String, Map<String, Any?>) -> Unit,
    ): Boolean {
        if (!config.enabled || config.provider != "ADMOB") return false
        val canRequestAds = ensureConsent(activity, report)
        if (!canRequestAds) {
            report("ad_failed", mapOf("placement" to "reroll_rewarded", "stage" to "consent"))
            return false
        }
        ensureMobileAdsInitialized(activity)

        val unitId = effectiveUnitId(config)
        val cached = rewardedAd
        if (
            cached != null &&
            loadedUnitId == unitId &&
            System.currentTimeMillis() - loadedAtMillis in 0L..CACHE_MAX_AGE_MILLIS
        ) {
            return true
        }
        if (loadInFlight) {
            return waitForExistingLoad(unitId, config.loadTimeoutMillis)
        }
        return load(activity, unitId, report)
    }

    private suspend fun ensureConsent(
        activity: Activity,
        report: (String, Map<String, Any?>) -> Unit,
    ): Boolean {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        if (consentUpdated) return consentInformation.canRequestAds()

        val params = ConsentRequestParameters.Builder().build()
        suspendCancellableCoroutine<Unit> { continuation ->
            consentInformation.requestConsentInfoUpdate(
                activity,
                params,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        consentUpdated = true
                        if (formError != null) {
                            report(
                                "ad_failed",
                                mapOf(
                                    "placement" to "reroll_rewarded",
                                    "stage" to "consent_form",
                                    "code" to formError.errorCode,
                                ),
                            )
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                },
                { requestError ->
                    consentUpdated = true
                    report(
                        "ad_failed",
                        mapOf(
                            "placement" to "reroll_rewarded",
                            "stage" to "consent_update",
                            "code" to requestError.errorCode,
                        ),
                    )
                    if (continuation.isActive) continuation.resume(Unit)
                },
            )
        }
        return consentInformation.canRequestAds()
    }

    private suspend fun ensureMobileAdsInitialized(activity: Activity) {
        if (mobileAdsInitialized) return
        suspendCancellableCoroutine<Unit> { continuation ->
            MobileAds.initialize(activity.applicationContext) {
                mobileAdsInitialized = true
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private suspend fun load(
        activity: Activity,
        unitId: String,
        report: (String, Map<String, Any?>) -> Unit,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        loadInFlight = true
        report("ad_load", mapOf("placement" to "reroll_rewarded", "outcome" to "started"))
        RewardedAd.load(
            activity,
            unitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadInFlight = false
                    rewardedAd = ad
                    loadedUnitId = unitId
                    loadedAtMillis = System.currentTimeMillis()
                    report("ad_load", mapOf("placement" to "reroll_rewarded", "outcome" to "success"))
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadInFlight = false
                    rewardedAd = null
                    loadedUnitId = null
                    loadedAtMillis = 0L
                    report(
                        "ad_failed",
                        mapOf(
                            "placement" to "reroll_rewarded",
                            "stage" to "load",
                            "code" to error.code,
                            "domain" to error.domain,
                        ),
                    )
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
    }

    private suspend fun waitForExistingLoad(unitId: String, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (loadInFlight && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50L)
        }
        return rewardedAd != null && loadedUnitId == unitId
    }

    private fun effectiveUnitId(config: AdsConfig): String =
        if (BuildConfig.DEBUG) TEST_REWARDED_UNIT_ID else config.rewardedUnitId
}

enum class RewardedResult {
    REWARDED,
    LOAD_FAILED,
    SHOW_FAILED,
    DISMISSED_WITHOUT_REWARD,
    RUNTIME_UNAVAILABLE,
}
