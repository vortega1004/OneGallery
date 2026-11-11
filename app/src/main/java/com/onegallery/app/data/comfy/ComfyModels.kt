package com.onegallery.app.data.comfy

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app knows about the ComfyUI gateway, as plain values: the jobs it offers and their
 * settings, a job in flight, and how each travels as JSON. No Android types and no network
 * here — the rules are unit-tested (`ComfyModelsTest`).
 *
 * The gateway is the small server in `comfyui-bridge/`. The app talks only to it, never to
 * ComfyUI: it can list jobs, start one with a photo, its settings and LoRAs, ask how a job is
 * doing and fetch its results.
 */

/** What kind of value a setting holds, and so which control edits it. */
enum class ComfyParamKind { TEXT, INT, FLOAT, CHOICE, BOOL, SEED }

/**
 * One thing about a job the phone may change — found by the gateway in the workflow itself:
 * the prompt, the seed, steps, CFG, the sampler, a model file, a LoRA's strength…
 *
 * [default] is the workflow's own value (String, Long, Double or Boolean). It is shown greyed
 * out; a setting left alone runs with it.
 */
data class ComfyParam(
    val id: String,
    val kind: ComfyParamKind,
    val label: String,
    /** "prompt", "settings", "models" or "loras": where on the sheet it goes. */
    val group: String,
    val default: Any?,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val choices: List<String> = emptyList(),
    val multiline: Boolean = false,
    /** For the seed: whether the gateway rolls a new one when the phone does not say. */
    val randomByDefault: Boolean = true
) {
    /** The default as it is shown in a text box. */
    val defaultText: String
        get() = when (val d = default) {
            null -> ""
            is Double -> formatNumber(d)
            else -> d.toString()
        }
}

/** Adding LoRAs: whether the job can take them, which files it offers and onto which model. */
data class ComfyLoraOptions(
    val canAdd: Boolean = false,
    /** Sub-folders of `models/loras` this job's LoRAs come from; null when it takes any. */
    val folders: List<String>? = null,
    val available: List<String> = emptyList(),
    /** (id, label) of each model a LoRA can go on — two for Wan 2.2's high and low noise. */
    val targets: List<Pair<String, String>> = emptyList()
)

/** One job the gateway offers — a workflow file in its jobs folder. */
data class ComfyWorkflow(
    val id: String,
    val name: String,
    val description: String,
    val requiresImage: Boolean,
    val params: List<ComfyParam> = emptyList(),
    val loras: ComfyLoraOptions = ComfyLoraOptions()
) {
    val prompt: ComfyParam? get() = params.firstOrNull { it.id == "prompt" }

    /**
     * The workflow's own prompt when it has one the phone may replace; null when the job takes
     * no text. Shown greyed out in the text box: leaving the box empty runs the job with it.
     */
    val promptDefault: String? get() = prompt?.let { it.default as? String ?: "" }
    val takesPrompt: Boolean get() = prompt != null
}

data class ComfyWorkflowList(
    val maxUploadBytes: Long,
    /** False when the gateway is up but ComfyUI behind it is not answering. */
    val comfyReachable: Boolean,
    val workflows: List<ComfyWorkflow>,
    /** Files in the jobs folder that cannot be run, with the gateway's reason for each. */
    val invalid: List<Pair<String, String>>
)

/** A LoRA to add to a job, at a strength, on one model ([target] null: on every model). */
data class ComfyLoraChoice(val name: String, val strength: Double = 1.0, val target: String? = null)

/**
 * What the person changed on the sheet for one job — kept per workflow, so a tuned CFG or a
 * favourite LoRA is still there next time.
 *
 * [values] holds the text of each changed control, by setting id ("true"/"false" for a switch);
 * a setting not in it runs with the workflow's own value. [seedFixed] null leaves the seed to
 * the gateway (random unless the gateway is set otherwise); the fixed value is `values["seed"]`.
 */
data class ComfySettings(
    val values: Map<String, String> = emptyMap(),
    val seedFixed: Boolean? = null,
    val loras: List<ComfyLoraChoice> = emptyList()
)

