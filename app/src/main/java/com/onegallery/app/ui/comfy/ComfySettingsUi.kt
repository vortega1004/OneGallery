package com.onegallery.app.ui.comfy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onegallery.app.data.comfy.ComfyLoraChoice
import com.onegallery.app.data.comfy.ComfyParam
import com.onegallery.app.data.comfy.ComfyParamKind
import com.onegallery.app.data.comfy.ComfySettings
import com.onegallery.app.data.comfy.ComfyWorkflow
import com.onegallery.app.data.comfy.loraFolder
import com.onegallery.app.data.comfy.loraTitle
import com.onegallery.app.ui.theme.DarkSurface
import com.onegallery.app.ui.theme.DarkTextPrimary
import com.onegallery.app.ui.theme.DarkTextSecondary
import com.onegallery.app.ui.theme.OneUIBlue
import kotlin.math.roundToInt

private val Chip = Color(0x14FFFFFF)

/**
 * Everything the gateway found in [workflow] that may be changed for this run: prompts first,
 * then the seed and sampler settings, then (folded away) the model files, then LoRAs.
 *
 * Every control shows the workflow's own value greyed out; only what is typed or picked is
 * sent. [lastSeed] is the seed the last run of this job used, offered when fixing the seed.
 */
@Composable
fun ComfySettingsEditor(
    workflow: ComfyWorkflow,
    settings: ComfySettings,
    lastSeed: Long?,
    onChange: (ComfySettings) -> Unit
) {
    fun set(id: String, text: String?) {
        val values = settings.values.toMutableMap()
        if (text == null) values.remove(id) else values[id] = text
        onChange(settings.copy(values = values))
    }

    val byGroup = workflow.params.groupBy { it.group }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        byGroup["prompt"].orEmpty().forEach { param ->
            PromptField(param, settings.values[param.id].orEmpty()) { set(param.id, it.ifEmpty { null }) }
        }

        val tuning = byGroup["settings"].orEmpty()
        if (tuning.isNotEmpty()) {
            Section("Settings", startOpen = true, trailing = if (settings != ComfySettings()) "Reset all" else null,
                onTrailing = { onChange(ComfySettings()) }) {
                tuning.firstOrNull { it.kind == ComfyParamKind.SEED }?.let { seed ->
                    SeedRow(seed, settings, lastSeed, onChange)
                }
                ParamFields(tuning.filter { it.kind != ComfyParamKind.SEED }, settings.values, ::set)
            }
        }

        val models = byGroup["models"].orEmpty()
        if (models.isNotEmpty()) {
            Section("Models", startOpen = false) { ParamFields(models, settings.values, ::set) }
        }

        val ownLoras = byGroup["loras"].orEmpty()
        if (ownLoras.isNotEmpty() || workflow.loras.canAdd) {
            Section("LoRAs", startOpen = ownLoras.isNotEmpty() || settings.loras.isNotEmpty()) {
                if (ownLoras.isNotEmpty()) {
                    Text("In the workflow", color = DarkTextSecondary, fontSize = 12.sp)
                    ParamFields(ownLoras, settings.values, ::set)
                }
                if (workflow.loras.canAdd) {
                    AddedLoras(workflow, settings, onChange)
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    startOpen: Boolean,
    trailing: String? = null,
    onTrailing: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Chip)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = DarkTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (trailing != null) TextButton(onClick = onTrailing) { Text(trailing) }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = DarkTextSecondary)
        }
        if (open) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) { content() }
        }
    }
}

