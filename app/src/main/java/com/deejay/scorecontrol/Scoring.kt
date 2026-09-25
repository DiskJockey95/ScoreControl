package com.deejay.scorecontrol

// Independent scoring utilities for unit testing winning logic

data class TestPlayer(val score: Int)

data class RoundOutcome(val winnerIndex: Int?, val isTie: Boolean)

private fun teamHasPlayerAtLeast(players: List<TestPlayer>, parity: Int, value: Int): Boolean {
    return players.withIndex().any { (index, player) -> index % 2 == parity && player.score >= value }
}

private fun qualifies(players: List<TestPlayer>, index: Int, threshold: Int = 41): Boolean {
    val player = players.getOrNull(index) ?: return false
    val teammateIndex = if (players.isEmpty()) return false else (index + 2) % players.size
    val teammateScore = players.getOrNull(teammateIndex)?.score ?: return false
    return player.score >= threshold && teammateScore >= 0
}

fun determineRoundOutcome(players: List<TestPlayer>): RoundOutcome {
    if (players.isEmpty()) return RoundOutcome(null, false)

    // Winning threshold fixed
    val threshold = 41

    // Players who reached >= 41
    val candidates = players.mapIndexedNotNull { index, p -> if (p.score >= threshold) index else null }
    if (candidates.isEmpty()) return RoundOutcome(null, false)

    val team0Has = candidates.any { it % 2 == 0 }
    val team1Has = candidates.any { it % 2 == 1 }

    // Helper to check teammate non-negative and player meets threshold
    fun playerQualifies(index: Int): Boolean = qualifies(players, index, threshold)

    // If both teams have a 41+ player, compare top scorers but honor teammate qualification first
    if (team0Has && team1Has) {
        val highestScore = players.maxOf { it.score }
        val topPlayers = players.mapIndexedNotNull { i, p -> if (p.score == highestScore) i else null }
        // Among the top scorers, prefer any who qualify (teammate non-negative)
        val qualifiedTop = topPlayers.filter { playerQualifies(it) }
        if (qualifiedTop.size == 1) {
            return RoundOutcome(qualifiedTop.first(), false)
        } else if (qualifiedTop.size > 1) {
            // If qualified top players are on different teams -> tie; if same team -> that team wins
            val teams = qualifiedTop.map { it % 2 }.distinct()
            return if (teams.size > 1) RoundOutcome(null, true) else RoundOutcome(qualifiedTop.first(), false)
        }
        // No top scorer qualifies (their teammates may be negative) -> continue play
        return RoundOutcome(null, false)
    }

    // Only one team has candidates (>=41). The highest scoring candidate on that team can win if teammate non-negative.
    val highestCandidateScore = candidates.maxOf { players[it].score }
    val highestCandidates = candidates.filter { players[it].score == highestCandidateScore }
    val candidate = highestCandidates.first()
    return if (playerQualifies(candidate)) RoundOutcome(candidate, false) else RoundOutcome(null, false)
}

fun determineRoundOutcomeFromScores(scores: List<Int>): Pair<Int?, Boolean> {
    val players = scores.map { TestPlayer(it) }
    val outcome = determineRoundOutcome(players)
    return Pair(outcome.winnerIndex, outcome.isTie)
}

