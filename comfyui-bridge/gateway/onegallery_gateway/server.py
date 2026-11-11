"""The gateway itself: the small API the phone uses, and the jobs it runs on ComfyUI.

The phone never reaches ComfyUI. It can list the jobs in the folder, start one with a photo,
its settings (the ones each job offers - see ``params``) and LoRAs, ask how a job is doing, and
fetch that job's results. Everything else ComfyUI can do stays on this computer.
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import json
import logging
import os
import socket
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path

import aiohttp
from aiohttp import web

from . import params as P
from . import workflows as wf
from .security import Config, Devices, read_json, write_json

log = logging.getLogger("onegallery.gateway")

ACTIVE = ("queued", "running")
MAX_SETTINGS_BYTES = 256 * 1024
DEVICE_KEY = web.AppKey("device", object)
DISCOVERY_QUESTION = b"ONEGALLERY_DISCOVER 1"
#: How long ComfyUI's node definitions and the model lists are trusted before being read again
CATALOGUE_SECONDS = 60


class ApiError(Exception):
    """A refusal the phone can show: an HTTP status, a stable code, a safe sentence."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def error_response(status: int, code: str, message: str) -> web.Response:
    return web.json_response({"error": {"code": code, "message": message}}, status=status)


@dataclass
class Job:
    job_id: str
    device_id: str
    workflow_id: str
    workflow_name: str
    status: str = "queued"
    created: float = 0.0
    updated: float = 0.0
    prompt_id: str | None = None
    error: dict | None = None
    #: {"id", "media_type", "filename", "subfolder", "type"} - the last three never leave here
    outputs: list[dict] = field(default_factory=list)
    idempotency_key: str | None = None
    payload_hash: str | None = None
    input_name: str | None = None
    #: The seed it ran with, so a result worth keeping can be made again
    seed: int | None = None

    def public(self) -> dict:
        shown = {
            "job_id": self.job_id,
            "workflow_id": self.workflow_id,
            "workflow_name": self.workflow_name,
            "status": self.status,
            "created": int(self.created),
        }
        if self.status == "completed":
            shown["outputs"] = [
                {
                    "id": o["id"],
                    "media_type": o["media_type"],
                    "filename": o["filename"],
                    "download_path": f"/v1/jobs/{self.job_id}/outputs/{o['id']}",
                }
                for o in self.outputs
            ]
        if self.error:
            shown["error"] = self.error
        if self.seed is not None:
            shown["seed"] = self.seed
        return shown


class JobStore:
    """Jobs, kept in ``jobs.json`` so that a restart forgets nothing a phone is waiting for."""

    def __init__(self, path: Path):
        self.path = path
        self.jobs: dict[str, Job] = {}
        for stored in read_json(path, []):
            try:
                job = Job(**stored)
            except TypeError:
                continue
            self.jobs[job.job_id] = job

    def save(self) -> None:
        write_json(self.path, [asdict(j) for j in self.jobs.values()])

    def put(self, job: Job) -> None:
        job.updated = time.time()
        self.jobs[job.job_id] = job
        self.save()

    def by_key(self, device_id: str, key: str) -> Job | None:
        return next(
            (j for j in self.jobs.values() if j.device_id == device_id and j.idempotency_key == key), None
        )

    def active_count(self) -> int:
        return sum(1 for j in self.jobs.values() if j.status in ACTIVE)

    def forget_older_than(self, seconds: float) -> None:
        cutoff = time.time() - seconds
        stale = [i for i, j in self.jobs.items() if j.status not in ACTIVE and j.updated < cutoff]
        for job_id in stale:
            del self.jobs[job_id]
        if stale:
            self.save()


