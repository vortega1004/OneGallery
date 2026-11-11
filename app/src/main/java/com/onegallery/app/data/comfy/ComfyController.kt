package com.onegallery.app.data.comfy

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onegallery.app.domain.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException
import kotlin.math.max
import kotlin.math.roundToInt

/** The gateway this phone is paired with. The token is deliberately not part of it. */
data class ComfyConnection(val address: String, val fingerprint: String, val deviceId: String)

/**
 * Everything the app does with ComfyUI, in one place that outlives any screen: the pairing,
 * the jobs in flight, and bringing their results home.
 *
 * One instance for the process ([get]). A job is started from the viewer, which is usually
 * closed again long before a video has rendered; the checking and the saving go on here
 * regardless, and a job that was still running when the app was closed is picked up again the
 * next time it opens.
 *
 * Results are saved into `Pictures/ComfyUI` — one album, photos and videos together — as soon
 * as a job finishes.
 */
class ComfyController private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store: DataStore<Preferences> = context.comfyDataStore
    private val writes = Mutex()
    private var watcher: Job? = null

    private val _connection = MutableStateFlow<ComfyConnection?>(null)
    val connection: StateFlow<ComfyConnection?> = _connection

    private val _jobs = MutableStateFlow<List<ComfyJob>>(emptyList())
    val jobs: StateFlow<List<ComfyJob>> = _jobs

    /** False until what was saved has been read — so "not paired" is never shown by mistake. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    /** Short sentences for the app's message pill ("Echo test finished · saved to ComfyUI"). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    @Volatile
    private var token: String? = null

    init {
        scope.launch {
            val saved = store.data.first()
            val address = saved[ADDRESS]
            val fingerprint = saved[FINGERPRINT]
            token = saved[TOKEN]?.let { decrypt(it) }
            if (address != null && fingerprint != null && token != null) {
                _connection.value = ComfyConnection(address, fingerprint, saved[DEVICE_ID].orEmpty())
            }
            // A job that was mid-upload when the app died never got an answer. It may or may
            // not have reached the gateway; sending it again with the same id settles which.
            _jobs.value = decodeJobs(saved[JOBS].orEmpty()).map {
                if (it.status == ComfyJobStatus.SENDING) {
                    it.copy(status = ComfyJobStatus.FAILED, error = "Interrupted while sending", canRetry = true)
                } else it
            }
            _loaded.value = true
            watch()
        }
    }

    private fun client(): ComfyClient? {
        val paired = _connection.value ?: return null
        return ComfyClient(paired.address, paired.fingerprint, token)
    }

    // --- Pairing ---------------------------------------------------------------------------------

    /** Pairs with the gateway at [address] using the code read off the computer. Null on success. */
    suspend fun pair(address: String, code: String): String? = withContext(Dispatchers.IO) {
        val url = normaliseGatewayAddress(address)
            ?: return@withContext "That is not an address the gateway can be at. It looks like https://192.168.1.50:8443"
        val parsed = PairingCode.parse(code)
            ?: return@withContext "The pairing code is 18 letters and digits, shown as XXXX-XXXX-XXXXX-XXXXX"
        try {
            val name = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").take(60)
            val (deviceId, newToken, fingerprint) =
                ComfyClient(url, pin = null, token = null, pairingPrefix = parsed.fingerprintPrefix)
                    .pair(parsed.secret, name)
            token = newToken
            store.edit {
                it[ADDRESS] = url
                it[FINGERPRINT] = fingerprint
                it[DEVICE_ID] = deviceId
                it[TOKEN] = encrypt(newToken)
            }
            _connection.value = ComfyConnection(url, fingerprint, deviceId)
            null
        } catch (t: Throwable) {
            describe(t, pairing = true)
        }
    }

    /** Forgets the gateway and the jobs list. Results already saved to the gallery stay. */
    suspend fun forget() {
        watcher?.cancel()
        token = null
        _connection.value = null
        _jobs.value = emptyList()
        store.edit { it.clear() }
    }

    /** The jobs on offer, or a sentence saying why they could not be fetched. */
    suspend fun workflows(): Result<ComfyWorkflowList> = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext Result.failure(IOException("Not paired with a gateway yet"))
        try {
            Result.success(client.workflows())
        } catch (t: Throwable) {
            Result.failure(IOException(describe(t)))
        }
    }

    // --- Jobs ------------------------------------------------------------------------------------

    /** Starts [workflow] on [item] (null for a job that takes no photo). Returns at once. */
    fun send(item: MediaItem?, workflow: ComfyWorkflow, prompt: String?, maxUploadBytes: Long) {
        val job = ComfyJob(
            localId = UUID.randomUUID().toString(),
            workflowId = workflow.id,
            workflowName = workflow.name,
            status = ComfyJobStatus.SENDING,
            createdMs = System.currentTimeMillis(),
            sourceUri = item?.uri?.toString(),
            prompt = prompt?.takeIf { it.isNotBlank() }
        )
        scope.launch { upload(job, maxUploadBytes) }
    }

    /** Sends a job again under the id it already has, so it cannot run twice. */
    fun retry(job: ComfyJob) {
        scope.launch {
            upload(job.copy(status = ComfyJobStatus.SENDING, error = null, canRetry = false), DEFAULT_MAX_UPLOAD)
        }
    }

    fun dismiss(job: ComfyJob) {
        scope.launch { update { jobs -> jobs.filter { it.localId != job.localId } } }
    }

    private suspend fun upload(job: ComfyJob, maxUploadBytes: Long) {
        put(job)
        val client = client()
        if (client == null) {
            put(job.copy(status = ComfyJobStatus.FAILED, error = "Not paired with a gateway"))
            return
        }
        try {
            val source = job.sourceUri?.let { Uri.parse(it) }
            val jobId = if (source == null) {
                client.submit(job.workflowId, job.prompt, null, "", job.localId)
            } else {
                val (bytes, name) = photoToSend(source, maxUploadBytes)
                client.submit(job.workflowId, job.prompt, ByteArrayInputStream(bytes), name, job.localId)
            }
            put(job.copy(jobId = jobId, status = ComfyJobStatus.QUEUED))
            _messages.tryEmit("Sent to ComfyUI: ${job.workflowName}")
            watch()
        } catch (t: Throwable) {
            // A refusal from the gateway is final. Anything else (no network, timeout) leaves
            // it unknown whether the job arrived, and that is exactly what the retry is for.
            val refused = t is ComfyApiException
            put(job.copy(status = ComfyJobStatus.FAILED, error = describe(t), canRetry = !refused))
            _messages.tryEmit("${job.workflowName} could not be sent")
        }
    }

    /**
     * The bytes to upload for a photo. The original file as it is when the gateway takes its
     * format and it is small enough; otherwise a JPEG of it, turned upright, scaled down until
     * it fits.
     */
    private fun photoToSend(uri: Uri, maxUploadBytes: Long): Pair<ByteArray, String> {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        if (mime in ACCEPTED_AS_IS) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("The photo could not be opened")
            if (bytes.size <= maxUploadBytes) return bytes to "photo.${mime.substringAfter('/')}"
        }
        var side = MAX_CONVERTED_SIDE
        while (true) {
            val limit = side
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > limit) {
                    val ratio = limit.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                        (info.size.height * ratio).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            if (out.size() <= maxUploadBytes || side <= 1024) return out.toByteArray() to "photo.jpeg"
            side = side * 3 / 4
        }
    }

    /** Asks the gateway about every job still running, until none is. */
    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            var unreachable = 0
            while (true) {
                val active = _jobs.value.filter { it.status.isActive && it.jobId != null }
                if (active.isEmpty()) return@launch
                val client = client() ?: return@launch
                for (job in active) {
                    try {
                        val remote = client.job(job.jobId!!)
                        unreachable = 0
                        when (remote.status) {
                            ComfyJobStatus.COMPLETED -> finish(client, job, remote)
                            ComfyJobStatus.FAILED -> {
                                put(job.copy(status = ComfyJobStatus.FAILED, error = remote.error ?: "The job failed"))
                                _messages.tryEmit("${job.workflowName} failed")
                            }
                            else -> if (remote.status != job.status) put(job.copy(status = remote.status))
                        }
                    } catch (gone: ComfyApiException) {
                        if (gone.status == 404) {
                            put(job.copy(status = ComfyJobStatus.FAILED, error = "The gateway no longer knows this job"))
                        } else if (gone.status == 401) {
                            put(job.copy(status = ComfyJobStatus.FAILED, error = gone.message))
                        }
                    } catch (t: Throwable) {
                        // Out of range of the network, the computer asleep: keep the job and
                        // ask less often
                        unreachable++
                    }
                }
                delay(if (unreachable > 3) SLOW_POLL_MS else POLL_MS)
            }
        }
    }

    private suspend fun finish(client: ComfyClient, job: ComfyJob, remote: ComfyRemoteJob) {
        val saved = ArrayList<String>()
        try {
            remote.outputs.forEachIndexed { number, output ->
                saveResult(client, job, output, number)?.let { saved += it.toString() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bring a result home", t)
        }
        if (saved.isEmpty()) {
            put(job.copy(status = ComfyJobStatus.FAILED, error = "The job finished but its result could not be saved"))
            _messages.tryEmit("${job.workflowName}: the result could not be saved")
        } else {
            put(job.copy(status = ComfyJobStatus.COMPLETED, savedUris = saved, error = null))
            _messages.tryEmit("${job.workflowName} finished · saved to the ComfyUI album")
        }
    }

    /** Downloads one result straight into a new MediaStore item in `Pictures/ComfyUI`. */
    private fun saveResult(client: ComfyClient, job: ComfyJob, output: ComfyOutput, number: Int): Uri? {
        val resolver = context.contentResolver
        val extension = output.filename.substringAfterLast('.', "").lowercase()
            .ifEmpty { if (output.isVideo) "mp4" else "png" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = job.workflowName.filter { it.isLetterOrDigit() || it in " _-" }.trim().ifEmpty { "ComfyUI" }
        val suffix = if (number == 0) "" else "_${number + 1}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_$stamp$suffix.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, output.mediaType)
            // One album for both kinds: MediaStore allows videos under Pictures/ as well
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (output.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { client.download(output.downloadPath, it) }
                ?: throw IOException("Could not open the new file")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private suspend fun put(job: ComfyJob) = update { it.withJob(job) }

    private suspend fun update(change: (List<ComfyJob>) -> List<ComfyJob>) = writes.withLock {
        val changed = change(_jobs.value)
        _jobs.value = changed
        store.edit { it[JOBS] = encodeJobs(changed) }
    }

    /** A failure as a sentence for the person holding the phone. */
    private fun describe(t: Throwable, pairing: Boolean = false): String = when {
        t is ComfyApiException && t.status == 401 && !pairing ->
            "The gateway no longer accepts this phone. Pair it again in ComfyUI connection."
        t is ComfyApiException -> t.message ?: "The gateway refused the request"
        t is CertificateException || t.cause is CertificateException || t is SSLException ->
            if (pairing) "The gateway's certificate does not match the pairing code. Check the address and the code."
            else "The computer at that address is not the gateway this phone was paired with."
        t is java.net.SocketTimeoutException -> "The gateway did not answer in time"
        t is IOException -> "The gateway could not be reached. Is it running, and is the phone on the same network?"
        else -> "Something went wrong (${t.javaClass.simpleName})"
    }

    // --- The token, at rest ------------------------------------------------------------------------
    // Encrypted with a key that lives in the Android Keystore and never leaves it; what is in
    // the app's storage is useless without this phone.

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val (iv, sealed) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.doFinal(sealed).decodeToString()
    }.getOrNull()

    companion object {
        private const val TAG = "ComfyController"

        /** The album results are saved into, under Pictures. */
        const val ALBUM = "ComfyUI"

        const val DEFAULT_MAX_UPLOAD = 20L * 1024 * 1024
        private const val POLL_MS = 3_000L
        private const val SLOW_POLL_MS = 15_000L
        private const val MAX_CONVERTED_SIDE = 4096
        private const val KEY_ALIAS = "onegallery_comfy_token"
        private val ACCEPTED_AS_IS = setOf("image/jpeg", "image/png", "image/webp")

        private val ADDRESS = stringPreferencesKey("address")
        private val FINGERPRINT = stringPreferencesKey("fingerprint")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val TOKEN = stringPreferencesKey("token")
        private val JOBS = stringPreferencesKey("jobs")

        @Volatile
        private var instance: ComfyController? = null

        fun get(context: Context): ComfyController =
            instance ?: synchronized(this) {
                instance ?: ComfyController(context.applicationContext).also { instance = it }
            }
    }
}

private val Context.comfyDataStore: DataStore<Preferences> by preferencesDataStore(name = "comfyui")
