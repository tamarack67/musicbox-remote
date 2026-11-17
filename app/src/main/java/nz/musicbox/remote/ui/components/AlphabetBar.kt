package nz.musicbox.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.Normalizer

/** "#" for names starting with a digit or symbol, then A to Z. */
private val LETTERS = listOf('#') + ('A'..'Z')

/** The letter a name is filed under: its first letter without accents, or '#'. */
fun indexLetter(name: String): Char {
    val first = name.trimStart().firstOrNull() ?: return '#'
    val plain = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
    return if (plain in 'A'..'Z') plain else '#'
}

/**
 * Where each letter's items start in a sorted list. A letter with no items jumps to the next one
 * that has some. Names with accented first letters (sorted after Z) are left out, so they don't
 * pull a letter's jump to the end of the list.
 */
fun letterPositions(names: List<String>): Map<Char, Int> {
    val first = HashMap<Char, Int>()
    names.forEachIndexed { i, name ->
        val letter = indexLetter(name)
        val ascii = name.trimStart().firstOrNull()?.let { it.uppercaseChar() == letter || letter == '#' } ?: true
        if (ascii && letter !in first) first[letter] = i
    }
    val result = HashMap<Char, Int>()
    var next = names.size - 1
    for (letter in LETTERS.reversed()) {
        first[letter]?.let { next = it }
        result[letter] = next.coerceAtLeast(0)
    }
    return result
}

/**
 * A list with an A–Z bar down its right-hand edge: touch or slide along the bar to jump.
 * [names] are the sort names of the list's items, in list order (one item per name).
 */
@Composable
fun AlphabetIndexedList(
    names: List<String>,
    listState: LazyListState,
    content: LazyListScope.() -> Unit,
) {
    val positions = remember(names) { letterPositions(names) }
    val present = remember(names) { names.mapTo(HashSet()) { indexLetter(it) } }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var active by remember { mutableStateOf<Char?>(null) }

    fun select(letter: Char) {
        if (letter == active) return
        active = letter
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch { listState.scrollToItem(positions[letter] ?: 0) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(end = BAR_WIDTH), content = content)

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(BAR_WIDTH)
                .padding(vertical = 4.dp)
                .pointerInput(positions) {
                    fun letterAt(y: Float): Char {
                        val i = (y / size.height * LETTERS.size).toInt().coerceIn(0, LETTERS.lastIndex)
                        return LETTERS[i]
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        select(letterAt(down.position.y))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            select(letterAt(change.position.y))
                        }
                        active = null
                    }
                },
        ) {
            for (letter in LETTERS) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        letter.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (letter == active) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            letter == active -> MaterialTheme.colorScheme.primary
                            letter in present -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    )
                }
            }
        }

        // Big letter in the middle while sliding, so it can be read under the thumb.
        active?.let { letter ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(88.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private val BAR_WIDTH = 28.dp