class Comfy:
    """The handful of ComfyUI routes the gateway uses, on loopback."""

    def __init__(self, base_url: str, session: aiohttp.ClientSession):
        self.base = base_url.rstrip("/")
        self.session = session
        self.client_id = uuid.uuid4().hex

    async def reachable(self) -> bool:
        try:
            async with self.session.get(f"{self.base}/system_stats", timeout=aiohttp.ClientTimeout(total=3)) as r:
                return r.status == 200
        except (aiohttp.ClientError, asyncio.TimeoutError):
            return False

    async def upload_image(self, data: bytes, filename: str) -> str:
        form = aiohttp.FormData()
        form.add_field("image", data, filename=filename, content_type="image/png")
        form.add_field("overwrite", "true")
        form.add_field("type", "input")
        async with self.session.post(f"{self.base}/upload/image", data=form) as response:
            response.raise_for_status()
            body = await response.json()
        subfolder = body.get("subfolder") or ""
        return f"{subfolder}/{body['name']}" if subfolder else body["name"]

    async def queue(self, graph: dict) -> str:
        """Queues a graph; returns ComfyUI's prompt id. Raises ApiError if ComfyUI refuses it."""
        async with self.session.post(f"{self.base}/prompt", json={"prompt": graph, "client_id": self.client_id}) as r:
            body = await r.json(content_type=None)
            if r.status != 200:
                raise ApiError(422, "workflow_invalid", _refusal(body))
            return body["prompt_id"]

    async def object_info(self) -> dict:
        async with self.session.get(f"{self.base}/object_info", timeout=aiohttp.ClientTimeout(total=30)) as r:
            r.raise_for_status()
            return await r.json(content_type=None)

    async def history(self, prompt_id: str) -> dict | None:
        async with self.session.get(f"{self.base}/history/{prompt_id}") as response:
            response.raise_for_status()
            return (await response.json()).get(prompt_id)

    async def is_running(self, prompt_id: str) -> bool:
        async with self.session.get(f"{self.base}/queue") as response:
            response.raise_for_status()
            body = await response.json()
        return any(len(item) > 1 and item[1] == prompt_id for item in body.get("queue_running", []))

    def view_url(self, output: dict) -> tuple[str, dict]:
        return f"{self.base}/view", {
            "filename": output["filename"],
            "subfolder": output["subfolder"],
            "type": output["type"],
        }


def _refusal(body) -> str:
    """One sentence from ComfyUI's validation answer: which node, what was wrong."""
    try:
        for node_id, problem in (body.get("node_errors") or {}).items():
            first = (problem.get("errors") or [{}])[0]
            detail = first.get("details") or first.get("message") or "invalid input"
            return f"{problem.get('class_type', 'Node')} ({node_id}): {detail}"[:300]
        return str((body.get("error") or {}).get("message") or "ComfyUI refused the workflow")[:300]
    except AttributeError:
        return "ComfyUI refused the workflow"


def _failure(entry: dict) -> str:
    """One sentence from a failed run: the node that stopped it and why. No stack trace."""
    for kind, detail in (entry.get("status") or {}).get("messages", []):
        if kind == "execution_error" and isinstance(detail, dict):
            node = detail.get("node_type") or "A node"
            reason = str(detail.get("exception_message") or "failed").strip().splitlines()[0]
            return f"{node}: {reason}"[:300]
        if kind == "execution_interrupted":
            return "The job was cancelled on the computer"
    return "Generation failed"


#: ComfyUI's loader nodes whose choices are the files of a models sub-folder
CATALOGUE_SOURCES = {
    "loras": ("LoraLoaderModelOnly", "lora_name"),
    "checkpoints": ("CheckpointLoaderSimple", "ckpt_name"),
    "diffusion_models": ("UNETLoader", "unet_name"),
    "vae": ("VAELoader", "vae_name"),
    "text_encoders": ("CLIPLoader", "clip_name"),
    "controlnet": ("ControlNetLoader", "control_net_name"),
    "upscale_models": ("UpscaleModelLoader", "model_name"),
}


def scan_models(models_dir: Path | None, limit: int = 20000) -> dict[str, list[str]]:
    """Model files under ComfyUI's ``models`` folder, by sub-folder, named the way ComfyUI
    names them (relative path, this system's separator)."""
    found: dict[str, list[str]] = {}
    if models_dir is None or not models_dir.is_dir():
        return found
    folders = {f for names in P.MODEL_FOLDERS.values() for f in names}
    count = 0
    for folder in sorted(folders):
        root = models_dir / folder
        if not root.is_dir():
            continue
        names: list[str] = []
        for directory, subdirs, files in os.walk(root, followlinks=True):
            subdirs.sort()
            for file in sorted(files):
                if file.lower().endswith(P.MODEL_EXTENSIONS):
                    names.append(os.path.relpath(os.path.join(directory, file), root))
                    count += 1
                    if count >= limit:
                        break
        found[folder] = names
    return found


