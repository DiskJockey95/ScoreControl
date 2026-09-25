package com.deejay.scorecontrol

import org.junit.Test
import org.junit.Assert.*

class ScoringTest {

    @Test
    fun singlePlayer41AndTeammatePositive_wins() {
        val players = listOf(
            TestPlayer(41), // index 0
            TestPlayer(10), // index 1
            TestPlayer(5),  // teammate of 0 (index 2)
            TestPlayer(0)
        )
        val outcome = determineRoundOutcome(players)
        assertFalse(outcome.isTie)
        assertEquals(0, outcome.winnerIndex)
    }

    @Test
    fun player41ButTeammateNegative_noWin() {
        val players = listOf(
            TestPlayer(41), // index 0
            TestPlayer(5),  // opponent
            TestPlayer(-3), // teammate negative
            TestPlayer(10)
        )
        val outcome = determineRoundOutcome(players)
        assertNull(outcome.winnerIndex)
        assertFalse(outcome.isTie)
    }

    @Test
    fun bothTeamsHave41_crossTeamTie_isTie() {
        val players = listOf(
            TestPlayer(41), // team0
            TestPlayer(41), // team1
            TestPlayer(10),
            TestPlayer(10)
        )
        val outcome = determineRoundOutcome(players)
        // top scorers span both teams -> tie
        assertNull(outcome.winnerIndex)
        assertTrue(outcome.isTie)
    }

    @Test
    fun bothTeamsHave41_oneReach51_winnerIs51() {
        val players = listOf(
            TestPlayer(51), // team0
            TestPlayer(41), // team1
            TestPlayer(0),
            TestPlayer(5)
        )
        val outcome = determineRoundOutcome(players)
        assertEquals(0, outcome.winnerIndex)
        assertFalse(outcome.isTie)
    }

    @Test
    fun tieAcrossTeams_continuesAsTie() {
        val players = listOf(
            TestPlayer(45), // index0
            TestPlayer(45), // index1
            TestPlayer(10),
            TestPlayer(10)
        )
        val outcome = determineRoundOutcome(players)
        // when highest scorers are on different teams, it's a tie -> continue
        assertNull(outcome.winnerIndex)
        assertTrue(outcome.isTie)
    }

    @Test
    fun multipleTopPlayersAcrossTeams_withTie_keepsPlaying() {
        val players = listOf(
            TestPlayer(42), // P1
            TestPlayer(42), // P2
            TestPlayer(42), // P3
            TestPlayer(30)  // P4
        )
        val outcome = determineRoundOutcome(players)
        // Top scorers span both teams -> tie and continue playing
        assertNull(outcome.winnerIndex)
        assertTrue(outcome.isTie)
    }

    @Test
    fun roundSequence_example_shouldDeclareWinnerTeam2() {
        // After two rounds, scores become: P1:44, P2:45, P3:0, P4:0
        val players = listOf(
            TestPlayer(44),
            TestPlayer(45),
            TestPlayer(0),
            TestPlayer(0)
        )
        val outcome = determineRoundOutcome(players)
        // Team 2 (player index 1) should win
        assertFalse(outcome.isTie)
        assertEquals(1, outcome.winnerIndex)
    }
}