/** A LoRA's folder, "/"-separated, "" at the top of `models/loras`. */
fun loraFolder(name: String): String = name.replace('\\', '/').substringBeforeLast('/', "")

/** A LoRA's file name without its folder or extension. */
fun loraTitle(name: String): String = name.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')

private fun formatNumber(value: Double): String =
    if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
    else value.toBigDecimal().stripTrailingZeros().toPlainString()

private fun trimNumber(value: Double): String = formatNumber(value)

/**
 * [this] as the gateway wants it: `params` (setting id → typed value) and `loras`. Boxes left
 * empty are left out, so they run with the workflow's own value. Fails with the problems, one
 * sentence per line, when a number is not one or is out of range — the gateway would refuse
 * those anyway, and this way the sheet can say so before anything is sent.
 */
fun ComfySettings.toRequest(params: List<ComfyParam>): Result<Pair<JSONObject, JSONArray>> {
    val out = JSONObject()
    val problems = ArrayList<String>()
    for (param in params) {
        if (param.kind == ComfyParamKind.SEED) {
            when (seedFixed) {
                true -> {
                    val number = values[param.id]?.trim()?.toLongOrNull() ?: (param.default as? Long)
                    if (number == null || number < 0) problems += "The fixed seed has to be a whole number from 0"
                    else out.put(param.id, JSONObject().put("mode", "fixed").put("value", number))
                }
                false -> out.put(param.id, JSONObject().put("mode", "random"))
                null -> Unit
            }
            continue
        }
        val text = values[param.id] ?: continue
        when (param.kind) {
            ComfyParamKind.TEXT -> if (text.isNotBlank()) out.put(param.id, text)
            ComfyParamKind.CHOICE -> if (text.isNotEmpty()) out.put(param.id, text)
            ComfyParamKind.BOOL -> out.put(param.id, text == "true")
            ComfyParamKind.INT, ComfyParamKind.FLOAT -> {
                val trimmed = text.trim().replace(',', '.')
                if (trimmed.isEmpty()) continue
                val number = trimmed.toDoubleOrNull()
                when {
                    number == null || number.isNaN() || number.isInfinite() -> problems += "${param.label} has to be a number"
                    param.kind == ComfyParamKind.INT && number != Math.floor(number) ->
                        problems += "${param.label} has to be a whole number"
                    param.min != null && number < param.min -> problems += "${param.label} has to be at least ${trimNumber(param.min)}"
                    param.max != null && number > param.max -> problems += "${param.label} has to be at most ${trimNumber(param.max)}"
                    param.kind == ComfyParamKind.INT -> out.put(param.id, number.toLong())
                    else -> out.put(param.id, number)
                }
            }
            ComfyParamKind.SEED -> Unit
        }
    }
    val loras = JSONArray()
    this.loras.forEach { lora ->
        val item = JSONObject().put("name", lora.name).put("strength", lora.strength)
        lora.target?.let { item.put("target", it) }
        loras.put(item)
    }
    return if (problems.isEmpty()) Result.success(out to loras)
    else Result.failure(IllegalArgumentException(problems.joinToString("\n")))
}

fun encodeSettings(settings: ComfySettings): String = JSONObject()
    .put("values", JSONObject().apply { settings.values.forEach { (k, v) -> put(k, v) } })
    .put("seed_fixed", settings.seedFixed ?: JSONObject.NULL)
    .put("loras", JSONArray().apply {
        settings.loras.forEach {
            put(JSONObject().put("name", it.name).put("strength", it.strength).put("target", it.target ?: JSONObject.NULL))
        }
    })
    .toString()

fun decodeSettings(text: String?): ComfySettings {
    val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ComfySettings()
    val stored = json.optJSONObject("values")
    val values = stored?.keys()?.asSequence()?.associateWith { stored.optString(it) }.orEmpty()
    val loras = json.optJSONArray("loras").objects().mapNotNull {
        val name = it.optString("name").takeIf { n -> n.isNotEmpty() } ?: return@mapNotNull null
        ComfyLoraChoice(name, it.optDouble("strength", 1.0), it.stringOrNull("target"))
    }
    val fixed = if (!json.has("seed_fixed") || json.isNull("seed_fixed")) null else json.optBoolean("seed_fixed")
    return ComfySettings(values, fixed, loras)
}

