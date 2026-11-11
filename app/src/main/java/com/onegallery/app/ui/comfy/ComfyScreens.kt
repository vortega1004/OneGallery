package com.onegallery.app.ui.comfy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.onegallery.app.data.comfy.ComfyController
import com.onegallery.app.data.comfy.ComfyJob
import com.onegallery.app.data.comfy.ComfyJobStatus
import com.onegallery.app.data.comfy.ComfyWorkflow
import com.onegallery.app.data.comfy.ComfyWorkflowList
import com.onegallery.app.domain.MediaItem
import com.onegallery.app.ui.common.rememberThumbnailRequest
import com.onegallery.app.ui.theme.DarkBackground
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Warning = Color(0xFFFFB454)
private val Failure = Color(0xFFFF6B6B)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = DarkTextPrimary,
    unfocusedTextColor = DarkTextPrimary,
    focusedLabelColor = OneUIBlue,
    unfocusedLabelColor = DarkTextSecondary,
    focusedPlaceholderColor = Color(0x80FFFFFF),
    unfocusedPlaceholderColor = Color(0x80FFFFFF),
    cursorColor = OneUIBlue
)

@Composable
private fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = DarkTextPrimary)
        }
        Spacer(Modifier.size(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DarkTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = DarkTextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Where the phone is introduced to the gateway, and where that can be checked or undone.
 *
 * Pairing takes two things read off the computer: the gateway's address and a one-time code.
 * Nothing is sent until the code has been matched against the certificate the gateway presents.
 */
@Composable
fun ComfyConnectionScreen(onClose: () -> Unit) {
    val controller = ComfyController.get(LocalContext.current)
    val connection by controller.connection.collectAsStateWithLifecycle()
    val loaded by controller.loaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<Result<ComfyWorkflowList>?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader("ComfyUI connection", "", onClose)
        val paired = connection
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                !loaded -> CircularProgressIndicator(color = Color.White)

                paired == null -> {
                    Text(
                        "On the computer that runs ComfyUI, start the OneGallery gateway, then run " +
                            "\"start-gateway pair\". It shows an address and a pairing code.",
                        color = DarkTextSecondary,
                        fontSize = 14.sp
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.trim(); problem = null },
                        label = { Text("Gateway address") },
                        placeholder = { Text("https://192.168.1.50:8443") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(24); problem = null },
                        label = { Text("Pairing code") },
                        placeholder = { Text("XXXX-XXXX-XXXXX-XXXXX") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false
                        ),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    problem?.let { Text(it, color = Failure, fontSize = 14.sp) }
                    Button(
                        enabled = !working && address.isNotBlank() && code.isNotBlank(),
                        onClick = {
                            working = true
                            scope.launch {
                                problem = controller.pair(address, code)
                                working = false
                                if (problem == null) code = ""
                            }
                        }
                    ) { Text(if (working) "Pairing…" else "Pair") }
                    Text(
                        "The phone only ever talks to the gateway, over an encrypted connection to the " +
                            "one certificate it sees now. The code works once.",
                        color = DarkTextSecondary,
                        fontSize = 12.sp
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(DarkSurface)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Paired", color = DarkTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(paired.address, color = DarkTextPrimary, fontSize = 14.sp)
                        Text(
                            "Certificate " + paired.fingerprint.take(16).chunked(4).joinToString("-") + "…",
                            color = DarkTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !working,
                            onClick = {
                                working = true
                                scope.launch {
                                    check = controller.workflows()
                                    working = false
                                }
                            }
                        ) { Text(if (working) "Checking…" else "Test connection") }
                        TextButton(onClick = { confirmForget = true }) { Text("Forget", color = Failure) }
                    }
                    check?.let { result ->
                        result.onFailure { Text(it.message ?: "Could not connect", color = Failure, fontSize = 14.sp) }
                        result.onSuccess { list ->
                            Text(
                                text = "Connected · " + jobCount(list.workflows.size) +
                                    if (list.comfyReachable) "" else " · ComfyUI itself is not answering",
                                color = if (list.comfyReachable) DarkTextPrimary else Warning,
                                fontSize = 14.sp
                            )
                            list.workflows.forEach {
                                Text("• ${it.name}", color = DarkTextSecondary, fontSize = 14.sp)
                            }
                            list.invalid.forEach { (file, reason) ->
                                Text("• $file — $reason", color = Warning, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = DarkSurface,
            titleContentColor = DarkTextPrimary,
            textContentColor = DarkTextSecondary,
            title = { Text("Forget this gateway?") },
            text = { Text("The phone will have to be paired again. Results already saved stay in the gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    check = null
                    scope.launch { controller.forget() }
                }) { Text("Forget", color = Failure) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } }
        )
    }
}

private fun jobCount(count: Int) = if (count == 1) "1 job" else "$count jobs"

/**
 * The Share button's sheet: the jobs the gateway offers for [item], and Start.
 *
 * One tap picks a job. A job that takes a prompt shows a text box with the workflow's own
 * prompt greyed out in it — leave it empty to use that, or type to replace it for this run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToComfySheet(item: MediaItem, onDismiss: () -> Unit) {
    val controller = ComfyController.get(LocalContext.current)
    val connection by controller.connection.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSetup by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<Result<ComfyWorkflowList>?>(null) }
    var chosen by remember { mutableStateOf<ComfyWorkflow?>(null) }
    var prompt by remember { mutableStateOf("") }

    LaunchedEffect(connection, attempt) {
        list = null
        if (connection != null) list = controller.workflows()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = rememberThumbnailRequest(item.uri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1F2125))
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Send to ComfyUI", color = DarkTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(item.displayName, color = DarkTextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            val loaded = list
            when {
                connection == null -> {
                    Text("This phone is not paired with a ComfyUI gateway yet.", color = DarkTextSecondary, fontSize = 14.sp)
                    Button(onClick = { showSetup = true }) { Text("Set up connection") }
                }

                loaded == null -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }

                loaded.isFailure -> {
                    Text(loaded.exceptionOrNull()?.message ?: "Could not reach the gateway", color = Failure, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { attempt++ }) { Text("Try again") }
                        TextButton(onClick = { showSetup = true }) { Text("Connection") }
                    }
                }

                else -> {
                    val workflows = loaded.getOrThrow()
                    // A video cannot go where a photo is asked for; those jobs are shown, greyed
                    val usable: (ComfyWorkflow) -> Boolean = { !it.requiresImage || !item.isVideo }
                    if (!workflows.comfyReachable) {
                        Text("The gateway is up, but ComfyUI is not answering on the computer.", color = Warning, fontSize = 13.sp)
                    }
                    if (workflows.workflows.isEmpty()) {
                        Text(
                            "The jobs folder is empty. Export a workflow from ComfyUI with Export (API) and put it there.",
                            color = DarkTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        workflows.workflows.forEach { workflow ->
                            val enabled = usable(workflow)
                            val selected = chosen?.id == workflow.id
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) OneUIBlue.copy(alpha = 0.28f) else Color(0x14FFFFFF))
                                    .border(1.dp, if (selected) OneUIBlue else Color.Transparent, RoundedCornerShape(14.dp))
                                    .clickable(enabled = enabled) {
                                        chosen = workflow
                                        prompt = ""
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    workflow.name,
                                    color = if (enabled) DarkTextPrimary else Color(0x61FFFFFF),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                val note = when {
                                    !enabled -> "Takes a photo, not a video"
                                    else -> workflow.description
                                }
                                if (note.isNotEmpty()) {
                                    Text(note, color = DarkTextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    val job = chosen
                    if (job != null && job.takesPrompt) {
                        // The heading is outside the box on purpose. Material only shows a
                        // placeholder under a floating label once the box has focus, and the
                        // greyed-out prompt has to be readable before anything is tapped.
                        Text("Prompt", color = DarkTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it.take(4000) },
                            // The workflow's own prompt, greyed out: what runs if this is left empty
                            placeholder = {
                                Text(job.promptDefault.orEmpty().ifEmpty { "The workflow has no prompt of its own" })
                            },
                            supportingText = {
                                Text(
                                    if (job.promptDefault.isNullOrEmpty()) "Type what you want for this run"
                                    else "Leave empty to use the workflow's own prompt",
                                    color = DarkTextSecondary
                                )
                            },
                            minLines = 2,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Button(
                        enabled = job != null,
                        onClick = {
                            if (job != null) {
                                controller.send(
                                    item = item.takeIf { job.requiresImage },
                                    workflow = job,
                                    prompt = prompt,
                                    maxUploadBytes = workflows.maxUploadBytes
                                )
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (job == null) "Pick a job" else "Start ${job.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }

    if (showSetup) {
        Dialog(
            onDismissRequest = { showSetup = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ComfyConnectionScreen(onClose = { showSetup = false })
        }
    }
}

/** Every job sent from this phone and where it has got to. Results are in the ComfyUI album. */
@Composable
fun ComfyJobsScreen(onClose: () -> Unit, onOpenConnection: () -> Unit) {
    val controller = ComfyController.get(LocalContext.current)
    val jobs by controller.jobs.collectAsStateWithLifecycle()
    val connection by controller.connection.collectAsStateWithLifecycle()
    val time = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
    ) {
        ScreenHeader(
            title = "ComfyUI jobs",
            subtitle = if (connection == null) "Not paired with a gateway" else "Results are saved to the ComfyUI album",
            onBack = onClose
        )
        if (connection == null) {
            Button(onClick = onOpenConnection, modifier = Modifier.padding(horizontal = 24.dp)) { Text("Set up connection") }
        }
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Open a photo and press Share to send it to a workflow.",
                    color = DarkTextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(32.dp)
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(jobs, key = { it.localId }) { job ->
                JobRow(
                    job = job,
                    time = time.format(Date(job.createdMs)),
                    onRetry = { controller.retry(job) },
                    onDismiss = { controller.dismiss(job) }
                )
            }
        }
    }
}

@Composable
private fun JobRow(job: ComfyJob, time: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The result once there is one; until then, what was sent
        val picture = job.savedUris.firstOrNull() ?: job.sourceUri
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1F2125)),
            contentAlignment = Alignment.Center
        ) {
            if (picture != null) {
                AsyncImage(
                    model = rememberThumbnailRequest(android.net.Uri.parse(picture)),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!job.isFinished) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(job.workflowName, color = DarkTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (label, color) = when (job.status) {
                ComfyJobStatus.SENDING -> "Sending…" to DarkTextSecondary
                ComfyJobStatus.QUEUED -> "Waiting its turn" to DarkTextSecondary
                ComfyJobStatus.RUNNING -> "Running…" to OneUIBlue
                ComfyJobStatus.COMPLETED -> (if (job.savedUris.size > 1) "Saved ${job.savedUris.size} results" else "Saved") to DarkTextPrimary
                ComfyJobStatus.FAILED -> (job.error ?: "Failed") to Failure
            }
            Text(label, color = color, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(time, color = DarkTextSecondary, fontSize = 11.sp)
        }
        if (job.status == ComfyJobStatus.FAILED && job.canRetry) {
            IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Send again", tint = DarkTextPrimary) }
        }
        if (job.isFinished) {
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Remove from the list", tint = DarkTextSecondary) }
        }
    }
}
