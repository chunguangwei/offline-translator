package com.offlinetranslator.app.feature.vision

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import java.io.File

@Composable
fun VisionScreen(
    padding: PaddingValues,
    vm: VisionViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> vm.setImage(uri) }

    // Camera capture pipeline.
    // Steps:
    //   1. Allocate a fresh JPEG file under cacheDir/camera/
    //   2. Wrap it with FileProvider → content:// URI
    //   3. Hand to TakePicture contract; system camera writes JPEG to that URI
    //   4. On success, feed that same URI back into VisionViewModel.setImage()
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCaptureUri?.let { vm.setImage(it) }
        }
        // Whether or not the user took the shot, drop the pending URI so the
        // next tap on "Take photo" allocates a fresh file (avoid cache reuse).
        pendingCaptureUri = null
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera(ctx) { uri ->
                pendingCaptureUri = uri
                takePicture.launch(uri)
            }
        }
    }
    val onTakePhoto = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera(ctx) { uri ->
                pendingCaptureUri = uri
                takePicture.launch(uri)
            }
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val quickPrompts = listOf(
        stringResource(R.string.vision_quick_describe),
        stringResource(R.string.vision_quick_translate),
        stringResource(R.string.vision_quick_what),
        stringResource(R.string.vision_quick_solve),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.vision_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
        )

        // Image area
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            if (ui.imageUri == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.height(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Camera FIRST (before gallery) per UX spec
                        OutlinedButton(onClick = onTakePhoto) {
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.vision_take_photo))
                        }
                        OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest()) }) {
                            Icon(Icons.Rounded.Image, contentDescription = null)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.vision_pick_image))
                        }
                    }
                }
            } else {
                Column {
                    AsyncImage(
                        model = ui.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .aspectRatio(ui.bitmap?.let { it.width.toFloat() / it.height } ?: 1f, false),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Replace controls — let the user re-take or re-pick without
                    // having to clear/exit the current image first.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onTakePhoto) {
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.vision_retake))
                        }
                        OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest()) }) {
                            Icon(Icons.Rounded.Image, contentDescription = null)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.vision_replace))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Quick prompt chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(quickPrompts) { p ->
                AssistChip(
                    onClick = { vm.setPrompt(p) },
                    label = { Text(p) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Input row
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = ui.prompt,
                    onValueChange = vm::setPrompt,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (ui.prompt.isEmpty()) {
                                Text(
                                    stringResource(R.string.vision_input_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (ui.isAnswering) {
                    // Red Stop button while generating; tapping cancels and
                    // returns the input to editable state.
                    IconButton(onClick = vm::stop) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(onClick = vm::ask, enabled = ui.bitmap != null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            tint = if (ui.bitmap != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Answer
        if (ui.answer.isNotEmpty() || ui.isAnswering) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = ui.answer.ifEmpty { stringResource(R.string.vision_analyzing) },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        ui.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Allocate a fresh JPEG file in cacheDir/camera/ and hand back its
 * FileProvider content URI. The system camera will write the captured photo
 * directly into that file.
 *
 * We use cacheDir (not filesDir) so old captures get evicted by the OS under
 * memory pressure — we don't need to keep them around once the user navigates
 * away from the screen.
 */
private fun launchCamera(
    ctx: android.content.Context,
    onUriReady: (Uri) -> Unit,
) {
    val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(
        ctx,
        "${ctx.packageName}.fileprovider",
        file,
    )
    onUriReady(uri)
}