@Composable
private fun PromptField(param: ComfyParam, text: String, onText: (String) -> Unit) {
    // The heading sits outside the box on purpose: Material shows a placeholder under a
    // floating label only once the box has focus, and the workflow's own prompt, greyed out,
    // has to be readable before anything is tapped.
    Text(param.label, color = DarkTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val own = param.defaultText
    OutlinedTextField(
        value = text,
        onValueChange = { onText(it.take(12_000)) },
        placeholder = { Text(own.ifEmpty { "The workflow has none of its own" }, maxLines = 6, overflow = TextOverflow.Ellipsis) },
        supportingText = {
            Text(
                if (own.isEmpty()) "Type what you want for this run" else "Leave empty to use the workflow's own",
                color = DarkTextSecondary
            )
        },
        minLines = if (param.id.startsWith("negative")) 1 else 2,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SeedRow(param: ComfyParam, settings: ComfySettings, lastSeed: Long?, onChange: (ComfySettings) -> Unit) {
    val fixed = settings.seedFixed ?: !param.randomByDefault
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Seed", color = DarkTextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Toggle("Random", !fixed) { onChange(settings.copy(seedFixed = false)) }
            Spacer(Modifier.width(6.dp))
            Toggle("Fixed", fixed) {
                val values = settings.values.toMutableMap()
                // Fixing the seed starts from the one the last run used: "keep this one"
                if (values[param.id].isNullOrEmpty() && lastSeed != null) values[param.id] = lastSeed.toString()
                onChange(settings.copy(seedFixed = true, values = values))
            }
        }
        if (fixed) {
            OutlinedTextField(
                value = settings.values[param.id].orEmpty(),
                onValueChange = { typed ->
                    val values = settings.values.toMutableMap()
                    values[param.id] = typed.filter(Char::isDigit).take(16)
                    onChange(settings.copy(values = values))
                },
                placeholder = { Text(param.defaultText) },
                supportingText = {
                    Text(if (lastSeed != null) "Last run used $lastSeed" else "The same seed makes the same picture", color = DarkTextSecondary)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("A new seed every run", color = DarkTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) OneUIBlue.copy(alpha = 0.3f) else Chip)
            .border(1.dp, if (on) OneUIBlue else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(label, color = DarkTextPrimary, fontSize = 13.sp) }
}

/** Numbers two to a row, everything else a row of its own. */
@Composable
private fun ParamFields(params: List<ComfyParam>, values: Map<String, String>, set: (String, String?) -> Unit) {
    val rows = ArrayList<List<ComfyParam>>()
    var pending: ComfyParam? = null
    for (param in params) {
        val number = param.kind == ComfyParamKind.INT || param.kind == ComfyParamKind.FLOAT
        if (number && param.label.length <= 18) {
            pending = if (pending == null) param else { rows += listOf(pending, param); null }
        } else {
            pending?.let { rows += listOf(it) }
            pending = null
            rows += listOf(param)
        }
    }
    pending?.let { rows += listOf(it) }
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { param ->
                Box(Modifier.weight(1f)) { ParamField(param, values[param.id], set) }
            }
            if (row.size == 1 && (row[0].kind == ComfyParamKind.INT || row[0].kind == ComfyParamKind.FLOAT) && row[0].label.length <= 18) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ParamField(param: ComfyParam, value: String?, set: (String, String?) -> Unit) {
    when (param.kind) {
        // The name above the box, not as its label: a label sits where the greyed-out value
        // should be until the box is tapped, and the workflow's own value has to show
        ComfyParamKind.INT, ComfyParamKind.FLOAT -> Column {
            Text(param.label, color = DarkTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(
                value = value.orEmpty(),
                onValueChange = { typed ->
                    val kept = typed.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(24)
                    set(param.id, kept.ifEmpty { null })
                },
                placeholder = { Text(param.defaultText) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (param.kind == ComfyParamKind.INT) KeyboardType.Number else KeyboardType.Decimal
                ),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        ComfyParamKind.BOOL -> {
            val on = value?.let { it == "true" } ?: (param.default as? Boolean ?: false)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(param.label, color = DarkTextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = { checked ->
                        set(param.id, if (checked == (param.default as? Boolean ?: false)) null else checked.toString())
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = OneUIBlue)
                )
            }
        }

        ComfyParamKind.CHOICE -> ChoiceField(param, value) { picked ->
            set(param.id, if (picked == param.defaultText) null else picked)
        }

        ComfyParamKind.TEXT -> OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = { set(param.id, it.ifEmpty { null }) },
            label = { Text(param.label) },
            placeholder = { Text(param.defaultText, maxLines = 2) },
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        ComfyParamKind.SEED -> Unit
    }
}

/** A drop-down list. A model file is shown by its name, with its folder underneath. */
@Composable
private fun ChoiceField(param: ComfyParam, value: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = value ?: param.defaultText
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, if (value != null) OneUIBlue else Color(0x40FFFFFF), RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(param.label, color = DarkTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shown.replace('\\', '/').substringAfterLast('/'),
                    color = if (value != null) DarkTextPrimary else DarkTextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Rounded.ArrowDropDown, null, tint = DarkTextSecondary)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 360.dp).background(DarkSurface)
        ) {
            param.choices.forEach { choice ->
                val folder = loraFolder(choice)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.replace('\\', '/').substringAfterLast('/'), color = DarkTextPrimary, fontSize = 14.sp)
                            if (folder.isNotEmpty()) Text(folder, color = DarkTextSecondary, fontSize = 11.sp)
                        }
                    },
                    trailingIcon = if (choice == shown) ({ Text("✓", color = OneUIBlue) }) else null,
                    onClick = {
                        open = false
                        onPick(choice)
                    }
                )
            }
        }
    }
}

