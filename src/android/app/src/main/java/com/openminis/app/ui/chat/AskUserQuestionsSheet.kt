package com.openminis.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AskUserQuestion
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton

/**
 * [T-android-ask-user] The agent's question, as a modal sheet.
 *
 * Modal rather than an inline card because the tool BLOCKS: the run is parked on
 * the answer, so a card the user could scroll past would leave the chat looking
 * idle while the agent waits. The sheet also gives the question the whole width,
 * which questions with 2-4 options + descriptions need.
 *
 * Free text is always available ("Other"), because the model's options are a
 * guess about what the user wants — the MCP this ports makes the same promise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskUserQuestionsSheet(
    questions: List<AskUserQuestion.Question>,
    onSubmit: (List<List<String>>) -> Unit,
    onSkip: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onSkip) {
        AskUserQuestionsBody(questions = questions, onSubmit = onSubmit, onSkip = onSkip)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskUserQuestionsBody(
    questions: List<AskUserQuestion.Question>,
    onSubmit: (List<List<String>>) -> Unit,
    onSkip: () -> Unit,
) {
    val selected = remember(questions) {
        MutableList(questions.size) { mutableStateListOf<String>() }
    }
    val otherText = remember(questions) {
        MutableList(questions.size) { mutableStateOf("") }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.ask_user_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(12.dp))
            // Scrollable question area, height-capped so the submit/skip buttons
            // below stay reachable even with long questions or many options.
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                questions.forEachIndexed { index, question ->
                    val header = question.header.ifBlank { question.question }
                    Text(header, style = MaterialTheme.typography.labelLarge)
                    if (question.header.isNotBlank() && question.header != question.question) {
                        Text(
                            question.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        question.options.forEach { option ->
                            val on = option.label in selected[index]
                            FilterChip(
                                selected = on,
                                onClick = {
                                    if (question.multiSelect) {
                                        if (on) selected[index].remove(option.label)
                                        else selected[index].add(option.label)
                                    } else {
                                        selected[index].clear()
                                        selected[index].add(option.label)
                                        // Picking an option means the free-text
                                        // answer to THIS question is no longer
                                        // wanted; leaving both would send two
                                        // contradictory answers.
                                        otherText[index].value = ""
                                    }
                                },
                                label = {
                                    Text(
                                        option.label,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = on,
                                    borderColor = MaterialTheme.colorScheme.outline,
                                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                    question.options
                        .firstOrNull { it.label in selected[index] && it.description.isNotBlank() }
                        ?.let { chosen ->
                            Text(
                                chosen.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    OutlinedTextField(
                        value = otherText[index].value,
                        onValueChange = {
                            otherText[index].value = it
                            if (it.isNotBlank()) selected[index].clear()
                        },
                        placeholder = { Text(stringResource(R.string.ask_user_other)) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                    if (index != questions.lastIndex) Spacer(Modifier.height(16.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                MinisOutlinedButton(onClick = onSkip) {
                    Text(stringResource(R.string.ask_user_skip))
                }
                Spacer(Modifier.width(12.dp))
                MinisButton(
                    // Submitting an empty answer is allowed when the user has not
                    // chosen anything: the model gets "no answer" rather than a
                    // fabricated one, which is what the skip path means too — so
                    // the button stays enabled and the distinction is the reason
                    // string that travels with the answers.
                    onClick = {
                        val answers = questions.mapIndexed { index, _ ->
                            val free = otherText[index].value.trim()
                            if (free.isNotEmpty()) listOf(free) else selected[index].toList()
                        }
                        onSubmit(answers)
                    },
                ) {
                    Text(stringResource(R.string.ask_user_submit))
                }
            }
        }
    }
}
