package no.ringlog.app.ui.birds

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import no.ringlog.app.BuildConfig
import no.ringlog.app.R
import no.ringlog.app.data.local.TokenStore
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen
import no.ringlog.app.ui.flocks.FlockViewModel
import no.ringlog.app.ui.util.formatIsoDate
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun createCameraImageUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdDetailScreen(
    birdId: Int,
    onBack: () -> Unit,
    tokenStore: TokenStore,
    vm: FlockViewModel = hiltViewModel(),
) {
    val state by vm.birdState.collectAsStateWithLifecycle()
    val uploadState by vm.imageUploadState.collectAsStateWithLifecycle()
    val updateState by vm.updateBirdState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(birdId) { vm.loadBird(birdId) }

    var noteContent by remember { mutableStateOf("") }
    val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    var showImageSheet by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    var editRing by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editSpecies by remember { mutableStateOf("") }
    var editBreed by remember { mutableStateOf("") }
    var editBreedMix by remember { mutableStateOf("") }
    var editSex by remember { mutableStateOf("") }
    var editBirthDate by remember { mutableStateOf("") }
    var editBirthApprox by remember { mutableStateOf(false) }
    var editNotes by remember { mutableStateOf("") }
    var editIsDead by remember { mutableStateOf(false) }
    var editDeathDate by remember { mutableStateOf("") }
    var editIsSold by remember { mutableStateOf(false) }
    var editSoldDate by remember { mutableStateOf("") }

    LaunchedEffect(uploadState) {
        if (uploadState is FlockViewModel.ImageUploadState.Success ||
            uploadState is FlockViewModel.ImageUploadState.Error) {
            kotlinx.coroutines.delay(2000)
            vm.resetImageUpload()
        }
    }

    LaunchedEffect(updateState) {
        if (updateState is FlockViewModel.UpdateBirdState.Success) {
            isEditing = false
            vm.resetUpdateBird()
        }
    }

    fun readAndUpload(uri: Uri) {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        context.contentResolver.openInputStream(uri)?.use { stream ->
            vm.uploadImage(birdId, stream.readBytes(), mime)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraUri?.let { readAndUpload(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createCameraImageUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { readAndUpload(it) } }

    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            val uri = createCameraImageUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val bird = (state as? FlockViewModel.BirdState.Success)?.bird

    fun enterEditMode() {
        bird?.let { b ->
            editRing = b.ring_number
            editName = b.name ?: ""
            editSpecies = b.species
            editBreed = b.breed ?: ""
            editBreedMix = b.breed_mix ?: ""
            editSex = b.sex
            editBirthDate = b.birth_date ?: ""
            editBirthApprox = b.birth_approximate
            editNotes = b.notes ?: ""
            editIsDead = b.is_dead
            editDeathDate = b.death_date ?: ""
            editIsSold = b.is_sold
            editSoldDate = b.sold_date ?: ""
        }
        isEditing = true
    }

    fun saveEdits() {
        val fields = buildMap<String, String> {
            put("ring_number", editRing.trim())
            if (editName.isNotBlank()) put("name", editName.trim())
            put("species", editSpecies.trim())
            if (editBreed.isNotBlank()) put("breed", editBreed.trim())
            if (editBreedMix.isNotBlank()) put("breed_mix", editBreedMix.trim())
            put("sex", editSex)
            if (editBirthDate.isNotBlank()) put("birth_date", editBirthDate.trim())
            put("birth_approximate", editBirthApprox.toString())
            if (editNotes.isNotBlank()) put("notes", editNotes.trim())
            put("is_dead", editIsDead.toString())
            if (editIsDead && editDeathDate.isNotBlank()) put("death_date", editDeathDate.trim())
            put("is_sold", editIsSold.toString())
            if (editIsSold && editSoldDate.isNotBlank()) put("sold_date", editSoldDate.trim())
        }
        vm.updateBird(birdId, fields)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isEditing) stringResource(R.string.edit_bird)
                         else bird?.let { it.name ?: it.ring_number } ?: stringResource(R.string.bird))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditing) { isEditing = false; vm.resetUpdateBird() }
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (isEditing) {
                        if (updateState is FlockViewModel.UpdateBirdState.Loading) {
                            CircularProgressIndicator(Modifier.size(24.dp).padding(end = 16.dp))
                        } else {
                            TextButton(onClick = ::saveEdits) { Text(stringResource(R.string.save)) }
                        }
                    } else if (bird != null) {
                        IconButton(onClick = ::enterEditMode) {
                            Icon(Icons.Default.Edit, stringResource(R.string.edit_bird))
                        }
                        IconButton(onClick = { showImageSheet = true }) {
                            Icon(Icons.Default.CameraAlt, stringResource(R.string.change_photo))
                        }
                    }
                },
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is FlockViewModel.BirdState.Loading -> LoadingScreen()
                is FlockViewModel.BirdState.Error   -> ErrorScreen(s.msg) { vm.loadBird(birdId) }
                is FlockViewModel.BirdState.Success -> {
                    if (isEditing) {
                        EditBirdForm(
                            ring = editRing, onRingChange = { editRing = it },
                            name = editName, onNameChange = { editName = it },
                            species = editSpecies, onSpeciesChange = { editSpecies = it },
                            breed = editBreed, onBreedChange = { editBreed = it },
                            breedMix = editBreedMix, onBreedMixChange = { editBreedMix = it },
                            sex = editSex, onSexChange = { editSex = it },
                            birthDate = editBirthDate, onBirthDateChange = { editBirthDate = it },
                            birthApprox = editBirthApprox, onBirthApproxChange = { editBirthApprox = it },
                            notes = editNotes, onNotesChange = { editNotes = it },
                            isDead = editIsDead, onIsDeadChange = { editIsDead = it },
                            deathDate = editDeathDate, onDeathDateChange = { editDeathDate = it },
                            isSold = editIsSold, onIsSoldChange = { editIsSold = it },
                            soldDate = editSoldDate, onSoldDateChange = { editSoldDate = it },
                            errorMsg = (updateState as? FlockViewModel.UpdateBirdState.Error)?.msg,
                        )
                    } else {
                        val b = s.bird
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (uploadState is FlockViewModel.ImageUploadState.Loading) {
                                        Box(Modifier.fillMaxWidth().height(180.dp),
                                            contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator()
                                        }
                                    } else if (b.has_image) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(BuildConfig.API_BASE_URL.trimEnd('/').dropLast(4) +
                                                        "v1/birds/${b.id}/image")
                                                .addHeader("Authorization",
                                                    "Bearer ${tokenStore.token.orEmpty()}")
                                                .crossfade(true)
                                                .diskCacheKey("bird_image_${b.id}_${System.currentTimeMillis() / 30000}")
                                                .build(),
                                            contentDescription = b.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                        )
                                    }
                                    if (uploadState is FlockViewModel.ImageUploadState.Error) {
                                        Text(
                                            (uploadState as FlockViewModel.ImageUploadState.Error).msg,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                                        )
                                    }
                                }

                                DetailRow(stringResource(R.string.ring), b.ring_number)
                                if (b.name != null) DetailRow(stringResource(R.string.name), b.name)
                                DetailRow(stringResource(R.string.species),
                                    b.species.replaceFirstChar { it.uppercase() })
                                if (!b.breed.isNullOrBlank()) {
                                    val breedText = if (!b.breed_mix.isNullOrBlank())
                                        "${b.breed} (${b.breed_mix})" else b.breed
                                    DetailRow(stringResource(R.string.breed), breedText)
                                }
                                DetailRow(stringResource(R.string.sex),
                                    b.sex.replaceFirstChar { it.uppercase() })
                                if (b.birth_date != null)
                                    DetailRow(stringResource(R.string.born),
                                        "${if (b.birth_approximate) "~" else ""}${formatIsoDate(b.birth_date)}")
                                if (b.is_dead)
                                    DetailRow(stringResource(R.string.deceased),
                                        b.death_date?.let { formatIsoDate(it) } ?: stringResource(R.string.yes))
                                if (b.is_sold)
                                    DetailRow(stringResource(R.string.sold),
                                        b.sold_date?.let { formatIsoDate(it) } ?: stringResource(R.string.yes))
                                if (!b.notes.isNullOrBlank())
                                    DetailRow(stringResource(R.string.notes), b.notes)
                                Spacer(Modifier.height(24.dp))
                                Text(stringResource(R.string.notes), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                            }

                            val notes = b.notes_list.orEmpty()
                            if (notes.isEmpty()) {
                                item {
                                    Text(stringResource(R.string.no_notes),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            } else {
                                items(notes) { note ->
                                    Column(Modifier.padding(vertical = 6.dp)) {
                                        Text(formatIsoDate(note.note_date), style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                                        Text(note.content, style = MaterialTheme.typography.bodyMedium)
                                        HorizontalDivider(Modifier.padding(top = 6.dp))
                                    }
                                }
                            }

                            item {
                                Spacer(Modifier.height(16.dp))
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = noteContent,
                                        onValueChange = { noteContent = it },
                                        modifier = Modifier.weight(1f),
                                        label = { Text(stringResource(R.string.add_note)) },
                                        singleLine = true,
                                    )
                                    Button(
                                        onClick = {
                                            if (noteContent.isNotBlank()) {
                                                vm.addNote(birdId, today, noteContent)
                                                noteContent = ""
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                    ) { Text(stringResource(R.string.add)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImageSheet) {
        ModalBottomSheet(onDismissRequest = { showImageSheet = false }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.take_photo)) },
                    leadingContent = { Icon(Icons.Default.CameraAlt, null) },
                    modifier = Modifier.clickable {
                        showImageSheet = false
                        launchCamera()
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.choose_from_gallery)) },
                    leadingContent = { Icon(Icons.Default.Photo, null) },
                    modifier = Modifier.clickable {
                        showImageSheet = false
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditBirdForm(
    ring: String, onRingChange: (String) -> Unit,
    name: String, onNameChange: (String) -> Unit,
    species: String, onSpeciesChange: (String) -> Unit,
    breed: String, onBreedChange: (String) -> Unit,
    breedMix: String, onBreedMixChange: (String) -> Unit,
    sex: String, onSexChange: (String) -> Unit,
    birthDate: String, onBirthDateChange: (String) -> Unit,
    birthApprox: Boolean, onBirthApproxChange: (Boolean) -> Unit,
    notes: String, onNotesChange: (String) -> Unit,
    isDead: Boolean, onIsDeadChange: (Boolean) -> Unit,
    deathDate: String, onDeathDateChange: (String) -> Unit,
    isSold: Boolean, onIsSoldChange: (Boolean) -> Unit,
    soldDate: String, onSoldDateChange: (String) -> Unit,
    errorMsg: String?,
) {
    val sexOptions = listOf("female", "male", "unknown")
    var sexExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (errorMsg != null) {
            Text(errorMsg, color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(ring, onRingChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ring)) }, singleLine = true)
        OutlinedTextField(name, onNameChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.name)) }, singleLine = true)
        OutlinedTextField(species, onSpeciesChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.species)) }, singleLine = true)
        OutlinedTextField(breed, onBreedChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.breed)) }, singleLine = true)
        OutlinedTextField(breedMix, onBreedMixChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.breed_mix)) }, singleLine = true)

        ExposedDropdownMenuBox(expanded = sexExpanded, onExpandedChange = { sexExpanded = it }) {
            OutlinedTextField(
                value = sex.replaceFirstChar { it.uppercase() },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sex)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sexExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = sexExpanded, onDismissRequest = { sexExpanded = false }) {
                sexOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.replaceFirstChar { it.uppercase() }) },
                        onClick = { onSexChange(option); sexExpanded = false },
                    )
                }
            }
        }

        OutlinedTextField(birthDate, onBirthDateChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.born)) },
            placeholder = { Text("YYYY-MM-DD") }, singleLine = true)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.birth_approximate), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Switch(birthApprox, onBirthApproxChange)
        }

        OutlinedTextField(notes, onNotesChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.notes)) }, minLines = 2)

        HorizontalDivider()

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.deceased), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Switch(isDead, onIsDeadChange)
        }
        if (isDead) {
            OutlinedTextField(deathDate, onDeathDateChange, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.death_date)) },
                placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sold), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Switch(isSold, onIsSoldChange)
        }
        if (isSold) {
            OutlinedTextField(soldDate, onSoldDateChange, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sold_date)) },
                placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
             modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