def catalogue(scanned: dict[str, list[str]], info: P.NodeInfo) -> P.ModelLists:
    """What ComfyUI itself lists (it knows extra model paths) plus what is on disk."""
    by_folder = {folder: list(names) for folder, names in scanned.items()}
    for folder, (class_type, name) in CATALOGUE_SOURCES.items():
        listed = by_folder.setdefault(folder, [])
        for choice in info.choices(class_type, name) or []:
            if isinstance(choice, str) and choice not in listed:
                listed.append(choice)
    by_folder.setdefault("unet", by_folder.get("diffusion_models", []))
    by_folder.setdefault("clip", by_folder.get("text_encoders", []))
    return P.ModelLists(by_folder)


def prepare_image(data: bytes, max_side: int) -> bytes:
    """Checks that ``data`` really is a picture the gateway accepts and re-saves it as PNG.

    Re-saving drops whatever else was in the file (location, camera, embedded thumbnails) and
    turns the picture the right way up, so ComfyUI gets pixels and nothing more.
    """
    from PIL import Image, ImageOps, UnidentifiedImageError

    try:
        with Image.open(io.BytesIO(data)) as probe:
            if probe.format not in ("JPEG", "PNG", "WEBP"):
                raise ApiError(415, "unsupported_media_type", "Only JPEG, PNG and WebP photos can be sent")
            probe.verify()
        with Image.open(io.BytesIO(data)) as image:
            image = ImageOps.exif_transpose(image)
            if max(image.size) > max_side:
                raise ApiError(422, "image_too_large", f"The photo is larger than {max_side} pixels on a side")
            if image.mode not in ("RGB", "RGBA", "L"):
                image = image.convert("RGBA" if "A" in image.getbands() else "RGB")
            out = io.BytesIO()
            image.save(out, format="PNG", compress_level=3)
            return out.getvalue()
    except ApiError:
        raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise ApiError(422, "image_unreadable", "That file could not be read as a photo")


