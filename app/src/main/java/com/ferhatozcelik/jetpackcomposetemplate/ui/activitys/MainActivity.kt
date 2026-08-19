package com.ferhatozcelik.jetpackcomposetemplate.ui.activitys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

// -------------------- Models --------------------

enum class Suit(val symbol: String, val color: Color) {
    SPADES("♠", Color.Black),
    HEARTS("♥", Color(0xFFC62828)),
    DIAMONDS("♦", Color(0xFFC62828)),
    CLUBS("♣", Color.Black),
    NONE("", Color.Transparent)
}

enum class Rank(val text: String) {
    A("A"), K("K"), Q("Q"), J("J"),
    TEN("10"), NINE("9"), EIGHT("8"), SEVEN("7"), SIX("6"),
    FIVE("5"), FOUR("4"), THREE("3"), TWO("2"), CORNER("★")
}

enum class TeamColor(val uiColor: Color) {
    NONE(Color.Transparent),
    BLUE(Color(0xFF1976D2)),
    GREEN(Color(0xFF159447)),
    RED(Color(0xFFD32F2F))
}

data class PlayingCard(
    val suit: Suit,
    val rank: Rank,
    val uniqueId: Int
) {
    val isTwoEyedJack: Boolean
        get() = rank == Rank.J && (suit == Suit.DIAMONDS || suit == Suit.CLUBS)

    val isOneEyedJack: Boolean
        get() = rank == Rank.J && (suit == Suit.SPADES || suit == Suit.HEARTS)

    fun matches(other: PlayingCard): Boolean = suit == other.suit && rank == other.rank
}

data class Player(
    val id: Int,
    val team: TeamColor,
    val isCpu: Boolean,
    val hand: List<PlayingCard> = emptyList()
)

data class BoardSpace(
    val row: Int,
    val col: Int,
    val card: PlayingCard,
    val occupant: TeamColor = TeamColor.NONE,
    val isHighlighted: Boolean = false,
    val isCompletedSequence: Boolean = false
)

data class CompletedLine(
    val team: TeamColor,
    val positions: Set<Pair<Int, Int>>
)

enum class GameState { SETUP, PASS_DEVICE, PLAYING, FINISHED }

// -------------------- ViewModel --------------------

class GameViewModel : ViewModel() {
    private val random = Random.Default
    private var deck = mutableListOf<PlayingCard>()
    private var players: List<Player> = emptyList()
    private var cpuJob: Job? = null

    var currentPlayerIndex by mutableStateOf(0)
        private set

    var currentGameState by mutableStateOf(GameState.SETUP)
        private set

    var gameMessage by mutableStateOf("Choose game settings.")
        private set

    var humanCount by mutableStateOf(1)
        private set

    var numberOfTeams by mutableStateOf(2)
        private set

    var winnerTeam by mutableStateOf<TeamColor?>(null)
        private set

    var isDraw by mutableStateOf(false)
        private set

    private val _board = MutableStateFlow<List<List<BoardSpace>>>(emptyList())
    val board: StateFlow<List<List<BoardSpace>>> = _board.asStateFlow()

    private val _selectedCardId = MutableStateFlow<Int?>(null)
    val selectedCardId: StateFlow<Int?> = _selectedCardId.asStateFlow()

    private val _sequenceCounts = MutableStateFlow<Map<TeamColor, Int>>(emptyMap())
    val sequenceCounts: StateFlow<Map<TeamColor, Int>> = _sequenceCounts.asStateFlow()

    val currentPlayer: Player
        get() = players.getOrElse(currentPlayerIndex) {
            Player(0, TeamColor.NONE, false)
        }

    private val requiredSequences: Int
        get() = if (numberOfTeams == 2) 2 else 1

