package com.onegallery.app.data.comfy

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app knows about the ComfyUI gateway, as plain values: the jobs it offers, a job in
 * flight, and how each travels as JSON. No Android types and no network here — the rules are
 * unit-tested (`ComfyModelsTest`).
 *
 * The gateway is the small server in `comfyui-bridge/`. The app talks only to it, never to
 * ComfyUI: it can list jobs, start one with a photo and/or a line of text, ask how a job is
 * doing and fetch its results.
 */

/** One job the gateway offers — a workflow file in its jobs folder. */
data class ComfyWorkflow(
    val id: String,
    val name: String,
    val description: String,
    val requiresImage: Boolean,
    /**
     * The workflow's own prompt when it has one the phone may replace; null when the job takes
     * no text. Shown greyed out in the text box: leaving the box empty runs the job with it.
     */
    val promptDefault: String?
) {
    val takesPrompt: Boolean get() = promptDefault != null
}

data class ComfyWorkflowList(
    val maxUploadBytes: Long,
    /** False when the gateway is up but ComfyUI behind it is not answering. */
    val comfyReachable: Boolean,
    val workflows: List<ComfyWorkflow>,
    /** Files in the jobs folder that cannot be run, with the gateway's reason for each. */
    val invalid: List<Pair<String, String>>
)

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
    val error: String?
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
    val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES
) {
    val isFinished: Boolean get() = status == ComfyJobStatus.COMPLETED || status == ComfyJobStatus.FAILED
}

/** A refusal from the gateway: the HTTP status, its stable code and a sentence that can be shown. */
class ComfyApiException(val status: Int, val code: String, message: String) : Exception(message)

/**
 * The code read off the computer: an eight-character one-time secret followed by the first ten
 * hex digits of the gateway certificate's fingerprint. The phone checks the second part against
 * the certificate it is shown *before* it sends the first.
 */
data class PairingCode(val secret: String, val fingerprintPrefix: String) {
    companion object {
        const val SECRET_LENGTH = 8
        const val PREFIX_LENGTH = 10

        /** Reads a code however it was typed: dashes, spaces and case do not matter. */
        fun parse(text: String): PairingCode? {
            val typed = text.uppercase().filter { it.isLetterOrDigit() }
            if (typed.length != SECRET_LENGTH + PREFIX_LENGTH) return null
            val prefix = typed.substring(SECRET_LENGTH)
            if (!prefix.all { it in "0123456789ABCDEF" }) return null
            return PairingCode(typed.substring(0, SECRET_LENGTH), prefix)
        }
    }
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
        val prompt = item.optJSONObject("prompt")
        ComfyWorkflow(
            id = item.getString("id"),
            name = item.optString("name", item.getString("id")),
            description = item.optString("description", ""),
            requiresImage = item.optBoolean("requires_image", false),
            promptDefault = if (prompt != null && prompt.optBoolean("editable", true)) {
                prompt.optString("default", "")
            } else null
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
    return ComfyRemoteJob(status, outputs, json.optJSONObject("error")?.optString("message"))
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
                maxUploadBytes = item.optLong("max_upload", DEFAULT_MAX_UPLOAD_BYTES)
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

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
