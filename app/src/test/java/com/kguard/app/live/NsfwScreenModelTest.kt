package com.kguard.app.live

import com.kguard.app.data.model.AgeProfile
import com.kguard.app.live.ScreenVerdict.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** Score → decision for K-Guard Live's on-device check; the model itself runs on the device. */
class NsfwScreenModelTest {

    @Test
    fun probabilityIsTheNsfwSoftmax() {
        assertEquals(0.5f, NsfwScreenModel.probability(1f, 1f), 1e-6f)
        assertEquals(0.880797f, NsfwScreenModel.probability(2f, 0f), 1e-5f) // index 0 is NSFW
        assertEquals(0.119203f, NsfwScreenModel.probability(0f, 2f), 1e-5f)
        assertEquals(1f, NsfwScreenModel.probability(1000f, 0f), 1e-6f) // no overflow
    }

    @Test
    fun youngerChildrenGetTheStricterBar() {
        assertEquals(Level.HARMFUL, NsfwScreenModel.verdictFor(0.91f, AgeProfile.KIDS_3_7))
        assertEquals(Level.HARMFUL, NsfwScreenModel.verdictFor(0.91f, AgeProfile.PRETEENS_8_12))
        assertEquals(Level.CAUTION, NsfwScreenModel.verdictFor(0.91f, AgeProfile.TEENS_13_17))
        assertEquals(Level.SAFE, NsfwScreenModel.verdictFor(0.91f, AgeProfile.ADULTS_18_PLUS))
    }

    @Test
    fun clearContentAtTheTopOfTheScale() {
        assertEquals(Level.HARMFUL, NsfwScreenModel.verdictFor(0.95f, AgeProfile.TEENS_13_17))
        assertEquals(Level.CAUTION, NsfwScreenModel.verdictFor(0.95f, AgeProfile.ADULTS_18_PLUS))
    }

    @Test
    fun ordinaryScreensPass() {
        for (age in AgeProfile.entries) assertEquals(Level.SAFE, NsfwScreenModel.verdictFor(0.2f, age))
    }
}