    fun setupGame(totalPlayers: Int, humans: Int, requestedTeams: Int) {
        require(totalPlayers in listOf(2, 3, 4, 6, 8, 9, 10, 12))
        require(requestedTeams in 2..3)
        require(totalPlayers % requestedTeams == 0)
        require(humans in 1..totalPlayers)

        cpuJob?.cancel()
        humanCount = humans
        numberOfTeams = requestedTeams
        winnerTeam = null
        isDraw = false
        currentPlayerIndex = 0
        _selectedCardId.value = null

        // Two full 52-card decks. A fresh shuffle is done for every game.
        deck = buildTwoDecks().shuffled(random).toMutableList()

        // The board contains two independently shuffled copies of every non-Jack card.
        val boardCards = buildBoardCards().shuffled(random).toMutableList()
        _board.value = List(10) { row ->
            List(10) { col ->
                val corner = (row == 0 || row == 9) && (col == 0 || col == 9)
                BoardSpace(
                    row = row,
                    col = col,
                    card = if (corner) {
                        PlayingCard(Suit.NONE, Rank.CORNER, -1 - row * 10 - col)
                    } else {
                        boardCards.removeFirst()
                    }
                )
            }
        }

        val teams = if (requestedTeams == 2) {
            listOf(TeamColor.BLUE, TeamColor.GREEN)
        } else {
            listOf(TeamColor.BLUE, TeamColor.GREEN, TeamColor.RED)
        }

        players = List(totalPlayers) { index ->
            Player(
                id = index + 1,
                team = teams[index % requestedTeams],
                isCpu = index >= humans
            )
        }

        // Deal one card at a time around the table from the shuffled draw pile.
        val handSize = handSizeFor(totalPlayers)
        repeat(handSize) {
            players = players.map { player ->
                player.copy(hand = player.hand + listOfNotNull(drawOneCard()))
            }
        }

        _sequenceCounts.value = teams.associateWith { 0 }
        currentGameState = GameState.PLAYING
        gameMessage = if (requiredSequences == 2) {
            "First team to complete 2 sequences wins."
        } else {
            "First team to complete 1 sequence wins."
        }

        if (currentPlayer.isCpu) startCpuTurn()
    }

    private fun buildTwoDecks(): List<PlayingCard> {
        var id = 0
        return buildList {
            repeat(2) {
                for (suit in Suit.entries.filter { it != Suit.NONE }) {
                    for (rank in Rank.entries.filter { it != Rank.CORNER }) {
                        add(PlayingCard(suit, rank, id++))
                    }
                }
            }
        }
    }

    private fun buildBoardCards(): List<PlayingCard> {
        var id = 10_000
        return buildList {
            repeat(2) {
                for (suit in Suit.entries.filter { it != Suit.NONE }) {
                    for (rank in Rank.entries.filter { it != Rank.J && it != Rank.CORNER }) {
                        add(PlayingCard(suit, rank, id++))
                    }
                }
            }
        }
    }

    private fun handSizeFor(totalPlayers: Int): Int = when (totalPlayers) {
        2 -> 7
        3, 4 -> 6
        6 -> 5
        8, 9 -> 4
        10, 12 -> 3
        else -> error("Unsupported player count")
    }

    private fun drawOneCard(): PlayingCard? =
        if (deck.isEmpty()) null else deck.removeFirst()

    fun selectCard(cardId: Int) {
        if (currentGameState != GameState.PLAYING || currentPlayer.isCpu) return
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return
        _selectedCardId.value = cardId

        _board.value = _board.value.map { row ->
            row.map { space ->
                space.copy(isHighlighted = isLegalDestination(card, space, currentPlayer.team))
            }
        }

        val count = _board.value.flatten().count { it.isHighlighted }
        gameMessage = if (count == 0) {
            "No legal position. If both matching spaces are occupied, replace this dead card."
        } else {
            "$count legal move${if (count == 1) "" else "s"} highlighted in green."
        }
    }

    private fun isLegalDestination(
        card: PlayingCard,
        space: BoardSpace,
        team: TeamColor
    ): Boolean {
        if (space.card.rank == Rank.CORNER) return false
        return when {
            card.isTwoEyedJack -> space.occupant == TeamColor.NONE
            card.isOneEyedJack ->
                space.occupant != TeamColor.NONE &&
                    space.occupant != team &&
                    !space.isCompletedSequence
            else -> space.occupant == TeamColor.NONE && space.card.matches(card)
        }
    }

    fun humanPlaceToken(row: Int, col: Int) {
        if (currentGameState != GameState.PLAYING || currentPlayer.isCpu) return
        val cardId = _selectedCardId.value ?: return
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return
        val space = _board.value.getOrNull(row)?.getOrNull(col) ?: return
        if (!space.isHighlighted || !isLegalDestination(card, space, currentPlayer.team)) return
        executeMove(row, col, card)
    }

