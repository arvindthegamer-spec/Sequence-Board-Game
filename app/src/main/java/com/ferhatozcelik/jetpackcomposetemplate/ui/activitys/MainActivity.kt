package com.ferhatozcelik.jetpackcomposetemplate.ui.activitys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
// 1. MAIN MENU (Entry Point)
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
    Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("SEQUENCE", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Text("Board Game", fontSize = 20.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 48.dp))

        Button(onClick = onPlayLocal, modifier = Modifier.fillMaxWidth(0.7f).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07852B))) {
            Text("Play Local (Pass & Play / CPU)", fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(onClick = onPlayOnline, modifier = Modifier.fillMaxWidth(0.7f).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) {
            Text("Play Online (With a Friend)", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(64.dp))
        
        Text("Developed by Aravind Valluri", fontSize = 15.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
    }
}

// ==============================================================================
// 2. OFFLINE CORE GAME (Untouched Original Logic)
// ==============================================================================

enum class Suit(val symbol: String, val color: Color) {
    SPADES("♠", Color.Black), HEARTS("♥", Color(0xFFC62828)),
    DIAMONDS("♦", Color(0xFFC62828)), CLUBS("♣", Color.Black), NONE("", Color.Transparent)
}
enum class Rank(val text: String) {
    A("A"), K("K"), Q("Q"), J("J"), TEN("10"), NINE("9"), EIGHT("8"), SEVEN("7"), SIX("6"),
    FIVE("5"), FOUR("4"), THREE("3"), TWO("2"), CORNER("★")
}
enum class TeamColor(val uiColor: Color) { NONE(Color.Transparent), BLUE(Color(0xFF1976D2)), GREEN(Color(0xFF159447)), RED(Color(0xFFD32F2F)) }

data class PlayingCard(val suit: Suit, val rank: Rank, val uniqueId: Int) {
    val isTwoEyedJack: Boolean get() = rank == Rank.J && (suit == Suit.DIAMONDS || suit == Suit.CLUBS)
    val isOneEyedJack: Boolean get() = rank == Rank.J && (suit == Suit.SPADES || suit == Suit.HEARTS)
    fun matches(other: PlayingCard): Boolean = suit == other.suit && rank == other.rank
}
data class Player(val id: Int, val team: TeamColor, val isCpu: Boolean, val hand: List<PlayingCard> = emptyList())
data class BoardSpace(val row: Int, val col: Int, val card: PlayingCard, val occupant: TeamColor = TeamColor.NONE, val isHighlighted: Boolean = false, val isCompletedSequence: Boolean = false)
data class CompletedLine(val team: TeamColor, val positions: Set<Pair<Int, Int>>)
enum class GameState { SETUP, PASS_DEVICE, PLAYING, FINISHED }

class GameViewModel : ViewModel() {
    private val random = Random.Default
    private var deck = mutableListOf<PlayingCard>()
    private var players: List<Player> = emptyList()
    private var cpuJob: Job? = null

    var currentPlayerIndex by mutableStateOf(0); private set
    var currentGameState by mutableStateOf(GameState.SETUP); private set
    var gameMessage by mutableStateOf("Choose game settings."); private set
    var humanCount by mutableStateOf(1); private set
    var numberOfTeams by mutableStateOf(2); private set
    var winnerTeam by mutableStateOf<TeamColor?>(null); private set
    var isDraw by mutableStateOf(false); private set

    private val _board = MutableStateFlow<List<List<BoardSpace>>>(emptyList())
    val board: StateFlow<List<List<BoardSpace>>> = _board.asStateFlow()
    private val _selectedCardId = MutableStateFlow<Int?>(null)
    val selectedCardId: StateFlow<Int?> = _selectedCardId.asStateFlow()
    private val _sequenceCounts = MutableStateFlow<Map<TeamColor, Int>>(emptyMap())
    val sequenceCounts: StateFlow<Map<TeamColor, Int>> = _sequenceCounts.asStateFlow()

    val currentPlayer: Player get() = players.getOrElse(currentPlayerIndex) { Player(0, TeamColor.NONE, false) }
    private val requiredSequences: Int get() = if (numberOfTeams == 2) 2 else 1

    fun setupGame(totalPlayers: Int, humans: Int, requestedTeams: Int) {
        cpuJob?.cancel()
        humanCount = humans
        numberOfTeams = requestedTeams
        winnerTeam = null; isDraw = false; currentPlayerIndex = 0; _selectedCardId.value = null

        deck = buildTwoDecks().shuffled(random).toMutableList()
        val boardCards = buildBoardCards().shuffled(random).toMutableList()
        _board.value = List(10) { row -> List(10) { col ->
            val corner = (row == 0 || row == 9) && (col == 0 || col == 9)
            BoardSpace(row, col, if (corner) PlayingCard(Suit.NONE, Rank.CORNER, -1 - row * 10 - col) else boardCards.removeFirst())
        }}

        val teams = if (requestedTeams == 2) listOf(TeamColor.BLUE, TeamColor.GREEN) else listOf(TeamColor.BLUE, TeamColor.GREEN, TeamColor.RED)
        players = List(totalPlayers) { index -> Player(index + 1, teams[index % requestedTeams], index >= humans) }
        
        val handSize = when (totalPlayers) { 2->7; 3,4->6; 6->5; 8,9->4; 10,12->3; else->5 }
        repeat(handSize) { players = players.map { p -> p.copy(hand = p.hand + listOfNotNull(drawOneCard())) } }
        
        _sequenceCounts.value = teams.associateWith { 0 }
        currentGameState = GameState.PLAYING
        gameMessage = if (requiredSequences == 2) "First team to complete 2 sequences wins." else "First team to complete 1 sequence wins."
        if (currentPlayer.isCpu) startCpuTurn()
    }

    private fun buildTwoDecks(): List<PlayingCard> = buildList { var id = 0; repeat(2) { for (suit in Suit.entries.filter { it != Suit.NONE }) for (rank in Rank.entries.filter { it != Rank.CORNER }) add(PlayingCard(suit, rank, id++)) } }
    private fun buildBoardCards(): List<PlayingCard> = buildList { var id = 10000; repeat(2) { for (suit in Suit.entries.filter { it != Suit.NONE }) for (rank in Rank.entries.filter { it != Rank.J && it != Rank.CORNER }) add(PlayingCard(suit, rank, id++)) } }
    private fun drawOneCard(): PlayingCard? = if (deck.isEmpty()) null else deck.removeFirst()

    fun selectCard(cardId: Int) {
        if (currentGameState != GameState.PLAYING || currentPlayer.isCpu) return
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return
        _selectedCardId.value = cardId
        _board.value = _board.value.map { row -> row.map { space -> space.copy(isHighlighted = isLegalDestination(card, space, currentPlayer.team)) } }
        val count = _board.value.flatten().count { it.isHighlighted }
        gameMessage = if (count == 0) "No legal position. Replace this dead card." else "$count legal move(s) highlighted."
    }

    private fun isLegalDestination(card: PlayingCard, space: BoardSpace, team: TeamColor): Boolean {
        if (space.card.rank == Rank.CORNER) return false
        return when {
            card.isTwoEyedJack -> space.occupant == TeamColor.NONE
            card.isOneEyedJack -> space.occupant != TeamColor.NONE && space.occupant != team && !space.isCompletedSequence
            else -> space.occupant == TeamColor.NONE && space.card.matches(card)
        }
    }

    fun humanPlaceToken(row: Int, col: Int) {
        if (currentGameState != GameState.PLAYING || currentPlayer.isCpu) return
        val cardId = _selectedCardId.value ?: return
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return
        val space = _board.value.getOrNull(row)?.getOrNull(col) ?: return
        if (space.isHighlighted && isLegalDestination(card, space, currentPlayer.team)) executeMove(row, col, card)
    }

    private fun executeMove(row: Int, col: Int, cardUsed: PlayingCard) {
        val movingPlayer = currentPlayer; val target = _board.value[row][col]
        val newTarget = if (cardUsed.isOneEyedJack) target.copy(occupant = TeamColor.NONE, isHighlighted = false, isCompletedSequence = false) else target.copy(occupant = movingPlayer.team, isHighlighted = false)
        _board.value = _board.value.mapIndexed { r, rList -> rList.mapIndexed { c, sp -> if (r == row && c == col) newTarget else sp.copy(isHighlighted = false) } }
        
        val newHand = movingPlayer.hand.filterNot { it.uniqueId == cardUsed.uniqueId }.toMutableList()
        drawOneCard()?.let(newHand::add)
        players = players.map { if (it.id == movingPlayer.id) it.copy(hand = newHand) else it }
        _selectedCardId.value = null
        
        gameMessage = if (cardUsed.isOneEyedJack) "Player ${movingPlayer.id} removed a chip." else "Player ${movingPlayer.id} placed ${cardUsed.rank.text}${cardUsed.suit.symbol}."
        
        if (!cardUsed.isOneEyedJack && updateSequencesAndCheckWinner(movingPlayer.team)) return
        if (checkForDraw()) return
        advanceTurn()
    }

    fun replaceSelectedDeadCard() {
        val cardId = _selectedCardId.value ?: return
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return
        if (card.rank == Rank.J || _board.value.flatten().filter { it.card.matches(card) }.any { it.occupant == TeamColor.NONE }) {
            gameMessage = "That card is not dead."; return
        }
        val newHand = currentPlayer.hand.filterNot { it.uniqueId == card.uniqueId }.toMutableList()
        drawOneCard()?.let(newHand::add)
        players = players.map { if (it.id == currentPlayer.id) it.copy(hand = newHand) else it }
        _selectedCardId.value = null
        _board.value = _board.value.map { row -> row.map { it.copy(isHighlighted = false) } }
        gameMessage = "Dead card replaced. Normal turn."
    }

    private fun updateSequencesAndCheckWinner(team: TeamColor): Boolean {
        val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        val candidates = mutableListOf<CompletedLine>()
        for (r in 0..9) for (c in 0..9) for ((dr, dc) in directions) {
            val positions = (0..4).map { r + dr * it to c + dc * it }
            if (positions.none { (pr, pc) -> pr !in 0..9 || pc !in 0..9 } && positions.all { (pr, pc) -> val sp = _board.value[pr][pc]; sp.card.rank == Rank.CORNER || sp.occupant == team }) {
                candidates += CompletedLine(team, positions.toSet())
            }
        }
        val accepted = mutableListOf<CompletedLine>()
        for (cand in candidates) if (accepted.all { prev -> cand.positions.intersect(prev.positions).size <= 1 }) accepted += cand
        
        _sequenceCounts.value = _sequenceCounts.value.toMutableMap().apply { this[team] = accepted.size }
        val protected = accepted.flatMap { it.positions }.toSet()
        _board.value = _board.value.mapIndexed { r, rl -> rl.mapIndexed { c, sp -> if (sp.occupant == team && (r to c) in protected) sp.copy(isCompletedSequence = true) else sp } }
        
        if (accepted.size >= requiredSequences) { winnerTeam = team; currentGameState = GameState.FINISHED; return true }
        return false
    }

    private fun checkForDraw(): Boolean {
        if (winnerTeam != null) return false
        if (players.all { it.hand.isEmpty() } || (deck.isEmpty() && players.none { p -> _board.value.flatten().any { sp -> p.hand.any { c -> isLegalDestination(c, sp, p.team) } } })) {
            isDraw = true; currentGameState = GameState.FINISHED; gameMessage = "Draw game."
            return true
        }
        return false
    }

    private fun advanceTurn() {
        if (currentGameState == GameState.FINISHED) return
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        _selectedCardId.value = null
        _board.value = _board.value.map { row -> row.map { it.copy(isHighlighted = false) } }
        if (currentPlayer.isCpu) { currentGameState = GameState.PLAYING; startCpuTurn() }
        else if (humanCount > 1) { currentGameState = GameState.PASS_DEVICE; gameMessage = "Pass the device." }
        else { currentGameState = GameState.PLAYING; gameMessage = "Player ${currentPlayer.id}'s turn." }
    }

    fun confirmPassDevice() { currentGameState = GameState.PLAYING; gameMessage = "Player ${currentPlayer.id}'s turn." }

    private fun startCpuTurn() {
        cpuJob?.cancel(); cpuJob = viewModelScope.launch {
            delay(1000)
            if (currentGameState != GameState.PLAYING || !currentPlayer.isCpu) return@launch
            val cpu = currentPlayer
            val legalMoves = cpu.hand.flatMap { card -> _board.value.flatten().filter { isLegalDestination(card, it, cpu.team) }.map { card to it } }
            if (legalMoves.isNotEmpty()) {
                val (c, s) = legalMoves.random(random); executeMove(s.row, s.col, c)
            } else advanceTurn()
        }
    }
    fun newGame() { currentGameState = GameState.SETUP }
}

@Composable
fun OfflineSequenceApp(onExit: () -> Unit, vm: GameViewModel = viewModel()) {
    when (vm.currentGameState) {
        GameState.SETUP -> OfflineSetupScreen(vm, onExit)
        GameState.PASS_DEVICE -> OfflinePassDeviceScreen(vm)
        GameState.PLAYING -> OfflineGameScreen(vm, onExit)
        GameState.FINISHED -> OfflineFinishedScreen(vm, onExit)
    }
}

@Composable
fun OfflineSetupScreen(vm: GameViewModel, onExit: () -> Unit) {
    var totalPlayers by remember { mutableStateOf(2) }; var humans by remember { mutableStateOf(1) }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Local Game Setup", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3, 4).forEach { count ->
                Button(onClick = { totalPlayers = count; if(humans > count) humans = count }, colors = ButtonDefaults.buttonColors(containerColor = if (totalPlayers == count) Color.Blue else Color.Gray)) { Text(count.toString()) }
            }
        }
        Text("Human Players: $humans", modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..totalPlayers).forEach { count ->
                Button(onClick = { humans = count }, colors = ButtonDefaults.buttonColors(containerColor = if (humans == count) Color.Blue else Color.Gray)) { Text(count.toString()) }
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = { vm.setupGame(totalPlayers, humans, 2) }) { Text("START LOCAL GAME") }
        TextButton(onClick = onExit, modifier = Modifier.padding(top=16.dp)) { Text("Back to Main Menu", color = Color.Red) }
    }
}

