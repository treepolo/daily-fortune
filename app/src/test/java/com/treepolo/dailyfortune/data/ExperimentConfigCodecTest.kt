package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AdFailurePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentConfigCodecTest {
    @Test
    fun decodesDisabledAdPolicyWithoutChangingFortuneConfig() {
        val config = ExperimentConfigCodec.decode(CONFIG_JSON)

        assertEquals("resolved-test", config.configId)
        assertFalse(config.ads.enabled)
        assertEquals("ADMOB", config.ads.provider)
        assertEquals("ca-app-pub-8284304703726644/9731073792", config.ads.rewardedUnitId)
        assertEquals(setOf(7), config.ads.bypassOverallScores)
        assertEquals(AdFailurePolicy.FAIL_OPEN, config.ads.failurePolicy)
        assertTrue(config.ads.preload)
        assertEquals(8_000L, config.ads.loadTimeoutMillis)
        assertEquals("uniform-v1", config.rerollDistribution.id)
        assertEquals("floor-v1", config.overallRule.id)
    }

    @Test
    fun missingAdsBlockDefaultsToDisabled() {
        val config = ExperimentConfigCodec.decode(CONFIG_WITHOUT_ADS)
        assertFalse(config.ads.enabled)
        assertEquals(setOf(7), config.ads.bypassOverallScores)
    }

    companion object {
        private const val FORTUNE_BLOCK = """
          "fortune": {
            "initial_distribution": {
              "id": "uniform-v1",
              "probabilities": [0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285]
            },
            "reroll_distribution": {
              "id": "uniform-v1",
              "probabilities": [0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285,0.14285714285714285]
            },
            "sampling": {
              "mode": "INDEPENDENT",
              "profile_id": "independent-v1"
            },
            "overall_rule": {
              "id": "floor-v1",
              "type": "FLOOR"
            }
          }
        """

        private const val CONFIG_WITHOUT_ADS = """
        {
          "config_id": "resolved-test",
          "assignments": [],
          $FORTUNE_BLOCK,
          "visual": {
            "static_variant_id": "baseline",
            "reveal_variant_id": "none"
          }
        }
        """

        private const val CONFIG_JSON = """
        {
          "config_id": "resolved-test",
          "assignments": [],
          $FORTUNE_BLOCK,
          "visual": {
            "static_variant_id": "baseline",
            "reveal_variant_id": "none"
          },
          "ads": {
            "enabled": false,
            "provider": "ADMOB",
            "rewarded_unit_id": "ca-app-pub-8284304703726644/9731073792",
            "bypass_overall_scores": [7],
            "failure_policy": "FAIL_OPEN",
            "preload": true,
            "load_timeout_millis": 8000
          }
        }
        """
    }
}