    private fun executeMove(row: Int, col: Int, cardUsed: PlayingCard) {
        if (currentGameState != GameState.PLAYING) return
        val movingPlayer = currentPlayer
        val target = _board.value[row][col]

        if (!isLegalDestination(cardUsed, target, movingPlayer.team)) return

        val newTarget = if (cardUsed.isOneEyedJack) {
            target.copy(
                occupant = TeamColor.NONE,
                isHighlighted = false,
                isCompletedSequence = false
            )
        } else {
            target.copy(
                occupant = movingPlayer.team,
                isHighlighted = false
            )
        }

        _board.value = _board.value.mapIndexed { r, boardRow ->
            boardRow.mapIndexed { c, space ->
                if (r == row && c == col) newTarget else space.copy(isHighlighted = false)
            }
        }

        val newHand = movingPlayer.hand
            .filterNot { it.uniqueId == cardUsed.uniqueId }
            .toMutableList()
        drawOneCard()?.let(newHand::add)
        updatePlayer(movingPlayer.copy(hand = newHand))
        _selectedCardId.value = null

        gameMessage = if (cardUsed.isOneEyedJack) {
            "Player ${movingPlayer.id} removed an opponent chip."
        } else {
            "Player ${movingPlayer.id} placed ${cardUsed.rank.text}${cardUsed.suit.symbol}."
        }

        if (!cardUsed.isOneEyedJack) {
            if (updateSequencesAndCheckWinner(movingPlayer.team)) return
        }

        if (checkForDraw()) return
        advanceTurn()
    }

    private fun updatePlayer(updated: Player) {
        players = players.map { if (it.id == updated.id) updated else it }
    }

    fun replaceSelectedDeadCard() {
        if (currentGameState != GameState.PLAYING || currentPlayer.isCpu) return
        val cardId = _selectedCardId.value ?: run {
            gameMessage = "Select a card first."
            return
        }
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return

        if (card.rank == Rank.J) {
            gameMessage = "A Jack is not a dead card."
            return
        }

        val matches = _board.value.flatten().filter { it.card.matches(card) }
        if (matches.isEmpty() || matches.any { it.occupant == TeamColor.NONE }) {
            gameMessage = "That card is not dead because a matching space is open."
            return
        }

        val newHand = currentPlayer.hand
            .filterNot { it.uniqueId == card.uniqueId }
            .toMutableList()
        val replacement = drawOneCard()
        replacement?.let(newHand::add)
        updatePlayer(currentPlayer.copy(hand = newHand))
        _selectedCardId.value = null
        clearHighlights()
        gameMessage = if (replacement != null) {
            "Dead card replaced. Take your normal turn."
        } else {
            "Dead card discarded. The draw pile is empty."
        }
        checkForDraw()
    }

    private fun clearHighlights() {
        _board.value = _board.value.map { row ->
            row.map { it.copy(isHighlighted = false) }
        }
    }

    private fun findCompletedLines(team: TeamColor): List<CompletedLine> {
        val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        val candidates = mutableListOf<CompletedLine>()

        for (row in 0 until 10) {
            for (col in 0 until 10) {
                for ((dr, dc) in directions) {
                    val positions = (0 until 5).map { offset ->
                        row + dr * offset to col + dc * offset
                    }
                    if (positions.any { (r, c) -> r !in 0..9 || c !in 0..9 }) continue

                    val complete = positions.all { (r, c) ->
                        val space = _board.value[r][c]
                        space.card.rank == Rank.CORNER || space.occupant == team
                    }
                    if (complete) candidates += CompletedLine(team, positions.toSet())
                }
            }
        }

        // Accept distinct sequences that share at most one board position.
        val accepted = mutableListOf<CompletedLine>()
        for (candidate in candidates) {
            if (accepted.all { previous ->
                    candidate.positions.intersect(previous.positions).size <= 1
                }
            ) {
                accepted += candidate
            }
        }
        return accepted
    }

