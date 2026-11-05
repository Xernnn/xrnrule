package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionsTest {
    private val youtube = "com.google.android.youtube"

    @Test
    fun aSiteOffersItsInstalledApp() {
        assertEquals(setOf(youtube), Companions.appsFor(setOf("youtube.com"), emptySet(), setOf(youtube, "x")))
        assertEquals(setOf(youtube), Companions.appsFor(setOf("m.youtube.com"), emptySet(), setOf(youtube)))
    }

    @Test
    fun noOfferForAnAppThatIsntInstalledOrIsAlreadyIn() {
        assertEquals(emptySet<String>(), Companions.appsFor(setOf("youtube.com"), emptySet(), emptySet()))
        assertEquals(emptySet<String>(), Companions.appsFor(setOf("youtube.com"), setOf(youtube), setOf(youtube)))
    }

    @Test
    fun oneHalfOfAServiceInTheBlockIsEnough() {
        val lite = "com.facebook.lite"
        val katana = "com.facebook.katana"
        assertEquals(emptySet<String>(), Companions.appsFor(setOf("facebook.com"), setOf(lite), setOf(lite, katana)))
    }

    @Test
    fun aPathIsOnlyPartOfASite() {
        assertEquals(emptySet<String>(), Companions.appsFor(setOf("youtube.com/shorts"), emptySet(), setOf(youtube)))
    }

    @Test
    fun anAppOffersItsSite() {
        assertEquals(setOf("youtube.com"), Companions.sitesFor(setOf(youtube), emptySet()))
        assertEquals(emptySet<String>(), Companions.sitesFor(setOf(youtube), setOf("youtube.com")))
        assertEquals(emptySet<String>(), Companions.sitesFor(setOf("com.other"), emptySet()))
    }

    @Test
    fun aPairTakesOneTile() {
        assertEquals(listOf("other.com"), Companions.withoutPaired(listOf("youtube.com", "other.com"), listOf(youtube)))
        assertEquals(listOf("youtube.com"), Companions.withoutPaired(listOf("youtube.com"), emptyList()))
    }
}
