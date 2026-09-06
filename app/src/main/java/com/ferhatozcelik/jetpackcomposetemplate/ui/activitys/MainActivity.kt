@file:OptIn(ExperimentalAnimationApi::class)
package com.ferhatozcelik.jetpackcomposetemplate.ui.activitys

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.math.max

// ==============================================================================
// GLOBAL STATIC BOARD LAYOUT
// ==============================================================================
val BOARD_LAYOUT = arrayOf(
    arrayOf("★", "2♠", "3♠", "4♠", "5♠", "6♠", "7♠", "8♠", "9♠", "★"),
    arrayOf("6♣", "5♣", "4♣", "3♣", "2♣", "A♥", "K♥", "Q♥", "10♥", "10♠"),
    arrayOf("7♣", "A♠", "2♦", "3♦", "4♦", "5♦", "6♦", "7♦", "9♥", "Q♠"),
    arrayOf("8♣", "K♠", "6♣", "5♣", "4♣", "3♣", "2♣", "8♦", "8♥", "K♠"),
    arrayOf("9♣", "Q♠", "7♣", "6♥", "5♥", "4♥", "A♥", "9♦", "7♥", "A♠"),
    arrayOf("10♣", "10♠", "8♣", "7♥", "2♥", "3♥", "K♥", "10♦", "6♥", "2♦"),
    arrayOf("Q♣", "9♠", "9♣", "8♥", "9♥", "10♥", "Q♥", "Q♦", "5♥", "3♦"),
    arrayOf("K♣", "8♠", "10♣", "Q♣", "K♣", "A♣", "A♦", "K♦", "4♥", "4♦"),
    arrayOf("A♣", "7♠", "6♠", "5♠", "4♠", "3♠", "2♠", "2♥", "3♥", "5♦"),
    arrayOf("★", "A♦", "K♦", "Q♦", "10♦", "9♦", "8♦", "7♦", "6♦", "★")
)

val TEAM_COLORS = listOf(
    0xFF1976D2, 0xFF159447, 0xFFD32F2F, 0xFFFFB300, 
    0xFF8E24AA, 0xFF00ACC1, 0xFFF4511E
)

// ==============================================================================
// HISTORY MANAGER (Local Storage)
// ==============================================================================
object HistoryManager {
    fun saveMatch(context: Context, room: GameRoom) {
        val prefs = context.getSharedPreferences("SeqHistory", Context.MODE_PRIVATE)
        val history = prefs.getStringSet("matches", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        val totalMatchSecs = if (room.matchStartTime > 0) (System.currentTimeMillis() - room.matchStartTime) / 1000 else 0
        val mHr = totalMatchSecs / 3600
        val mMin = (totalMatchSecs % 3600) / 60
        val mSec = totalMatchSecs % 60
        val timeStr = if (mHr > 0) String.format("%02d:%02d:%02d", mHr, mMin, mSec) else String.format("%02d:%02d", mMin, mSec)
        
        val matchId = "${room.roomId}_${room.matchNumber}"
        if (history.none { it.startsWith(matchId) }) {
            val groupKey = "${room.sessionStartTimeStr} (Room: ${room.roomId})"
            val record = "$matchId|$groupKey|${room.matchNumber}|${room.winnerTeam}|$timeStr"
            history.add(record)
            prefs.edit().putStringSet("matches", history).apply()
        }
    }

    fun getHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences("SeqHistory", Context.MODE_PRIVATE)
        return prefs.getStringSet("matches", emptySet())?.toList()?.sortedDescending() ?: emptyList()
    }
}

// ==============================================================================
// 1. MAIN MENU & INSTRUCTIONS
// ==============================================================================
enum class AppMode { MENU, OFFLINE, ONLINE, HISTORY, INSTRUCTIONS }

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
                            onPlayOnline = { currentMode = AppMode.ONLINE },
                            onHistory = { currentMode = AppMode.HISTORY },
                            onInstructions = { currentMode = AppMode.INSTRUCTIONS }
                        )
                        AppMode.OFFLINE -> OfflineSequenceApp(onExit = { currentMode = AppMode.MENU })
                        AppMode.ONLINE -> OnlineSequenceApp(onExit = { currentMode = AppMode.MENU })
                        AppMode.HISTORY -> HistoryScreen(onExit = { currentMode = AppMode.MENU })
                        AppMode.INSTRUCTIONS -> InstructionsScreen(onExit = { currentMode = AppMode.MENU })
                    }
                }
            }
        }
    }
}

@Composable
fun MainMenuScreen(onPlayLocal: () -> Unit, onPlayOnline: () -> Unit, onHistory: () -> Unit, onInstructions: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF1F3F4)), 
        horizontalAlignment = Alignment.CenterHorizontally, 
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "SEQUENCE", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Text(text = "Board Game", fontSize = 20.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        
        Button(onClick = onPlayLocal, modifier = Modifier.fillMaxWidth(0.7f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07852B))) { 
            Text(text = "Play Local (Pass & Play / CPU)", fontSize = 16.sp) 
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onPlayOnline, modifier = Modifier.fillMaxWidth(0.7f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) { 
            Text(text = "Play Online (With Friends)", fontSize = 16.sp) 
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth(0.7f).height(48.dp)) { 
            Text(text = "View Match History", fontSize = 16.sp, color = Color.DarkGray) 
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onInstructions) { 
            Text(text = "How to Play / Tutorial", fontSize = 16.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold) 
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        Text(text = "Developed by Aravind Valluri", fontSize = 15.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun InstructionsScreen(onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "How to Play", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
            TextButton(onClick = onExit) { Text(text = "Back", color = Color.Red) }
        }
        Divider(color = Color.LightGray, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
        
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            item {
                Text(text = "Objective", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF07852B))
                Text(text = "Be the first team to complete the required number of Sequences (5 chips in a row). 2-Team games require 2 Sequences. 3-Team games require 1 Sequence.", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                
                Text(text = "The Cards & Board", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF07852B))
                Text(text = "• The board contains two of every card in a standard deck (except Jacks).\n• The 4 Corners (★) are free spaces. Any team can use them to complete a 5-chip line.\n• When it's your turn, select a card from your hand, then tap the matching highlighted space on the board to place your chip.", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                
                Text(text = "The Jacks (Special Powers)", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Red)
                Text(text = "• WILD (Two-Eyed Jacks - ♦ / ♣): These allow you to place your chip on ANY empty space on the board.\n• REMOVE (One-Eyed Jacks - ♠ / ♥): These allow you to remove an opponent's chip from the board (unless it is locked in a completed sequence).", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

                Text(text = "Dead Cards", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF07852B))
                Text(text = "If you hold a card in your hand but both matching spaces on the board are already occupied, it is a 'Dead Card'. Select it and tap 'Replace selected dead card' to draw a new one and end your turn.", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                
                Text(text = "Online Play & Lobby Navigation", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1976D2))
                Text(text = "• Host a room and share the 4-digit code. Wait for everyone to join before assigning teams.\n• The Host can dynamically change the number of teams (2 or 3) and assign players to specific teams.\n• The Host can turn on a Turn Timer (15s, 30s, 60s). If a player's timer runs out, the CPU will automatically take their turn for them so the game doesn't stall.", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

                Text(text = "In-Game Chat", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1976D2))
                Text(text = "You can chat with players in the lobby, during the game, and on the scorecard! Use the toggle switch in the chat window to switch between sending messages to ALL players, or sending private messages only to your TEAM.", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 32.dp))
            }
        }
    }
}

