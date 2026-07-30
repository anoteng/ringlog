package no.ringlog.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import no.ringlog.app.R

private val SPECIES = listOf(
    "chicken"     to R.string.species_chicken,
    "duck"        to R.string.species_duck,
    "goose"       to R.string.species_goose,
    "turkey"      to R.string.species_turkey,
    "quail"       to R.string.species_quail,
    "pigeon"      to R.string.species_pigeon,
    "guinea fowl" to R.string.species_guinea_fowl,
    "peafowl"     to R.string.species_peafowl,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesDropdown(selected: String, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val label = SPECIES.firstOrNull { it.first == selected }?.second?.let { stringResource(it) } ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.species)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPECIES.forEach { (value, resId) ->
                DropdownMenuItem(
                    text = { Text(stringResource(resId)) },
                    onClick = { onSelected(value); expanded = false },
                )
            }
        }
    }
}
