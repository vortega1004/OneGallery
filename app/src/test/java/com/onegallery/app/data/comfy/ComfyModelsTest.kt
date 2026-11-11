package com.onegallery.app.data.comfy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone understands of the gateway, checked against the gateway's real answers. */
class ComfyModelsTest {

    @Test fun `a pairing code is read however it is typed`() {
        val expected = PairingCode("MS8EWTGX", "587BF0CDAA")
        assertEquals(expected, PairingCode.parse("MS8E-WTGX-587BF-0CDAA"))
        assertEquals(expected, PairingCode.parse(" ms8e wtgx 587bf 0cdaa "))
        assertEquals(expected, PairingCode.parse("MS8EWTGX587BF0CDAA"))
    }

    @Test fun `a code of the wrong length or with a non-hex fingerprint half is refused`() {
        assertNull(PairingCode.parse("MS8E-WTGX-587BF"))
        assertNull(PairingCode.parse("MS8E-WTGX-587BF-0CDAAX"))
        assertNull(PairingCode.parse("MS8E-WTGX-587BF-0CDAZ"))
        assertNull(PairingCode.parse(""))
    }

    @Test fun `an address gets https and the usual port when they are left off`() {
        assertEquals("https://192.168.1.50:8443", normaliseGatewayAddress("192.168.1.50"))
        assertEquals("https://192.168.1.50:9000", normaliseGatewayAddress("192.168.1.50:9000"))
        assertEquals("https://192.168.1.50:8443", normaliseGatewayAddress(" https://192.168.1.50:8443/ "))
        assertEquals("https://studio-pc:8443", normaliseGatewayAddress("HTTPS://studio-pc"))
    }

    @Test fun `plain http and nonsense are refused, never upgraded`() {
        assertNull(normaliseGatewayAddress("http://192.168.1.50:8443"))
        assertNull(normaliseGatewayAddress(""))
        assertNull(normaliseGatewayAddress("192.168.1.50/path"))
        assertNull(normaliseGatewayAddress("two words"))
    }

    @Test fun `the job list is read, with and without a prompt`() {
        val list = parseWorkflowList(
            JSONObject(
                """{"max_upload_bytes": 20971520, "comfyui_reachable": true,
                    "workflows": [
                      {"id": "echo-test", "name": "Echo test", "description": "Sends it back", "requires_image": true,
                       "accepted_mime_types": ["image/jpeg"]},
                      {"id": "freestyle", "name": "Freestyle", "description": "", "requires_image": false,
                       "accepted_mime_types": [], "prompt": {"editable": true, "default": "a photo"}}],
                    "invalid": [{"file": "Saved.json", "reason": "Use Export (API)"}]}"""
            )
        )
        assertEquals(20971520L, list.maxUploadBytes)
        assertEquals(listOf("Echo test", "Freestyle"), list.workflows.map { it.name })
        assertFalse(list.workflows[0].takesPrompt)
        assertTrue(list.workflows[0].requiresImage)
        assertEquals("a photo", list.workflows[1].promptDefault)
        assertEquals(listOf("Saved.json" to "Use Export (API)"), list.invalid)
    }

    @Test fun `a prompt with an empty default is still a prompt`() {
        val list = parseWorkflowList(
            JSONObject("""{"workflows": [{"id": "a", "name": "A", "prompt": {"editable": true, "default": ""}}]}""")
        )
        assertEquals("", list.workflows.single().promptDefault)
        assertTrue(list.workflows.single().takesPrompt)
    }

    @Test fun `a finished job carries its results, images and videos`() {
        val job = parseRemoteJob(
            JSONObject(
                """{"job_id": "j1", "status": "completed", "outputs": [
                    {"id": "o0", "media_type": "image/png", "filename": "a.png", "download_path": "/v1/jobs/j1/outputs/o0"},
                    {"id": "o1", "media_type": "video/mp4", "filename": "b.mp4", "download_path": "/v1/jobs/j1/outputs/o1"}]}"""
            )
        )
        assertEquals(ComfyJobStatus.COMPLETED, job.status)
        assertEquals(listOf(false, true), job.outputs.map { it.isVideo })
        assertEquals("/v1/jobs/j1/outputs/o1", job.outputs[1].downloadPath)
    }

