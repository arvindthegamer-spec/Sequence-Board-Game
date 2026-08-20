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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

// ==============================================================================
// 1. MAIN MENU
// ==============================================================================

enum class AppMode { MENU, OFFLINE, ONLINE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var currentMode by remember { mutableStateOf(AppMode.MENU) }

                    when (currentMode) {
                        AppMode.MENU -> MainMenuScreen(
                            onPlayLocal = { currentMode = AppMode.OFFLINE },
                            onPlayOnline = { currentMode = AppMode.ONLINE }
                        )
                        AppMode.OFFLINE -> OfflineSequenceApp(onExit = { currentMode = AppMode.MENU })
                        AppMode.ONLINE -> OnlineSequenceApp(onExit = { currentMode = AppMode.MENU })
                    }
                }
            }
        }
    }
}

@Composable
fun MainMenuScreen(onPlayLocal: () -> Unit, onPlayOnline: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF1F3F4)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SEQUENCE", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Text("Board Game", fontSize = 20.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 48.dp))

        Button(onClick = onPlayLocal, modifier = Modifier.fillMaxWidth(0.7f).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07852B))) {
            Text("Play Local (Pass & Play / CPU)", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onPlayOnline, modifier = Modifier.fillMaxWidth(0.7f).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) {
            Text("Play Online (With Friends)", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(64.dp))
        Text("Developed by Aravind Valluri", fontSize = 15.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
    }
}

// ==============================================================================
// 2. OFFLINE CORE GAME (100% Untouched Original Logic)
// ==============================================================================

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

        deck = buildTwoDecks().shuffled(random).toMutableList()
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

@Composable
fun OfflineSequenceApp(onExit: () -> Unit, gameViewModel: GameViewModel = viewModel()) {
    when (gameViewModel.currentGameState) {
        GameState.SETUP -> SetupScreen(gameViewModel, onExit)
        GameState.PASS_DEVICE -> PassDeviceScreen(gameViewModel)
        GameState.PLAYING -> GameScreen(gameViewModel, onExit)
        GameState.FINISHED -> FinishedScreen(gameViewModel, onExit)
    }
}

@Composable
fun SetupScreen(gameViewModel: GameViewModel, onExit: () -> Unit = {}) {
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
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 16.dp)) {
            Text("Back to Main Menu", color = Color.Red)
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
fun GameScreen(gameViewModel: GameViewModel, onExit: () -> Unit = {}) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${sequenceCounts[player.team] ?: 0}/${if (gameViewModel.numberOfTeams == 2) 2 else 1}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = player.team.uiColor,
                    modifier = Modifier.padding(end = 8.dp)
                )
                TextButton(onClick = onExit) { Text("Exit", color = Color.Red) }
            }
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
            .aspectRatio(0.68f)
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
fun FinishedScreen(gameViewModel: GameViewModel, onExit: () -> Unit = {}) {
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
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 16.dp)) {
            Text("Back to Main Menu", color = Color.Red)
        }
    }
}

// ==============================================================================
// 3. ONLINE MULTIPLAYER (Full 2-12 Players, Teams, Jacks, & Names)
// ==============================================================================

data class FBCard(val suit: String = "", val rank: String = "", val id: Int = 0) {
    val isTwoEyedJack: Boolean get() = rank == "J" && (suit == "♦" || suit == "♣")
    val isOneEyedJack: Boolean get() = rank == "J" && (suit == "♠" || suit == "♥")
    fun matches(other: FBCard): Boolean = suit == other.suit && rank == other.rank
}

data class FBSpace(val r: Int = 0, val c: Int = 0, val card: FBCard = FBCard(), val occupant: String = "NONE", val isCompletedSequence: Boolean = false)

data class FBPlayer(
    val playerId: Int = 0,
    val playerName: String = "",
    val team: String = "BLUE",
    val hand: List<FBCard> = emptyList()
)

