package com.ferhatozcelik.jetpackcomposetemplate.ui.activitys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ==========================================
// 1. GAME DATA MODELS
// ==========================================
enum class Suit(val symbol: String, val color: Color) {
    SPADES("♠", Color.Black), HEARTS("♥", Color.Red),
    DIAMONDS("♦", Color.Red), CLUBS("♣", Color.Black), NONE("", Color.Transparent)
}
enum class Rank(val text: String) {
    A("A"), K("K"), Q("Q"), J("J"), `10`("10"), `9`("9"), `8`("8"),
    `7`("7"), `6`("6"), `5`("5"), `4`("4"), `3`("3"), `2`("2"), CORNER("⭐")
}
enum class TeamColor(val uiColor: Color) { 
    NONE(Color.Transparent), BLUE(Color.Blue), GREEN(Color(0xFF00AA00)), RED(Color.Red), YELLOW(Color(0xFFDDDD00)) 
}

data class Card(val suit: Suit, val rank: Rank) {
    val isTwoEyedJack: Boolean get() = rank == Rank.J && (suit == Suit.DIAMONDS || suit == Suit.CLUBS)
    val isOneEyedJack: Boolean get() = rank == Rank.J && (suit == Suit.SPADES || suit == Suit.HEARTS)
}

data class Player(val id: Int, val team: TeamColor, val isCpu: Boolean, var hand: List<Card> = emptyList())

data class BoardSpace(
    val row: Int, val col: Int, val card: Card,
    var occupant: TeamColor = TeamColor.NONE,
    var isHighlighted: Boolean = false
)

enum class GameState { SETUP, PASS_DEVICE, PLAYING }

// ==========================================
// 2. GAME LOGIC (VIEWMODEL)
// ==========================================
class GameViewModel : ViewModel() {
    private var deck = mutableListOf<Card>()
    private var players = listOf<Player>()
    var currentPlayerIndex by mutableStateOf(0)
    
    var currentGameState by mutableStateOf(GameState.SETUP)
    var gameMessage by mutableStateOf("Game Started")
    var humanCount by mutableStateOf(0)

    private val _board = MutableStateFlow<List<List<BoardSpace>>>(emptyList())
    val board: StateFlow<List<List<BoardSpace>>> = _board.asStateFlow()

    private val _selectedCard = MutableStateFlow<Card?>(null)
    val selectedCard: StateFlow<Card?> = _selectedCard.asStateFlow()

    val currentPlayer: Player get() = players[currentPlayerIndex]

    // --- SETUP ---
    fun setupGame(totalPlayers: Int, humans: Int) {
        val handSize = when (totalPlayers) { 2 -> 7; 3, 4 -> 6; else -> 5 }
        humanCount = humans

        deck.clear()
        for (i in 1..2) {
            Suit.entries.filter { it != Suit.NONE }.forEach { suit ->
                Rank.entries.filter { it != Rank.CORNER }.forEach { rank -> deck.add(Card(suit, rank)) }
            }
        }
        deck.shuffle()

        val boardCards = deck.filter { it.rank != Rank.J }.toMutableList()
        val newBoard = List(10) { row -> List(10) { col ->
            if ((row == 0 || row == 9) && (col == 0 || col == 9)) BoardSpace(row, col, Card(Suit.NONE, Rank.CORNER))
            else BoardSpace(row, col, boardCards.removeFirst())
        }}
        _board.value = newBoard

        val teamColors = listOf(TeamColor.BLUE, TeamColor.GREEN, TeamColor.RED, TeamColor.YELLOW)
        players = List(totalPlayers) { index ->
            val isCpu = index >= humans
            Player(id = index + 1, team = teamColors[index], isCpu = isCpu, hand = drawCards(handSize))
        }

        currentPlayerIndex = 0
        currentGameState = GameState.PLAYING
    }

    private fun drawCards(amount: Int): List<Card> {
        val drawn = mutableListOf<Card>()
        repeat(amount) { if (deck.isNotEmpty()) drawn.add(deck.removeFirst()) }
        return drawn
    }

    // --- HUMAN HINTS & MOVES ---
    fun selectCard(card: Card) {
        if (currentPlayer.isCpu) return
        _selectedCard.value = card
        val currentBoard = _board.value.map { row -> row.map { it.copy() } }
        
        currentBoard.forEach { row -> row.forEach { it.isHighlighted = false } }

        currentBoard.forEach { row ->
            row.forEach { space ->
                if (space.card.rank == Rank.CORNER) return@forEach
                if (card.isTwoEyedJack && space.occupant == TeamColor.NONE) space.isHighlighted = true
                else if (card.isOneEyedJack && space.occupant != TeamColor.NONE && space.occupant != currentPlayer.team) space.isHighlighted = true
                else if (!card.isTwoEyedJack && !card.isOneEyedJack && space.card == card && space.occupant == TeamColor.NONE) space.isHighlighted = true
            }
        }
        _board.value = currentBoard
    }