class Gateway:
    def __init__(self, config: Config, data_dir: Path, certificate_fingerprint: str):
        self.config = config
        self.data_dir = data_dir
        self.fingerprint = certificate_fingerprint
        self.workflows_dir = config.workflows_path(data_dir)
        self.jobs = JobStore(data_dir / "jobs.json")
        self.comfy: Comfy | None = None
        self._tasks: set[asyncio.Task] = set()
        self._info = P.NodeInfo(read_json(data_dir / "object_info.json", {}))
        self._info_read = 0.0
        self._models = P.ModelLists()
        self._models_read = 0.0
        self._discovery = None

    # --- lifecycle -------------------------------------------------------------------------------

    def app(self) -> web.Application:
        app = web.Application(
            middlewares=[self._errors, self._authenticate],
            client_max_size=self.config.max_upload_bytes + 64 * 1024,
        )
        app.router.add_post("/v1/pair", self.pair)
        app.router.add_get("/v1/workflows", self.list_workflows)
        app.router.add_post("/v1/jobs", self.create_job)
        app.router.add_get("/v1/jobs", self.list_jobs)
        app.router.add_get("/v1/jobs/{job_id}", self.get_job)
        app.router.add_get("/v1/jobs/{job_id}/outputs/{output_id}", self.get_output)
        app.on_startup.append(self._start)
        app.on_cleanup.append(self._stop)
        return app

    async def _start(self, app: web.Application) -> None:
        session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120))
        self.comfy = Comfy(self.config.comfy_url, session)
        self.jobs.forget_older_than(self.config.job_retention_hours * 3600)
        if self.config.discovery:
            await self._start_discovery()
        # Jobs that were in flight when the gateway last stopped
        for job in list(self.jobs.jobs.values()):
            if job.status in ACTIVE:
                if job.prompt_id:
                    self._spawn(self._watch(job))
                else:
                    self._fail(job, "gateway_restarted", "The gateway restarted before this job reached ComfyUI")

    async def _stop(self, app: web.Application) -> None:
        for task in self._tasks:
            task.cancel()
        if self._discovery is not None:
            self._discovery.close()
        if self.comfy:
            await self.comfy.session.close()

    async def _start_discovery(self) -> None:
        """Answers a phone that asks, by UDP broadcast, whether a gateway is on this network."""
        gateway = self

        class Answer(asyncio.DatagramProtocol):
            def connection_made(self, transport):
                self.transport = transport

            def datagram_received(self, data, address):
                if data.strip() != DISCOVERY_QUESTION:
                    return
                reply = {
                    "service": "onegallery-gateway",
                    "name": socket.gethostname(),
                    "port": gateway.config.port,
                    "pairing": Devices.open(gateway.data_dir).pairing_open(),
                }
                self.transport.sendto(json.dumps(reply).encode(), address)

        try:
            transport, _ = await asyncio.get_running_loop().create_datagram_endpoint(
                Answer, local_addr=("0.0.0.0", self.config.port), allow_broadcast=True
            )
            self._discovery = transport
        except OSError as error:
            log.warning("Phones cannot find this gateway by themselves (UDP %s: %s)", self.config.port, error)

    async def node_info(self) -> P.NodeInfo:
        """ComfyUI's node definitions: fresh when ComfyUI answers, the last copy when it does not."""
        if time.time() - self._info_read < CATALOGUE_SECONDS:
            return self._info
        self._info_read = time.time()
        try:
            data = await self.comfy.object_info()
            if isinstance(data, dict) and data:
                self._info = P.NodeInfo(data)
                await asyncio.get_running_loop().run_in_executor(None, write_json, self.data_dir / "object_info.json", data)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
            pass
        return self._info

    async def models(self) -> P.ModelLists:
        info = await self.node_info()
        if time.time() - self._models_read >= CATALOGUE_SECONDS:
            scanned = await asyncio.get_running_loop().run_in_executor(None, scan_models, self.config.models_dir())
            self._models = catalogue(scanned, info)
            self._models_read = time.time()
        return self._models

    async def load_workflows(self):
        info = await self.node_info()
        return await asyncio.get_running_loop().run_in_executor(None, wf.load_workflows, self.workflows_dir, info)

    def _spawn(self, coroutine) -> None:
        task = asyncio.ensure_future(coroutine)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    # --- middleware ------------------------------------------------------------------------------

    @web.middleware
    async def _errors(self, request: web.Request, handler):
        try:
            return await handler(request)
        except ApiError as error:
            return error_response(error.status, error.code, error.message)
        except web.HTTPRequestEntityTooLarge:
            return error_response(413, "image_too_large", "The photo is too large to send")
        except web.HTTPException as error:
            return error_response(error.status, "http_error", error.reason or "Request failed")
        except (aiohttp.ClientError, asyncio.TimeoutError):
            return error_response(503, "comfyui_unavailable", "ComfyUI is not answering on this computer")
        except Exception:  # the phone must never be shown a stack trace
            log.exception("Unhandled error on %s %s", request.method, request.path)
            return error_response(500, "internal_error", "The gateway hit an unexpected problem")

    @web.middleware
    async def _authenticate(self, request: web.Request, handler):
        if request.path == "/v1/pair":
            return await handler(request)
        header = request.headers.get("Authorization", "")
        device = None
        if header.startswith("Bearer "):
            device = Devices.open(self.data_dir).authenticate(header[7:].strip())
        if device is None:
            raise ApiError(401, "unauthorized", "This phone is not paired with the gateway")
        request[DEVICE_KEY] = device
        return await handler(request)

    # --- routes ----------------------------------------------------------------------------------

    async def pair(self, request: web.Request) -> web.Response:
        try:
            body = await request.json()
            secret, name = str(body["code"]), str(body.get("device_name") or "")
        except (ValueError, KeyError, TypeError):
            raise ApiError(400, "bad_request", "Send the pairing code as JSON")
        result = Devices.open(self.data_dir).finish_pairing(secret, name)
        if result is None:
            # One answer for wrong, expired and used-up, so nothing can be learned by guessing;
            # and not a quick one, so guessing is slow as well as limited
            log.warning("A phone at %s tried a wrong or expired pairing code", request.remote)
            await asyncio.sleep(1.5)
            raise ApiError(
                401, "pairing_failed",
                "That code is not valid. Check the code in the ComfyUI window, or restart ComfyUI for a new one.",
            )
        device, token = result
        log.info("Paired device %s (%s)", device.id, device.name)
        return web.json_response({"device_id": device.id, "token": token, "fingerprint": self.fingerprint})

    async def list_workflows(self, request: web.Request) -> web.Response:
        found, invalid = await self.load_workflows()
        models = await self.models()
        loras = models.files(("loras",))
        return web.json_response(
            {
                "max_upload_bytes": self.config.max_upload_bytes,
                "comfyui_reachable": await self.comfy.reachable(),
                "workflows": [w.describe(models, loras, self.config.randomize_seeds) for w in found],
                # Files in the folder that cannot be run, and why - so a job that is missing
                # from the list on the phone is not a mystery
                "invalid": [asdict(i) for i in invalid],
            }
        )

    async def list_jobs(self, request: web.Request) -> web.Response:
        device = request[DEVICE_KEY]
        mine = sorted(
            (j for j in self.jobs.jobs.values() if j.device_id == device.id),
            key=lambda j: j.created,
            reverse=True,
        )
        return web.json_response({"jobs": [j.public() for j in mine[:50]]})

    async def get_job(self, request: web.Request) -> web.Response:
        return web.json_response(self._job_for(request).public())

    async def get_output(self, request: web.Request) -> web.StreamResponse:
        job = self._job_for(request)
        output = next((o for o in job.outputs if o["id"] == request.match_info["output_id"]), None)
        if job.status != "completed" or output is None:
            raise ApiError(404, "not_found", "That result does not exist")
        url, params = self.comfy.view_url(output)
        async with self.comfy.session.get(url, params=params, timeout=aiohttp.ClientTimeout(total=None)) as source:
            if source.status != 200:
                raise ApiError(404, "not_found", "ComfyUI no longer has that file")
            response = web.StreamResponse(headers={"Content-Type": output["media_type"]})
            if source.content_length is not None:
                response.content_length = source.content_length
            await response.prepare(request)
            async for chunk in source.content.iter_chunked(256 * 1024):
                await response.write(chunk)
            await response.write_eof()
            return response

    async def create_job(self, request: web.Request) -> web.Response:
        device = request[DEVICE_KEY]
        if not request.content_type.startswith("multipart/"):
            raise ApiError(400, "bad_request", "Send the job as multipart/form-data")

        workflow_id, prompt_text, image = "", None, None
        settings_text, loras_text = "", ""
        async for part in await request.multipart():
            if part.name == "workflow_id":
                workflow_id = (await part.text()).strip()
            elif part.name == "prompt":
                prompt_text = (await part.text())[: P.MAX_TEXT_CHARS]
            elif part.name in ("params", "loras"):
                text = (await part.read(decode=False))[: MAX_SETTINGS_BYTES + 1]
                if len(text) > MAX_SETTINGS_BYTES:
                    raise ApiError(413, "too_large", "The settings are too long")
                if part.name == "params":
                    settings_text = text.decode("utf-8", "replace")
                else:
                    loras_text = text.decode("utf-8", "replace")
            elif part.name == "image":
                chunks, size = [], 0
                while chunk := await part.read_chunk(256 * 1024):
                    size += len(chunk)
                    if size > self.config.max_upload_bytes:
                        raise ApiError(413, "image_too_large", "The photo is too large to send")
                    chunks.append(chunk)
                image = b"".join(chunks)

        found, _ = await self.load_workflows()
        workflow = next((w for w in found if w.id == workflow_id), None)
        if workflow is None:
            raise ApiError(404, "unknown_workflow", "That job is not in the folder any more")
        if workflow.requires_image and not image:
            raise ApiError(400, "image_required", "This job needs a photo")

        models = await self.models()
        try:
            sent = json.loads(settings_text) if settings_text.strip() else {}
            if prompt_text is not None and prompt_text.strip() and isinstance(sent, dict) and workflow.prompt:
                sent.setdefault("prompt", prompt_text)  # a phone from before "params"
            values = P.clean_values(workflow.params, sent, models)
            loras = P.clean_loras(
                json.loads(loras_text) if loras_text.strip() else [],
                workflow.available_loras(models.files(("loras",))),
                workflow.analysis.lora_targets,
            )
        except ValueError as error:  # P.InvalidValue, and JSON that is not JSON
            message = str(error) if isinstance(error, P.InvalidValue) else "The settings are not valid JSON"
            raise ApiError(400, "invalid_settings", message)

        # Same key, same job: a phone that never heard the answer can simply ask again
        key = request.headers.get("Idempotency-Key") or None
        digest = hashlib.sha256()
        digest.update(workflow_id.encode())
        digest.update(b"\0" + json.dumps(values, sort_keys=True).encode())
        digest.update(b"\0" + json.dumps([vars(l) for l in loras], sort_keys=True).encode())
        digest.update(b"\0" + (image or b""))
        payload_hash = digest.hexdigest()
        if key:
            earlier = self.jobs.by_key(device.id, key)
            if earlier is not None:
                if earlier.payload_hash != payload_hash:
                    raise ApiError(409, "idempotency_conflict", "That key was already used for a different job")
                return web.json_response({"job_id": earlier.job_id, "status": earlier.status}, status=202)

        if self.jobs.active_count() >= self.config.max_active_jobs:
            raise ApiError(429, "busy", "Too many jobs are waiting. Try again when one finishes.")

        prepared = None
        if workflow.requires_image:
            prepared = await asyncio.get_running_loop().run_in_executor(
                None, prepare_image, image, self.config.max_image_side
            )

        now = time.time()
        job = Job(
            job_id=str(uuid.uuid4()),
            device_id=device.id,
            workflow_id=workflow.id,
            workflow_name=workflow.name,
            created=now,
            idempotency_key=key,
            payload_hash=payload_hash,
        )
        self.jobs.put(job)
        self._spawn(self._run(job, workflow, prepared, values, loras))
        return web.json_response({"job_id": job.job_id, "status": job.status}, status=202)

    def _job_for(self, request: web.Request) -> Job:
        job = self.jobs.jobs.get(request.match_info["job_id"])
        # Another phone's job is "not found", not "forbidden": its existence is not this one's business
        if job is None or job.device_id != request[DEVICE_KEY].id:
            raise ApiError(404, "not_found", "That job does not exist")
        return job

    # --- running a job ---------------------------------------------------------------------------

    def _fail(self, job: Job, code: str, message: str) -> None:
        job.status, job.error = "failed", {"code": code, "message": message}
        self.jobs.put(job)
        log.info("Job %s failed: %s", job.job_id, message)

    async def _run(self, job: Job, workflow: wf.Workflow, image: bytes | None, values: dict, loras: list) -> None:
        try:
            image_name = None
            if image is not None:
                # One file per job, named for the job: two phones sending at once cannot collide
                image_name = await self.comfy.upload_image(image, f"onegallery_{job.job_id}.png")
                job.input_name = image_name
            graph, used = wf.build_prompt(workflow, image_name, values, loras, self.config.randomize_seeds)
            job.seed = used.get("seed")
            job.prompt_id = await self.comfy.queue(graph)
            self.jobs.put(job)
            log.info("Job %s queued as %s (%s)", job.job_id, job.prompt_id, workflow.name)
        except ApiError as error:
            self._fail(job, error.code, error.message)
            return
        except (aiohttp.ClientError, asyncio.TimeoutError):
            self._fail(job, "comfyui_unavailable", "ComfyUI is not answering on this computer")
            return
        except Exception:
            log.exception("Job %s could not be started", job.job_id)
            self._fail(job, "internal_error", "The job could not be started")
            return
        await self._watch(job)

    async def _watch(self, job: Job) -> None:
        """Follows a queued job until ComfyUI has finished it, however long that takes."""
        found, _ = await self.load_workflows()
        workflow = next((w for w in found if w.id == job.workflow_id), None)
        output_nodes = workflow.output_nodes if workflow else []
        missing_since = None
        while True:
            await asyncio.sleep(1.0)
            try:
                entry = await self.comfy.history(job.prompt_id)
                if entry is None:
                    running = await self.comfy.is_running(job.prompt_id)
                    if running and job.status != "running":
                        job.status = "running"
                        self.jobs.put(job)
                    continue
                missing_since = None
            except (aiohttp.ClientError, asyncio.TimeoutError):
                # ComfyUI restarting, or busy enough not to answer. Give it ten minutes.
                missing_since = missing_since or time.time()
                if time.time() - missing_since > 600:
                    self._fail(job, "comfyui_unavailable", "ComfyUI stopped answering while the job was running")
                    return
                await asyncio.sleep(4.0)
                continue

            status = entry.get("status") or {}
            if status.get("status_str") == "error":
                self._fail(job, "workflow_failed", _failure(entry))
            else:
                job.outputs = wf.collect_outputs(entry.get("outputs") or {}, output_nodes)
                if job.outputs:
                    job.status = "completed"
                    self.jobs.put(job)
                    log.info("Job %s completed with %d result(s)", job.job_id, len(job.outputs))
                else:
                    self._fail(job, "no_results", "The workflow finished but saved nothing to send back")
            self._remove_input(job)
            return

    def _remove_input(self, job: Job) -> None:
        folder = self.config.input_dir()
        if folder is None or not job.input_name or not job.input_name.startswith("onegallery_"):
            return
        try:
            (folder / job.input_name).unlink(missing_ok=True)
        except OSError:
            pass