data class GameRoom(
    var roomId: String = "",
    var password: String = "",
    var status: String = "WAITING", // WAITING, PLAYING, FINISHED
    var hostName: String = "",
    var numberOfTeams: Int = 2,
    var turnPlayerId: Int = 1,
    var message: String = "Waiting for players to join...",
    var board: List<FBSpace> = emptyList(),
    var players: List<FBPlayer> = emptyList(),
    var deck: List<FBCard> = emptyList(),
    var winnerTeam: String = ""
)

enum class OnlineAppState { LOBBY, ENTER_NAME, CREATE_ROOM, JOIN_ROOM, WAITING_ROOM, PLAYING }

class MultiplayerViewModel : ViewModel() {
    private val db = Firebase.database.reference
    var currentAppState by mutableStateOf(OnlineAppState.LOBBY)
    var playerName by mutableStateOf("Player")
    var roomCode by mutableStateOf("")
    var roomPassword by mutableStateOf("")
    var lobbyError by mutableStateOf("")

    var selectedTeams by mutableStateOf(2)

    private val _roomData = MutableStateFlow(GameRoom())
    val roomData: StateFlow<GameRoom> = _roomData.asStateFlow()
    private var roomListener: ValueEventListener? = null
    private var myAssignedId: Int = 1

    fun backToLobby() {
        currentAppState = OnlineAppState.LOBBY
        roomListener?.let { db.child("rooms").child(roomCode).removeEventListener(it) }
    }

    fun proceedToCreate(name: String) {
        if (name.isBlank()) { lobbyError = "Enter a valid name"; return }
        playerName = name.trim()
        roomCode = Random.nextInt(1000, 9999).toString()
        lobbyError = ""
        currentAppState = OnlineAppState.CREATE_ROOM
    }

    fun proceedToJoin(name: String) {
        if (name.isBlank()) { lobbyError = "Enter a valid name"; return }
        playerName = name.trim()
        lobbyError = ""
        currentAppState = OnlineAppState.JOIN_ROOM
    }

    fun executeCreateRoom(password: String, numTeams: Int) {
        roomPassword = password
        selectedTeams = numTeams
        myAssignedId = 1
        currentAppState = OnlineAppState.WAITING_ROOM

        val hostPlayer = FBPlayer(playerId = 1, playerName = playerName, team = "BLUE", hand = emptyList())
        val initialRoom = GameRoom(
            roomId = roomCode,
            password = password,
            status = "WAITING",
            hostName = playerName,
            numberOfTeams = numTeams,
            turnPlayerId = 1,
            message = "Waiting for players...",
            board = buildInitialBoard(),
            players = listOf(hostPlayer),
            deck = buildDeck()
        )

        db.child("rooms").child(roomCode).setValue(initialRoom).addOnSuccessListener {
            listenToRoom(roomCode)
        }.addOnFailureListener {
            lobbyError = "Database error. Check connection."
            currentAppState = OnlineAppState.CREATE_ROOM
        }
    }

    fun joinRoom(code: String, password: String) {
        lobbyError = "Joining room..."
        db.child("rooms").child(code).get().addOnSuccessListener { snapshot ->
            val room = snapshot.getValue(GameRoom::class.java)
            if (room == null) {
                lobbyError = "Room not found."
            } else if (room.password != password) {
                lobbyError = "Wrong password."
            } else if (room.status != "WAITING") {
                lobbyError = "Game already started."
            } else if (room.players.size >= 12) {
                lobbyError = "Room is full (max 12)."
            } else {
                roomCode = code
                val newId = room.players.size + 1
                myAssignedId = newId

                val teamList = if (room.numberOfTeams == 2) listOf("BLUE", "GREEN") else listOf("BLUE", "GREEN", "RED")
                val assignedTeam = teamList[(newId - 1) % room.numberOfTeams]

                val updatedPlayers = room.players.toMutableList()
                updatedPlayers.add(FBPlayer(playerId = newId, playerName = playerName, team = assignedTeam, hand = emptyList()))

                db.child("rooms").child(code).child("players").setValue(updatedPlayers).addOnSuccessListener {
                    listenToRoom(code)
                    currentAppState = OnlineAppState.WAITING_ROOM
                }
            }
        }.addOnFailureListener { lobbyError = "Connection failed." }
    }