@Composable
private fun AddedLoras(workflow: ComfyWorkflow, settings: ComfySettings, onChange: (ComfySettings) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val options = workflow.loras
    fun update(index: Int, lora: ComfyLoraChoice?) {
        val list = settings.loras.toMutableList()
        if (lora == null) list.removeAt(index) else list[index] = lora
        onChange(settings.copy(loras = list))
    }

    settings.loras.forEachIndexed { index, lora ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x0FFFFFFF))
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(loraTitle(lora.name), color = DarkTextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    loraFolder(lora.name).takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = DarkTextSecondary, fontSize = 11.sp)
                    }
                }
                Text(String.format(java.util.Locale.US, "%.2f", lora.strength), color = DarkTextPrimary, fontSize = 13.sp)
                IconButton(onClick = { update(index, null) }) {
                    Icon(Icons.Rounded.Close, "Remove this LoRA", tint = DarkTextSecondary)
                }
            }
            Slider(
                value = lora.strength.toFloat().coerceIn(-2f, 2f),
                onValueChange = { update(index, lora.copy(strength = (it * 20).roundToInt() / 20.0)) },
                valueRange = -2f..2f,
                colors = SliderDefaults.colors(thumbColor = OneUIBlue, activeTrackColor = OneUIBlue),
                modifier = Modifier.padding(end = 8.dp)
            )
            if (options.targets.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Toggle("All models", lora.target == null) { update(index, lora.copy(target = null)) }
                    options.targets.forEach { (id, label) ->
                        Toggle(label.take(28), lora.target == id) { update(index, lora.copy(target = id)) }
                    }
                }
            }
        }
    }

    val left = options.available.filter { name -> settings.loras.none { it.name == name } }
    if (options.available.isEmpty()) {
        Text(
            if (options.folders.isNullOrEmpty()) "No LoRAs found in the models folder"
            else "No LoRAs in " + options.folders.joinToString { it.ifEmpty { "the top folder" } },
            color = DarkTextSecondary,
            fontSize = 12.sp
        )
    } else {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = left.isNotEmpty() && settings.loras.size < 8) { picking = true }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, tint = OneUIBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add LoRA", color = OneUIBlue, fontSize = 14.sp)
            options.folders?.takeIf { it.isNotEmpty() }?.let { folders ->
                Text("  from " + folders.joinToString { it.ifEmpty { "top folder" } }, color = DarkTextSecondary, fontSize = 12.sp)
            }
        }
    }

    if (picking) {
        LoraPicker(
            available = left,
            onPick = { name ->
                picking = false
                onChange(settings.copy(loras = settings.loras + ComfyLoraChoice(name)))
            },
            onDismiss = { picking = false }
        )
    }
}

/** The LoRAs on offer, a folder at a time. */
@Composable
private fun LoraPicker(available: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val folders = remember(available) { available.map(::loraFolder).distinct().sorted() }
    var folder by rememberSaveable { mutableStateOf<String?>(if (folders.size == 1) folders[0] else null) }
    val shown = available.filter { folder == null || loraFolder(it) == folder }.sortedBy { loraTitle(it).lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        titleContentColor = DarkTextPrimary,
        title = { Text("Add a LoRA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (folders.size > 1) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Toggle("All", folder == null) { folder = null }
                        folders.forEach { f -> Toggle(f.ifEmpty { "Top folder" }, folder == f) { folder = f } }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it }) { name ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(name) }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(loraTitle(name), color = DarkTextPrimary, fontSize = 14.sp)
                            if (folder == null && loraFolder(name).isNotEmpty()) {
                                Text(loraFolder(name), color = DarkTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
