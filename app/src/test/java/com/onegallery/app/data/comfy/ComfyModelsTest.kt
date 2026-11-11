package com.onegallery.app.data.comfy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone understands of the gateway, checked against the gateway's real answers. */
class ComfyModelsTest {

    @Test fun `a pairing code is four digits, however it is typed`() {
        assertEquals("0427", PairingCode.parse("0427"))
        assertEquals("0427", PairingCode.parse(" 04 27 "))
        assertEquals("0427", PairingCode.parse("04-27"))
    }

    @Test fun `anything else is not a pairing code`() {
        assertNull(PairingCode.parse("427"))
        assertNull(PairingCode.parse("04271"))
        assertNull(PairingCode.parse("04a7"))
        assertNull(PairingCode.parse("٠٤٢٧")) // digits of another script are not what the gateway printed
        assertNull(PairingCode.parse(""))
    }

    @Test fun `a gateway's discovery answer becomes an address to pair with`() {
        val found = parseDiscoveryAnswer("""{"service":"onegallery-gateway","name":"STUDIO","port":8443,"pairing":true}""", "192.168.3.77")
        assertEquals(FoundGateway("STUDIO", "https://192.168.3.77:8443", true), found)
        assertNull(parseDiscoveryAnswer("""{"service":"something-else"}""", "192.168.3.77"))
        assertNull(parseDiscoveryAnswer("not json", "192.168.3.77"))
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

    /** What the gateway sends for Qwen Image 2.1, trimmed to a few settings of each kind. */
    private val qwen = parseWorkflowList(
        JSONObject(
            """{"workflows": [{"id": "qwen", "name": "Qwen 2.1", "requires_image": false,
                "prompt": {"editable": true, "default": "a lighthouse"},
                "params": [
                  {"id": "prompt", "kind": "text", "label": "Prompt", "group": "prompt", "default": "a lighthouse", "multiline": true},
                  {"id": "negative", "kind": "text", "label": "Negative prompt", "group": "prompt", "default": ""},
                  {"id": "seed", "kind": "seed", "label": "Seed", "group": "settings", "default": 447606998181262, "min": 0, "mode": "random"},
                  {"id": "steps", "kind": "int", "label": "Steps", "group": "settings", "default": 25, "min": 1, "max": 10000},
                  {"id": "cfg", "kind": "float", "label": "CFG", "group": "settings", "default": 1, "min": 0.0, "max": 100.0, "step": 0.1},
                  {"id": "sampler_name", "kind": "choice", "label": "Sampler", "group": "settings", "default": "euler", "choices": ["euler", "res_multistep"]},
                  {"id": "enable_lightning", "kind": "bool", "label": "Enable Lightning LoRA", "group": "settings", "default": false},
                  {"id": "unet_name", "kind": "choice", "label": "Diffusion model", "group": "models", "default": "qwen.safetensors", "choices": ["qwen.safetensors"]},
                  {"id": "future", "kind": "colour", "label": "Something new", "group": "settings", "default": "#fff"}],
                "loras": {"can_add": true, "folders": ["qwen"], "available": ["qwen\\sketch.safetensors"],
                          "targets": [{"id": "m1", "label": "qwen_image_2.1"}]}}]}"""
        )
    ).workflows.single()

    @Test fun `settings are read with their kinds, defaults and ranges`() {
        assertEquals(listOf("prompt", "negative", "seed", "steps", "cfg", "sampler_name", "enable_lightning", "unet_name"), qwen.params.map { it.id })
        assertEquals("a lighthouse", qwen.promptDefault)
        val steps = qwen.params.first { it.id == "steps" }
        assertEquals(Triple(ComfyParamKind.INT, 25L, 1.0), Triple(steps.kind, steps.default, steps.min))
        assertEquals("1", qwen.params.first { it.id == "cfg" }.defaultText)
        assertEquals(447606998181262L, qwen.params.first { it.id == "seed" }.default)
        assertTrue(qwen.params.first { it.id == "seed" }.randomByDefault)
        assertEquals(listOf("euler", "res_multistep"), qwen.params.first { it.id == "sampler_name" }.choices)
        assertEquals(ComfyLoraOptions(true, listOf("qwen"), listOf("qwen\\sketch.safetensors"), listOf("m1" to "qwen_image_2.1")), qwen.loras)
    }

    @Test fun `only what was changed is sent, as the gateway wants it`() {
        val settings = ComfySettings(
            values = mapOf("prompt" to "a red bicycle", "negative" to "  ", "steps" to "30", "cfg" to "2,5",
                "sampler_name" to "res_multistep", "enable_lightning" to "true", "seed" to "77"),
            seedFixed = true,
            loras = listOf(ComfyLoraChoice("qwen\\sketch.safetensors", 0.6, "m1"))
        )
        val (params, loras) = settings.toRequest(qwen.params).getOrThrow()
        assertEquals("a red bicycle", params.getString("prompt"))
        assertFalse(params.has("negative")) // blank: the workflow's own
        assertEquals(30L, params.getLong("steps"))
        assertEquals(2.5, params.getDouble("cfg"), 0.0)
        assertEquals("res_multistep", params.getString("sampler_name"))
        assertTrue(params.getBoolean("enable_lightning"))
        assertEquals("fixed", params.getJSONObject("seed").getString("mode"))
        assertEquals(77L, params.getJSONObject("seed").getLong("value"))
        assertFalse(params.has("unet_name"))
        assertEquals("qwen\\sketch.safetensors", loras.getJSONObject(0).getString("name"))
        assertEquals("m1", loras.getJSONObject(0).getString("target"))
    }

    @Test fun `nothing changed sends nothing, and random is said only when chosen`() {
        assertEquals(0, ComfySettings().toRequest(qwen.params).getOrThrow().first.length())
        val random = ComfySettings(seedFixed = false).toRequest(qwen.params).getOrThrow().first
        assertEquals("random", random.getJSONObject("seed").getString("mode"))
    }

    @Test fun `numbers that are not, or out of range, are named before anything is sent`() {
        val failed = ComfySettings(values = mapOf("steps" to "0", "cfg" to "lots"), seedFixed = true)
            .toRequest(qwen.params.map { if (it.id == "seed") it.copy(default = null) else it })
        val message = failed.exceptionOrNull()!!.message!!
        assertTrue(message, "Steps has to be at least 1" in message)
        assertTrue(message, "CFG has to be a number" in message)
        assertTrue(message, "seed" in message)
        assertTrue(ComfySettings(values = mapOf("steps" to "2.5")).toRequest(qwen.params).isFailure)
    }

    @Test fun `settings are kept per workflow and survive being saved`() {
        val settings = ComfySettings(mapOf("cfg" to "3.5", "prompt" to "line one\n\"two\""), true, listOf(ComfyLoraChoice("a/b.safetensors", -0.5, null)))
        assertEquals(settings, decodeSettings(encodeSettings(settings)))
        assertEquals(ComfySettings(), decodeSettings(null))
        assertEquals(ComfySettings(), decodeSettings("not json"))
    }

    @Test fun `a lora is shown by its file name, its folder beneath`() {
        assertEquals("wan/high", loraFolder("wan\\high\\orbit cam.safetensors"))
        assertEquals("orbit cam", loraTitle("wan\\high\\orbit cam.safetensors"))
        assertEquals("", loraFolder("top.safetensors"))
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
        assertNull(job.seed)
        assertEquals(42L, parseRemoteJob(JSONObject("""{"status": "running", "seed": 42}""")).seed)
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
                prompt = "line one\nline \"two\"", savedUris = listOf("content://media/9"), canRetry = false,
                paramsJson = """{"prompt":"x","seed":{"mode":"random"}}""", lorasJson = "[]", seed = 123456789
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