data class ComfyOutput(
    val id: String,
    val mediaType: String,
    val filename: String,
    val downloadPath: String
) {
    val isVideo: Boolean get() = mediaType.startsWith("video/")
}

enum class ComfyJobStatus {
    /** Still on the phone: the photo is being uploaded, or the upload has to be tried again. */
    SENDING,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED;

    /** Whether the gateway still has to be asked about it. */
    val isActive: Boolean get() = this == QUEUED || this == RUNNING
}

/** What the gateway says about a job when asked. */
data class ComfyRemoteJob(
    val status: ComfyJobStatus,
    val outputs: List<ComfyOutput>,
    val error: String?,
    /** The seed it ran with (the gateway picks one when the phone left it random). */
    val seed: Long? = null
)

/**
 * A job as the phone remembers it — kept across restarts, so a job started before the app was
 * closed is picked up again when it opens.
 *
 * [localId] is made on the phone when Start is pressed and doubles as the idempotency key: a
 * submission whose answer never arrived is retried with the same id and the gateway hands back
 * the same job instead of running it twice.
 */
data class ComfyJob(
    val localId: String,
    val jobId: String? = null,
    val workflowId: String,
    val workflowName: String,
    val status: ComfyJobStatus,
    val createdMs: Long,
    val sourceUri: String? = null,
    val prompt: String? = null,
    val error: String? = null,
    /** MediaStore uris of the results once they are saved to the ComfyUI album. */
    val savedUris: List<String> = emptyList(),
    /** Whether a failed job can simply be sent again (the trouble was the network, not the job). */
    val canRetry: Boolean = false,
    /**
     * The gateway's upload limit when this job was started. Kept with the job so that sending
     * it again shrinks the photo against the same limit, not against a default.
     */
    val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    /** The settings and LoRAs sent, as JSON — kept so that sending it again sends the same. */
    val paramsJson: String? = null,
    val lorasJson: String? = null,
    /** The seed it ran with, once the gateway has said. */
    val seed: Long? = null
) {
    val isFinished: Boolean get() = status == ComfyJobStatus.COMPLETED || status == ComfyJobStatus.FAILED
}

/** A refusal from the gateway: the HTTP status, its stable code and a sentence that can be shown. */
class ComfyApiException(val status: Int, val code: String, message: String) : Exception(message)

/**
 * The four-digit code the gateway prints in the ComfyUI window when it starts. It works once,
 * for half an hour, and five wrong tries close pairing on the computer.
 */
object PairingCode {
    const val LENGTH = 4

    /** The digits of a typed code, or null when it is not four of them. Spaces do not matter. */
    fun parse(text: String): String? {
        val digits = text.filter { !it.isWhitespace() && it != '-' }
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }
}

/** A gateway that answered the phone's "is anyone there?" on the local network. */
data class FoundGateway(val name: String, val address: String, val pairingOpen: Boolean)

/** What the phone sends, by UDP broadcast to the gateway's port, to find gateways. */
const val DISCOVERY_QUESTION = "ONEGALLERY_DISCOVER 1"

/** A gateway's answer to the discovery question, or null when it is not one. */
fun parseDiscoveryAnswer(text: String, fromHost: String): FoundGateway? {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (json.optString("service") != "onegallery-gateway") return null
    val port = json.optInt("port", DEFAULT_GATEWAY_PORT)
    val host = if (':' in fromHost) "[$fromHost]" else fromHost
    return FoundGateway(
        name = json.optString("name").ifEmpty { fromHost },
        address = "https://$host:$port",
        pairingOpen = json.optBoolean("pairing", false)
    )
}

/**
 * A typed gateway address as `https://host:port`, or null when it cannot be one.
 *
 * `https://` and the gateway's usual port are filled in when left off. Plain `http://` is
 * refused rather than quietly upgraded or, worse, used: the gateway does not listen on it.
 */