    fun changePlayerTeam(playerId: Int) {
        val room = _roomData.value
        if (room.hostName != playerName) return
        val players = room.players.toMutableList()
        val index = players.indexOfFirst { it.playerId == playerId }
        if (index != -1) {
            val currentTeam = players[index].team
            val nextTeam = if (room.numberOfTeams == 2) {
                if (currentTeam == "BLUE") "GREEN" else "BLUE"
            } else {
                when (currentTeam) { "BLUE" -> "GREEN"; "GREEN" -> "RED"; else -> "BLUE" }
            }
            players[index] = players[index].copy(team = nextTeam)
            db.child("rooms").child(roomCode).child("players").setValue(players)
        }
    }

    fun hostStartGame() {
        val room = _roomData.value
        val pCount = room.players.size
        val validCounts = listOf(2, 3, 4, 6, 8, 9, 10, 12)
        if (pCount !in validCounts) {
            lobbyError = "Invalid number of players ($pCount). Need 2,3,4,6,8,9,10, or 12."
            return
        }
        if (pCount % room.numberOfTeams != 0) {
            lobbyError = "Players must divide evenly into ${room.numberOfTeams} teams."
            return
        }

        val currentDeck = room.deck.toMutableList()
        val handSize = when (pCount) {
            2 -> 7
            in 3..4 -> 6
            6 -> 5
            in 8..9 -> 4
            else -> 3
        }

        val updatedPlayers = room.players.map { player ->
            val pHand = mutableListOf<FBCard>()
            repeat(handSize) { if (currentDeck.isNotEmpty()) pHand.add(currentDeck.removeAt(0)) }
            player.copy(hand = pHand)
        }

        val updates = mapOf(
            "status" to "PLAYING",
            "deck" to currentDeck,
            "players" to updatedPlayers,
            "message" to "Game started! Player 1's turn."
        )
        db.child("rooms").child(roomCode).updateChildren(updates)
    }