@Composable
fun OfflinePassDeviceScreen(vm: GameViewModel) {
    val next = vm.currentPlayer
    Column(Modifier.fillMaxSize().background(next.team.uiColor.copy(alpha = 0.2f)).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(vm.gameMessage, color = Color.DarkGray); Spacer(Modifier.height(28.dp))
        Text("Pass to Player ${next.id}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = next.team.uiColor)
        Spacer(Modifier.height(40.dp))
        Button(onClick = vm::confirmPassDevice) { Text("I'M READY", fontSize = 19.sp) }
    }
}

@Composable
fun OfflineGameScreen(vm: GameViewModel, onExit: () -> Unit) {
    val board by vm.board.collectAsState()
    val selectedCardId by vm.selectedCardId.collectAsState()
    val player = vm.currentPlayer

    Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4)).padding(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (player.isCpu) "CPU ${player.id} is thinking..." else "Player ${player.id}'s turn", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = player.team.uiColor)
                Text(vm.gameMessage, fontSize = 12.sp, color = Color.DarkGray)
            }
            TextButton(onClick = onExit) { Text("Exit", color = Color.Red) }
        }

        if (board.isNotEmpty()) {
            LazyVerticalGrid(columns = GridCells.Fixed(10), modifier = Modifier.fillMaxWidth().weight(1f), userScrollEnabled = false) {
                items(board.flatten()) { space ->
                    val bg = if(space.card.rank==Rank.CORNER) Color(0xFFFFD75E) else if(space.isHighlighted) Color(0xFF9EE6AC) else Color.White
                    Box(modifier = Modifier.aspectRatio(0.68f).background(bg).border(1.dp, Color.Gray).clickable(enabled = space.isHighlighted) { vm.humanPlaceToken(space.row, space.col) }.padding(1.dp)) {
                        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(space.card.rank.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = space.card.suit.color)
                            Text(space.card.suit.symbol, fontSize = 9.sp, color = space.card.suit.color)
                        }
                        if (space.occupant != TeamColor.NONE) {
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.7f).aspectRatio(1f).clip(CircleShape).background(space.occupant.uiColor).border(1.dp, Color.Black, CircleShape))
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(4.dp))
        if (!player.isCpu) {
            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                player.hand.forEach { card ->
                    val selected = card.uniqueId == selectedCardId
                    Card(modifier = Modifier.weight(1f).padding(2.dp).fillMaxHeight().border(if (selected) 2.dp else 1.dp, if (selected) Color.Green else Color.LightGray).clickable { vm.selectCard(card.uniqueId) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(card.rank.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = card.suit.color)
                            Text(card.suit.symbol, fontSize = 14.sp, color = card.suit.color)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OfflineFinishedScreen(vm: GameViewModel, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if(vm.isDraw) "DRAW" else "WINNER: ${vm.winnerTeam}", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Button(onClick = vm::newGame, modifier = Modifier.padding(top=32.dp)) { Text("PLAY AGAIN") }
        TextButton(onClick = onExit, modifier = Modifier.padding(top=16.dp)) { Text("Back to Main Menu", color = Color.Red) }
    }
}


// ==============================================================================
// 3. ONLINE MULTIPLAYER (Firebase Integration)
// ==============================================================================

data class FBCard(val suit: String = "", val rank: String = "", val id: Int = 0)
data class FBSpace(val r: Int = 0, val c: Int = 0, val card: FBCard = FBCard(), val occupant: String = "NONE")
data class GameRoom(
    var roomId: String = "", var password: String = "", var status: String = "WAITING",
    var turn: Int = 1, var message: String = "Waiting for Player 2...",
    var board: List<FBSpace> = emptyList(), var p1Hand: List<FBCard> = emptyList(),
    var p2Hand: List<FBCard> = emptyList(), var deck: List<FBCard> = emptyList()
)

enum class OnlineAppState { LOBBY, CREATE, JOIN, WAITING, PLAYING }

class MultiplayerViewModel : ViewModel() {
    private val db = Firebase.database.reference
    var currentAppState by mutableStateOf(OnlineAppState.LOBBY)
    var myPlayerNumber by mutableStateOf(1)
    var roomCode by mutableStateOf("")
    var roomPassword by mutableStateOf("")
    var lobbyError by mutableStateOf("")

    private val _roomData = MutableStateFlow(GameRoom())
    val roomData: StateFlow<GameRoom> = _roomData.asStateFlow()
    private var roomListener: ValueEventListener? = null

    fun backToLobby() { currentAppState = OnlineAppState.LOBBY; roomListener?.let { db.child("rooms").child(roomCode).removeEventListener(it) } }

    fun createRoom(password: String) {
        lobbyError = ""; val newCode = Random.nextInt(1000, 9999).toString()
        roomCode = newCode; roomPassword = password; myPlayerNumber = 1
        
        val initialRoom = GameRoom(roomId = newCode, password = password, status = "WAITING", board = buildInitialBoard(), deck = buildDeck())
        db.child("rooms").child(newCode).setValue(initialRoom).addOnSuccessListener {
            listenToRoom(newCode); currentAppState = OnlineAppState.WAITING
        }.addOnFailureListener { lobbyError = "Connection failed." }
    }

    fun joinRoom(code: String, password: String) {
        lobbyError = "Checking room..."
        db.child("rooms").child(code).get().addOnSuccessListener { snapshot ->
            val room = snapshot.getValue(GameRoom::class.java)
            if (room == null) lobbyError = "Room not found."
            else if (room.password != password) lobbyError = "Wrong password."
            else if (room.status != "WAITING") lobbyError = "Room is full/started."
            else { roomCode = code; myPlayerNumber = 2; dealStartingHands(room) }
        }.addOnFailureListener { lobbyError = "Database error." }
    }

    private fun dealStartingHands(room: GameRoom) {
        val currentDeck = room.deck.toMutableList(); val p1Cards = mutableListOf<FBCard>(); val p2Cards = mutableListOf<FBCard>()
        repeat(7) { if(currentDeck.isNotEmpty()) p1Cards.add(currentDeck.removeAt(0)); if(currentDeck.isNotEmpty()) p2Cards.add(currentDeck.removeAt(0)) }
        val updates = mapOf("p1Hand" to p1Cards, "p2Hand" to p2Cards, "deck" to currentDeck, "status" to "PLAYING", "message" to "Game Started! Player 1's Turn.")
        db.child("rooms").child(roomCode).updateChildren(updates).addOnSuccessListener { listenToRoom(roomCode); currentAppState = OnlineAppState.PLAYING }
    }

    private fun listenToRoom(code: String) {
        roomListener?.let { db.child("rooms").child(code).removeEventListener(it) }
        roomListener = db.child("rooms").child(code).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(GameRoom::class.java)
                if (room != null) {
                    _roomData.value = room
                    if (room.status == "PLAYING" && currentAppState == OnlineAppState.WAITING) currentAppState = OnlineAppState.PLAYING
                }
            }
            override fun onCancelled(error: DatabaseError) { lobbyError = "Connection lost." }
        })
    }

    fun playCard(card: FBCard, row: Int, col: Int) {
        val currentRoom = _roomData.value; if (currentRoom.turn != myPlayerNumber) return
        val updatedBoard = currentRoom.board.toMutableList()
        val targetIndex = updatedBoard.indexOfFirst { it.r == row && it.c == col }
        
        if (targetIndex != -1) {
            val teamColor = if (myPlayerNumber == 1) "BLUE" else "GREEN"
            updatedBoard[targetIndex] = updatedBoard[targetIndex].copy(occupant = teamColor)
            val myHand = if (myPlayerNumber == 1) currentRoom.p1Hand.toMutableList() else currentRoom.p2Hand.toMutableList()
            myHand.remove(card)
            val deck = currentRoom.deck.toMutableList(); if (deck.isNotEmpty()) myHand.add(deck.removeAt(0))
            val nextTurn = if (myPlayerNumber == 1) 2 else 1
            
            val updates = mutableMapOf<String, Any>("board" to updatedBoard, "deck" to deck, "turn" to nextTurn, "message" to "Player $nextTurn's Turn.")
            if (myPlayerNumber == 1) updates["p1Hand"] = myHand else updates["p2Hand"] = myHand
            db.child("rooms").child(roomCode).updateChildren(updates)
        }
    }

    private fun buildDeck(): List<FBCard> = buildList { var id=0; repeat(2) { for(s in listOf("♠","♥","♦","♣")) for(r in listOf("A","K","Q","J","10","9","8","7","6","5","4","3","2")) add(FBCard(s,r,id++)) } }.shuffled()
    private fun buildInitialBoard(): List<FBSpace> {
        val deck = buildDeck().filter { it.rank != "J" }.toMutableList()
        return buildList { for(r in 0..9) for(c in 0..9) if ((r==0||r==9)&&(c==0||c==9)) add(FBSpace(r,c,FBCard("","★",-1),"NONE")) else add(FBSpace(r,c,deck.removeAt(0),"NONE")) }
    }
}

@Composable
fun OnlineSequenceApp(onExit: () -> Unit, viewModel: MultiplayerViewModel = viewModel()) {
    when (viewModel.currentAppState) {
        OnlineAppState.LOBBY -> OnlineLobbyScreen(viewModel, onExit)
        OnlineAppState.CREATE -> OnlineCreateScreen(viewModel)
        OnlineAppState.JOIN -> OnlineJoinScreen(viewModel)
        OnlineAppState.WAITING -> OnlineWaitingScreen(viewModel, onExit)
        OnlineAppState.PLAYING -> OnlineGameScreen(viewModel, onExit)
    }
}

@Composable
fun OnlineLobbyScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Online Multiplayer", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 32.dp))
        Button(onClick = { vm.currentAppState = OnlineAppState.CREATE }, modifier = Modifier.fillMaxWidth(0.6f).padding(8.dp)) { Text("Create Room") }
        Button(onClick = { vm.currentAppState = OnlineAppState.JOIN }, modifier = Modifier.fillMaxWidth(0.6f).padding(8.dp)) { Text("Join Room") }
        TextButton(onClick = onExit, modifier = Modifier.padding(top=32.dp)) { Text("Back to Main Menu", color = Color.Red) }
    }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OnlineCreateScreen(vm: MultiplayerViewModel) {
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Create Private Room", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Set Password") }, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = { vm.createRoom(password) }) { Text("Create & Wait") }
        Button(onClick = { vm.backToLobby() }, modifier = Modifier.padding(top=16.dp)) { Text("Cancel") }
        if (vm.lobbyError.isNotEmpty()) Text(vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
    }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OnlineJoinScreen(vm: MultiplayerViewModel) {
    var code by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Join Friend's Room", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("4-Digit Room Code") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = { vm.joinRoom(code, password) }) { Text("Join Game") }
        Button(onClick = { vm.backToLobby() }, modifier = Modifier.padding(top=16.dp)) { Text("Cancel") }
        if (vm.lobbyError.isNotEmpty()) Text(vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun OnlineWaitingScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Room Code: ${vm.roomCode}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Password: ${vm.roomPassword}", fontSize = 20.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        CircularProgressIndicator()
        Text("Waiting for Player 2...", modifier = Modifier.padding(top = 16.dp))
        TextButton(onClick = { vm.backToLobby(); onExit() }, modifier = Modifier.padding(top=32.dp)) { Text("Cancel and Leave", color = Color.Red) }
    }
}