@Composable
fun HistoryScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val historyItems = remember { HistoryManager.getHistory(context) }
    
    Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Match History", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
            TextButton(onClick = onExit) { 
                Text(text = "Back", color = Color.Red) 
            }
        }
        Spacer(Modifier.height(16.dp))
        
        if (historyItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "No match history found.", color = Color.Gray)
            }
        } else {
            val grouped = historyItems.map { it.split("|") }.groupBy { it.getOrNull(1) ?: "Unknown Session" }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (sessionKey, matches) ->
                    item {
                        Text(
                            text = sessionKey, 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 15.sp, 
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).background(Color(0xFFE3F2FD)).fillMaxWidth().padding(8.dp)
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth().background(Color.LightGray).padding(8.dp)) {
                            Text(text = "Match", Modifier.weight(0.3f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(text = "Winner", Modifier.weight(0.4f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(text = "Duration", Modifier.weight(0.3f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(matches.sortedBy { it.getOrNull(2)?.toIntOrNull() ?: 0 }) { matchData ->
                        val mNum = matchData.getOrNull(2) ?: ""
                        val winner = matchData.getOrNull(3) ?: ""
                        val time = matchData.getOrNull(4) ?: ""
                        
                        Row(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(text = mNum, Modifier.weight(0.3f), fontSize = 14.sp)
                            Text(text = winner, Modifier.weight(0.4f), fontSize = 14.sp, color = Color(0xFF07852B), fontWeight = FontWeight.Bold)
                            Text(text = time, Modifier.weight(0.3f), fontSize = 14.sp)
                        }
                        Divider(color = Color.LightGray, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 2. OFFLINE CORE GAME (UNTOUCHED LOGIC)
// ==============================================================================
enum class Suit(val symbol: String, val color: Color) { 
    SPADES("♠", Color.Black), HEARTS("♥", Color(0xFFC62828)), 
    DIAMONDS("♦", Color(0xFFC62828)), CLUBS("♣", Color.Black), 
    NONE("", Color.Transparent) 
}

enum class Rank(val text: String) { 
    A("A"), K("K"), Q("Q"), J("J"), TEN("10"), NINE("9"), 
    EIGHT("8"), SEVEN("7"), SIX("6"), FIVE("5"), FOUR("4"), 
    THREE("3"), TWO("2"), CORNER("★") 
}

enum class TeamColor(val uiColor: Color) { 
    NONE(Color.Transparent), BLUE(Color(0xFF1976D2)), 
    GREEN(Color(0xFF159447)), RED(Color(0xFFD32F2F)) 
}

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

    val currentPlayer: Player get() = players.getOrElse(currentPlayerIndex) { Player(0, TeamColor.NONE, false) }
    private val requiredSequences: Int get() = if (numberOfTeams == 2) 2 else 1

    private fun getPlayingCardFromString(cardString: String, id: Int): PlayingCard {
        if (cardString == "★") return PlayingCard(Suit.NONE, Rank.CORNER, id)
        val rankStr = cardString.dropLast(1)
        val suitStr = cardString.takeLast(1)
        val rank = Rank.entries.first { it.text == rankStr }
        val suit = Suit.entries.first { it.symbol == suitStr }
        return PlayingCard(suit, rank, id)
    }

    fun setupGame(totalPlayers: Int, humans: Int, requestedTeams: Int) {
        cpuJob?.cancel()
        humanCount = humans
        numberOfTeams = requestedTeams
        winnerTeam = null
        isDraw = false
        currentPlayerIndex = 0
        _selectedCardId.value = null
        
        deck = buildTwoDecks().shuffled(random).toMutableList()
        var idCounter = 10000
        _board.value = List(10) { row -> 
            List(10) { col -> 
                BoardSpace(row = row, col = col, card = getPlayingCardFromString(BOARD_LAYOUT[row][col], idCounter++)) 
            } 
        }
        
        val teams = if (requestedTeams == 2) listOf(TeamColor.BLUE, TeamColor.GREEN) else listOf(TeamColor.BLUE, TeamColor.GREEN, TeamColor.RED)
        players = List(totalPlayers) { index -> Player(id = index + 1, team = teams[index % requestedTeams], isCpu = index >= humans) }
        
        val handSize = handSizeFor(totalPlayers)
        repeat(handSize) { 
            players = players.map { player -> player.copy(hand = player.hand + listOfNotNull(drawOneCard())) } 
        }
        
        _sequenceCounts.value = teams.associateWith { 0 }
        currentGameState = GameState.PLAYING
        gameMessage = if (requiredSequences == 2) "First team to complete 2 sequences wins. Player 1's turn." else "First team to complete 1 sequence wins. Player 1's turn."
        
        if (currentPlayer.isCpu) startCpuTurn()
    }
    
    private fun buildTwoDecks(): List<PlayingCard> = buildList { 
        var id = 0
        repeat(2) { 
            for (suit in Suit.entries.filter { it != Suit.NONE }) { 
                for (rank in Rank.entries.filter { it != Rank.CORNER }) { 
                    add(PlayingCard(suit, rank, id++)) 
                } 
            } 
        } 
    }
    
    private fun handSizeFor(totalPlayers: Int): Int = when (totalPlayers) { 2 -> 7; 3, 4 -> 6; 6 -> 5; 8, 9 -> 4; 10, 12 -> 3; else -> 3 }
    private fun drawOneCard(): PlayingCard? = if (deck.isEmpty()) null else deck.removeFirst()
    
    fun selectCard(cardId: Int) {
        if (currentGameState != GameState.PLAYING || currentPlayer.isCpu) return
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return
        _selectedCardId.value = cardId
        _board.value = _board.value.map { row -> row.map { space -> space.copy(isHighlighted = isLegalDestination(card, space, currentPlayer.team)) } }
        val count = _board.value.flatten().count { it.isHighlighted }
        gameMessage = if (count == 0) "No legal position. If both matching spaces are occupied, replace this dead card." else "$count legal move${if (count == 1) "" else "s"} highlighted in green."
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
        if (!space.isHighlighted || !isLegalDestination(card, space, currentPlayer.team)) return
        executeMove(row, col, card)
    }
    
    private fun executeMove(row: Int, col: Int, cardUsed: PlayingCard) {
        if (currentGameState != GameState.PLAYING) return
        val movingPlayer = currentPlayer
        val target = _board.value[row][col]
        if (!isLegalDestination(cardUsed, target, movingPlayer.team)) return
        
        val newTarget = if (cardUsed.isOneEyedJack) {
            target.copy(occupant = TeamColor.NONE, isHighlighted = false, isCompletedSequence = false) 
        } else {
            target.copy(occupant = movingPlayer.team, isHighlighted = false)
        }
        
        _board.value = _board.value.mapIndexed { r, boardRow -> 
            boardRow.mapIndexed { c, space -> 
                if (r == row && c == col) newTarget else space.copy(isHighlighted = false) 
            } 
        }
        
        val newHand = movingPlayer.hand.filterNot { it.uniqueId == cardUsed.uniqueId }.toMutableList()
        drawOneCard()?.let(newHand::add)
        updatePlayer(movingPlayer.copy(hand = newHand))
        _selectedCardId.value = null
        
        var turnSummary = when { 
            cardUsed.isOneEyedJack -> "Player ${movingPlayer.id} used a Remove Jack."
            cardUsed.isTwoEyedJack -> "Player ${movingPlayer.id} used a Wild Jack."
            else -> "Player ${movingPlayer.id} placed ${cardUsed.rank.text}${cardUsed.suit.symbol}." 
        }
        
        if (!cardUsed.isOneEyedJack) {
            val seqResult = updateSequencesAndCheckWinner(movingPlayer.team)
            if (seqResult == 1) {
                turnSummary += " Sequence completed!" 
            } else if (seqResult == 2) { 
                gameMessage = "$turnSummary Team ${movingPlayer.team.name} wins!"
                return 
            }
        }
        
        if (checkForDraw(turnSummary)) return
        advanceTurn(turnSummary)
    }
    
    private fun updatePlayer(updated: Player) { 
        players = players.map { if (it.id == updated.id) updated else it } 
    }
    
    fun replaceSelectedDeadCard() {
        if (currentGameState != GameState.PLAYING || currentPlayer.isCpu) return
        val cardId = _selectedCardId.value ?: return
        val card = currentPlayer.hand.firstOrNull { it.uniqueId == cardId } ?: return
        if (card.rank == Rank.J) return
        
        val matches = _board.value.flatten().filter { it.card.matches(card) }
        if (matches.isEmpty() || matches.any { it.occupant == TeamColor.NONE }) return
        
        val newHand = currentPlayer.hand.filterNot { it.uniqueId == card.uniqueId }.toMutableList()
        val replacement = drawOneCard()
        replacement?.let(newHand::add)
        updatePlayer(currentPlayer.copy(hand = newHand))
        _selectedCardId.value = null
        clearHighlights()
        
        val msg = "Player ${currentPlayer.id} replaced a dead card."
        gameMessage = "$msg Player ${currentPlayer.id}'s turn."
        checkForDraw()
    }
    
    private fun clearHighlights() { 
        _board.value = _board.value.map { row -> row.map { it.copy(isHighlighted = false) } } 
    }
    
    private fun findCompletedLines(team: TeamColor): List<CompletedLine> {
        val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        val candidates = mutableListOf<CompletedLine>()
        
        for (row in 0 until 10) { 
            for (col in 0 until 10) { 
                for ((dr, dc) in directions) {
                    val positions = (0 until 5).map { offset -> row + offset * dr to col + offset * dc }
                    if (positions.any { (r, c) -> r !in 0..9 || c !in 0..9 }) continue
                    
                    if (positions.all { (r, c) -> 
                        val space = _board.value[r][c]
                        space.card.rank == Rank.CORNER || space.occupant == team 
                    }) {
                        candidates += CompletedLine(team, positions.toSet())
                    }
                } 
            } 
        }
        
        val accepted = mutableListOf<CompletedLine>()
        for (candidate in candidates) { 
            if (accepted.all { previous -> candidate.positions.intersect(previous.positions).size <= 1 }) {
                accepted += candidate 
            }
        }
        return accepted
    }
    
    private fun updateSequencesAndCheckWinner(team: TeamColor): Int {
        val completed = findCompletedLines(team)
        val oldCount = _sequenceCounts.value[team] ?: 0
        val newCount = completed.size
        val protectedPositions = completed.flatMap { it.positions }.toSet()
        
        _sequenceCounts.value = _sequenceCounts.value.toMutableMap().apply { this[team] = newCount }
        
        _board.value = _board.value.mapIndexed { row, boardRow -> 
            boardRow.mapIndexed { col, space -> 
                if (space.occupant == team && (row to col) in protectedPositions) {
                    space.copy(isCompletedSequence = true) 
                } else {
                    space 
                }
            } 
        }
        
        if (newCount >= requiredSequences) { 
            winnerTeam = team
            currentGameState = GameState.FINISHED
            cpuJob?.cancel()
            return 2 
        } else if (newCount > oldCount) {
            return 1
        }
        return 0
    }
    
    private fun playerHasLegalMove(player: Player): Boolean { 
        val spaces = _board.value.flatten()
        return player.hand.any { card -> spaces.any { space -> isLegalDestination(card, space, player.team) } } 
    }
    
    private fun checkForDraw(prefix: String = ""): Boolean {
        if (winnerTeam != null) return false
        if (players.all { it.hand.isEmpty() } || (deck.isEmpty() && players.none(::playerHasLegalMove))) {
            isDraw = true
            currentGameState = GameState.FINISHED
            val prefixStr = if (prefix.isNotEmpty()) "$prefix " else ""
            gameMessage = "${prefixStr}Draw: the draw pile is empty and no legal moves remain."
            cpuJob?.cancel()
            return true
        }
        return false
    }
    
    private fun advanceTurn(prefix: String = "") {
        if (currentGameState == GameState.FINISHED) return
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        _selectedCardId.value = null
        clearHighlights()
        
        val p = if (prefix.isNotEmpty()) "$prefix " else ""
        if (currentPlayer.isCpu) { 
            currentGameState = GameState.PLAYING
            gameMessage = "${p}CPU ${currentPlayer.id} is thinking..."
            startCpuTurn() 
        }
        else if (humanCount > 1) { 
            currentGameState = GameState.PASS_DEVICE
            gameMessage = "${p}Pass to Player ${currentPlayer.id}." 
        }
        else { 
            currentGameState = GameState.PLAYING
            gameMessage = "${p}Player ${currentPlayer.id}'s turn." 
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
            delay(1200)
            if (currentGameState != GameState.PLAYING || !currentPlayer.isCpu) return@launch
            
            val cpu = currentPlayer
            val legalMoves = cpu.hand.flatMap { card -> 
                _board.value.flatten().filter { isLegalDestination(card, it, cpu.team) }.map { space -> card to space } 
            }
            
            if (legalMoves.isNotEmpty()) { 
                val (card, space) = legalMoves.random(random)
                executeMove(space.row, space.col, card)
                return@launch 
            }
            
            val deadCard = cpu.hand.firstOrNull { card -> 
                card.rank != Rank.J && _board.value.flatten().filter { it.card.matches(card) }.all { it.occupant != TeamColor.NONE } 
            }
            
            if (deadCard != null) { 
                val newHand = cpu.hand.filterNot { it.uniqueId == deadCard.uniqueId }.toMutableList()
                drawOneCard()?.let(newHand::add)
                updatePlayer(cpu.copy(hand = newHand))
                val msg = "CPU ${cpu.id} replaced a dead card."
                if (!checkForDraw(msg)) { 
                    gameMessage = "$msg CPU ${cpu.id} is thinking..."
                    startCpuTurn() 
                }
            } else if (!checkForDraw()) {
                advanceTurn("CPU ${cpu.id} has no legal move.")
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
        Text(text = "Five Line Cards", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(text = "Classic local play with optional CPU players", color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        
        Text(text = "Total players: $totalPlayers", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { 
            supportedPlayers.forEach { count -> 
                TextButton(onClick = { 
                    totalPlayers = count
                    humans = humans.coerceAtMost(count)
                    teams = when (count) { 
                        3, 9 -> 3 
                        else -> if (teams == 3 && count !in listOf(6, 12)) 2 else teams 
                    } 
                }) { 
                    Text(
                        text = count.toString(), 
                        color = if (count == totalPlayers) Color(0xFF1976D2) else Color.Gray, 
                        fontWeight = if (count == totalPlayers) FontWeight.Bold else FontWeight.Normal
                    ) 
                } 
            } 
        }
        
        Text(text = "Teams: $effectiveTeams", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
            availableTeams.forEach { count -> 
                Button(
                    onClick = { teams = count }, 
                    colors = ButtonDefaults.buttonColors(containerColor = if (effectiveTeams == count) Color(0xFF1976D2) else Color.Gray)
                ) { 
                    Text(text = count.toString()) 
                } 
            } 
        }
        
        Spacer(Modifier.height(12.dp))
        Text(text = "Human players: $effectiveHumans", fontWeight = FontWeight.Bold)
        Text(text = "Remaining players are CPU-controlled", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { 
            (1..totalPlayers).forEach { count -> 
                TextButton(onClick = { humans = count }) { 
                    Text(
                        text = count.toString(), 
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
            Text(text = "START GAME", fontSize = 17.sp) 
        }
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 16.dp)) { 
            Text(text = "Back to Main Menu", color = Color.Red) 
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
        Text(text = gameViewModel.gameMessage, color = Color.DarkGray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Text(text = "Pass the device to", fontSize = 23.sp)
        Text(
            text = "Player ${next.id}", 
            fontSize = 46.sp, 
            fontWeight = FontWeight.Bold, 
            color = next.team.uiColor
        )
        Text(text = "Team ${next.team.name}", fontSize = 18.sp)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = gameViewModel::confirmPassDevice, 
            modifier = Modifier.fillMaxWidth(0.65f).height(58.dp)
        ) { 
            Text(text = "I'M READY", fontSize = 19.sp) 
        }
    }
}

@Composable
fun GameScreen(gameViewModel: GameViewModel, onExit: () -> Unit = {}) {
    val board by gameViewModel.board.collectAsState()
    val selectedCardId by gameViewModel.selectedCardId.collectAsState()
    val sequenceCounts by gameViewModel.sequenceCounts.collectAsState()
    val player = gameViewModel.currentPlayer
    
    Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4)).padding(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { 
                Text(
                    text = gameViewModel.gameMessage, 
                    fontSize = 15.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = player.team.uiColor, 
                    maxLines = 2
                ) 
            }
            Row(verticalAlignment = Alignment.CenterVertically) { 
                val currentSeq = sequenceCounts[player.team] ?: 0
                val targetSeq = if (gameViewModel.numberOfTeams == 2) 2 else 1
                val seqStr = "$currentSeq/$targetSeq"
                Text(
                    text = seqStr, 
                    fontSize = 18.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = player.team.uiColor, 
                    modifier = Modifier.padding(end = 8.dp)
                )
                TextButton(onClick = onExit) { 
                    Text(text = "Exit", color = Color.Red) 
                } 
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
                    BoardCard(space) { gameViewModel.humanPlaceToken(space.row, space.col) } 
                } 
            } 
        }
        
        Spacer(Modifier.height(4.dp))
        if (!player.isCpu) { 
            val hintString = if (selectedCardId == null) "Select a card to show legal moves" else "Green spaces are legal moves"
            Text(text = hintString, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF087F23))
            PlayerHand(player, selectedCardId, gameViewModel::selectCard, gameViewModel::replaceSelectedDeadCard) 
        } else { 
            Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) { 
                Text(text = "CPU cards are hidden", color = Color.Gray) 
            } 
        }
    }
}

@Composable
private fun BoardCard(space: BoardSpace, onClick: () -> Unit) {
    val bgBrush = when { 
        space.card.rank == Rank.CORNER -> Brush.linearGradient(listOf(Color(0xFFFFE082), Color(0xFFFFCA28)))
        space.isHighlighted -> Brush.linearGradient(listOf(Color(0xFFA5D6A7), Color(0xFF81C784)))
        space.isCompletedSequence -> Brush.linearGradient(listOf(space.occupant.uiColor.copy(alpha = 0.15f), space.occupant.uiColor.copy(alpha = 0.25f)))
        else -> Brush.linearGradient(listOf(Color.White, Color(0xFFF3F3F3)))
    }
    
    val borderC = when { 
        space.isHighlighted -> Color(0xFF07852B)
        space.isCompletedSequence -> space.occupant.uiColor
        else -> Color(0xFFDDDDDD) 
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .padding(1.dp)
            .shadow(2.dp, MaterialTheme.shapes.extraSmall)
            .background(bgBrush, MaterialTheme.shapes.extraSmall)
            .border(if (space.isHighlighted || space.isCompletedSequence) 2.dp else 0.5.dp, borderC, MaterialTheme.shapes.extraSmall)
            .clickable(enabled = space.isHighlighted, onClick = onClick)
    ) {
        if (space.card.rank == Rank.CORNER) { 
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { 
                Text(text = "★", fontSize = 12.sp, color = Color(0xFF6D4C00))
                Text(text = "FREE", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6D4C00)) 
            } 
        }
        else { 
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) { 
                Text(text = space.card.rank.text, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = space.card.suit.color)
                Text(text = space.card.suit.symbol, fontSize = 9.sp, lineHeight = 9.sp, color = space.card.suit.color) 
            } 
        }
        
        if (space.occupant != TeamColor.NONE) { 
            val chipAlpha = if (space.isCompletedSequence) 0.62f else 0.9f
            val chipBaseColor = space.occupant.uiColor
            val cColor1 = chipBaseColor.copy(alpha = chipAlpha)
            val cColor2 = chipBaseColor.copy(alpha = (chipAlpha - 0.2f).coerceAtLeast(0.1f))
            val chipBrush = Brush.radialGradient(listOf(cColor2, cColor1))
            
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .fillMaxWidth(0.68f)
                    .aspectRatio(1f)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(chipBrush)
                    .border(
                        if (space.isCompletedSequence) 2.dp else 1.dp, 
                        if (space.isCompletedSequence) Color.White else Color.Black.copy(alpha = 0.65f), 
                        CircleShape
                    )
            ) { 
                if (space.isCompletedSequence) { 
                    Text(text = "✓", Modifier.align(Alignment.Center), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White) 
                } 
            } 
        }
        
        if (space.isHighlighted) { 
            Box(Modifier.align(Alignment.BottomEnd).padding(2.dp).size(6.dp).clip(CircleShape).background(Color(0xFF07852B))) 
        }
    }
}

@Composable
private fun PlayerHand(player: Player, selectedCardId: Int?, onSelect: (Int) -> Unit, onReplaceDeadCard: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            player.hand.forEach { card ->
                val selected = card.uniqueId == selectedCardId
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .shadow(if (selected) 6.dp else 2.dp, MaterialTheme.shapes.small)
                        .background(Brush.linearGradient(listOf(Color.White, Color(0xFFF7F7F7))), MaterialTheme.shapes.small)
                        .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF07852B) else Color.LightGray, MaterialTheme.shapes.small)
                        .clickable { onSelect(card.uniqueId) }
                ) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(text = card.rank.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = card.suit.color)
                        Text(text = card.suit.symbol, fontSize = 14.sp, color = card.suit.color)
                        when { 
                            card.isTwoEyedJack -> Text(text = "WILD", fontSize = 7.sp, color = Color.Blue, fontWeight = FontWeight.Bold)
                            card.isOneEyedJack -> Text(text = "REMOVE", fontSize = 7.sp, color = Color.Red, fontWeight = FontWeight.Bold) 
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onReplaceDeadCard, enabled = selectedCardId != null) { 
            Text(text = "Replace selected dead card", fontSize = 12.sp) 
        }
    }
}

@Composable
fun FinishedScreen(gameViewModel: GameViewModel, onExit: () -> Unit = {}) {
    val counts by gameViewModel.sequenceCounts.collectAsState()
    val winner = gameViewModel.winnerTeam
    val background = if (gameViewModel.isDraw || winner == null) Color(0xFFE7E7E7) else winner.uiColor.copy(alpha = 0.15f)
    
    Column(
        Modifier.fillMaxSize().background(background).padding(24.dp), 
        verticalArrangement = Arrangement.Center, 
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val titleText = if (gameViewModel.isDraw) "DRAW" else "WINNER"
        Text(text = titleText, fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        if (winner != null) { 
            Text(text = "Team ${winner.name}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = winner.uiColor)
            val countStr = "${counts[winner] ?: 0}"
            Text(text = "Completed sequences: $countStr", fontSize = 18.sp) 
        } else { 
            Text(text = "No legal moves remain.", fontSize = 20.sp, textAlign = TextAlign.Center) 
        }
        
        Spacer(Modifier.height(16.dp))
        Text(text = gameViewModel.gameMessage, textAlign = TextAlign.Center, color = Color.DarkGray)
        Spacer(Modifier.height(32.dp))
        
        Button(onClick = gameViewModel::newGame) { 
            Text(text = "NEW GAME") 
        }
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 16.dp)) { 
            Text(text = "Back to Main Menu", color = Color.Red) 
        }
    }
}


// ==============================================================================
// 3. ONLINE MULTIPLAYER & CHAT SYSTEM
// ==============================================================================

data class ChatMessage(val sender: String = "", val team: String = "", val text: String = "", val isTeamOnly: Boolean = false, val timestamp: Long = 0L)

data class FBCard(val suit: String = "", val rank: String = "", val id: Int = 0) {
    val isTwoEyedJack: Boolean get() = rank == "J" && (suit == "♦" || suit == "♣")
    val isOneEyedJack: Boolean get() = rank == "J" && (suit == "♠" || suit == "♥")
    fun matches(other: FBCard): Boolean = suit == other.suit && rank == other.rank
}

data class FBSpace(val r: Int = 0, val c: Int = 0, val card: FBCard = FBCard(), val occupant: String = "NONE", @field:JvmField var completedSequence: Boolean = false) {
    val isCompletedSequence: Boolean get() = completedSequence
}

data class FBPlayer(
    val playerId: Int = 0, val playerName: String = "", val team: String = "Team1", 
    val hand: List<FBCard> = emptyList(), var timePlayedMs: Long = 0L, 
    var wildUsed: Int = 0, var removeUsed: Int = 0
)

data class GameRoom(
    var roomId: String = "", var password: String = "", var status: String = "WAITING",
    var hostName: String = "", var originalHostName: String = "",
    var numberOfTeams: Int = 2, var turnPlayerId: Int = 1,
    var message: String = "Waiting for players to join...", var board: List<FBSpace> = emptyList(),
    var players: List<FBPlayer> = emptyList(), var deck: List<FBCard> = emptyList(),
    var winnerTeam: String = "", var lastMoveRow: Int = -1, var lastMoveCol: Int = -1,
    var presence: Map<String, Boolean> = emptyMap(), var rematchVotes: Map<String, Boolean> = emptyMap(),
    var teamColors: Map<String, Long> = emptyMap(), var matchNumber: Int = 1,
    var matchHistory: List<String> = emptyList(), var consecutiveWins: Int = 0, var lastWinningTeam: String = "",
    var matchStartTime: Long = 0L, var sessionStartTimeStr: String = "",
    var lastPlayedCard: FBCard? = null,
    var timerEnabled: Boolean = false, var timerDurationSecs: Int = 30, var currentTurnStartTime: Long = 0L,
    var chatMessages: Map<String, ChatMessage> = emptyMap()
)

enum class OnlineAppState { LOBBY, ENTER_NAME, CREATE_ROOM, JOIN_ROOM, WAITING_ROOM, PLAYING }

@OptIn(ExperimentalAnimationApi::class)
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

class MultiplayerViewModel : ViewModel() {
    private val db = Firebase.database.reference
    var currentAppState by mutableStateOf(OnlineAppState.LOBBY)
    var playerName by mutableStateOf("Player")
    var roomCode by mutableStateOf("")
    var roomPassword by mutableStateOf("")
    var lobbyError by mutableStateOf("")

    private val _roomData = MutableStateFlow(GameRoom())
    val roomData: StateFlow<GameRoom> = _roomData.asStateFlow()
    private var roomListener: ValueEventListener? = null
    
    var lastReadMessageCount by mutableStateOf(0)
    
    private val myPlayerId: Int get() = _roomData.value.players.firstOrNull { it.playerName == playerName }?.playerId ?: -1

    fun getRelevantMessageCount(): Int {
        val room = _roomData.value
        val myTeam = room.players.firstOrNull { it.playerName == playerName }?.team ?: "Team1"
        return room.chatMessages.values.count { !it.isTeamOnly || (it.isTeamOnly && it.team == myTeam) }
    }

    fun backToLobby() {
        currentAppState = OnlineAppState.LOBBY
        roomListener?.let { db.child("rooms").child(roomCode).removeEventListener(it) }
    }
    
    fun syncPresence() {
        if (roomCode.isNotEmpty() && playerName.isNotEmpty()) {
            val ref = db.child("rooms").child(roomCode).child("presence").child(playerName)
            ref.setValue(true)
            ref.onDisconnect().setValue(false)
            
            val currentRoom = _roomData.value
            if (currentRoom.originalHostName == playerName && currentRoom.hostName != playerName) {
                db.child("rooms").child(roomCode).child("hostName").setValue(playerName)
            }
        }
    }

    fun hostManualShuffle() {
        val room = _roomData.value
        if (room.hostName != playerName) return
        val newDeck = buildDeck()
        db.child("rooms").child(roomCode).updateChildren(mapOf(
            "deck" to newDeck,
            "message" to "Host shuffled the deck manually!"
        ))
    }
    
    fun updateTimerSettings(enabled: Boolean, duration: Int, context: Context) {
        val room = _roomData.value
        if (room.hostName != playerName) return
        
        context.getSharedPreferences("SeqPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("saved_timer_enabled", enabled)
            .putInt("saved_timer_duration", duration)
            .apply()
            
        db.child("rooms").child(roomCode).updateChildren(mapOf(
            "timerEnabled" to enabled,
            "timerDurationSecs" to duration
        ))
    }

    fun sendChatMessage(text: String, isTeamOnly: Boolean) {
        if (text.isBlank() || roomCode.isEmpty()) return
        val room = _roomData.value
        val myTeam = room.players.firstOrNull { it.playerName == playerName }?.team ?: "Team1"
        val msg = ChatMessage(sender = playerName, team = myTeam, text = text, isTeamOnly = isTeamOnly, timestamp = System.currentTimeMillis())
        db.child("rooms").child(roomCode).child("chatMessages").push().setValue(msg)
    }

    fun proceedToCreate(name: String, context: Context) {
        val safeName = name.trim().replace(Regex("[.#$\\[\\]]"), "")
        if (safeName.isBlank()) { lobbyError = "Enter a valid name"; return }
        playerName = safeName
        context.getSharedPreferences("SeqPrefs", Context.MODE_PRIVATE).edit().putString("saved_name", safeName).apply()
        
        roomCode = Random.nextInt(1000, 9999).toString()
        lobbyError = ""
        currentAppState = OnlineAppState.CREATE_ROOM
    }

    fun proceedToJoin(name: String, context: Context) {
        val safeName = name.trim().replace(Regex("[.#$\\[\\]]"), "")
        if (safeName.isBlank()) { lobbyError = "Enter a valid name"; return }
        playerName = safeName
        context.getSharedPreferences("SeqPrefs", Context.MODE_PRIVATE).edit().putString("saved_name", safeName).apply()
        
        lobbyError = ""
        currentAppState = OnlineAppState.JOIN_ROOM
    }

    fun executeCreateRoom(password: String, context: Context) {
        roomPassword = password
        currentAppState = OnlineAppState.WAITING_ROOM
        
        val prefs = context.getSharedPreferences("SeqPrefs", Context.MODE_PRIVATE)
        val savedTimerEnabled = prefs.getBoolean("saved_timer_enabled", false)
        val savedTimerDuration = prefs.getInt("saved_timer_duration", 30)
        
        val hostPlayer = FBPlayer(playerId = 1, playerName = playerName, team = "Team1", hand = emptyList())
        val defaultColors = mapOf("Team1" to TEAM_COLORS[0], "Team2" to TEAM_COLORS[1], "Team3" to TEAM_COLORS[2])
        val dateStr = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        
        val initialRoom = GameRoom(
            roomId = roomCode, password = password, status = "WAITING", 
            hostName = playerName, originalHostName = playerName,
            numberOfTeams = 2, turnPlayerId = 1, message = "Waiting for players...",
            board = buildInitialBoard(), players = listOf(hostPlayer), deck = buildDeck(), 
            teamColors = defaultColors, sessionStartTimeStr = dateStr, lastPlayedCard = null,
            timerEnabled = savedTimerEnabled, timerDurationSecs = savedTimerDuration, 
            currentTurnStartTime = 0L, chatMessages = emptyMap()
        )
        
        db.child("rooms").child(roomCode).setValue(initialRoom).addOnSuccessListener { 
            syncPresence()
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
                return@addOnSuccessListener
            }
            
            if (room.players.any { it.playerName == playerName }) {
                roomCode = code
                syncPresence()
                listenToRoom(code)
                currentAppState = if (room.status == "PLAYING" || room.status == "FINISHED") OnlineAppState.PLAYING else OnlineAppState.WAITING_ROOM
                return@addOnSuccessListener
            }
            
            if (room.password != password) { 
                lobbyError = "Wrong password." 
            } 
            else if (room.status != "WAITING" && room.status != "FINISHED") { 
                lobbyError = "Game actively playing." 
            } 
            else if (room.players.size >= 12) { 
                lobbyError = "Room is full (max 12)." 
            } 
            else {
                roomCode = code
                val newId = room.players.size + 1
                val teamList = if (room.numberOfTeams == 2) listOf("Team1", "Team2") else listOf("Team1", "Team2", "Team3")
                val assignedTeam = teamList[(newId - 1) % room.numberOfTeams]
                
                val updatedPlayers = room.players.toMutableList().apply { 
                    add(FBPlayer(playerId = newId, playerName = playerName, team = assignedTeam, hand = emptyList())) 
                }
                
                db.child("rooms").child(code).child("players").setValue(updatedPlayers).addOnSuccessListener { 
                    syncPresence()
                    listenToRoom(code)
                    currentAppState = if (room.status == "FINISHED") OnlineAppState.PLAYING else OnlineAppState.WAITING_ROOM 
                }
            }
        }.addOnFailureListener { lobbyError = "Connection failed." }
    }

    fun kickPlayer(playerIdToKick: Int) {
        val room = _roomData.value
        if (room.hostName != playerName) return
        val players = room.players.toMutableList()
        val pKick = players.firstOrNull { it.playerId == playerIdToKick } ?: return
        
        players.removeAll { it.playerId == playerIdToKick }
        val updatedPlayers = players.mapIndexed { index, p -> p.copy(playerId = index + 1) }
        db.child("rooms").child(roomCode).child("players").setValue(updatedPlayers)
        db.child("rooms").child(roomCode).child("presence").child(pKick.playerName).removeValue()
    }

    fun changeTotalTeams(newTeamCount: Int) {
        val room = _roomData.value
        if (room.hostName != playerName) return
        if (room.numberOfTeams == newTeamCount) return
        
        val teamList = if (newTeamCount == 2) listOf("Team1", "Team2") else listOf("Team1", "Team2", "Team3")
        val updatedPlayers = room.players.mapIndexed { index, player ->
            val assignedTeam = teamList[index % newTeamCount]
            player.copy(team = assignedTeam)
        }
        
        val updates = mapOf(
            "numberOfTeams" to newTeamCount,
            "players" to updatedPlayers
        )
        db.child("rooms").child(roomCode).updateChildren(updates)
    }

    fun changePlayerTeam(playerId: Int) {
        val room = _roomData.value
        if (room.hostName != playerName) return
        val players = room.players.toMutableList()
        val index = players.indexOfFirst { it.playerId == playerId }
        
        if (index != -1) {
            val currentTeam = players[index].team
            val nextTeam = if (room.numberOfTeams == 2) { 
                if (currentTeam == "Team1") "Team2" else "Team1" 
            } else { 
                when (currentTeam) { "Team1" -> "Team2"; "Team2" -> "Team3"; else -> "Team1" } 
            }
            players[index] = players[index].copy(team = nextTeam)
            db.child("rooms").child(roomCode).child("players").setValue(players)
        }
    }
    
    fun cycleTeamColor(team: String) {
        val room = _roomData.value
        if (room.hostName != playerName) return
        val currentC = room.teamColors[team] ?: TEAM_COLORS[0]
        var nextIdx = TEAM_COLORS.indexOf(currentC)
        if (nextIdx == -1) nextIdx = 0
        
        val usedColorsByOtherTeams = room.teamColors.filterKeys { it != team }.values.toSet()
        
        var attempts = 0
        do {
            nextIdx = (nextIdx + 1) % TEAM_COLORS.size
            attempts++
        } while (TEAM_COLORS[nextIdx] in usedColorsByOtherTeams && attempts < TEAM_COLORS.size)
        
        val mutColors = room.teamColors.toMutableMap()
        mutColors[team] = TEAM_COLORS[nextIdx]
        db.child("rooms").child(roomCode).child("teamColors").setValue(mutColors)
    }

    fun hostStartGame() {
        val room = _roomData.value
        val pCount = room.players.size
        
        if (pCount !in listOf(2, 3, 4, 6, 8, 9, 10, 12)) { lobbyError = "Cannot start: Invalid player count."; return }
        if (pCount % room.numberOfTeams != 0) { lobbyError = "Cannot start: Uneven teams ($pCount players / ${room.numberOfTeams} teams)."; return }

        lobbyError = ""
        val teamList = if (room.numberOfTeams == 2) listOf("Team1", "Team2") else listOf("Team1", "Team2", "Team3")
        val groupedByTeam = room.players.groupBy { it.team }
        val reorderedPlayers = mutableListOf<FBPlayer>()
        val maxPerTeam = groupedByTeam.values.maxOfOrNull { it.size } ?: 0
        
        for (i in 0 until maxPerTeam) { 
            for (team in teamList) { 
                groupedByTeam[team]?.getOrNull(i)?.let { reorderedPlayers.add(it) } 
            } 
        }

        val currentDeck = buildDeck().toMutableList()
        val handSize = when (pCount) { 2 -> 7; 3, 4 -> 6; 6 -> 5; 8, 9 -> 4; else -> 3 }
        val hands = Array(pCount) { mutableListOf<FBCard>() }
        
        repeat(handSize) {
            for (i in 0 until pCount) {
                if (currentDeck.isNotEmpty()) {
                    hands[i].add(currentDeck.removeAt(0))
                }
            }
        }
        
        val updatedPlayers = reorderedPlayers.mapIndexed { index, player ->
            player.copy(playerId = index + 1, hand = hands[index])
        }

        val firstPlayerName = updatedPlayers.firstOrNull()?.playerName ?: "Player 1"
        val firstPlayerId = updatedPlayers.firstOrNull()?.playerId ?: 1

        val updates = mapOf(
            "status" to "PLAYING", 
            "deck" to currentDeck, 
            "players" to updatedPlayers,
            "turnPlayerId" to firstPlayerId, 
            "message" to "Game started! $firstPlayerName's turn.",
            "matchStartTime" to System.currentTimeMillis(),
            "lastPlayedCard" to null,
            "currentTurnStartTime" to System.currentTimeMillis()
        )
        db.child("rooms").child(roomCode).updateChildren(updates)
    }

    fun voteRematch(vote: Boolean) {
        val room = _roomData.value
        val mutVotes = room.rematchVotes.toMutableMap()
        mutVotes[playerName] = vote
        db.child("rooms").child(roomCode).child("rematchVotes").setValue(mutVotes)
    }

    fun hostStartRematch() {
        val room = _roomData.value
        if (room.hostName != playerName) return
        val remainingPlayers = room.players.filter { room.rematchVotes[it.playerName] == true }
        if (remainingPlayers.isEmpty()) return
        
        val pCount = remainingPlayers.size
        
        if (pCount !in listOf(2, 3, 4, 6, 8, 9, 10, 12)) {
            db.child("rooms").child(roomCode).child("message").setValue("Cannot start: Invalid player count.")
            return
        }
        if (pCount % room.numberOfTeams != 0) {
            db.child("rooms").child(roomCode).child("message").setValue("Cannot start: Uneven teams. Waiting for players...")
            return
        }

        val teamList = if (room.numberOfTeams == 2) listOf("Team1", "Team2") else listOf("Team1", "Team2", "Team3")
        val groupedByTeam = remainingPlayers.groupBy { it.team }
        val reorderedPlayers = mutableListOf<FBPlayer>()
        val maxPerTeam = groupedByTeam.values.maxOfOrNull { it.size } ?: 0
        
        for (i in 0 until maxPerTeam) { 
            for (team in teamList) { 
                groupedByTeam[team]?.getOrNull(i)?.let { reorderedPlayers.add(it) } 
            } 
        }

        val newDeck = buildDeck().toMutableList()
        val handSize = when { pCount <= 2 -> 7; pCount in 3..4 -> 6; pCount == 6 -> 5; pCount in 8..9 -> 4; else -> 3 }
        val hands = Array(pCount) { mutableListOf<FBCard>() }
        
        repeat(handSize) {
            for (i in 0 until pCount) {
                if (newDeck.isNotEmpty()) {
                    hands[i].add(newDeck.removeAt(0))
                }
            }
        }
        
        val updatedPlayers = reorderedPlayers.mapIndexed { index, player ->
            player.copy(playerId = index + 1, hand = hands[index], timePlayedMs = 0L, wildUsed = 0, removeUsed = 0)
        }
        
        val nextTurnIndex = room.matchNumber % pCount
        val firstTurnPlayerId = updatedPlayers.getOrNull(nextTurnIndex)?.playerId ?: updatedPlayers.first().playerId
        val firstPlayerName = updatedPlayers.getOrNull(nextTurnIndex)?.playerName ?: updatedPlayers.first().playerName
        
        val updates = mapOf(
            "status" to "PLAYING", 
            "deck" to newDeck, 
            "board" to buildInitialBoard(), 
            "players" to updatedPlayers,
            "turnPlayerId" to firstTurnPlayerId, 
            "message" to "Match ${room.matchNumber+1} started! $firstPlayerName's turn.",
            "matchStartTime" to System.currentTimeMillis(), 
            "rematchVotes" to emptyMap<String, Boolean>(),
            "lastMoveRow" to -1, 
            "lastMoveCol" to -1, 
            "matchNumber" to room.matchNumber + 1,
            "lastPlayedCard" to null,
            "currentTurnStartTime" to System.currentTimeMillis()
        )
        db.child("rooms").child(roomCode).updateChildren(updates)
    }

    private fun listenToRoom(code: String) {
        roomListener?.let { db.child("rooms").child(code).removeEventListener(it) }
        roomListener = db.child("rooms").child(code).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(GameRoom::class.java)
                if (room != null) {
                    val wasMyTurn = (_roomData.value.turnPlayerId == myPlayerId)
                    val isMyTurnNow = (room.turnPlayerId == myPlayerId && room.status == "PLAYING")
                    
                    if (room.status == "PLAYING" && currentAppState == OnlineAppState.WAITING_ROOM) {
                        currentAppState = OnlineAppState.PLAYING
                    }
                    
                    val isHostOnline = room.presence[room.hostName] == true
                    val hostStillInGame = room.players.any { it.playerName == room.hostName }
                    if (!isHostOnline || !hostStillInGame) {
                        val newHost = room.players.firstOrNull { room.presence[it.playerName] == true }?.playerName
                        if (newHost != null && newHost == playerName && room.originalHostName != room.hostName) {
                            db.child("rooms").child(roomCode).child("hostName").setValue(newHost)
                        }
                    }

                    _roomData.value = room
                }
            }
            override fun onCancelled(error: DatabaseError) { lobbyError = "Lost connection." }
        })
    }
    
    fun triggerCpuTurn(playerId: Int) {
        val room = _roomData.value
        if (room.status != "PLAYING" || room.turnPlayerId != playerId) return
        val cpuPlayer = room.players.firstOrNull { it.playerId == playerId } ?: return
        
        val legalMoves = cpuPlayer.hand.flatMap { card ->
            room.board.filter { space -> isOnlineLegalMove(card, space, cpuPlayer.team) }.map { space -> card to space }
        }
        
        if (legalMoves.isNotEmpty()) {
            val bestMove = legalMoves.maxByOrNull { (card, space) -> evaluateCpuMoveScore(card, space, cpuPlayer.team, room.board) } ?: legalMoves.random()
            performOnlineMove(bestMove.first, bestMove.second.r, bestMove.second.c, cpuPlayer, isCpu = true)
        } else {
            val deadCard = cpuPlayer.hand.firstOrNull { card -> card.rank != "J" && room.board.filter { it.card.matches(card) }.all { it.occupant != "NONE" } }
            if (deadCard != null) {
                replaceDeadCardInternal(deadCard, cpuPlayer, isCpu = true)
            } else {
                val nextTurnId = if (room.turnPlayerId >= room.players.size) 1 else room.turnPlayerId + 1
                val nextPlayerName = room.players.firstOrNull { it.playerId == nextTurnId }?.playerName ?: "Player $nextTurnId"
                db.child("rooms").child(roomCode).updateChildren(mapOf(
                    "turnPlayerId" to nextTurnId, 
                    "message" to "CPU (${cpuPlayer.playerName}) had no moves. $nextPlayerName's turn.",
                    "currentTurnStartTime" to System.currentTimeMillis()
                ))
            }
        }
    }

    private fun evaluateCpuMoveScore(card: FBCard, space: FBSpace, myTeam: String, board: List<FBSpace>): Int {
        var score = 0
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        if (card.isOneEyedJack) {
            val enemyTeam = space.occupant
            var groupSize = 1
            for((dr, dc) in dirs) {
                var localGrp = 0
                for(i in 1..4) { val r = space.r+dr*i; val c = space.c+dc*i; if(r in 0..9 && c in 0..9 && board.firstOrNull{it.r==r && it.c==c}?.occupant == enemyTeam) localGrp++ else break }
                for(i in 1..4) { val r = space.r-dr*i; val c = space.c-dc*i; if(r in 0..9 && c in 0..9 && board.firstOrNull{it.r==r && it.c==c}?.occupant == enemyTeam) localGrp++ else break }
                if(localGrp > groupSize) groupSize = localGrp
            }
            score += groupSize * 25
        } else {
            var groupSize = 1
            for((dr, dc) in dirs) {
                var localGrp = 0
                for(i in 1..4) { val r = space.r+dr*i; val c = space.c+dc*i; val occ = board.firstOrNull{it.r==r && it.c==c}?.occupant; if(occ == myTeam || occ == "★") localGrp++ else break }
                for(i in 1..4) { val r = space.r-dr*i; val c = space.c-dc*i; val occ = board.firstOrNull{it.r==r && it.c==c}?.occupant; if(occ == myTeam || occ == "★") localGrp++ else break }
                if(localGrp > groupSize) groupSize = localGrp
            }
            score += groupSize * 15
            if (card.isTwoEyedJack) score += 5 
        }
        return score + Random.nextInt(0, 5)
    }

    private fun isOnlineLegalMove(card: FBCard, space: FBSpace, myTeam: String): Boolean {
        if (space.card.rank == "★") return false
        return when { 
            card.isTwoEyedJack -> space.occupant == "NONE"
            card.isOneEyedJack -> space.occupant != "NONE" && space.occupant != myTeam && !space.isCompletedSequence
            else -> space.occupant == "NONE" && space.card.matches(card) 
        }
    }

    private fun updateAllSequencesAndCheckWinner(board: MutableList<FBSpace>): String? {
        val teams = if (_roomData.value.numberOfTeams == 2) listOf("Team1", "Team2") else listOf("Team1", "Team2", "Team3")
        val reqSeq = if (_roomData.value.numberOfTeams == 2) 2 else 1
        var winningTeam: String? = null
        val protectedPositions = mutableSetOf<Pair<Int, Int>>()

        for (team in teams) {
            val candidates = mutableListOf<Set<Pair<Int, Int>>>()
            for (r in 0..9) { 
                for (c in 0..9) { 
                    for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
                        val positions = (0..4).map { r + dr * it to c + dc * it }
                        if (positions.none { (pr, pc) -> pr !in 0..9 || pc !in 0..9 } && positions.all { (pr, pc) -> val space = board.firstOrNull { it.r == pr && it.c == pc }; space != null && (space.card.rank == "★" || space.occupant == team) }) {
                            candidates.add(positions.toSet())
                        }
                    } 
                } 
            }
            val accepted = mutableListOf<Set<Pair<Int, Int>>>()
            for (cand in candidates) { 
                if (accepted.all { prev -> cand.intersect(prev).size <= 1 }) accepted.add(cand) 
            }
            protectedPositions.addAll(accepted.flatten())
            if (accepted.size >= reqSeq && winningTeam == null) winningTeam = team
        }
        
        for (i in board.indices) { 
            val isLocked = (board[i].r to board[i].c) in protectedPositions
            board[i] = board[i].copy(completedSequence = isLocked) 
        }
        return winningTeam
    }

    fun playCard(card: FBCard, row: Int, col: Int) {
        val room = _roomData.value
        if (room.turnPlayerId != myPlayerId || room.status == "FINISHED") return
        val myPlayerObj = room.players.firstOrNull { it.playerId == myPlayerId } ?: return
        performOnlineMove(card, row, col, myPlayerObj, isCpu = false)
    }
    
    private fun performOnlineMove(card: FBCard, row: Int, col: Int, actingPlayer: FBPlayer, isCpu: Boolean) {
        val room = _roomData.value
        val updatedBoard = room.board.toMutableList()
        val targetIndex = updatedBoard.indexOfFirst { it.r == row && it.c == col }
        if (targetIndex == -1) return
        val targetSpace = updatedBoard[targetIndex]
        
        if (!isOnlineLegalMove(card, targetSpace, actingPlayer.team)) return

        val newOccupant = if (card.isOneEyedJack) "NONE" else actingPlayer.team
        updatedBoard[targetIndex] = if (card.isOneEyedJack) targetSpace.copy(occupant = newOccupant, completedSequence = false) else targetSpace.copy(occupant = newOccupant)

        val winningTeam = updateAllSequencesAndCheckWinner(updatedBoard)

        val updatedHand = actingPlayer.hand.toMutableList()
        updatedHand.remove(card)
        val deckList = room.deck.toMutableList()
        if (deckList.isNotEmpty()) updatedHand.add(deckList.removeAt(0))

        val currentMillis = System.currentTimeMillis()
        val timeTaken = if (isCpu) 1500L else if (room.currentTurnStartTime > 0L) currentMillis - room.currentTurnStartTime else 0L

        val updatedPlayers = room.players.map { p -> 
            if (p.playerId == actingPlayer.playerId) p.copy(hand = updatedHand, wildUsed = p.wildUsed + if(card.isTwoEyedJack) 1 else 0, removeUsed = p.removeUsed + if(card.isOneEyedJack) 1 else 0, timePlayedMs = p.timePlayedMs + timeTaken) else p 
        }
        
        val nextTurnId = if (room.turnPlayerId >= room.players.size) 1 else room.turnPlayerId + 1
        val nextPlayerName = room.players.firstOrNull { it.playerId == nextTurnId }?.playerName ?: "Player $nextTurnId"

        val prefix = if (isCpu) "CPU (${actingPlayer.playerName}) " else "${actingPlayer.playerName} "
        val actionMsg = when { 
            card.isOneEyedJack -> "${prefix}used a Remove Jack."
            card.isTwoEyedJack -> "${prefix}used a Wild Jack."
            else -> "${prefix}placed a chip." 
        }

        if (winningTeam != null) {
            val totalMatchSecs = if (room.matchStartTime > 0) (System.currentTimeMillis() - room.matchStartTime) / 1000 else 0
            val mHr = totalMatchSecs / 3600
            val mMin = (totalMatchSecs % 3600) / 60
            val mSec = totalMatchSecs % 60
            val matchTimeStr = if (mHr > 0) String.format("%02d:%02d:%02d", mHr, mMin, mSec) else String.format("%02d:%02d", mMin, mSec)

            val isHatTrick = (room.lastWinningTeam == winningTeam && room.consecutiveWins + 1 >= 3)
            val consec = if (room.lastWinningTeam == winningTeam) room.consecutiveWins + 1 else 1
            val newHist = room.matchHistory.toMutableList().apply { add("Match ${room.matchNumber}: $winningTeam Won ($matchTimeStr)") }
            val htStr = if(isHatTrick) " HAT-TRICK!" else ""
            
            _roomData.value = room.copy(
                board = updatedBoard, players = updatedPlayers, status = "FINISHED", 
                message = "$actionMsg Team $winningTeam Wins!$htStr", winnerTeam = winningTeam, 
                lastMoveRow = row, lastMoveCol = col, matchHistory = newHist, 
                consecutiveWins = consec, lastWinningTeam = winningTeam, lastPlayedCard = card
            )

            val winUpdates = mapOf(
                "board" to updatedBoard, "players" to updatedPlayers, "status" to "FINISHED", 
                "message" to "$actionMsg Team $winningTeam Wins!$htStr", "winnerTeam" to winningTeam, 
                "lastMoveRow" to row, "lastMoveCol" to col, "matchHistory" to newHist, 
                "consecutiveWins" to consec, "lastWinningTeam" to winningTeam, "lastPlayedCard" to card
            )
            db.child("rooms").child(roomCode).updateChildren(winUpdates)
        } else {
            _roomData.value = room.copy(
                board = updatedBoard, deck = deckList, players = updatedPlayers, 
                turnPlayerId = nextTurnId, message = "$actionMsg $nextPlayerName's turn.", 
                lastMoveRow = row, lastMoveCol = col, lastPlayedCard = card, currentTurnStartTime = System.currentTimeMillis()
            )

            val updates = mapOf(
                "board" to updatedBoard, "deck" to deckList, "players" to updatedPlayers, 
                "turnPlayerId" to nextTurnId, "message" to "$actionMsg $nextPlayerName's turn.", 
                "lastMoveRow" to row, "lastMoveCol" to col, "lastPlayedCard" to card, "currentTurnStartTime" to System.currentTimeMillis()
            )
            db.child("rooms").child(roomCode).updateChildren(updates)
        }
    }
    
    fun replaceDeadCard(card: FBCard) {
        val room = _roomData.value
        if (room.turnPlayerId != myPlayerId || room.status == "FINISHED") return
        val myPlayerObj = room.players.firstOrNull { it.playerId == myPlayerId } ?: return
        replaceDeadCardInternal(card, myPlayerObj, isCpu = false)
    }
    
    private fun replaceDeadCardInternal(card: FBCard, actingPlayer: FBPlayer, isCpu: Boolean) {
        val room = _roomData.value
        if (card.rank == "J") { 
            if (!isCpu) db.child("rooms").child(roomCode).child("message").setValue("A Jack is not a dead card.")
            return 
        }
        val matches = room.board.filter { it.card.matches(card) }
        if (matches.isEmpty() || matches.any { it.occupant == "NONE" }) { 
            if (!isCpu) db.child("rooms").child(roomCode).child("message").setValue("That card is not dead. Open space available.")
            return 
        }

        val updatedHand = actingPlayer.hand.toMutableList()
        updatedHand.remove(card)
        val deckList = room.deck.toMutableList()
        if (deckList.isNotEmpty()) updatedHand.add(deckList.removeAt(0))

        val currentMillis = System.currentTimeMillis()
        val timeTaken = if (isCpu) 1500L else if (room.currentTurnStartTime > 0L) currentMillis - room.currentTurnStartTime else 0L

        val updatedPlayers = room.players.map { 
            if (it.playerId == actingPlayer.playerId) it.copy(hand = updatedHand, timePlayedMs = it.timePlayedMs + timeTaken) else it 
        }
        val prefix = if (isCpu) "CPU (${actingPlayer.playerName}) " else "${actingPlayer.playerName} "

        _roomData.value = room.copy(
            deck = deckList, players = updatedPlayers, message = "${prefix}replaced a dead card. ${actingPlayer.playerName}'s turn.", lastPlayedCard = card, currentTurnStartTime = System.currentTimeMillis()
        )

        db.child("rooms").child(roomCode).updateChildren(
            mapOf("deck" to deckList, "players" to updatedPlayers, "message" to "${prefix}replaced a dead card. ${actingPlayer.playerName}'s turn.", "lastPlayedCard" to card, "currentTurnStartTime" to System.currentTimeMillis())
        )
    }

    private fun buildDeck(): List<FBCard> {
        val secureRnd = SecureRandom()
        val initialList = mutableListOf<FBCard>()
        var id = 0
        repeat(2) {
            for (s in listOf("♠", "♥", "♦", "♣")) {
                for (r in listOf("A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2")) {
                    initialList.add(FBCard(s, r, id++))
                }
            }
        }
        
        var currentDeck = initialList.toList()
        
        repeat(10) {
            val halfSize = currentDeck.size / 2
            val half1 = currentDeck.take(halfSize).shuffled(secureRnd)
            val half2 = currentDeck.drop(halfSize).shuffled(secureRnd)
            
            val combined = half1 + half2
            currentDeck = combined.shuffled(secureRnd)
        }
        
        return currentDeck
    }
    
    private fun buildInitialBoard(): List<FBSpace> { 
        var idCounter = -100
        return buildList { 
            for (r in 0..9) { 
                for (c in 0..9) { 
                    val cardStr = BOARD_LAYOUT[r][c]
                    val fbCard = if (cardStr == "★") FBCard("", "★", idCounter--) else FBCard(suit = cardStr.takeLast(1), rank = cardStr.dropLast(1), id = idCounter--)
                    add(FBSpace(r, c, fbCard, "NONE")) 
                } 
            } 
        } 
    }
}

// ==============================================================================
// CHAT UI COMPONENT
// ==============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDialog(vm: MultiplayerViewModel, onDismiss: () -> Unit) {
    val room by vm.roomData.collectAsState()
    val myPlayer = room.players.firstOrNull { it.playerName == vm.playerName }
    val myTeam = myPlayer?.team ?: "Team1"
    
    var isTeamChat by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filteredMessages = room.chatMessages.values.sortedBy { it.timestamp }.filter { 
        !it.isTeamOnly || (it.isTeamOnly && it.team == myTeam)
    }

    LaunchedEffect(filteredMessages.size) {
        vm.lastReadMessageCount = vm.getRelevantMessageCount() // Auto-mark read while open
        if (filteredMessages.isNotEmpty()) {
            listState.scrollToItem(max(0, filteredMessages.size - 1))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Chat Room", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                    TextButton(onClick = onDismiss) { Text("Close", color = Color.Red) }
                }
                
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                    Button(
                        onClick = { isTeamChat = false },
                        colors = ButtonDefaults.buttonColors(containerColor = if (!isTeamChat) Color(0xFF1976D2) else Color.LightGray),
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    ) { Text("ALL", fontSize = 12.sp) }
                    Button(
                        onClick = { isTeamChat = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isTeamChat) Color(room.teamColors[myTeam] ?: TEAM_COLORS[0]) else Color.LightGray),
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    ) { Text("TEAM ONLY", fontSize = 12.sp) }
                }
                
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, Color.LightGray).padding(8.dp)) {
                    items(filteredMessages) { msg ->
                        val isMe = msg.sender == vm.playerName
                        val msgColor = if (msg.isTeamOnly) Color(room.teamColors[msg.team] ?: TEAM_COLORS[0]) else Color.DarkGray
                        val align = if (isMe) Alignment.End else Alignment.Start
                        val bg = if (isMe) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                        
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
                            Text(text = if (isMe) "You" else msg.sender, fontSize = 10.sp, color = Color.Gray)
                            Box(modifier = Modifier.background(bg, MaterialTheme.shapes.small).padding(8.dp)) {
                                Text(text = msg.text, fontSize = 14.sp, color = msgColor)
                            }
                        }
                    }
                }
                
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = chatInput, 
                        onValueChange = { chatInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type message...") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { 
                        vm.sendChatMessage(chatInput, isTeamChat)
                        chatInput = ""
                    }) { Text("Send") }
                }
            }
        }
    }
}

