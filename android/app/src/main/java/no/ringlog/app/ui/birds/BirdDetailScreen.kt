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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import no.ringlog.app.BuildConfig
import no.ringlog.app.data.local.TokenStore
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen
import no.ringlog.app.ui.flocks.FlockViewModel
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
    val context = LocalContext.current

    LaunchedEffect(birdId) { vm.loadBird(birdId) }

    var noteContent by remember { mutableStateOf("") }
    val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    var showImageSheet by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // Show error snackbar on upload failure, reset on success
    LaunchedEffect(uploadState) {
        if (uploadState is FlockViewModel.ImageUploadState.Success ||
            uploadState is FlockViewModel.ImageUploadState.Error) {
            // Brief delay then reset so the indicator goes away
            kotlinx.coroutines.delay(2000)
            vm.resetImageUpload()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? FlockViewModel.BirdState.Success)?.bird
                    ?.let { it.name ?: it.ring_number } ?: "Bird") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showImageSheet = true }) {
                        Icon(Icons.Default.CameraAlt, "Change photo")
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
                    val bird = s.bird
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        item {
                            // Image area
                            Box(
                                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uploadState is FlockViewModel.ImageUploadState.Loading) {
                                    Box(
                                        Modifier.fillMaxWidth().height(180.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }
                                } else if (bird.has_image) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(BuildConfig.API_BASE_URL.trimEnd('/').dropLast(4) +
                                                    "v1/birds/${bird.id}/image")
                                            .addHeader("Authorization",
                                                "Bearer ${tokenStore.token.orEmpty()}")
                                            .crossfade(true)
                                            .diskCacheKey("bird_image_${bird.id}_${System.currentTimeMillis() / 30000}")
                                            .build(),
                                        contentDescription = bird.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                    )
                                }
                                if (uploadState is FlockViewModel.ImageUploadState.Error) {
                                    Text(
                                        (uploadState as FlockViewModel.ImageUploadState.Error).msg,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                            .padding(4.dp),
                                    )
                                }
                            }

                            DetailRow("Ring", bird.ring_number)
                            if (bird.name != null) DetailRow("Name", bird.name)
                            DetailRow("Species", bird.species.replaceFirstChar { it.uppercase() })
                            if (!bird.breed.isNullOrBlank()) {
                                val breedText = if (!bird.breed_mix.isNullOrBlank())
                                    "${bird.breed} (${bird.breed_mix})" else bird.breed
                                DetailRow("Breed", breedText)
                            }
                            DetailRow("Sex", bird.sex.replaceFirstChar { it.uppercase() })
                            if (bird.birth_date != null)
                                DetailRow("Born", "${if (bird.birth_approximate) "~" else ""}${bird.birth_date}")
                            if (bird.is_dead) DetailRow("Deceased", bird.death_date ?: "yes")
                            if (bird.is_sold) DetailRow("Sold", bird.sold_date ?: "yes")
                            if (!bird.notes.isNullOrBlank()) DetailRow("Notes", bird.notes)
                            Spacer(Modifier.height(24.dp))
                            Text("Notes", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                        }

                        val notes = bird.notes_list.orEmpty()
                        if (notes.isEmpty()) {
                            item {
                                Text("No notes.",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        } else {
                            items(notes) { note ->
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text(note.note_date, style = MaterialTheme.typography.labelSmall,
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
                                    label = { Text("Add note") },
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
                                ) { Text("Add") }
                            }
                        }
                    }
                }
            }
        }
    }

    // Bottom sheet: choose camera or gallery
    if (showImageSheet) {
        ModalBottomSheet(onDismissRequest = { showImageSheet = false }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text("Take a photo") },
                    leadingContent = { Icon(Icons.Default.CameraAlt, null) },
                    modifier = Modifier.clickable {
                        showImageSheet = false
                        launchCamera()
                    },
                )
                ListItem(
                    headlineContent = { Text("Choose from gallery") },
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

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
             modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
