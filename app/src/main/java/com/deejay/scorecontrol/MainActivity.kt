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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    private data class RoundLog(
        val round: Int,
        val bids: List<Int>,
        var results: MutableList<RoundResult?> = MutableList(4) { null }
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
    private lateinit var setupTitleText: TextView
    private lateinit var teamsText: TextView
    private lateinit var nameLayouts: List<TextInputLayout>
    private lateinit var nameInputs: List<TextInputEditText>
    private lateinit var roundTitleText: TextView
    private lateinit var roundStatusText: TextView
    private lateinit var lockBidsButton: MaterialButton
    private lateinit var nextRoundButton: MaterialButton
    private lateinit var newGameButton: MaterialButton
    private lateinit var resetGameButton: MaterialButton
    private lateinit var viewLogButton: MaterialButton
    private lateinit var playerListContainer: LinearLayout
    private lateinit var bidLogPage: LinearLayout
    private lateinit var bidLogList: LinearLayout
    private lateinit var bidLogBackButton: MaterialButton
    private lateinit var mainScrollView: NestedScrollView

    private val bidLayouts = mutableListOf<TextInputLayout>()
    private val bidInputs = mutableListOf<EditText>()
    private val players = mutableListOf<PlayerState>()
    private val setupDraftNames = MutableList(4) { "" }
    private val bidLog = mutableListOf<RoundLog>()
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
        setupTitleText = findViewById(R.id.setupTitleText)
        teamsText = findViewById(R.id.teamsText)
        bidLogPage = findViewById(R.id.bidLogPage)
        bidLogList = findViewById(R.id.bidLogList)
        bidLogBackButton = findViewById(R.id.bidLogBackButton)
        roundTitleText = findViewById(R.id.roundTitleText)
        roundStatusText = findViewById(R.id.roundStatusText)
        lockBidsButton = findViewById(R.id.lockBidsButton)
        nextRoundButton = findViewById(R.id.nextRoundButton)
        newGameButton = findViewById(R.id.newGameButton)
        resetGameButton = findViewById(R.id.resetGameButton)
        viewLogButton = findViewById(R.id.viewLogButton)
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

        ViewCompat.setOnApplyWindowInsetsListener(mainScrollView) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                view.paddingLeft,
                statusBars.top + dp(16),
                view.paddingRight,
                maxOf(systemBars.bottom, ime.bottom) + dp(20)
            )
            insets
        }

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
            resetScores()
        }
        viewLogButton.setOnClickListener {
            // show log page
            bidLogPage.visibility = View.VISIBLE
            gameContainer.visibility = View.GONE
        }
        bidLogBackButton.setOnClickListener {
            bidLogPage.visibility = View.GONE
            gameContainer.visibility = View.VISIBLE
        }
    }

    private fun renderSetup() {
        phase = Phase.SETUP
        setupContainer.visibility = View.VISIBLE
        gameContainer.visibility = View.GONE
        setupTitleText.visibility = View.VISIBLE
        newGameButton.visibility = View.GONE
        resetGameButton.visibility = View.GONE
        viewLogButton.visibility = View.GONE
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
       bidLog.clear()
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
       // Ensure log page is hidden when starting a new game
       bidLogPage.visibility = View.GONE
       persistState()
       renderGame()
    }

    private fun renderGame() {
        setupContainer.visibility = View.GONE
        gameContainer.visibility = View.VISIBLE
        setupTitleText.visibility = View.GONE
        newGameButton.visibility = View.VISIBLE
        resetGameButton.visibility = View.VISIBLE
        viewLogButton.visibility = View.VISIBLE
        teamsText.text = getTeamsText()
        roundTitleText.text = getString(R.string.round_and_minimum_label, currentRound, minimumBidForGame())
        // Populate the detailed log page list with a header row of player names and one row per round
        bidLogList.removeAllViews()

        // Header row: first column "Round" then one column per player (equal width)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val roundHeaderCell = TextView(this).apply {
            text = "Round"
            setPadding(dp(8), dp(6), dp(8), dp(6))
            // fixed width for round column
            layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        headerRow.addView(roundHeaderCell)
        // divider between round and players
        val firstDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.LTGRAY)
        }
        headerRow.addView(firstDivider)

        // Name cells — use weight so they're equal-width and show only first letter
        players.forEachIndexed { idx, p ->
            val initial = p.name.firstOrNull()?.toString()?.uppercase().orEmpty()
            val nameCell = TextView(this).apply {
                text = initial
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            headerRow.addView(nameCell)
            if (idx < players.size - 1) {
                val vdiv = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(Color.LTGRAY)
                }
                headerRow.addView(vdiv)
            }
        }
        bidLogList.addView(headerRow)

        // Divider under header
        val headerDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(4); it.bottomMargin = dp(8) }
            setBackgroundColor(Color.LTGRAY)
        }
        bidLogList.addView(headerDivider)

        // Rows per round
        bidLog.forEach { log ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            // Round number cell (fixed width)
            val roundCell = TextView(this).apply {
                text = log.round.toString()
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            row.addView(roundCell)
            // divider between round and players
            val firstDivRow = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.LTGRAY)
            }
            row.addView(firstDivRow)

            // Player cells — equal width using weight
            log.bids.forEachIndexed { i, b ->
                val r = log.results.getOrNull(i)
                val valueText = when (r) {
                    RoundResult.PASS -> "+${b}"
                    RoundResult.FAIL -> "-${b}"
                    else -> b.toString()
                }
                val cell = TextView(this).apply {
                    text = valueText
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                }
                row.addView(cell)
                if (i < log.bids.size - 1) {
                    val vdiv = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(Color.LTGRAY)
                    }
                    row.addView(vdiv)
                }
            }

            bidLogList.addView(row)

            // Divider after each row
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(4); it.bottomMargin = dp(4) }
                setBackgroundColor(Color.LTGRAY)
            }
            bidLogList.addView(divider)
        }

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

        // Choose color: positive -> green, negative -> red, zero -> default
        val defaultScoreColor = nameText.currentTextColor
        val positiveColor = Color.parseColor("#2E7D32") // Material Green 700
        val negativeColor = Color.parseColor("#C62828") // Material Red 800

        val scoreText = TextView(this).apply {
            text = getString(R.string.score_label, player.score)
            setTextColor(
                when {
                    player.score > 0 -> positiveColor
                    player.score < 0 -> negativeColor
                    else -> defaultScoreColor
                }
            )
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

                // Add an Undo button to revert the last resolve for this player
                val undoButton = MaterialButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                        it.topMargin = dp(8)
                    }
                    text = getString(R.string.undo_label)
                    setOnClickListener {
                        undoResolve(index)
                    }
                }
                outer.addView(undoButton)
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
                    mainScrollView.postDelayed({
                        scrollFocusedViewIntoView(focusedView)
                    }, 120)
                }
            }
        }
    }

    private fun scrollFocusedViewIntoView(focusedView: View) {
        val rect = Rect()
        focusedView.getDrawingRect(rect)
        mainScrollView.offsetDescendantRectToMyCoords(focusedView, rect)
        val visibleBottom = mainScrollView.height - mainScrollView.paddingBottom - dp(24)
        if (rect.bottom > visibleBottom) {
            val delta = rect.bottom - visibleBottom
            mainScrollView.smoothScrollBy(0, delta)
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

        // Save this round's bids to the bid log (results will be filled when resolved)
        bidLog.add(RoundLog(currentRound, totalBid.toList(), MutableList(playerCount) { null }))

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
            // update the last bidLog entry with the results for this round (if present)
            if (bidLog.isNotEmpty()) {
                val last = bidLog.last()
                // only update if rounds match
                if (last.round == currentRound) {
                    last.results = players.map { it.result }.toMutableList()
                }
            }

            // Do not finalize the game here. Wait for the user to click Next Round so they
            // can undo before the hand is closed. Set phase to RESOLVING and clear any
            // previously stored winner so that beginNextRound() will perform the final check.
            phase = Phase.RESOLVING
            winningPlayerIndex = null
        }

        persistState()
        renderGame()
    }

    private fun undoResolve(index: Int) {
        val player = players.getOrNull(index) ?: return
        if (!player.resolved) return

        // Revert score based on previous result
        when (player.result) {
            RoundResult.PASS -> player.score -= player.bid // made -> subtract the made points
            RoundResult.FAIL -> player.score += player.bid // fail -> add the fail points back
            null -> {}
        }

        player.result = null
        player.resolved = false

        // If the game was marked finished, undo that and clear the winner so users can re-resolve
        if (phase == Phase.FINISHED) {
            phase = Phase.RESOLVING
            winningPlayerIndex = null
        }

        persistState()
        renderGame()
    }

    private fun beginNextRound() {
        if (!players.all { it.resolved }) {
            return
        }

        // Final outcome check happens when the user clicks Next Round so they have a chance to undo.
        val outcome = determineRoundOutcome()
        winningPlayerIndex = outcome.winnerIndex
        if (winningPlayerIndex != null) {
            phase = Phase.FINISHED
            persistState()
            renderGame()
            return
        }

        // No winner => advance to next round
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
        bidLog.clear()
        currentRound = 1
        phase = Phase.SETUP
        winningPlayerIndex = null
        setupDraftNames.indices.forEach { setupDraftNames[it] = "" }
        // Ensure log page is hidden when returning to setup
        bidLogPage.visibility = View.GONE
        renderSetup()
    }

    private fun resetScores() {
        if (players.size != playerCount) {
            // No players configured — go back to setup instead
            resetToSetup()
            return
        }

        players.forEach { player ->
            player.score = 0
            player.draftBid = null
            player.bid = 0
            player.resolved = false
            player.result = null
        }
        // Clear bid log when resetting scores
        bidLog.clear()
        // Ensure log page is hidden when resetting scores
        bidLogPage.visibility = View.GONE

        currentRound = 1
        phase = Phase.BIDDING
        winningPlayerIndex = null
        persistState()
        renderGame()
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

        // Persist bid log
        val logArray = JSONArray()
        bidLog.forEach { log ->
            val obj = JSONObject()
            obj.put("round", log.round)
            val bids = JSONArray()
            log.bids.forEach { bids.put(it) }
            obj.put("bids", bids)
            // persist results as optional array of strings (PASS/FAIL/null)
            val results = JSONArray()
            log.results.forEach { r -> results.put(r?.name) }
            obj.put("results", results)
            logArray.put(obj)
        }
        state.put("bidLog", logArray)

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

            // Restore bid log if present
            bidLog.clear()
            val logArray = state.optJSONArray("bidLog") ?: JSONArray()
            for (i in 0 until logArray.length()) {
                val obj = logArray.getJSONObject(i)
                val roundNum = obj.optInt("round", i + 1)
                val bids = mutableListOf<Int>()
                val bidsArray = obj.optJSONArray("bids") ?: JSONArray()
                for (j in 0 until bidsArray.length()) {
                    bids.add(bidsArray.optInt(j, 0))
                }
                // restore results if present
                val resultsList = MutableList(playerCount) { null as RoundResult? }
                val resultsArray = obj.optJSONArray("results")
                if (resultsArray != null) {
                    for (j in 0 until resultsArray.length()) {
                        val v = if (resultsArray.isNull(j)) null else resultsArray.optString(j, null)
                        resultsList[j] = v?.let { RoundResult.valueOf(it) }
                    }
                }
                bidLog.add(RoundLog(roundNum, bids, resultsList))
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

    // Returns true if any player on the given team (parity 0 or 1) has score >= value
    private fun teamHasPlayerAtLeast(parity: Int, value: Int): Boolean {
        return players.withIndex().any { (index, player) -> index % 2 == parity && player.score >= value }
    }

    private fun determineRoundOutcome(): RoundOutcome {
        val scores = players.map { it.score }
        val (winnerIndex, isTie) = determineRoundOutcomeFromScores(scores)
        return RoundOutcome(winnerIndex, isTie)
    }

    private fun qualifies(index: Int, threshold: Int = 41): Boolean {
        val player = players.getOrNull(index) ?: return false
        val teammateScore = players.getOrNull(teammateIndexFor(index))?.score ?: return false
        return player.score >= threshold && teammateScore >= 0
    }

    private fun teammateIndexFor(index: Int): Int {
        return (index + 2) % playerCount
    }

    companion object {
        private const val STATE_KEY = "score_control_state"
    }
}