// ==============================================================================
// ONLINE SCREENS
// ==============================================================================
@Composable
fun OnlineLobbyScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = "Online Multiplayer", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 32.dp))
        Button(onClick = { vm.currentAppState = OnlineAppState.ENTER_NAME; vm.lobbyError = "" }, modifier = Modifier.fillMaxWidth(0.6f).padding(8.dp)) { 
            Text(text = "Play Online") 
        }
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 32.dp)) { 
            Text(text = "Back to Main Menu", color = Color.Red) 
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineEnterNameScreen(vm: MultiplayerViewModel) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("SeqPrefs", Context.MODE_PRIVATE)
    var nameInput by remember { mutableStateOf(prefs.getString("saved_name", "") ?: "") }
    
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = "Enter Your Name", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text(text = "Display Name") }, modifier = Modifier.padding(vertical = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.proceedToCreate(nameInput, context) }) { Text(text = "Create Room") }
            Button(onClick = { vm.proceedToJoin(nameInput, context) }) { Text(text = "Join Room") }
        }
        TextButton(onClick = { vm.currentAppState = OnlineAppState.LOBBY }) { Text(text = "Back") }
        if (vm.lobbyError.isNotEmpty()) {
            Text(text = vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineCreateScreen(vm: MultiplayerViewModel) {
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val rc = vm.roomCode
        Text(text = "Room Code: $rc", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(text = "Optional Password") }, modifier = Modifier.padding(vertical = 8.dp))
        Spacer(Modifier.height(32.dp))
        Button(onClick = { vm.executeCreateRoom(password, context) }) { Text(text = "Open Lobby") }
        TextButton(onClick = { vm.currentAppState = OnlineAppState.ENTER_NAME }) { Text(text = "Cancel") }
        if (vm.lobbyError.isNotEmpty()) {
            Text(text = vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineJoinScreen(vm: MultiplayerViewModel) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = "Join Friend's Room", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text(text = "4-Digit Room Code") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(text = "Password (if any)") }, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = { vm.joinRoom(code, password) }) { Text(text = "Join Lobby") }
        TextButton(onClick = { vm.currentAppState = OnlineAppState.ENTER_NAME }) { Text(text = "Cancel") }
        if (vm.lobbyError.isNotEmpty()) {
            Text(text = vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun OnlineWaitingScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    val room by vm.roomData.collectAsState()
    val isHost = room.hostName == vm.playerName
    val context = LocalContext.current
    var showChat by remember { mutableStateOf(false) }
    val unreadCount = max(0, vm.getRelevantMessageCount() - vm.lastReadMessageCount)

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val rc = room.roomId
            Text(text = "Room: $rc", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
            Button(onClick = { showChat = true; vm.lastReadMessageCount = vm.getRelevantMessageCount() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))) { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💬 Chat")
                    if (unreadCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(modifier = Modifier.size(20.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
                            Text(text = "$unreadCount", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        
        if (isHost) {
            TextButton(onClick = {
                val sendIntent = Intent().apply { action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, "Join my Sequence match! Code: ${room.roomId}"); type = "text/plain"; setPackage("com.whatsapp") }
                try { context.startActivity(sendIntent) } catch(e: Exception) { /* No whatsapp */ }
            }) { Text(text = "Share via WhatsApp", color = Color(0xFF25D366), fontWeight = FontWeight.Bold) }
        }

        val pCountStr = "Players Joined (${room.players.size}):"
        Text(text = pCountStr, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))

        room.players.forEach { p ->
            val isOnline = room.presence[p.playerName] == true
            val teamCol = Color(room.teamColors[p.team] ?: TEAM_COLORS[0])
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                if(isHost) { Box(Modifier.size(16.dp).clip(CircleShape).background(teamCol).border(1.dp, Color.Black, CircleShape).clickable { vm.cycleTeamColor(p.team) }) }
                Spacer(Modifier.width(8.dp))
                val pStr = "- ${p.playerName} (${p.team})"
                Text(text = pStr, fontSize = 16.sp, color = if (isOnline) teamCol else Color.Gray, modifier = Modifier.weight(1f))
                
                if (!isOnline) {
                    Text(text = "(Offline) ", fontSize = 12.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                }
                if (isHost && p.playerName != vm.playerName) {
                    Button(onClick = { vm.changePlayerTeam(p.playerId) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(4.dp)) { Text(text = "Team", fontSize = 10.sp) }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { vm.kickPlayer(p.playerId) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.height(30.dp), contentPadding = PaddingValues(4.dp)) { Text(text = "X", fontSize = 10.sp) }
                }
            }
        }
        
        if (isHost) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = "Number of Teams:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                listOf(2, 3).forEach { tCount -> 
                    Button(
                        onClick = { vm.changeTotalTeams(tCount) }, 
                        colors = ButtonDefaults.buttonColors(containerColor = if (room.numberOfTeams == tCount) Color(0xFF1976D2) else Color.Gray),
                        modifier = Modifier.padding(horizontal = 4.dp).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { 
                        Text(text = tCount.toString()) 
                    } 
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Turn Timer:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Checkbox(
                    checked = room.timerEnabled, 
                    onCheckedChange = { vm.updateTimerSettings(it, room.timerDurationSecs, context) }
                )
                if (room.timerEnabled) {
                    var tVal by remember(room.timerDurationSecs) { mutableStateOf(room.timerDurationSecs.toString()) }
                    BasicTextField(
                        value = tVal,
                        onValueChange = { newVal -> 
                            tVal = newVal
                            newVal.toIntOrNull()?.let { num -> if(num > 0) vm.updateTimerSettings(true, num, context) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(40.dp).background(Color.White).border(1.dp, Color.Gray).padding(4.dp),
                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                    )
                    Text(text = "s", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        } else {
            Text(text = "Teams: ${room.numberOfTeams}", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
            val tStr = if (room.timerEnabled) "${room.timerDurationSecs}s" else "Off"
            Text(text = "Turn Timer: $tStr", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
        }

        if (vm.lobbyError.isNotEmpty()) {
            Text(text = vm.lobbyError, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { vm.syncPresence() }) { Text(text = "Sync Status", color = Color(0xFF1976D2)) }
        }
        if (isHost) { 
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.hostManualShuffle() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))) { 
                    Text(text = "Shuffle Deck") 
                }
                Button(onClick = { vm.hostStartGame() }) { Text(text = "START GAME") } 
            }
        } 
        else { 
            CircularProgressIndicator()
            Text(text = "Waiting for host to start...", modifier = Modifier.padding(top = 12.dp)) 
        }

        TextButton(onClick = { vm.backToLobby(); onExit() }, modifier = Modifier.padding(top = 24.dp)) { 
            Text(text = "Leave Room", color = Color.Red) 
        }
    }
    
    if (showChat) {
        ChatDialog(vm, onDismiss = { showChat = false })
    }
}

@Composable
fun OnlineGameScreen(vm: MultiplayerViewModel, onExit: () -> Unit) {
    val room by vm.roomData.collectAsState()
    val myPlayer = room.players.firstOrNull { it.playerName == vm.playerName }
    val isMyTurn = myPlayer != null && room.turnPlayerId == myPlayer.playerId && room.status != "FINISHED"
    var selectedCard by remember { mutableStateOf<FBCard?>(null) }
    var showScorecard by remember { mutableStateOf(true) }
    var showChat by remember { mutableStateOf(false) }
    val unreadCount = max(0, vm.getRelevantMessageCount() - vm.lastReadMessageCount)
    
    // Central Play Animation State
    var centralCardToDisplay by remember { mutableStateOf<FBCard?>(null) }
    var animationTrigger by remember(room.lastPlayedCard) { mutableStateOf(room.lastPlayedCard) }
    
    val context = LocalContext.current
    var matchSeconds by remember { mutableStateOf(0L) }
    var turnTimeRemaining by remember { mutableStateOf(0) }
    
    LaunchedEffect(room.status, room.matchStartTime) {
        if(room.status == "PLAYING" && room.matchStartTime > 0) {
            showScorecard = true
            while(true) { matchSeconds = (System.currentTimeMillis() - room.matchStartTime) / 1000; delay(1000) }
        } else if (room.status == "FINISHED") {
            showScorecard = true
            if (room.winnerTeam.isNotEmpty() && room.players.isNotEmpty()) {
                HistoryManager.saveMatch(context, room)
            }
        }
    }

    LaunchedEffect(room.status, room.currentTurnStartTime, room.timerEnabled) {
        if (room.status == "PLAYING" && room.timerEnabled) {
            while (true) {
                val elapsed = (System.currentTimeMillis() - room.currentTurnStartTime) / 1000
                turnTimeRemaining = (room.timerDurationSecs - elapsed).toInt().coerceAtLeast(0)
                
                if (turnTimeRemaining <= 0) {
                    if (isMyTurn) {
                        vm.triggerCpuTurn(room.turnPlayerId)
                        break
                    } else if (vm.playerName == room.hostName) {
                        if (elapsed >= room.timerDurationSecs + 2) {
                            vm.triggerCpuTurn(room.turnPlayerId)
                            break
                        }
                    }
                }
                delay(200)
            }
        }
    }

    LaunchedEffect(animationTrigger) {
        if (animationTrigger != null) {
            centralCardToDisplay = animationTrigger
            delay(600)
            centralCardToDisplay = null
        }
    }

    LaunchedEffect(isMyTurn) {
        if (isMyTurn) {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)
                }
            } catch(e: Exception){}
        }
    }

    val myTeamColor = Color(room.teamColors[myPlayer?.team] ?: TEAM_COLORS[0])
    val teamSequences = remember(room.board, myPlayer?.team) {
        if (myPlayer != null) {
            val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
            val candidates = mutableListOf<Set<Pair<Int, Int>>>()
            for (r in 0..9) { 
                for (c in 0..9) { 
                    for ((dr, dc) in directions) {
                        val positions = (0..4).map { r + dr * it to c + dc * it }
                        if (positions.none { (pr, pc) -> pr !in 0..9 || pc !in 0..9 } && positions.all { (pr, pc) -> val space = room.board.firstOrNull { it.r == pr && it.c == pc }; space != null && (space.card.rank == "★" || space.occupant == myPlayer.team) }) {
                            candidates.add(positions.toSet())
                        }
                    } 
                } 
            }
            val accepted = mutableListOf<Set<Pair<Int, Int>>>()
            for (cand in candidates) { 
                if (accepted.all { prev -> cand.intersect(prev).size <= 1 }) accepted.add(cand) 
            }
            accepted.size
        } else 0
    }
    val reqSequences = if (room.numberOfTeams == 2) 2 else 1

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(5.dp)) {
            // New Clean Top Bar Layout
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Left side: Messages
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = room.message, fontWeight = FontWeight.Bold, color = if (isMyTurn) Color(0xFF07852B) else Color.DarkGray, fontSize = 14.sp)
                    }
                    val rInfo = "Room: ${room.roomId} | You: ${vm.playerName}"
                    Text(text = rInfo, fontSize = 10.sp, color = Color.Gray)
                }
                
                // Center: Time and Turn Timer Display
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
                    val h = matchSeconds / 3600
                    val m = (matchSeconds % 3600) / 60
                    val s = matchSeconds % 60
                    val tStr = if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
                    Text(text = tStr, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.DarkGray)
                    
                    if (room.timerEnabled && room.status == "PLAYING") {
                        val tColor = if (turnTimeRemaining <= 5) Color.Red else Color(0xFFE65100)
                        Text(text = "⏱ ${turnTimeRemaining}s", fontWeight = FontWeight.Bold, color = tColor, fontSize = 12.sp)
                    }
                }
                
                // Right side: Progress & Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sStr = "$teamSequences/$reqSequences"
                    Text(text = sStr, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = myTeamColor, modifier = Modifier.padding(end = 4.dp))
                    TextButton(onClick = { showChat = true; vm.lastReadMessageCount = vm.getRelevantMessageCount() }, contentPadding = PaddingValues(2.dp)) { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("💬 Chat", fontSize=12.sp, color = Color(0xFF00ACC1))
                            if (unreadCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Box(modifier = Modifier.size(16.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
                                    Text(text = "$unreadCount", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { vm.syncPresence() }, contentPadding = PaddingValues(2.dp)) { Text(text = "Sync", fontSize=12.sp) }
                    TextButton(onClick = { vm.backToLobby(); onExit() }, contentPadding = PaddingValues(2.dp)) { Text(text = "Exit", fontSize=12.sp, color = Color.Red) }
                }
            }

            if (room.board.isNotEmpty()) {
                LazyVerticalGrid(columns = GridCells.Fixed(10), modifier = Modifier.weight(1f)) {
                    items(room.board) { space ->
                        val isTwoEyed = selectedCard?.isTwoEyedJack == true
                        val isOneEyed = selectedCard?.isOneEyedJack == true
                        val myTeam = myPlayer?.team ?: "Team1"
                        val isLegalMove = selectedCard != null && space.card.rank != "★" && when { 
                            isTwoEyed -> space.occupant == "NONE"
                            isOneEyed -> space.occupant != "NONE" && space.occupant != myTeam && !space.isCompletedSequence
                            else -> space.occupant == "NONE" && space.card.matches(selectedCard!!) 
                        }
                        val isLastMove = space.r == room.lastMoveRow && space.c == room.lastMoveCol
                        
                        val bgBrush = when { 
                            space.card.rank == "★" -> Brush.linearGradient(listOf(Color(0xFFFFE082), Color(0xFFFFCA28)))
                            isLegalMove -> Brush.linearGradient(listOf(Color(0xFFA5D6A7), Color(0xFF81C784)))
                            isLastMove -> Brush.linearGradient(listOf(Color(0xFFFFF59D), Color(0xFFFFF176)))
                            space.isCompletedSequence -> Brush.linearGradient(listOf(Color(room.teamColors[space.occupant] ?: TEAM_COLORS[0]).copy(alpha=0.15f), Color(room.teamColors[space.occupant] ?: TEAM_COLORS[0]).copy(alpha=0.3f)))
                            else -> Brush.linearGradient(listOf(Color.White, Color(0xFFF3F3F3)))
                        }

                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.74f)
                            .padding(1.dp)
                            .shadow(2.dp, MaterialTheme.shapes.extraSmall)
                            .background(bgBrush, MaterialTheme.shapes.extraSmall)
                            .border(if (isLegalMove || space.isCompletedSequence || isLastMove) 2.dp else 0.5.dp, when { isLegalMove -> Color(0xFF07852B); isLastMove -> Color(0xFFFF9800); else -> Color.LightGray }, MaterialTheme.shapes.extraSmall)
                            .clickable(enabled = isLegalMove && isMyTurn) { vm.playCard(selectedCard!!, space.r, space.c); selectedCard = null }) {
                            
                            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = space.card.rank, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (space.card.suit == "♥" || space.card.suit == "♦") Color.Red else Color.Black)
                                Text(text = space.card.suit, fontSize = 9.sp, color = if (space.card.suit == "♥" || space.card.suit == "♦") Color.Red else Color.Black)
                            }
                            if (space.occupant != "NONE") {
                                val chipAlpha = if (space.isCompletedSequence) 0.62f else 0.9f
                                val chipBaseColor = Color(room.teamColors[space.occupant] ?: TEAM_COLORS[0])
                                val cColor1 = chipBaseColor.copy(alpha = chipAlpha)
                                val cColor2 = chipBaseColor.copy(alpha = (chipAlpha - 0.2f).coerceAtLeast(0.1f))
                                val chipBrush = Brush.radialGradient(listOf(cColor2, cColor1))
                                
                                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).fillMaxWidth(0.68f).aspectRatio(1f)
                                    .shadow(2.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(chipBrush)
                                    .border(if (space.isCompletedSequence) 2.dp else 1.dp, if (space.isCompletedSequence) Color.White else Color.Black.copy(alpha = 0.65f), CircleShape)) {
                                    if (space.isCompletedSequence) Text(text = "✓", Modifier.align(Alignment.Center), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            
            val playerListState = rememberLazyListState()
            val turnIndex = room.players.indexOfFirst { it.playerId == room.turnPlayerId }
            LaunchedEffect(room.turnPlayerId, room.players.size) {
                if (turnIndex in 0 until room.players.size) {
                    playerListState.animateScrollToItem(turnIndex)
                }
            }

            LazyRow(state = playerListState, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                items(room.players) { p ->
                    val isOnline = room.presence[p.playerName] == true
                    val pColor = Color(room.teamColors[p.team] ?: TEAM_COLORS[0])
                    val isThisTurn = p.playerId == room.turnPlayerId
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(if (isThisTurn) pColor.copy(alpha = 0.15f) else Color.Transparent, MaterialTheme.shapes.extraSmall).border(if (isThisTurn) 1.dp else 0.dp, if (isThisTurn) pColor else Color.Transparent, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if(isOnline) pColor else Color.Gray).border(0.5.dp, Color.Black, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        val pName = if(isOnline) p.playerName else "${p.playerName} (CPU)"
                        Text(text = pName, fontSize = 11.sp, fontWeight = if (isThisTurn) FontWeight.Bold else FontWeight.Normal, color = if (!isOnline) Color.Gray else if (isThisTurn) Color.Black else Color.DarkGray)
                        if(!isOnline && isThisTurn && room.status == "PLAYING" && (myPlayer?.team == p.team || vm.playerName == room.hostName)) {
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = { vm.triggerCpuTurn(p.playerId) }, modifier = Modifier.height(24.dp), contentPadding = PaddingValues(2.dp)) { 
                                Text(text = "Play CPU", fontSize = 9.sp) 
                            }
                        }
                    }
                }
            }

            val visibleCardIds = remember { mutableStateListOf<Int>() }
            LaunchedEffect(myPlayer?.hand) {
                val hand = myPlayer?.hand ?: emptyList()
                visibleCardIds.retainAll(hand.map { it.id }.toSet())
                hand.forEach { c ->
                    if (!visibleCardIds.contains(c.id)) {
                        delay(50)
                        visibleCardIds.add(c.id)
                    }
                }
            }

            Column {
                Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (myPlayer?.hand ?: emptyList()).forEach { card ->
                        AnimatedVisibility(
                            visible = visibleCardIds.contains(card.id),
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                            modifier = Modifier.weight(1f).padding(2.dp)
                        ) {
                            val selected = card == selectedCard
                            Box(
                                modifier = Modifier.fillMaxHeight()
                                    .shadow(if (selected) 6.dp else 2.dp, MaterialTheme.shapes.small)
                                    .background(Brush.linearGradient(listOf(Color.White, Color(0xFFF7F7F7))), MaterialTheme.shapes.small)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF07852B) else Color.LightGray, MaterialTheme.shapes.small)
                                    .clickable(enabled = isMyTurn) { selectedCard = if (selectedCard == card) null else card }
                            ) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(text = card.rank, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (card.suit == "♥" || card.suit == "♦") Color.Red else Color.Black)
                                    Text(text = card.suit, fontSize = 14.sp, color = if (card.suit == "♥" || card.suit == "♦") Color.Red else Color.Black)
                                    when { 
                                        card.isTwoEyedJack -> Text(text = "WILD", fontSize = 7.sp, color = Color.Blue, fontWeight = FontWeight.Bold)
                                        card.isOneEyedJack -> Text(text = "REMOVE", fontSize = 7.sp, color = Color.Red, fontWeight = FontWeight.Bold) 
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { selectedCard?.let { vm.replaceDeadCard(it) }; selectedCard = null }, enabled = selectedCard != null && isMyTurn) { 
                    Text(text = "Replace selected dead card", fontSize = 12.sp) 
                }
            }
        }
        
        // Center Screen Animation Overlay
        Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = centralCardToDisplay != null,
                enter = scaleIn(tween(150), 0.5f) + fadeIn(tween(150)),
                exit = scaleOut(tween(200), 1.5f) + fadeOut(tween(200))
            ) {
                centralCardToDisplay?.let { cCard ->
                    Card(
                        modifier = Modifier.size(120.dp, 160.dp).shadow(12.dp, MaterialTheme.shapes.medium).border(2.dp, Color.Gray, MaterialTheme.shapes.medium),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(text = cCard.rank, fontSize = 42.sp, fontWeight = FontWeight.Bold, color = if (cCard.suit == "♥" || cCard.suit == "♦") Color.Red else Color.Black)
                            Text(text = cCard.suit, fontSize = 36.sp, color = if (cCard.suit == "♥" || cCard.suit == "♦") Color.Red else Color.Black)
                        }
                    }
                }
            }
        }

        if (room.status == "FINISHED") {
            if (showScorecard) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable(enabled=false){}, contentAlignment = Alignment.Center) {
                    Card(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.95f).padding(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                val winStr = "${room.winnerTeam} WINS MATCH ${room.matchNumber}!"
                                Text(text = winStr, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(room.teamColors[room.winnerTeam] ?: TEAM_COLORS[0]), textAlign = TextAlign.Center)
                                if(room.consecutiveWins >= 3 && room.lastWinningTeam == room.winnerTeam) {
                                    Text(text = "HAT-TRICK WINNER!", fontSize = 18.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Spacer(Modifier.height(12.dp))
                            
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                Text(text = "Player Stats", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                                Column(Modifier.fillMaxWidth().border(1.dp, Color.LightGray).padding(4.dp)) {
                                    Row(Modifier.fillMaxWidth().background(Color.LightGray).padding(4.dp)) { 
                                        Text(text = "Player", Modifier.weight(1f), fontSize=12.sp)
                                        Text(text = "Time", Modifier.weight(0.6f), fontSize=12.sp)
                                        Text(text = "Wild", Modifier.weight(0.4f), fontSize=12.sp)
                                        Text(text = "Rem", Modifier.weight(0.4f), fontSize=12.sp) 
                                    } 
                                    room.players.forEach { p -> 
                                        Row(Modifier.fillMaxWidth().padding(4.dp)) { 
                                            Text(text = p.playerName, Modifier.weight(1f), fontSize=12.sp, color=Color(room.teamColors[p.team] ?: TEAM_COLORS[0]))
                                            val tSecs = p.timePlayedMs / 1000
                                            val hr = tSecs / 3600
                                            val min = (tSecs % 3600) / 60
                                            val sec = tSecs % 60
                                            val timeStr = if (hr > 0) String.format("%02d:%02d:%02d", hr, min, sec) else String.format("%02d:%02d", min, sec)
                                            Text(text = timeStr, Modifier.weight(0.6f), fontSize=12.sp) 
                                            val wStr = "${p.wildUsed}"
                                            Text(text = wStr, Modifier.weight(0.4f), fontSize=12.sp) 
                                            val rStr = "${p.removeUsed}"
                                            Text(text = rStr, Modifier.weight(0.4f), fontSize=12.sp) 
                                        } 
                                    }
                                }
                                
                                Spacer(Modifier.height(12.dp))
                                Text(text = "Match History", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                                Column(Modifier.fillMaxWidth().border(1.dp, Color.LightGray).padding(4.dp)) {
                                    room.matchHistory.forEach { h -> Text(text = h, fontSize = 12.sp, modifier = Modifier.padding(2.dp)) }
                                }

                                if (vm.playerName == room.hostName) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(text = "Host: Manage Next Match", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                        Text(text = "Teams:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Spacer(Modifier.width(8.dp))
                                        listOf(2, 3).forEach { tCount -> 
                                            Button(
                                                onClick = { vm.changeTotalTeams(tCount) }, 
                                                colors = ButtonDefaults.buttonColors(containerColor = if (room.numberOfTeams == tCount) Color(0xFF1976D2) else Color.Gray),
                                                modifier = Modifier.padding(horizontal = 4.dp).height(28.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) { 
                                                Text(text = tCount.toString(), fontSize = 11.sp) 
                                            } 
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        Text(text = "Timer:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Checkbox(
                                            checked = room.timerEnabled, 
                                            onCheckedChange = { vm.updateTimerSettings(it, room.timerDurationSecs, context) }
                                        )
                                        if (room.timerEnabled) {
                                            var tVal by remember(room.timerDurationSecs) { mutableStateOf(room.timerDurationSecs.toString()) }
                                            BasicTextField(
                                                value = tVal,
                                                onValueChange = { newVal -> 
                                                    tVal = newVal
                                                    newVal.toIntOrNull()?.let { num -> if(num > 0) vm.updateTimerSettings(true, num, context) }
                                                },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.width(40.dp).background(Color.White).border(1.dp, Color.Gray).padding(4.dp),
                                                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                                            )
                                            Text(text = "s", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Column(Modifier.fillMaxWidth().border(1.dp, Color.LightGray).padding(4.dp)) {
                                        room.players.forEach { p ->
                                            val isOnline = room.presence[p.playerName] == true
                                            val teamCol = Color(room.teamColors[p.team] ?: TEAM_COLORS[0])
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                                Box(Modifier.size(12.dp).clip(CircleShape).background(teamCol).border(1.dp, Color.Black, CircleShape).clickable { vm.cycleTeamColor(p.team) })
                                                Spacer(Modifier.width(4.dp))
                                                Text(text = p.playerName, fontSize = 11.sp, color = if (isOnline) teamCol else Color.Gray, modifier = Modifier.weight(1f))
                                                if (!isOnline) {
                                                    Text(text = "(Offline)", fontSize = 9.sp, color = Color.Red, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                                                }
                                                if (p.playerName != vm.playerName) {
                                                    Button(onClick = { vm.changePlayerTeam(p.playerId) }, modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { 
                                                        Text(text = "Team", fontSize = 9.sp) 
                                                    }
                                                    Spacer(Modifier.width(4.dp))
                                                    Button(onClick = { vm.kickPlayer(p.playerId) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { 
                                                        Text(text = "X", fontSize = 9.sp) 
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Divider(Modifier.padding(bottom = 8.dp))
                                val pendingPlayers = room.players.filter { room.rematchVotes[it.playerName] != true }
                                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                    room.players.forEach { p ->
                                        val isReady = room.rematchVotes[p.playerName] == true
                                        val bColor = Color(room.teamColors[p.team] ?: TEAM_COLORS[0])
                                        Box(
                                            modifier = Modifier.size(16.dp).background(if (isReady) bColor else Color.LightGray, MaterialTheme.shapes.small)
                                                .border(1.dp, Color.Black, MaterialTheme.shapes.small)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                if (pendingPlayers.isNotEmpty()) {
                                    val names = pendingPlayers.map { it.playerName }
                                    val verb = if (names.size == 1) "is" else "are"
                                    val waitStr = "${names.joinToString(", ")} $verb yet to join for rematch."
                                    Text(text = waitStr, fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(Modifier.height(8.dp))

                                if (room.message.contains("Cannot start")) {
                                    Text(text = room.message, color = Color.Red, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp).fillMaxWidth())
                                }

                                if(room.rematchVotes[vm.playerName] != true) {
                                    val readyStr = "Ready for Match ${room.matchNumber+1}"
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                        Button(onClick = { vm.voteRematch(true) }) { Text(text = readyStr) }
                                    }
                                } else { 
                                    Text(text = "Waiting for Host...", color = Color.Gray, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) 
                                }
                                
                                if(vm.playerName == room.hostName) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                        Button(onClick = { vm.hostManualShuffle() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))) { 
                                            Text(text = "Shuffle Deck") 
                                        }
                                        Button(onClick = { vm.hostStartRematch() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07852B))) { 
                                            Text(text = "Start Next Match") 
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { showChat = true; vm.lastReadMessageCount = vm.getRelevantMessageCount() }) { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("💬 Chat", color = Color(0xFF00ACC1))
                                            if (unreadCount > 0) {
                                                Spacer(Modifier.width(4.dp))
                                                Box(modifier = Modifier.size(18.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
                                                    Text(text = "$unreadCount", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                    TextButton(onClick = { vm.syncPresence() }) { Text(text = "Sync Status", color = Color(0xFF1976D2)) }
                                }
                                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { showScorecard = false }) { Text(text = "View Board", color = Color(0xFFE65100)) }
                                    TextButton(onClick = { vm.backToLobby(); onExit() }) { Text(text = "Leave Room", color = Color.Red) }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Button(onClick = { showScorecard = true }, modifier = Modifier.padding(top = 16.dp)) {
                        Text(text = "Show Scorecard")
                    }
                }
            }
        }
    }
    
    if (showChat) {
        ChatDialog(vm, onDismiss = { showChat = false })
    }
}