fun normaliseGatewayAddress(text: String): String? {
    var address = text.trim().trimEnd('/')
    if (address.isEmpty() || address.any { it.isWhitespace() }) return null
    if (address.startsWith("http://", ignoreCase = true)) return null
    if (address.startsWith("https://", ignoreCase = true)) address = address.substring(8)
    if (address.isEmpty() || address.contains('/')) return null
    val hasPort = address.substringAfterLast(':', "").let { it.isNotEmpty() && it.all(Char::isDigit) } &&
        !address.endsWith("]")
    return "https://" + if (hasPort) address else "$address:$DEFAULT_GATEWAY_PORT"
}

const val DEFAULT_GATEWAY_PORT = 8443

/** The gateway's own default, used only when it has never said otherwise. */
const val DEFAULT_MAX_UPLOAD_BYTES = 20L * 1024 * 1024

// --- JSON ----------------------------------------------------------------------------------------

fun parseWorkflowList(json: JSONObject): ComfyWorkflowList {
    val workflows = json.optJSONArray("workflows").objects().map { item ->
        var params = item.optJSONArray("params").objects().mapNotNull(::parseParam)
        val prompt = item.optJSONObject("prompt")
        if (params.isEmpty() && prompt != null && prompt.optBoolean("editable", true)) {
            // A gateway from before "params": only the prompt
            params = listOf(
                ComfyParam("prompt", ComfyParamKind.TEXT, "Prompt", "prompt", prompt.optString("default", ""), multiline = true)
            )
        }
        val loras = item.optJSONObject("loras")
        ComfyWorkflow(
            id = item.getString("id"),
            name = item.optString("name", item.getString("id")),
            description = item.optString("description", ""),
            requiresImage = item.optBoolean("requires_image", false),
            params = params,
            loras = if (loras == null) ComfyLoraOptions() else ComfyLoraOptions(
                canAdd = loras.optBoolean("can_add", false),
                folders = if (loras.isNull("folders")) null else loras.optJSONArray("folders")?.strings(keepEmpty = true),
                available = loras.optJSONArray("available").strings(),
                targets = loras.optJSONArray("targets").objects().map { it.optString("id") to it.optString("label") }
            )
        )
    }
    val invalid = json.optJSONArray("invalid").objects().map {
        it.optString("file", "") to it.optString("reason", "")
    }
    return ComfyWorkflowList(
        maxUploadBytes = json.optLong("max_upload_bytes", DEFAULT_MAX_UPLOAD_BYTES),
        comfyReachable = json.optBoolean("comfyui_reachable", true),
        workflows = workflows,
        invalid = invalid
    )
}

private fun parseParam(item: JSONObject): ComfyParam? {
    val kind = when (item.optString("kind")) {
        "text" -> ComfyParamKind.TEXT
        "int" -> ComfyParamKind.INT
        "float" -> ComfyParamKind.FLOAT
        "choice" -> ComfyParamKind.CHOICE
        "bool" -> ComfyParamKind.BOOL
        "seed" -> ComfyParamKind.SEED
        else -> return null // a kind this version cannot edit is simply not offered
    }
    val id = item.optString("id").ifEmpty { return null }
    val raw = if (!item.has("default") || item.isNull("default")) null else item.opt("default")
    val default: Any? = when (kind) {
        ComfyParamKind.INT, ComfyParamKind.SEED -> (raw as? Number)?.toLong()
        ComfyParamKind.FLOAT -> (raw as? Number)?.toDouble()
        ComfyParamKind.BOOL -> raw as? Boolean
        else -> raw?.toString()
    }
    fun number(key: String) =
        if (item.has(key) && !item.isNull(key)) item.optDouble(key).takeIf { !it.isNaN() } else null
    return ComfyParam(
        id = id,
        kind = kind,
        label = item.optString("label", id),
        group = item.optString("group", "settings"),
        default = default,
        min = number("min"),
        max = number("max"),
        step = number("step"),
        choices = item.optJSONArray("choices").strings(keepEmpty = true),
        multiline = item.optBoolean("multiline", false),
        randomByDefault = item.optString("mode", "random") != "fixed"
    )
}