    private fun updateSequencesAndCheckWinner(team: TeamColor): Boolean {
        val completed = findCompletedLines(team)
        val oldCount = _sequenceCounts.value[team] ?: 0
        val newCount = completed.size
        val protectedPositions = completed.flatMap { it.positions }.toSet()

        _sequenceCounts.value = _sequenceCounts.value.toMutableMap().apply {
            this[team] = newCount
        }

        _board.value = _board.value.mapIndexed { row, boardRow ->
            boardRow.mapIndexed { col, space ->
                if (space.occupant == team && (row to col) in protectedPositions) {
                    space.copy(isCompletedSequence = true)
                } else {
                    space
                }
            }
        }

        if (newCount > oldCount) {
            gameMessage = when {
                newCount >= requiredSequences ->
                    "Team ${team.name} completed ${if (newCount == 1) "a sequence" else "$newCount sequences"} and wins!"
                requiredSequences == 2 ->
                    "Team ${team.name} completed its first sequence. One more is needed."
                else -> "Team ${team.name} completed a sequence!"
            }
        }

        if (newCount >= requiredSequences) {
            winnerTeam = team
            currentGameState = GameState.FINISHED
            cpuJob?.cancel()
            return true
        }
        return false
    }

    private fun playerHasLegalMove(player: Player): Boolean {
        val spaces = _board.value.flatten()
        return player.hand.any { card ->
            spaces.any { space -> isLegalDestination(card, space, player.team) }
        }
    }

    private fun checkForDraw(): Boolean {
        if (winnerTeam != null) return false

        val nobodyHasCards = players.all { it.hand.isEmpty() }
        val noPlayerCanMove = players.none(::playerHasLegalMove)
        val draw = nobodyHasCards || (deck.isEmpty() && noPlayerCanMove)

        if (draw) {
            isDraw = true
            currentGameState = GameState.FINISHED
            gameMessage = "Draw: the draw pile is empty and no legal moves remain."
            cpuJob?.cancel()
            return true
        }
        return false
    }

    private fun advanceTurn() {
        if (currentGameState == GameState.FINISHED) return
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        _selectedCardId.value = null
        clearHighlights()

        if (currentPlayer.isCpu) {
            currentGameState = GameState.PLAYING
            gameMessage = "CPU Player ${currentPlayer.id} is thinking..."
            startCpuTurn()
        } else if (humanCount > 1) {
            currentGameState = GameState.PASS_DEVICE
            gameMessage = "Pass the device without showing the next player's cards."
        } else {
            currentGameState = GameState.PLAYING
            gameMessage = "Player ${currentPlayer.id}, choose a card."
        }
    }

    fun confirmPassDevice() {
        if (currentGameState != GameState.PASS_DEVICE) return
        currentGameState = GameState.PLAYING
        gameMessage = "Player ${currentPlayer.id}, choose a card."
    }

    private fun startCpuTurn() {
        cpuJob?.cancel()
        cpuJob = viewModelScope.launch {
            delay(800)
            if (currentGameState != GameState.PLAYING || !currentPlayer.isCpu) return@launch

            val cpu = currentPlayer
            val legalMoves = cpu.hand.flatMap { card ->
                _board.value.flatten()
                    .filter { isLegalDestination(card, it, cpu.team) }
                    .map { space -> card to space }
            }

            if (legalMoves.isNotEmpty()) {
                val (card, space) = legalMoves.random(random)
                executeMove(space.row, space.col, card)
                return@launch
            }

            val deadCard = cpu.hand.firstOrNull { card ->
                card.rank != Rank.J &&
                    _board.value.flatten()
                        .filter { it.card.matches(card) }
                        .all { it.occupant != TeamColor.NONE }
            }

            if (deadCard != null) {
                val newHand = cpu.hand
                    .filterNot { it.uniqueId == deadCard.uniqueId }
                    .toMutableList()
                drawOneCard()?.let(newHand::add)
                updatePlayer(cpu.copy(hand = newHand))
                gameMessage = "CPU Player ${cpu.id} replaced a dead card."
                if (!checkForDraw()) startCpuTurn()
            } else if (!checkForDraw()) {
                gameMessage = "CPU Player ${cpu.id} has no legal move."
                advanceTurn()
            }
        }
    }

    fun newGame() {
        cpuJob?.cancel()
        players = emptyList()
        deck.clear()
        _board.value = emptyList()
        _selectedCardId.value = null
        _sequenceCounts.value = emptyMap()
        currentPlayerIndex = 0
        winnerTeam = null
        isDraw = false
        gameMessage = "Choose game settings."
        currentGameState = GameState.SETUP
    }
}

// -------------------- Activity and UI --------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SequenceApp()
                }
            }
        }
    }
}

