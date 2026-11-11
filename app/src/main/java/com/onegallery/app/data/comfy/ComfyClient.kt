package com.onegallery.app.data.comfy

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * HTTPS to the gateway, trusting exactly one certificate.
 *
 * The gateway's certificate is self-signed — there is no authority that could vouch for a
 * computer on a home network — so the usual chain check has nothing to check against. Instead
 * the phone remembers the certificate's SHA-256 fingerprint from pairing ([pin]) and refuses
 * any other. This is stricter than the usual check, not looser: no certificate authority in
 * the world can issue something this will accept.
 *
 * During pairing there is no pin yet. Then [pairingPrefix] — the half of the pairing code that
 * came from the certificate — must match the start of the fingerprint before anything is sent.
 *
 * Blocking. Call from a background dispatcher.
 */
class ComfyClient(
    private val address: String,
    private val pin: String?,
    private val token: String?,
    private val pairingPrefix: String? = null
) {
    /** The fingerprint of the certificate the gateway last presented. */
    @Volatile
    var seenFingerprint: String? = null
        private set

    private val trust = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val presented = chain?.firstOrNull() ?: throw CertificateException("No certificate presented")
            val fingerprint = fingerprintOf(presented.encoded)
            when {
                pin != null && !fingerprint.equals(pin, ignoreCase = true) ->
                    throw CertificateException("This is not the gateway the phone was paired with")
                pin == null && pairingPrefix == null ->
                    throw CertificateException("Not paired")
                pin == null && !fingerprint.startsWith(pairingPrefix!!, ignoreCase = true) ->
                    throw CertificateException("The gateway's certificate does not match the pairing code")
            }
            seenFingerprint = fingerprint
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sockets = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory

    private fun open(path: String, method: String, readTimeoutMs: Int = 30_000): HttpsURLConnection {
        val connection = URL(address + path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = sockets
        // The name in the certificate is not what identifies the gateway (it is reached by a
        // bare address that may change); its fingerprint is. So the "host name" check is: is
        // this session's certificate the pinned one.
        connection.setHostnameVerifier { _, session ->
            val presented = session.peerCertificates.firstOrNull()?.encoded
            val expected = pin ?: seenFingerprint
            presented != null && expected != null && fingerprintOf(presented).equals(expected, ignoreCase = true)
        }
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    private fun HttpsURLConnection.jsonOrThrow(): JSONObject {
        try {
            val status = responseCode
            val body = (if (status in 200..299) inputStream else errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (status !in 200..299) throw parseApiError(status, body)
            return JSONObject(body)
        } finally {
            disconnect()
        }
    }

    /** Trades the one-time secret for this phone's token. Returns (device id, token, fingerprint). */
    fun pair(secret: String, deviceName: String): Triple<String, String, String> {
        val connection = open("/v1/pair", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use {
            it.write(JSONObject().put("code", secret).put("device_name", deviceName).toString().toByteArray())
        }
        val answer = connection.jsonOrThrow()
        val presented = seenFingerprint ?: throw CertificateException("No certificate seen")
        // What the gateway says about itself must be what it showed
        if (!answer.getString("fingerprint").equals(presented, ignoreCase = true)) {
            throw CertificateException("The gateway reported a different certificate from the one it presented")
        }
        return Triple(answer.getString("device_id"), answer.getString("token"), presented)
    }

    fun workflows(): ComfyWorkflowList = parseWorkflowList(open("/v1/workflows", "GET").jsonOrThrow())

    fun job(jobId: String): ComfyRemoteJob = parseRemoteJob(open("/v1/jobs/$jobId", "GET").jsonOrThrow())

    /**
     * Starts a job. [image] is copied to the connection in chunks; the caller hands over bytes
     * it already holds (the photo, at most the gateway's upload limit — see
     * `ComfyController.photoToSend`). [idempotencyKey] is what makes a retry safe: the same key
     * gets the same job back. Returns the gateway's job id.
     */
    fun submit(
        workflowId: String,
        prompt: String?,
        image: InputStream?,
        imageName: String,
        idempotencyKey: String
    ): String {
        val boundary = "onegallery-" + UUID.randomUUID()
        val connection = open("/v1/jobs", "POST", readTimeoutMs = 120_000)
        connection.doOutput = true
        connection.setChunkedStreamingMode(64 * 1024)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Idempotency-Key", idempotencyKey)
        connection.outputStream.use { out ->
            out.field(boundary, "workflow_id", workflowId)
            if (!prompt.isNullOrBlank()) out.field(boundary, "prompt", prompt)
            if (image != null) {
                val safeName = imageName.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "photo" }
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"$safeName\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
                )
                image.copyTo(out, 64 * 1024)
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary--\r\n".toByteArray())
        }
        return connection.jsonOrThrow().getString("job_id")
    }

    /** Copies one result into [into]. [downloadPath] is the path the gateway gave for it. */
    fun download(downloadPath: String, into: OutputStream) {
        val connection = open(downloadPath, "GET", readTimeoutMs = 120_000)
        connection.setRequestProperty("Accept", "*/*")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw parseApiError(status, connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty())
            }
            connection.inputStream.use { it.copyTo(into, 256 * 1024) }
        } finally {
            connection.disconnect()
        }
    }

    private fun OutputStream.field(boundary: String, name: String, value: String) {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        write(value.toByteArray())
        write("\r\n".toByteArray())
    }

    companion object {
        /** SHA-256 of a certificate, as upper-case hex — the form the gateway prints. */
        fun fingerprintOf(encodedCertificate: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(encodedCertificate)
                .joinToString("") { byte -> HEX[(byte.toInt() shr 4) and 0xF].toString() + HEX[byte.toInt() and 0xF] }

        private const val HEX = "0123456789ABCDEF"
    }
}
