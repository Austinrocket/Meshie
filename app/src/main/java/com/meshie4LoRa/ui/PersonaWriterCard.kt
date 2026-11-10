package com.meshie4LoRa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.brain.GeneratedPersona
import com.meshie4LoRa.llm.EngineState

private val ideaHints = listOf(
    "a grumpy lighthouse keeper who collects crabs",
    "a sentient vending machine with opinions",
    "a retired superhero who now runs a bakery",
    "a ghost who haunts the campground wifi",
    "a frog who thinks it's a famous jazz singer",
)

/** "Describe an idea, the brain writes the persona." [onResult] fills the form; nothing is saved here. */
@Composable
fun PersonaWriterCard(vm: CharacterViewModel, onResult: (GeneratedPersona) -> Unit, modifier: Modifier = Modifier) {
    val writing by vm.writing.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val ready = engine is EngineState.Ready
    var idea by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    var hint by rememberSaveable { mutableIntStateOf(ideaHints.indices.random()) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🪄 Let the brain write it", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ready) "Describe an idea in a few words (or leave it empty for a total surprise). You can edit everything after."
                else "Available once a brain is loaded.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                idea, { idea = it.take(300) }, Modifier.fillMaxWidth(), enabled = ready && !writing,
                placeholder = { Text("e.g. ${ideaHints[hint]}") }, maxLines = 3,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        failed = false
                        vm.writePersona(idea) { result ->
                            if (result == null) failed = true else onResult(result)
                            hint = ideaHints.indices.random()
                        }
                    },
                    enabled = ready && !writing,
                ) { Text(if (idea.isBlank()) "🪄 Dream one up" else "🪄 Write it for me") }
                if (writing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Dreaming…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (failed) Text("That one came out garbled. Try again?", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
