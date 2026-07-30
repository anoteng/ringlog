package no.ringlog.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import no.ringlog.app.R

private val SEX_OPTIONS = listOf(
    "unknown" to R.string.sex_unknown,
    "female"  to R.string.sex_female,
    "male"    to R.string.sex_male,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SexDropdown(selected: String, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val label = SEX_OPTIONS.firstOrNull { it.first == selected }?.second?.let { stringResource(it) } ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.sex)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SEX_OPTIONS.forEach { (value, resId) ->
                DropdownMenuItem(
                    text = { Text(stringResource(resId)) },
                    onClick = { onSelected(value); expanded = false },
                )
            }
        }
    }
}