    fun humanPlaceToken(row: Int, col: Int) {
        val cardUsed = _selectedCard.value ?: return
        if (_board.value[row][col].isHighlighted) {
            executeMove(row, col, cardUsed)
        }
    }

    // --- CORE MOVE EXECUTION ---
    private fun executeMove(row: Int, col: Int, cardUsed: Card) {
        val currentBoard = _board.value.map { r -> r.map { it.copy() } }
        val targetSpace = currentBoard[row][col]

        if (cardUsed.isOneEyedJack) targetSpace.occupant = TeamColor.NONE
        else targetSpace.occupant = currentPlayer.team

        currentBoard.forEach { r -> r.forEach { it.isHighlighted = false } }
        _board.value = currentBoard

        val updatedHand = currentPlayer.hand.toMutableList()
        updatedHand.remove(cardUsed)
        if (deck.isNotEmpty()) updatedHand.add(deck.removeFirst())
        players[currentPlayerIndex].hand = updatedHand
        
        _selectedCard.value = null
        
        val playerType = if (currentPlayer.isCpu) "CPU" else "Human"
        gameMessage = "Player ${currentPlayer.id} ($playerType) played ${cardUsed.rank.text}${cardUsed.suit.symbol}"

        advanceTurn()
    }

    // --- TURN ROTATION & PASS DEVICE ---
    private fun advanceTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        
        if (currentPlayer.isCpu) {
            currentGameState = GameState.PLAYING
            playCpuTurn()
        } else {
            // Only show Pass Device screen if there are multiple humans playing
            if (humanCount > 1) {
                currentGameState = GameState.PASS_DEVICE
            } else {
                currentGameState = GameState.PLAYING
            }
        }
    }

    // --- CPU LOGIC ---
    private fun playCpuTurn() = viewModelScope.launch {
        delay(1500) 
        val currentBoard = _board.value
        var moveMade = false

        for (card in currentPlayer.hand.shuffled()) {
            val flatBoard = currentBoard.flatten()
            
            if (card.isTwoEyedJack) {
                val validSpaces = flatBoard.filter { it.occupant == TeamColor.NONE && it.card.rank != Rank.CORNER }
                if (validSpaces.isNotEmpty()) {
                    val space = validSpaces.random()
                    executeMove(space.row, space.col, card)
                    moveMade = true; break
                }
            } else if (card.isOneEyedJack) {
                val validSpaces = flatBoard.filter { it.occupant != TeamColor.NONE && it.occupant != currentPlayer.team }
                if (validSpaces.isNotEmpty()) {
                    val space = validSpaces.random()
                    executeMove(space.row, space.col, card)
                    moveMade = true; break
                }
            } else {
                val validSpaces = flatBoard.filter { it.card == card && it.occupant == TeamColor.NONE }
                if (validSpaces.isNotEmpty()) {
                    val space = validSpaces.random()
                    executeMove(space.row, space.col, card)
                    moveMade = true; break
                }
            }
        }

        if (!moveMade) {
            val updatedHand = currentPlayer.hand.toMutableList()
            updatedHand.removeAt(0)
            if (deck.isNotEmpty()) updatedHand.add(deck.removeFirst())
            players[currentPlayerIndex].hand = updatedHand
            gameMessage = "Player ${currentPlayer.id} (CPU) discarded a blocked card."
            advanceTurn()
        }
    }
}

// ==========================================
// 3. JETPACK COMPOSE UI
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SequenceApp() } }
    }
}

@Composable
fun SequenceApp(viewModel: GameViewModel = viewModel()) {
    when (viewModel.currentGameState) {
        GameState.SETUP -> SetupScreen(viewModel)
        GameState.PASS_DEVICE -> PassDeviceScreen(viewModel)
        GameState.PLAYING -> GameScreen(viewModel)
    }
}

@Composable
fun SetupScreen(viewModel: GameViewModel) {
    var totalPlayers by remember { mutableStateOf(2) }
    var humans by remember { mutableStateOf(1) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sequence setup", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 32.dp))
        
        Text("Total Players: $totalPlayers", fontWeight = FontWeight.Bold)
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { totalPlayers = 2; if(humans > 2) humans = 2 }, colors = ButtonDefaults.buttonColors(containerColor = if(totalPlayers == 2) Color.Blue else Color.Gray)) { Text("2") }
            Button(onClick = { totalPlayers = 3; if(humans > 3) humans = 3 }, colors = ButtonDefaults.buttonColors(containerColor = if(totalPlayers == 3) Color.Blue else Color.Gray)) { Text("3") }
            Button(onClick = { totalPlayers = 4; if(humans > 4) humans = 4 }, colors = ButtonDefaults.buttonColors(containerColor = if(totalPlayers == 4) Color.Blue else Color.Gray)) { Text("4") }
        }

        Spacer(Modifier.height(16.dp))

        Text("How many Humans?: $humans", fontWeight = FontWeight.Bold)
        Text("(The rest will be CPU bots)", fontSize = 12.sp, color = Color.Gray)
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 1..totalPlayers) {
                Button(onClick = { humans = i }, colors = ButtonDefaults.buttonColors(containerColor = if(humans == i) Color.Blue else Color.Gray)) { Text("$i") }
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = { viewModel.setupGame(totalPlayers, humans) }, modifier = Modifier.fillMaxWidth(0.6f).height(50.dp)) {
            Text("START GAME", fontSize = 18.sp)
        }
    }
}

