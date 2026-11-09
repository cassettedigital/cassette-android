package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.util.CassetteComputerCard.Facts
import com.cappielloantonio.tempo.util.CassetteComputerCard.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CassetteComputerCardTest {
    private val base = Facts(isPaired = true, outOfReach = false, arriving = false, onPhone = 0, firstSyncDone = false,
        computerAlbums = 120, computerHasRead = true, scope = "everything")

    @Test fun noComputerIsNotSetUp() = assertEquals(Phase.NOT_SET_UP, CassetteComputerCard.phase(base.copy(isPaired = false)))
    @Test fun notReadYetSaysNothing() = assertNull(CassetteComputerCard.phase(base.copy(onPhone = null)))
    @Test fun musicInMeansNoCard() = assertNull(CassetteComputerCard.phase(base.copy(onPhone = 160, firstSyncDone = true)))
    @Test fun firstSyncShowsSyncing() = assertEquals(Phase.SYNCING, CassetteComputerCard.phase(base.copy(onPhone = 3, arriving = true)))
    @Test fun afterFirstSyncNewArrivalsHideTheCard() = assertNull(CassetteComputerCard.phase(base.copy(onPhone = 3, arriving = true, firstSyncDone = true)))
    @Test fun outOfReachWins() = assertEquals(Phase.OUT_OF_REACH, CassetteComputerCard.phase(base.copy(arriving = true, outOfReach = true)))
    @Test fun emptyLibraryIsNoMusic() = assertEquals(Phase.NO_MUSIC, CassetteComputerCard.phase(base.copy(computerAlbums = 0)))
    @Test fun selectiveWithNothingIsNothingChosen() = assertEquals(Phase.NOTHING_CHOSEN, CassetteComputerCard.phase(base.copy(scope = "selective")))
    @Test fun unreadLibraryIsGettingReady() = assertEquals(Phase.GETTING_READY, CassetteComputerCard.phase(base.copy(computerHasRead = false)))
    @Test fun syncingLineCountsAlbums() = assertEquals("3 of 12 albums on this phone", CassetteComputerCard.body(Phase.SYNCING, 3, 12))
}