@Composable
fun SequenceApp(gameViewModel: GameViewModel = viewModel()) {
    when (gameViewModel.currentGameState) {
        GameState.SETUP -> SetupScreen(gameViewModel)
        GameState.PASS_DEVICE -> PassDeviceScreen(gameViewModel)
        GameState.PLAYING -> GameScreen(gameViewModel)
        GameState.FINISHED -> FinishedScreen(gameViewModel)
    }
}

@Composable
fun SetupScreen(gameViewModel: GameViewModel) {
    var totalPlayers by remember { mutableStateOf(2) }
    var humans by remember { mutableStateOf(1) }
    var teams by remember { mutableStateOf(2) }

    val supportedPlayers = listOf(2, 3, 4, 6, 8, 9, 10, 12)
    val availableTeams = when (totalPlayers) {
        3, 9 -> listOf(3)
        6, 12 -> listOf(2, 3)
        else -> listOf(2)
    }
    val effectiveTeams = if (teams in availableTeams) teams else availableTeams.first()
    val effectiveHumans = humans.coerceAtMost(totalPlayers)

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Five Line Cards", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Classic local play with optional CPU players", color = Color.Gray)
        Spacer(Modifier.height(24.dp))

        Text("Total players: $totalPlayers", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            supportedPlayers.forEach { count ->
                TextButton(onClick = { totalPlayers = count; humans = humans.coerceAtMost(count); teams = when (count) { 3, 9 -> 3; else -> if (teams == 3 && count !in listOf(6, 12)) 2 else teams } }) {
                    Text(
                        count.toString(),
                        color = if (count == totalPlayers) Color(0xFF1976D2) else Color.Gray,
                        fontWeight = if (count == totalPlayers) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Text("Teams: $effectiveTeams", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            availableTeams.forEach { count ->
                Button(
                    onClick = { teams = count },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (effectiveTeams == count) Color(0xFF1976D2) else Color.Gray
                    )
                ) { Text(count.toString()) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Human players: $effectiveHumans", fontWeight = FontWeight.Bold)
        Text("Remaining players are CPU-controlled", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (1..totalPlayers).forEach { count ->
                TextButton(onClick = { humans = count }) {
                    Text(
                        count.toString(),
                        color = if (effectiveHumans == count) Color(0xFF1976D2) else Color.Gray
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { gameViewModel.setupGame(totalPlayers, effectiveHumans, effectiveTeams) },
            modifier = Modifier.fillMaxWidth(0.7f).height(52.dp)
        ) {
            Text("START GAME", fontSize = 17.sp)
        }
    }
}

@Composable
fun PassDeviceScreen(gameViewModel: GameViewModel) {
    val next = gameViewModel.currentPlayer
    Column(
        Modifier.fillMaxSize().background(next.team.uiColor.copy(alpha = 0.13f)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(gameViewModel.gameMessage, color = Color.DarkGray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Text("Pass the device to", fontSize = 23.sp)
        Text(
            "Player ${next.id}",
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
            color = next.team.uiColor
        )
        Text("Team ${next.team.name}", fontSize = 18.sp)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = gameViewModel::confirmPassDevice,
            modifier = Modifier.fillMaxWidth(0.65f).height(58.dp)
        ) { Text("I'M READY", fontSize = 19.sp) }
    }
}

@Composable
fun GameScreen(gameViewModel: GameViewModel) {
    val board by gameViewModel.board.collectAsState()
    val selectedCardId by gameViewModel.selectedCardId.collectAsState()
    val sequenceCounts by gameViewModel.sequenceCounts.collectAsState()
    val player = gameViewModel.currentPlayer

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF1F3F4)).padding(5.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (player.isCpu) "CPU ${player.id} is thinking..." else "Player ${player.id}'s turn",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = player.team.uiColor
                )
                Text(
                    gameViewModel.gameMessage,
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                    maxLines = 2
                )
            }
            Text(
                "${sequenceCounts[player.team] ?: 0}/${if (gameViewModel.numberOfTeams == 2) 2 else 1}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = player.team.uiColor
            )
        }

        if (board.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(10),
                modifier = Modifier.fillMaxWidth().weight(1f),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(board.flatten(), key = { it.row * 10 + it.col }) { space ->
                    BoardCard(space) {
                        gameViewModel.humanPlaceToken(space.row, space.col)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        if (!player.isCpu) {
            Text(
                if (selectedCardId == null) "Select a card to show legal moves" else "Green spaces are legal moves",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF087F23)
            )
            PlayerHand(
                player = player,
                selectedCardId = selectedCardId,
                onSelect = gameViewModel::selectCard,
                onReplaceDeadCard = gameViewModel::replaceSelectedDeadCard
            )
        } else {
            Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
                Text("CPU cards are hidden", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun BoardCard(space: BoardSpace, onClick: () -> Unit) {
    val background = when {
        space.card.rank == Rank.CORNER -> Color(0xFFFFD75E)
        space.isHighlighted -> Color(0xFF9EE6AC)
        space.isCompletedSequence -> space.occupant.uiColor.copy(alpha = 0.18f)
        else -> Color.White
    }
    val borderColor = when {
        space.isHighlighted -> Color(0xFF07852B)
        space.isCompletedSequence -> space.occupant.uiColor
        else -> Color(0xFF777777)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f) // Taller rectangle, leaving the rank and suit visible.
            .background(background, MaterialTheme.shapes.extraSmall)
            .border(
                if (space.isHighlighted || space.isCompletedSequence) 2.dp else 0.6.dp,
                borderColor,
                MaterialTheme.shapes.extraSmall
            )
            .clickable(enabled = space.isHighlighted, onClick = onClick)
            .padding(1.dp)
    ) {
        if (space.card.rank == Rank.CORNER) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("★", fontSize = 12.sp, color = Color(0xFF6D4C00))
                Text("FREE", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6D4C00))
            }
        } else {
            Column(
                Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    space.card.rank.text,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = space.card.suit.color
                )
                Text(
                    space.card.suit.symbol,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    color = space.card.suit.color
                )
            }
        }

        if (space.occupant != TeamColor.NONE) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .fillMaxWidth(0.68f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(
                        space.occupant.uiColor.copy(
                            alpha = if (space.isCompletedSequence) 0.62f else 0.9f
                        )
                    )
                    .border(
                        if (space.isCompletedSequence) 2.dp else 1.dp,
                        if (space.isCompletedSequence) Color.White else Color.Black.copy(alpha = 0.65f),
                        CircleShape
                    )
            ) {
                if (space.isCompletedSequence) {
                    Text(
                        "✓",
                        Modifier.align(Alignment.Center),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        if (space.isHighlighted) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(2.dp).size(6.dp)
                    .clip(CircleShape).background(Color(0xFF07852B))
            )
        }
    }
}

@Composable
private fun PlayerHand(
    player: Player,
    selectedCardId: Int?,
    onSelect: (Int) -> Unit,
    onReplaceDeadCard: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        player.hand.forEach { card ->
            val selected = card.uniqueId == selectedCardId
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .border(
                        if (selected) 3.dp else 1.dp,
                        if (selected) Color(0xFF07852B) else Color.LightGray,
                        MaterialTheme.shapes.small
                    )
                    .clickable { onSelect(card.uniqueId) },
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(card.rank.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = card.suit.color)
                    Text(card.suit.symbol, fontSize = 14.sp, color = card.suit.color)
                    when {
                        card.isTwoEyedJack -> Text("WILD", fontSize = 7.sp, color = Color.Blue, fontWeight = FontWeight.Bold)
                        card.isOneEyedJack -> Text("REMOVE", fontSize = 7.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = onReplaceDeadCard, enabled = selectedCardId != null) {
        Text("Replace selected dead card", fontSize = 12.sp)
    }
}

@Composable
fun FinishedScreen(gameViewModel: GameViewModel) {
    val counts by gameViewModel.sequenceCounts.collectAsState()
    val winner = gameViewModel.winnerTeam
    val background = if (gameViewModel.isDraw || winner == null) {
        Color(0xFFE7E7E7)
    } else {
        winner.uiColor.copy(alpha = 0.15f)
    }

    Column(
        Modifier.fillMaxSize().background(background).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (gameViewModel.isDraw) "DRAW" else "WINNER",
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        if (winner != null) {
            Text(
                "Team ${winner.name}",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = winner.uiColor
            )
            Text("Completed sequences: ${counts[winner] ?: 0}", fontSize = 18.sp)
        } else {
            Text("No legal moves remain.", fontSize = 20.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Text(gameViewModel.gameMessage, textAlign = TextAlign.Center, color = Color.DarkGray)
        Spacer(Modifier.height(32.dp))
        Button(onClick = gameViewModel::newGame) { Text("NEW GAME") }
    }
}