@Composable
fun OnlineGameScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    val room by vm.roomData.collectAsState()
    val isMyTurn = room.turn == vm.myPlayerNumber
    val myHand = if (vm.myPlayerNumber == 1) room.p1Hand else room.p2Hand
    var selectedCard by remember { mutableStateOf<FBCard?>(null) }

    Column(Modifier.fillMaxSize().padding(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(room.message, fontWeight = FontWeight.Bold, color = if (isMyTurn) Color.Blue else Color.Gray, fontSize = 18.sp)
                Text("Room: ${room.roomId} | You are Player ${vm.myPlayerNumber}", fontSize = 12.sp, color = Color.DarkGray)
            }
            TextButton(onClick = { vm.backToLobby(); onExit() }) { Text("Exit", color = Color.Red) }
        }

        if (room.board.isNotEmpty()) {
            LazyVerticalGrid(columns = GridCells.Fixed(10), modifier = Modifier.weight(1f)) {
                items(room.board) { space ->
                    val isLegalMove = selectedCard != null && space.occupant == "NONE" && space.card.rank == selectedCard!!.rank && space.card.suit == selectedCard!!.suit
                    Box(modifier = Modifier.aspectRatio(0.68f).padding(1.dp).background(if(space.card.rank=="★") Color(0xFFFFD75E) else Color.White).border(if (isLegalMove) 2.dp else 1.dp, if (isLegalMove) Color.Green else Color.LightGray).clickable(enabled = isLegalMove && isMyTurn) { vm.playCard(selectedCard!!, space.r, space.c); selectedCard = null }) {
                        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(space.card.rank, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (space.card.suit == "♥" || space.card.suit == "♦") Color.Red else Color.Black)
                            Text(space.card.suit, fontSize = 10.sp, color = if (space.card.suit == "♥" || space.card.suit == "♦") Color.Red else Color.Black)
                        }
                        if (space.occupant != "NONE") Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).fillMaxWidth(0.7f).aspectRatio(1f).clip(CircleShape).background(if (space.occupant == "BLUE") Color.Blue else Color.Green).border(1.dp, Color.Black, CircleShape))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            myHand.forEach { card ->
                val selected = card == selectedCard
                Card(modifier = Modifier.weight(1f).padding(2.dp).fillMaxHeight().border(if (selected) 3.dp else 1.dp, if (selected) Color.Green else Color.LightGray, MaterialTheme.shapes.small).clickable(enabled = isMyTurn) { selectedCard = if (selectedCard == card) null else card }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(card.rank, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (card.suit == "♥" || card.suit == "♦") Color.Red else Color.Black)
                        Text(card.suit, fontSize = 14.sp, color = if (card.suit == "♥" || card.suit == "♦") Color.Red else Color.Black)
                    }
                }
            }
        }
    }
}