    private fun listenToRoom(code: String) {
        roomListener?.let { db.child("rooms").child(code).removeEventListener(it) }
        roomListener = db.child("rooms").child(code).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(GameRoom::class.java)
                if (room != null) {
                    _roomData.value = room
                    if (room.status == "PLAYING" && currentAppState == OnlineAppState.WAITING_ROOM) {
                        currentAppState = OnlineAppState.PLAYING
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) { lobbyError = "Lost connection." }
        })
    }

    private fun checkAndLockSequences(board: MutableList<FBSpace>, team: String): Boolean {
        val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        val candidates = mutableListOf<Set<Pair<Int, Int>>>()
        
        for (r in 0..9) {
            for (c in 0..9) {
                for ((dr, dc) in directions) {
                    val positions = (0..4).map { r + dr * it to c + dc * it }
                    if (positions.none { (pr, pc) -> pr !in 0..9 || pc !in 0..9 }) {
                        val isComplete = positions.all { (pr, pc) ->
                            val space = board.first { it.r == pr && it.c == pc }
                            space.card.rank == "★" || space.occupant == team
                        }
                        if (isComplete) candidates.add(positions.toSet())
                    }
                }
            }
        }
        
        val accepted = mutableListOf<Set<Pair<Int, Int>>>()
        for (cand in candidates) {
            if (accepted.all { prev -> cand.intersect(prev).size <= 1 }) {
                accepted.add(cand)
            }
        }
        
        val protected = accepted.flatten().toSet()
        for (i in board.indices) {
            if (board[i].occupant == team && (board[i].r to board[i].c) in protected) {
                board[i] = board[i].copy(isCompletedSequence = true)
            }
        }
        
        val reqSeq = if (_roomData.value.numberOfTeams == 2) 2 else 1
        return accepted.size >= reqSeq
    }

    fun playCard(card: FBCard, row: Int, col: Int) {
        val room = _roomData.value
        if (room.turnPlayerId != myAssignedId || room.status == "FINISHED") return

        val myPlayerObj = room.players.firstOrNull { it.playerId == myAssignedId } ?: return
        val updatedBoard = room.board.toMutableList()
        val targetIndex = updatedBoard.indexOfFirst { it.r == row && it.c == col }
        if (targetIndex == -1) return
        val targetSpace = updatedBoard[targetIndex]

        val myTeam = myPlayerObj.team
        val isTwoEyed = card.isTwoEyedJack
        val isOneEyed = card.isOneEyedJack

        val isLegal = targetSpace.card.rank != "★" && when {
            isTwoEyed -> targetSpace.occupant == "NONE"
            isOneEyed -> targetSpace.occupant != "NONE" && targetSpace.occupant != myTeam && !targetSpace.isCompletedSequence
            else -> targetSpace.occupant == "NONE" && targetSpace.card.matches(card)
        }
        if (!isLegal) return

        val newOccupant = if (isOneEyed) "NONE" else myTeam
        val finalTarget = if (isOneEyed) targetSpace.copy(occupant = newOccupant, isCompletedSequence = false) else targetSpace.copy(occupant = newOccupant)
        updatedBoard[targetIndex] = finalTarget

        val isWinner = if (!isOneEyed) checkAndLockSequences(updatedBoard, myTeam) else false

        val updatedHand = myPlayerObj.hand.toMutableList()
        updatedHand.remove(card)
        val deckList = room.deck.toMutableList()
        if (deckList.isNotEmpty()) updatedHand.add(deckList.removeAt(0))

        val updatedPlayers = room.players.map { if (it.playerId == myAssignedId) it.copy(hand = updatedHand) else it }
        val nextTurnId = if (room.turnPlayerId >= room.players.size) 1 else room.turnPlayerId + 1
        val nextPlayerName = room.players.firstOrNull { it.playerId == nextTurnId }?.playerName ?: "Player $nextTurnId"

        val actionMsg = if (isOneEyed) "${myPlayerObj.playerName} removed a chip!" else "${myPlayerObj.playerName} placed a chip."
        
        if (isWinner) {
            val winUpdates = mapOf("board" to updatedBoard, "players" to updatedPlayers, "status" to "FINISHED", "message" to "Team $myTeam Wins!", "winnerTeam" to myTeam)
            db.child("rooms").child(roomCode).updateChildren(winUpdates)
        } else {
            val updates = mapOf("board" to updatedBoard, "deck" to deckList, "players" to updatedPlayers, "turnPlayerId" to nextTurnId, "message" to "$actionMsg $nextPlayerName's turn.")
            db.child("rooms").child(roomCode).updateChildren(updates)
        }
    }

    private fun buildDeck(): List<FBCard> = buildList {
        var id = 0
        repeat(2) {
            for (s in listOf("♠", "♥", "♦", "♣")) {
                for (r in listOf("A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2")) {
                    add(FBCard(s, r, id++))
                }
            }
        }
    }.shuffled()

    private fun buildInitialBoard(): List<FBSpace> {
        val deck = buildDeck().filter { it.rank != "J" }.toMutableList()
        return buildList {
            for (r in 0..9) {
                for (c in 0..9) {
                    if ((r == 0 || r == 9) && (c == 0 || c == 9)) {
                        add(FBSpace(r, c, FBCard("", "★", -1), "NONE"))
                    } else {
                        add(FBSpace(r, c, deck.removeAt(0), "NONE"))
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineSequenceApp(onExit: () -> Unit, viewModel: MultiplayerViewModel = viewModel()) {
    when (viewModel.currentAppState) {
        OnlineAppState.LOBBY -> OnlineLobbyScreen(viewModel, onExit)
        OnlineAppState.ENTER_NAME -> OnlineEnterNameScreen(viewModel)
        OnlineAppState.CREATE_ROOM -> OnlineCreateScreen(viewModel)
        OnlineAppState.JOIN_ROOM -> OnlineJoinScreen(viewModel)
        OnlineAppState.WAITING_ROOM -> OnlineWaitingScreen(viewModel, onExit)
        OnlineAppState.PLAYING -> OnlineGameScreen(viewModel, onExit)
    }
}

@Composable
fun OnlineLobbyScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Online Multiplayer", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 32.dp))
        Button(onClick = { vm.currentAppState = OnlineAppState.ENTER_NAME; vm.lobbyError = "" }, modifier = Modifier.fillMaxWidth(0.6f).padding(8.dp)) { Text("Play Online") }
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 32.dp)) { Text("Back to Main Menu", color = Color.Red) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineEnterNameScreen(vm: MultiplayerViewModel) {
    var nameInput by remember { mutableStateOf(vm.playerName) }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Enter Your Name", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Display Name") }, modifier = Modifier.padding(vertical = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.proceedToCreate(nameInput) }) { Text("Create Room") }
            Button(onClick = { vm.proceedToJoin(nameInput) }) { Text("Join Room") }
        }
        TextButton(onClick = { vm.currentAppState = OnlineAppState.LOBBY }) { Text("Back") }
        if (vm.lobbyError.isNotEmpty()) Text(vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineCreateScreen(vm: MultiplayerViewModel) {
    var password by remember { mutableStateOf("") }
    var teams by remember { mutableStateOf(2) }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Room Code: ${vm.roomCode}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Optional Password") }, modifier = Modifier.padding(vertical = 8.dp))

        Text("Teams: $teams", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3).forEach { tCount ->
                Button(onClick = { teams = tCount }, colors = ButtonDefaults.buttonColors(containerColor = if (teams == tCount) Color.Blue else Color.Gray)) { Text(tCount.toString()) }
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = { vm.executeCreateRoom(password, teams) }) { Text("Open Lobby") }
        TextButton(onClick = { vm.currentAppState = OnlineAppState.ENTER_NAME }) { Text("Cancel") }
        if (vm.lobbyError.isNotEmpty()) Text(vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineJoinScreen(vm: MultiplayerViewModel) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Join Friend's Room", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("4-Digit Room Code") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (if any)") }, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = { vm.joinRoom(code, password) }) { Text("Join Lobby") }
        TextButton(onClick = { vm.currentAppState = OnlineAppState.ENTER_NAME }) { Text("Cancel") }
        if (vm.lobbyError.isNotEmpty()) Text(vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun OnlineWaitingScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    val room by vm.roomData.collectAsState()
    val isHost = room.hostName == vm.playerName

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Room Code: ${room.roomId}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Text("Players Joined (${room.players.size}):", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))

        room.players.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("- ${p.playerName} (Team ${p.team})", fontSize = 16.sp, color = Color.DarkGray, modifier = Modifier.weight(1f))
                if (isHost) {
                    Button(onClick = { vm.changePlayerTeam(p.playerId) }, modifier = Modifier.height(30.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                        Text("Change Team", fontSize = 10.sp)
                    }
                }
            }
        }

        if (vm.lobbyError.isNotEmpty()) Text(vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(24.dp))
        if (isHost) {
            Text("Wait for players, then start.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = { vm.hostStartGame() }) { Text("START GAME NOW") }
        } else {
            CircularProgressIndicator()
            Text("Waiting for host to start...", modifier = Modifier.padding(top = 12.dp))
        }

        TextButton(onClick = { vm.backToLobby(); onExit() }, modifier = Modifier.padding(top = 24.dp)) { Text("Leave Room", color = Color.Red) }
    }
}

@Composable
fun OnlineGameScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    val room by vm.roomData.collectAsState()
    val myPlayer = room.players.firstOrNull { it.playerName == vm.playerName }
    val isMyTurn = myPlayer != null && room.turnPlayerId == myPlayer.playerId && room.status != "FINISHED"
    val myHand = myPlayer?.hand ?: emptyList()
    var selectedCard by remember { mutableStateOf<FBCard?>(null) }

    Column(Modifier.fillMaxSize().padding(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (room.status == "FINISHED") {
                    Text(room.message, fontWeight = FontWeight.Bold, color = Color.Red, fontSize = 22.sp)
                } else {
                    Text(room.message, fontWeight = FontWeight.Bold, color = if (isMyTurn) Color(0xFF07852B) else Color.Gray, fontSize = 16.sp)
                }
                Text("Room: ${room.roomId} | You: ${vm.playerName} (${myPlayer?.team})", fontSize = 12.sp, color = Color.DarkGray)
            }
            TextButton(onClick = { vm.backToLobby(); onExit() }) { Text("Exit", color = Color.Red) }
        }

        if (room.board.isNotEmpty()) {
            LazyVerticalGrid(columns = GridCells.Fixed(10), modifier = Modifier.weight(1f)) {
                items(room.board) { space ->
                    val isTwoEyed = selectedCard?.isTwoEyedJack == true
                    val isOneEyed = selectedCard?.isOneEyedJack == true
                    val myTeam = myPlayer?.team ?: "BLUE"

                    val isLegalMove = selectedCard != null && space.card.rank != "★" && when {
                        isTwoEyed -> space.occupant == "NONE"
                        isOneEyed -> space.occupant != "NONE" && space.occupant != myTeam && !space.isCompletedSequence
                        else -> space.occupant == "NONE" && space.card.matches(selectedCard!!)
                    }

                    val bg = when {
                        space.card.rank == "★" -> Color(0xFFFFD75E)
                        isLegalMove -> Color(0xFF9EE6AC)
                        space.isCompletedSequence -> {
                            when (space.occupant) {
                                "BLUE" -> Color(0xFF1976D2).copy(alpha = 0.18f)
                                "GREEN" -> Color(0xFF159447).copy(alpha = 0.18f)
                                else -> Color(0xFFD32F2F).copy(alpha = 0.18f)
                            }
                        }
                        else -> Color.White
                    }

                    Box(modifier = Modifier.aspectRatio(0.68f).padding(1.dp).background(bg).border(if (isLegalMove || space.isCompletedSequence) 2.dp else 1.dp, if (isLegalMove) Color(0xFF07852B) else Color.LightGray).clickable(enabled = isLegalMove && isMyTurn) {
                        vm.playCard(selectedCard!!, space.r, space.c)
                        selectedCard = null
                    }) {
                        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(space.card.rank, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (space.card.suit == "♥" || space.card.suit == "♦") Color.Red else Color.Black)
                            Text(space.card.suit, fontSize = 9.sp, color = if (space.card.suit == "♥" || space.card.suit == "♦") Color.Red else Color.Black)
                        }
                        if (space.occupant != "NONE") {
                            val chipColor = when (space.occupant) {
                                "BLUE" -> Color(0xFF1976D2)
                                "GREEN" -> Color(0xFF159447)
                                else -> Color(0xFFD32F2F)
                            }
                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).fillMaxWidth(0.7f).aspectRatio(1f).clip(CircleShape).background(chipColor).border(if(space.isCompletedSequence) 2.dp else 1.dp, if(space.isCompletedSequence) Color.White else Color.Black, CircleShape))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            myHand.forEach { card ->
                val selected = card == selectedCard
                Card(modifier = Modifier.weight(1f).padding(2.dp).fillMaxHeight().border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF07852B) else Color.LightGray, MaterialTheme.shapes.small).clickable(enabled = isMyTurn) { selectedCard = if (selectedCard == card) null else card }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(card.rank, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (card.suit == "♥" || card.suit == "♦") Color.Red else Color.Black)
                        Text(card.suit, fontSize = 14.sp, color = if (card.suit == "♥" || card.suit == "♦") Color.Red else Color.Black)
                        when {
                            card.isTwoEyedJack -> Text("WILD", fontSize = 7.sp, color = Color.Blue, fontWeight = FontWeight.Bold)
                            card.isOneEyedJack -> Text("REMOVE", fontSize = 7.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
