package no.ringlog.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import no.ringlog.app.R

private val SPECIES_LABELS = mapOf(
    "chicken"     to R.string.species_chicken,
    "duck"        to R.string.species_duck,
    "goose"       to R.string.species_goose,
    "turkey"      to R.string.species_turkey,
    "quail"       to R.string.species_quail,
    "pigeon"      to R.string.species_pigeon,
    "guinea fowl" to R.string.species_guinea_fowl,
    "peafowl"     to R.string.species_peafowl,
    "muscovy"     to R.string.species_muscovy,
    "custom"      to R.string.species_custom,
)

private val SEX_LABELS = mapOf(
    "unknown" to R.string.sex_unknown,
    "female"  to R.string.sex_female,
    "male"    to R.string.sex_male,
)

/** Localized name for a stored species value; unknown values are shown as entered. */
@Composable
fun speciesLabel(value: String): String =
    SPECIES_LABELS[value.lowercase()]?.let { stringResource(it) } ?: value.replaceFirstChar { it.uppercase() }

@Composable
fun sexLabel(value: String): String =
    SEX_LABELS[value.lowercase()]?.let { stringResource(it) } ?: value.replaceFirstChar { it.uppercase() }
