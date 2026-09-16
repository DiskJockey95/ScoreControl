package com.deejay.scorecontrol

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private enum class Phase {
        SETUP,
        BIDDING,
        RESOLVING,
        FINISHED
    }

    private enum class RoundResult {
        PASS,
        FAIL
    }

    private data class PlayerState(
        val name: String,
        val color: Int,
        var score: Int = 0,
        var draftBid: Int? = null,
        var bid: Int = 0,
        var resolved: Boolean = false,
        var result: RoundResult? = null,
    )

    private val playerCount = 4
    private val playerColors = listOf(
        Color.parseColor("#E57373"),
        Color.parseColor("#64B5F6"),
        Color.parseColor("#81C784"),
        Color.parseColor("#FFB74D"),
        Color.parseColor("#BA68C8"),
        Color.parseColor("#4DB6AC"),
        Color.parseColor("#FFD54F"),
        Color.parseColor("#90A4AE"),
    )

    private lateinit var setupContainer: View
    private lateinit var gameContainer: View
    private lateinit var teamsText: TextView
    private lateinit var nameLayouts: List<TextInputLayout>
    private lateinit var nameInputs: List<TextInputEditText>
    private lateinit var roundTitleText: TextView
    private lateinit var roundStatusText: TextView
    private lateinit var lockBidsButton: MaterialButton
    private lateinit var nextRoundButton: MaterialButton
    private lateinit var newGameButton: MaterialButton
    private lateinit var resetGameButton: MaterialButton
    private lateinit var playerListContainer: LinearLayout
    private lateinit var mainScrollView: NestedScrollView

    private val bidLayouts = mutableListOf<TextInputLayout>()
    private val bidInputs = mutableListOf<EditText>()
    private val players = mutableListOf<PlayerState>()
    private val setupDraftNames = MutableList(4) { "" }
    private var currentRound = 1
    private var phase = Phase.SETUP
    private var winningPlayerIndex: Int? = null
    private var restoringUi = false

    private val prefs by lazy { getSharedPreferences("score_control_state", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainScrollView = findViewById(R.id.main)
        setupContainer = findViewById(R.id.setupContainer)
        gameContainer = findViewById(R.id.gameContainer)
        teamsText = findViewById(R.id.teamsText)
        roundTitleText = findViewById(R.id.roundTitleText)
        roundStatusText = findViewById(R.id.roundStatusText)
        lockBidsButton = findViewById(R.id.lockBidsButton)
        nextRoundButton = findViewById(R.id.nextRoundButton)
        newGameButton = findViewById(R.id.newGameButton)
        resetGameButton = findViewById(R.id.resetGameButton)
        playerListContainer = findViewById(R.id.playerListContainer)

        nameLayouts = listOf(
            findViewById(R.id.nameLayout1),
            findViewById(R.id.nameLayout2),
            findViewById(R.id.nameLayout3),
            findViewById(R.id.nameLayout4),
        )
        nameInputs = listOf(
            findViewById(R.id.nameInput1),
            findViewById(R.id.nameInput2),
            findViewById(R.id.nameInput3),
            findViewById(R.id.nameInput4),
        )

        setupSetupWatchers()
        setupFocusScrollListeners()
        setupActionButtons()

        if (!restoreSavedState()) {
            renderSetup()
        }
    }

    override fun onPause() {
        super.onPause()
        persistState()
    }

    private fun setupSetupWatchers() {
        nameInputs.forEachIndexed { index, input ->
            input.doAfterTextChanged { editable ->
                if (restoringUi) return@doAfterTextChanged
                setupDraftNames[index] = editable?.toString().orEmpty()
                persistState()
            }
        }
    }

    private fun setupFocusScrollListeners() {
        nameInputs.forEach { input ->
            attachScrollOnFocus(input)
        }
    }

    private fun setupActionButtons() {
        findViewById<MaterialButton>(R.id.startGameButton).setOnClickListener {
            startGame()
        }
        lockBidsButton.setOnClickListener {
            lockBids()
        }
        nextRoundButton.setOnClickListener {
            beginNextRound()
        }
        newGameButton.setOnClickListener {
            resetToSetup()
        }
        resetGameButton.setOnClickListener {
            resetToSetup()
        }
    }

    private fun renderSetup() {
        phase = Phase.SETUP
        setupContainer.visibility = View.VISIBLE
        gameContainer.visibility = View.GONE
        newGameButton.visibility = View.GONE
        resetGameButton.visibility = View.GONE
        restoringUi = true
        nameInputs.forEachIndexed { index, input ->
            input.setText(setupDraftNames[index])
            nameLayouts[index].error = null
        }
        restoringUi = false
    }

    private fun startGame() {
        val names = nameInputs.mapIndexed { index, input ->
            val name = input.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                nameLayouts[index].error = getString(R.string.need_name_error)
            } else {
                nameLayouts[index].error = null
            }
            name
        }

        if (names.any { it.isEmpty() }) {
            return
        }

        players.clear()
        val shuffledColors = playerColors.shuffled()
        names.forEachIndexed { index, name ->
            players.add(
                PlayerState(
                    name = name,
                    color = shuffledColors[index % shuffledColors.size],
                )
            )
        }
        currentRound = 1
        phase = Phase.BIDDING
        winningPlayerIndex = null
        persistState()
        renderGame()
    }

    private fun renderGame() {
        setupContainer.visibility = View.GONE
        gameContainer.visibility = View.VISIBLE
        newGameButton.visibility = View.VISIBLE
        resetGameButton.visibility = View.VISIBLE
        teamsText.text = getTeamsText()
        roundTitleText.text = getString(R.string.round_and_minimum_label, currentRound, minimumBidForGame())
        playerListContainer.removeAllViews()
        bidLayouts.clear()
        bidInputs.clear()

        when (phase) {
            Phase.BIDDING -> {
                roundStatusText.text = getString(R.string.enter_bids_prompt)
                lockBidsButton.visibility = View.VISIBLE
                nextRoundButton.visibility = View.GONE
                players.forEachIndexed { index, player ->
                    player.resolved = false
                    player.result = null
                    addPlayerCard(player, index, showingBids = true)
                }
            }

            Phase.RESOLVING -> {
                val allResolved = players.all { it.resolved }
                val outcome = if (allResolved) determineRoundOutcome() else null
                roundStatusText.text = if (allResolved) {
                    if (outcome != null && outcome.isTie) {
                        getString(R.string.round_tied_message)
                    } else {
                        getString(R.string.all_players_resolved)
                    }
                } else {
                    getString(R.string.resolve_round_prompt)
                }
                lockBidsButton.visibility = View.GONE
                nextRoundButton.visibility = if (allResolved) View.VISIBLE else View.GONE
                players.forEachIndexed { index, player ->
                    addPlayerCard(player, index, showingBids = false)
                }
            }

            Phase.FINISHED -> {
                roundStatusText.text = getGameOverMessage()
                lockBidsButton.visibility = View.GONE
                nextRoundButton.visibility = View.GONE
                players.forEachIndexed { index, player ->
                    addPlayerCard(player, index, showingBids = false)
                }
            }

            Phase.SETUP -> renderSetup()
        }
    }

    private fun addPlayerCard(player: PlayerState, index: Int, showingBids: Boolean) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.topMargin = if (index == 0) 0 else dp(12)
            }
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val colorChip = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also {
                it.marginEnd = dp(10)
            }
            setBackgroundColor(player.color)
        }

        val nameText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = player.name
            textSize = 18f
        }

        val scoreText = TextView(this).apply {
            text = getString(R.string.score_label, player.score)
        }

        header.addView(colorChip)
        header.addView(nameText)
        header.addView(scoreText)

        outer.addView(header)

        if (showingBids) {
            val bidLayout = TextInputLayout(this).apply {
                hint = getString(R.string.bid_hint)
                helperText = getString(R.string.allowed_bids_label, allowedBidsLabel(player.score))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also {
                    it.topMargin = dp(12)
                }
            }

            val bidInput = TextInputEditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(player.draftBid?.toString().orEmpty())
                doAfterTextChanged { editable ->
                    if (restoringUi) return@doAfterTextChanged
                    player.draftBid = editable?.toString()?.toIntOrNull()
                    persistState()
                }
                attachScrollOnFocus(this)
            }

            bidLayout.addView(
                bidInput
            )
            bidLayouts.add(bidLayout)
            bidInputs.add(bidInput)
            outer.addView(bidLayout)
        } else {
            val bidText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also {
                    it.topMargin = dp(10)
                }
                text = getString(R.string.bid_label, player.bid)
            }
            outer.addView(bidText)

            if (player.resolved) {
                val resultText = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also {
                        it.topMargin = dp(10)
                    }
                    text = when (player.result) {
                        RoundResult.PASS -> getString(R.string.result_pass)
                        RoundResult.FAIL -> getString(R.string.result_fail)
                        null -> ""
                    }
                }
                outer.addView(resultText)
            } else if (phase != Phase.FINISHED) {
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also {
                        it.topMargin = dp(12)
                    }
                }

                val passButton = MaterialButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                        it.marginEnd = dp(8)
                    }
                    text = getString(R.string.pass_label)
                    setOnClickListener {
                        resolvePlayer(index, RoundResult.PASS)
                    }
                }

                val failButton = MaterialButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = getString(R.string.fail_label)
                    setOnClickListener {
                        resolvePlayer(index, RoundResult.FAIL)
                    }
                }

                actions.addView(passButton)
                actions.addView(failButton)
                outer.addView(actions)
            }
        }

        card.addView(outer)
        playerListContainer.addView(card)
    }

    private fun attachScrollOnFocus(view: View) {
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            if (hasFocus) {
                mainScrollView.post {
                    val rect = Rect(0, 0, focusedView.width, focusedView.height)
                    focusedView.requestRectangleOnScreen(rect, true)
                }
            }
        }
    }

    private fun lockBids() {
        if (players.size != bidInputs.size) {
            return
        }

        val minimumBid = minimumBidForGame()
        val totalBid = mutableListOf<Int>()
        var hasError = false
        players.forEachIndexed { index, player ->
            val value = bidInputs[index].text?.toString()?.trim()?.toIntOrNull()
            val allowedBids = allowedBidsFor(player.score)
            if (value == null || value !in allowedBids) {
                bidLayouts[index].error = getString(R.string.invalid_bid_error, allowedBidsLabel(player.score))
                hasError = true
            } else {
                bidLayouts[index].error = null
                player.bid = value
                player.draftBid = value
                player.resolved = false
                player.result = null
                totalBid.add(value)
            }
        }

        if (hasError) {
            return
        }

        if (totalBid.sum() < minimumBid) {
            roundStatusText.text = getString(R.string.minimum_bid_not_met, minimumBid)
            return
        }

        phase = Phase.RESOLVING
        winningPlayerIndex = null
        persistState()
        renderGame()
    }

    private fun resolvePlayer(index: Int, result: RoundResult) {
        val player = players.getOrNull(index) ?: return
        if (player.resolved) {
            return
        }

        player.score += if (result == RoundResult.PASS) player.bid else -player.bid
        player.result = result
        player.resolved = true

        if (players.all { it.resolved }) {
            val outcome = determineRoundOutcome()
            winningPlayerIndex = outcome.winnerIndex
            if (winningPlayerIndex != null) {
                phase = Phase.FINISHED
            } else {
                phase = Phase.RESOLVING
            }
        }

        persistState()
        renderGame()
    }

    private fun beginNextRound() {
        if (!players.all { it.resolved }) {
            return
        }

        currentRound += 1
        players.forEach { player ->
            player.draftBid = null
            player.bid = 0
            player.resolved = false
            player.result = null
        }
        phase = Phase.BIDDING
        winningPlayerIndex = null
        persistState()
        renderGame()
    }

    private fun resetToSetup() {
        prefs.edit().remove(STATE_KEY).apply()
        players.clear()
        currentRound = 1
        phase = Phase.SETUP
        winningPlayerIndex = null
        setupDraftNames.indices.forEach { setupDraftNames[it] = "" }
        renderSetup()
    }

    private fun persistState() {
        val state = JSONObject()
        state.put("phase", phase.name)
        state.put("round", currentRound)
        state.put("winningPlayerIndex", winningPlayerIndex)

        val names = JSONArray()
        setupDraftNames.forEach { names.put(it) }
        state.put("setupNames", names)

        val playerArray = JSONArray()
        players.forEach { player ->
            playerArray.put(
                JSONObject().apply {
                    put("name", player.name)
                    put("color", player.color)
                    put("score", player.score)
                    put("draftBid", player.draftBid)
                    put("bid", player.bid)
                    put("resolved", player.resolved)
                    put("result", player.result?.name)
                }
            )
        }
        state.put("players", playerArray)

        prefs.edit().putString(STATE_KEY, state.toString()).apply()
    }

    private fun restoreSavedState(): Boolean {
        val raw = prefs.getString(STATE_KEY, null) ?: return false
        try {
            val state = JSONObject(raw)
            currentRound = state.optInt("round", 1)
            winningPlayerIndex = if (state.isNull("winningPlayerIndex")) null else state.optInt("winningPlayerIndex")
            setupDraftNames.indices.forEach { index ->
                setupDraftNames[index] = state.optJSONArray("setupNames")?.optString(index).orEmpty()
            }

            phase = runCatching {
                Phase.valueOf(state.optString("phase", Phase.SETUP.name))
            }.getOrDefault(Phase.SETUP)
            val playerArray = state.optJSONArray("players") ?: JSONArray()
            players.clear()
            for (i in 0 until playerArray.length()) {
                val playerJson = playerArray.getJSONObject(i)
                players.add(
                    PlayerState(
                        name = playerJson.getString("name"),
                        color = playerJson.getInt("color"),
                        score = playerJson.getInt("score"),
                        draftBid = if (playerJson.isNull("draftBid")) null else playerJson.optInt("draftBid"),
                        bid = playerJson.optInt("bid", 0),
                        resolved = playerJson.optBoolean("resolved", false),
                        result = if (playerJson.isNull("result")) null else RoundResult.valueOf(playerJson.getString("result")),
                    )
                )
            }
            if (players.size != playerCount) {
                throw JSONException("Expected $playerCount players")
            }
            if (phase == Phase.FINISHED && winningPlayerIndex == null) {
                throw JSONException("Missing winner")
            }
        } catch (_: JSONException) {
            prefs.edit().remove(STATE_KEY).apply()
            players.clear()
            currentRound = 1
            winningPlayerIndex = null
            setupDraftNames.indices.forEach { setupDraftNames[it] = "" }
            phase = Phase.SETUP
            return false
        } catch (_: IllegalArgumentException) {
            prefs.edit().remove(STATE_KEY).apply()
            players.clear()
            currentRound = 1
            winningPlayerIndex = null
            setupDraftNames.indices.forEach { setupDraftNames[it] = "" }
            phase = Phase.SETUP
            return false
        }

        restoringUi = true
        when (phase) {
            Phase.SETUP -> renderSetup()
            Phase.BIDDING, Phase.RESOLVING -> renderGame()
            Phase.FINISHED -> renderGame()
        }
        restoringUi = false
        return true
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun minimumBidForGame(): Int {
        val thirtyPlus = players.count { it.score >= 30 && it.score < 40 }
        val fortyPlus = players.count { it.score >= 40 && it.score < 50 }
        val fiftyPlus = players.count { it.score >= 50 }
        return 11 + thirtyPlus + (fortyPlus * 2) + (fiftyPlus * 3)
    }

    private fun allowedBidsFor(score: Int): List<Int> {
        return when {
            score >= 50 -> listOf(5, 6, 7, 8, 27, 40)
            score >= 40 -> listOf(4, 5, 6, 7, 16, 27, 40)
            score >= 30 -> listOf(3, 4, 5, 6, 14, 16, 27, 40)
            else -> listOf(2, 3, 4, 10, 12, 14, 16, 27, 40)
        }
    }

    private fun allowedBidsLabel(score: Int): String {
        return allowedBidsFor(score).joinToString(", ")
    }

    private fun getTeamsText(): String {
        return getString(
            R.string.team_label,
            1,
            players.getOrNull(0)?.name.orEmpty(),
            players.getOrNull(2)?.name.orEmpty()
        ) + "\n" + getString(
            R.string.team_label,
            2,
            players.getOrNull(1)?.name.orEmpty(),
            players.getOrNull(3)?.name.orEmpty()
        )
    }

    private fun getGameOverMessage(): String {
        val winnerIndex = winningPlayerIndex ?: return getString(R.string.game_over_unknown)
        val winnerName = players.getOrNull(winnerIndex)?.name.orEmpty()
        val teammateIndex = teammateIndexFor(winnerIndex)
        val teammateName = players.getOrNull(teammateIndex)?.name.orEmpty()
        val teamNumber = if (winnerIndex % 2 == 0) 1 else 2
        return getString(R.string.game_over_message, teamNumber, winnerName, teammateName)
    }

    private data class RoundOutcome(val winnerIndex: Int?, val isTie: Boolean)

    private fun determineRoundOutcome(): RoundOutcome {
        val highestScore = players.maxOfOrNull { it.score } ?: return RoundOutcome(null, false)
        val highestScorers = players.mapIndexedNotNull { index, player ->
            if (player.score == highestScore) index else null
        }
        if (highestScorers.size > 1) {
            val sameTeamTie = highestScorers.map { it % 2 }.distinct().size == 1
            if (sameTeamTie) {
                val tiedTeamParity = highestScorers.first() % 2
                val opposingNegativeCount = players.filterIndexed { index, player ->
                    index % 2 != tiedTeamParity && player.score < 0
                }.count()
                if (opposingNegativeCount == 1) {
                    return RoundOutcome(highestScorers.first(), false)
                }
            }
            return RoundOutcome(null, true)
        }

        val winnerIndex = highestScorers.first()
        if (qualifies(winnerIndex)) {
            return RoundOutcome(winnerIndex, false)
        }

        val qualifiedPlayers = players.mapIndexedNotNull { index, player ->
            if (qualifies(index)) index to player.score else null
        }
        if (qualifiedPlayers.isEmpty()) {
            return RoundOutcome(null, false)
        }

        val highestQualifiedScore = qualifiedPlayers.maxOf { it.second }
        val highestPlayers = qualifiedPlayers.filter { it.second == highestQualifiedScore }
        return if (highestPlayers.size == 1) {
            RoundOutcome(highestPlayers.first().first, false)
        } else {
            RoundOutcome(null, true)
        }
    }

    private fun qualifies(index: Int): Boolean {
        val player = players.getOrNull(index) ?: return false
        val teammateScore = players.getOrNull(teammateIndexFor(index))?.score ?: return false
        return player.score >= 41 && teammateScore >= 0
    }

    private fun teammateIndexFor(index: Int): Int {
        return if (index % 2 == 0) index + 2 else index - 2
    }

    companion object {
        private const val STATE_KEY = "score_control_state"
    }
}
