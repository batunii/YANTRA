package ie.napkin.supertasks.ui.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.db.PropertyKind
import ie.napkin.supertasks.data.db.PropertyValueEntity
import ie.napkin.supertasks.ui.components.dateLabel
import ie.napkin.supertasks.ui.components.selectConfig
import kotlinx.coroutines.flow.map

/**
 * Bottom sheet editing one node's built-in properties (Priority/Due) plus its labels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertySheet(
    vm: NodePageViewModel,
    targetId: String,
    onDismiss: () -> Unit,
) {
    val defs by vm.defs.collectAsStateWithLifecycle()
    val values by remember(targetId) {
        vm.valuesFor(targetId).map { list -> list.associateBy { it.defId } }
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val allLabels by vm.allLabels.collectAsStateWithLifecycle()
    val attachedLabels by remember(targetId) { vm.labelsFor(targetId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Properties", style = MaterialTheme.typography.titleLarge)

            defs.forEach { def ->
                PropertyEditor(
                    def = def,
                    value = values[def.id],
                    onSet = { text, number, date, bool ->
                        vm.setProperty(targetId, def, text, number, date, bool)
                    },
                    onClear = { vm.clearProperty(targetId, def.id) },
                )
            }

            HorizontalDivider()
            Text("Labels", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LabelChipsRow(
                allLabels = allLabels,
                attached = attachedLabels,
                onDetach = { label -> vm.detachLabel(targetId, label.id) },
                onAttach = { label -> vm.attachLabel(targetId, label.id) },
                onCreateAndAttach = { name -> vm.createAndAttachLabel(targetId, name) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PropertyEditor(
    def: PropertyDefEntity,
    value: PropertyValueEntity?,
    onSet: (text: String?, number: Double?, date: Long?, bool: Boolean?) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            def.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (def.kind) {
            PropertyKind.SELECT -> {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selectConfig(def).options.forEach { option ->
                        val selected = value?.vText == option.name
                        FilterChip(
                            selected = selected,
                            onClick = { if (selected) onClear() else onSet(option.name, null, null, null) },
                            label = { Text(option.name) },
                        )
                    }
                }
            }
            PropertyKind.DATE -> {
                var showPicker by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showPicker = true }) {
                        Text(value?.vDate?.let { dateLabel(it) } ?: "Set date")
                    }
                    if (value?.vDate != null) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                }
                if (showPicker) {
                    val state = rememberDatePickerState(initialSelectedDateMillis = value?.vDate)
                    DatePickerDialog(
                        onDismissRequest = { showPicker = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    state.selectedDateMillis?.let { onSet(null, null, it, null) }
                                    showPicker = false
                                },
                            ) { Text("Set") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPicker = false }) { Text("Cancel") }
                        },
                    ) {
                        DatePicker(state = state)
                    }
                }
            }
            PropertyKind.CHECKBOX -> {
                Switch(
                    checked = value?.vBool == true,
                    onCheckedChange = { onSet(null, null, null, it) },
                )
            }
            PropertyKind.NUMBER -> {
                var text by remember(value?.vNumber) {
                    mutableStateOf(value?.vNumber?.let {
                        if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
                    }.orEmpty())
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { t ->
                        text = t
                        t.toDoubleOrNull()?.let { onSet(null, it, null, null) }
                        if (t.isBlank()) onClear()
                    },
                    singleLine = true,
                    placeholder = { Text("0") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                var text by remember(def.id) { mutableStateOf(value?.vText.orEmpty()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { t ->
                        text = t
                        if (t.isBlank()) onClear() else onSet(t, null, null, null)
                    },
                    singleLine = true,
                    placeholder = { Text("Value") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