fun parseRemoteJob(json: JSONObject): ComfyRemoteJob {
    val status = when (json.optString("status")) {
        "queued" -> ComfyJobStatus.QUEUED
        "running" -> ComfyJobStatus.RUNNING
        "completed" -> ComfyJobStatus.COMPLETED
        "failed" -> ComfyJobStatus.FAILED
        // A status this version does not know is not a reason to give up on the job
        else -> ComfyJobStatus.RUNNING
    }
    val outputs = json.optJSONArray("outputs").objects().map {
        ComfyOutput(
            id = it.getString("id"),
            mediaType = it.optString("media_type", "application/octet-stream"),
            filename = it.optString("filename", it.getString("id")),
            downloadPath = it.getString("download_path")
        )
    }
    val seed = if (json.has("seed") && !json.isNull("seed")) json.optLong("seed") else null
    return ComfyRemoteJob(status, outputs, json.optJSONObject("error")?.optString("message"), seed)
}

/** The gateway's `{"error":{"code","message"}}`, or a plain sentence when the body is not that. */
fun parseApiError(status: Int, body: String): ComfyApiException {
    val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
    return ComfyApiException(
        status = status,
        code = error?.optString("code").orEmpty().ifEmpty { "http_$status" },
        message = error?.optString("message").orEmpty().ifEmpty { "The gateway answered with an error ($status)" }
    )
}

fun encodeJobs(jobs: List<ComfyJob>): String = JSONArray().apply {
    jobs.forEach { job ->
        put(
            JSONObject()
                .put("local_id", job.localId)
                .put("job_id", job.jobId ?: JSONObject.NULL)
                .put("workflow_id", job.workflowId)
                .put("workflow_name", job.workflowName)
                .put("status", job.status.name)
                .put("created", job.createdMs)
                .put("source", job.sourceUri ?: JSONObject.NULL)
                .put("prompt", job.prompt ?: JSONObject.NULL)
                .put("error", job.error ?: JSONObject.NULL)
                .put("saved", JSONArray(job.savedUris))
                .put("can_retry", job.canRetry)
                .put("max_upload", job.maxUploadBytes)
                .put("params", job.paramsJson ?: JSONObject.NULL)
                .put("loras", job.lorasJson ?: JSONObject.NULL)
                .put("seed", job.seed ?: JSONObject.NULL)
        )
    }
}.toString()

fun decodeJobs(text: String): List<ComfyJob> {
    val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return array.objects().mapNotNull { item ->
        runCatching {
            ComfyJob(
                localId = item.getString("local_id"),
                jobId = item.stringOrNull("job_id"),
                workflowId = item.getString("workflow_id"),
                workflowName = item.optString("workflow_name", item.getString("workflow_id")),
                status = ComfyJobStatus.valueOf(item.getString("status")),
                createdMs = item.optLong("created"),
                sourceUri = item.stringOrNull("source"),
                prompt = item.stringOrNull("prompt"),
                error = item.stringOrNull("error"),
                savedUris = item.optJSONArray("saved").strings(),
                canRetry = item.optBoolean("can_retry", false),
                maxUploadBytes = item.optLong("max_upload", DEFAULT_MAX_UPLOAD_BYTES),
                paramsJson = item.stringOrNull("params"),
                lorasJson = item.stringOrNull("loras"),
                seed = if (item.has("seed") && !item.isNull("seed")) item.optLong("seed") else null
            )
        }.getOrNull()
    }
}

/** How many finished jobs the phone keeps in its list. */
const val MAX_REMEMBERED_JOBS = 40

/** [jobs] with [job] added or replaced (matched on [ComfyJob.localId]), newest first, trimmed. */
fun List<ComfyJob>.withJob(job: ComfyJob): List<ComfyJob> {
    val others = filter { it.localId != job.localId }
    val all = (others + job).sortedByDescending { it.createdMs }
    val unfinished = all.filter { !it.isFinished }
    val finished = all.filter { it.isFinished }.take(MAX_REMEMBERED_JOBS)
    return (unfinished + finished).sortedByDescending { it.createdMs }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(keepEmpty: Boolean = false): List<String> =
    if (this == null) emptyList()
    else (0 until length()).mapNotNull { i -> if (isNull(i)) null else optString(i).takeIf { keepEmpty || it.isNotEmpty() } }

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