@Composable
fun PassDeviceScreen(viewModel: GameViewModel) {
    val nextPlayer = viewModel.currentPlayer
    Column(Modifier.fillMaxSize().background(nextPlayer.team.uiColor.copy(alpha = 0.2f)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(viewModel.gameMessage, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        Text("Pass the device to", fontSize = 24.sp)
        Text("Player ${nextPlayer.id}", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = nextPlayer.team.uiColor)
        Spacer(Modifier.height(48.dp))
        Button(onClick = { viewModel.currentGameState = GameState.PLAYING }, modifier = Modifier.fillMaxWidth(0.6f).height(60.dp)) {
            Text("I'M READY", fontSize = 20.sp)
        }
    }
}

@Composable
fun GameScreen(viewModel: GameViewModel) {
    val board by viewModel.board.collectAsState()
    val selectedCard by viewModel.selectedCard.collectAsState()
    val currentPlayer = viewModel.currentPlayer

    Column(Modifier.fillMaxSize().background(Color(0xFFF0F0F0)).padding(8.dp)) {
        // --- TOP BAR ---
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(viewModel.gameMessage, fontSize = 14.sp, color = Color.DarkGray)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (currentPlayer.isCpu) "CPU ${currentPlayer.id} is thinking..." else "Player ${currentPlayer.id}'s Turn", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = currentPlayer.team.uiColor)
            }
        }

        // --- 10x10 BOARD ---
        LazyVerticalGrid(columns = GridCells.Fixed(10), modifier = Modifier.weight(1f)) {
            items(100) { index ->
                val row = index / 10; val col = index % 10; val space = board[row][col]
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(1.dp).aspectRatio(1f)
                        .border(if (space.isHighlighted) 3.dp else 1.dp, if (space.isHighlighted) Color.Yellow else Color.DarkGray)
                        .background(if (space.card.rank == Rank.CORNER) Color.LightGray else Color.White)
                        .clickable(enabled = space.isHighlighted) { viewModel.humanPlaceToken(row, col) }
                ) {
                    // ALWAYS draw the card text underneath so it is visible
                    if (space.card.rank == Rank.CORNER) {
                        Text("⭐\nWILD", fontSize = 10.sp, textAlign = TextAlign.Center, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(space.card.rank.text, fontSize = 14.sp, color = space.card.suit.color, fontWeight = FontWeight.Bold)
                            Text(space.card.suit.symbol, fontSize = 12.sp, color = space.card.suit.color)
                        }
                    }

                    // DRAW THE COIN CIRCLE OVERLAY IF OCCUPIED
                    if (space.occupant != TeamColor.NONE) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.65f) // Makes the circle take up 65% of the square so corners peek out
                                .clip(CircleShape)
                                .background(space.occupant.uiColor.copy(alpha = 0.85f)) // Slight transparency to read text
                                .border(1.dp, Color.Black, CircleShape)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- PLAYER HAND (FIT TO SCREEN) ---
        if (!currentPlayer.isCpu) {
            Text("Your Cards:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(
                Modifier.fillMaxWidth().height(90.dp),
                horizontalArrangement = Arrangement.SpaceEvenly // Distributes evenly without scrolling
            ) {
                currentPlayer.hand.forEach { card ->
                    Card(
                        modifier = Modifier
                            .weight(1f) // Forces all cards to fit on screen
                            .padding(2.dp)
                            .fillMaxHeight()
                            .border(3.dp, if (card == selectedCard) Color.Cyan else Color.Transparent, MaterialTheme.shapes.small)
                            .clickable { viewModel.selectCard(card) },
                        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(card.rank.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = card.suit.color)
                            Text(card.suit.symbol, fontSize = 16.sp, color = card.suit.color)
                            if (card.isTwoEyedJack) Text("WILD", fontSize = 8.sp, color = Color.Blue)
                            if (card.isOneEyedJack) Text("REMOVE", fontSize = 8.sp, color = Color.Red)
                        }
                    }
                }
            }
        } else {
            // CPU Hand Placeholder (hidden from human)
            Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Text("CPU is deciding...", color = Color.Gray, fontSize = 18.sp)
            }
        }
    }
}