    @Test fun `a failed job carries the gateway's sentence`() {
        val job = parseRemoteJob(
            JSONObject("""{"status": "failed", "error": {"code": "workflow_failed", "message": "KSampler: out of memory"}}""")
        )
        assertEquals(ComfyJobStatus.FAILED, job.status)
        assertEquals("KSampler: out of memory", job.error)
        assertTrue(job.outputs.isEmpty())
    }

    @Test fun `a status this version does not know keeps the job alive`() {
        assertEquals(ComfyJobStatus.RUNNING, parseRemoteJob(JSONObject("""{"status": "paused"}""")).status)
    }

    @Test fun `a refusal is read from the gateway's error shape, and survives a body that is not JSON`() {
        val refusal = parseApiError(413, """{"error": {"code": "image_too_large", "message": "The photo is too large to send"}}""")
        assertEquals(Triple(413, "image_too_large", "The photo is too large to send"), Triple(refusal.status, refusal.code, refusal.message))
        val other = parseApiError(502, "<html>Bad gateway</html>")
        assertEquals("http_502", other.code)
    }

    private fun job(id: String, status: ComfyJobStatus, created: Long) =
        ComfyJob(localId = id, workflowId = "w", workflowName = "W", status = status, createdMs = created)

    @Test fun `jobs survive being saved and read back`() {
        val jobs = listOf(
            ComfyJob(
                localId = "a", jobId = "j1", workflowId = "echo", workflowName = "Echo test",
                status = ComfyJobStatus.COMPLETED, createdMs = 5, sourceUri = "content://media/1",
                prompt = "line one\nline \"two\"", savedUris = listOf("content://media/9"), canRetry = false
            ),
            ComfyJob(
                localId = "b", workflowId = "x", workflowName = "X", status = ComfyJobStatus.FAILED,
                createdMs = 3, error = "The gateway could not be reached", canRetry = true
            )
        )
        assertEquals(jobs, decodeJobs(encodeJobs(jobs)))
        assertEquals(emptyList<ComfyJob>(), decodeJobs("not json"))
    }

    @Test fun `a job remembers the upload limit it was started under`() {
        val started = ComfyJob(
            localId = "a", workflowId = "w", workflowName = "W", status = ComfyJobStatus.FAILED,
            createdMs = 1, canRetry = true, maxUploadBytes = 5_000_000
        )
        assertEquals(5_000_000L, decodeJobs(encodeJobs(listOf(started))).single().maxUploadBytes)
        // A job saved before the limit was recorded falls back to the gateway's default
        val old = """[{"local_id":"b","workflow_id":"w","workflow_name":"W","status":"FAILED","created":1}]"""
        assertEquals(DEFAULT_MAX_UPLOAD_BYTES, decodeJobs(old).single().maxUploadBytes)
    }

    @Test fun `a job is replaced by its id, newest first`() {
        val list = listOf(job("a", ComfyJobStatus.QUEUED, 1), job("b", ComfyJobStatus.QUEUED, 2))
        val updated = list.withJob(job("a", ComfyJobStatus.RUNNING, 1))
        assertEquals(listOf("b", "a"), updated.map { it.localId })
        assertEquals(ComfyJobStatus.RUNNING, updated[1].status)
    }

    @Test fun `old finished jobs drop off, running ones never do`() {
        var list = listOf(job("running", ComfyJobStatus.RUNNING, 0))
        for (i in 1..(MAX_REMEMBERED_JOBS + 10)) list = list.withJob(job("done$i", ComfyJobStatus.COMPLETED, i.toLong()))
        assertEquals(MAX_REMEMBERED_JOBS + 1, list.size)
        assertTrue(list.any { it.localId == "running" })
        assertFalse(list.any { it.localId == "done1" })
    }
}
