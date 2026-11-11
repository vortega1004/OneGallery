# OneGallery ↔ ComfyUI bridge

Run ComfyUI workflows from the OneGallery app on a phone: pick a photo, pick a job, get the result back.

```text
OneGallery (phone) ──HTTPS, paired──> gateway (this folder, port 8443) ──loopback──> ComfyUI (127.0.0.1:8188)
```

The phone only ever talks to the gateway. ComfyUI stays on loopback; nothing here needs it opened to the network.

| Folder | What it is |
|---|---|
| `gateway/` | The gateway: one small Python program |
| `custom_nodes/ComfyUI-OneGallery/` | Three ComfyUI nodes: Phone Image, Phone Prompt, Send to Phone |
| `workflows/` | **The jobs folder.** Every API-format `.json` here is a job on the phone, named after the file |
| `templates/` | Starting points to open in ComfyUI's editor and fill in |

## Set up (on the computer that runs ComfyUI)

1. **Nodes.** Copy `custom_nodes/ComfyUI-OneGallery` into `ComfyUI/custom_nodes/` and restart ComfyUI. No extra packages.
2. **Gateway.** Python 3.10 or newer. In `gateway/`:
   - Windows: `start-gateway.bat`
   - macOS/Linux: `sh start-gateway.sh`

   The first run creates `.venv`, installs `aiohttp`, `cryptography` and `Pillow`, makes a certificate, and writes `gateway/data/config.json`. It prints the addresses it is listening on and the jobs it found.
3. **Firewall.** Allow inbound TCP 8443 from the local network (Windows asks the first time).
4. **Pair the phone.** With the gateway running, in a second window: `start-gateway.bat pair`. It prints an address and a pairing code. In OneGallery: Settings → ComfyUI connection → enter both. The code works once, for ten minutes.

Other commands: `devices` (list paired phones), `revoke <id>` (un-pair one), `check` (look over the jobs folder and ComfyUI, then exit).

## Adding a job

1. Open a file from `templates/` in ComfyUI (or any workflow of your own).
2. Build between the OneGallery nodes and test with Queue. Phone Image behaves like Load Image.
3. **Workflow → Export (API)** and save into `workflows/`. The file name is the job's name on the phone.
4. Optional: a `.txt` with the same name; its first line is the job's description.

The ordinary **Save** writes a different format that ComfyUI's server cannot run. Such a file in the folder is not offered as a job; `check` and the gateway's start-up listing say why.

No restart is needed: the folder is read each time the phone asks.

### What the gateway does with a workflow

| It looks for | And does | Without it |
|---|---|---|
| **Phone Image** node(s) | Puts the phone's photo there | Uses a Load Image titled "Phone…", else the first Load Image. No image node at all: the job takes no photo |
| **Phone Prompt** node | Replaces its text with what was typed on the phone. Its own text is the default, shown greyed out on the phone | Uses a node titled "Phone Prompt…" that has a `text` input. None: no text box on the phone |
| **Send to Phone** node(s) | Sends back only what reaches them | Sends back every image/video the workflow saved |
| Inputs named `seed` / `noise_seed` | Gives each a new random value per job (`randomize_seeds` in config) | — |

Nothing else in a workflow is ever changed, and the phone cannot send a workflow or any other value.

**Send to Phone** takes any of: `images` (IMAGE, saved as PNG), `video` (VIDEO from ComfyUI's Create Video, saved as MP4), `filenames` (the Filenames output of Video Helper Suite's Video Combine).

## Settings (`gateway/data/config.json`)

| Key | Default | Meaning |
|---|---|---|
| `comfy_url` | `http://127.0.0.1:8188` | Where ComfyUI is |
| `host`, `port` | `0.0.0.0`, `8443` | Where the gateway listens |
| `workflows_dir` | `../../workflows` | The jobs folder: relative to `gateway/data/`, or an absolute path. The default is the `workflows` folder beside `gateway` |
| `max_upload_bytes` | 20 MB | Largest photo accepted |
| `max_image_side` | 8192 | Largest photo side, in pixels |
| `max_active_jobs` | 4 | Jobs waiting or running at once, all phones together |
| `randomize_seeds` | `true` | New seed per job |
| `job_retention_hours` | 72 | How long finished jobs are remembered |
| `comfy_input_dir` | `null` | Set to ComfyUI's `input` folder to have uploaded photos deleted after their job |

## API (version 1, as built)

All routes need `Authorization: Bearer <token>` except `/v1/pair`. Errors are `{"error":{"code","message"}}`.

| Route | Purpose |
|---|---|
| `POST /v1/pair` `{"code","device_name"}` | One-time secret → `{"device_id","token","fingerprint"}` |
| `GET /v1/workflows` | `{"max_upload_bytes","comfyui_reachable","workflows":[…],"invalid":[{"file","reason"}]}` |
| `POST /v1/jobs` multipart: `workflow_id`, `image`?, `prompt`? + `Idempotency-Key` | `202 {"job_id","status"}` |
| `GET /v1/jobs` | This phone's last 50 jobs |
| `GET /v1/jobs/{id}` | `{"job_id","workflow_id","workflow_name","status","created","outputs"?,"error"?}` |
| `GET /v1/jobs/{id}/outputs/{output_id}` | The file, with its `Content-Type` |

A workflow entry: `{"id","name","description","requires_image","accepted_mime_types","prompt"?:{"editable":true,"default":"…"}}`.
An output entry: `{"id","media_type","filename","download_path"}`. Statuses: `queued`, `running`, `completed`, `failed`.

Status codes: 400 bad request / photo missing, 401 not paired, 404 unknown job/workflow/output, 409 idempotency key reused with a different payload, 413 too large, 415 not JPEG/PNG/WebP, 422 unreadable or oversized photo, 429 too many jobs waiting, 503 ComfyUI not answering.

### Where this differs from `ComfyUI_Android_Gallery_Handoff.md`

| The handoff said | Built | Why |
|---|---|---|
| A fixed server-side manifest of workflows | The jobs folder; file name = job | The owner's request |
| No user-adjustable parameters in v1 | One optional `prompt` text part, only for workflows that have a prompt node | The owner's request |
| Results are images | Images and videos (`media_type` says which) | The owner mostly makes videos |
| Pairing/TLS method to be agreed | Self-signed certificate, pinned by the phone at pairing (below) | Needs no certificate authority and no trust-all |
| Reuse of an idempotency key with another payload: "reject" | `409 idempotency_conflict` | Made concrete |
| — | `GET /v1/jobs`, `comfyui_reachable`, `invalid`, `filename`, `workflow_name` | So the phone can resync and explain a missing job |

## Security, for the reviewer

- **Transport.** TLS 1.2+ only; there is no plain-HTTP listener. The certificate is self-signed (ECDSA P-256), made on first run, kept in `data/`.
- **Pairing.** The code is `SSSS-SSSS-FFFFF-FFFFF`: an 8-character one-time secret (10 minutes, one use, 5 wrong guesses and it is void) and the first 10 hex digits of the certificate's SHA-256. The phone checks the certificate it is shown against the second half **before** sending the first, then stores the full fingerprint and accepts no other certificate afterwards. A machine in the middle would need a certificate matching 40 bits of fingerprint.
- **Tokens.** 256-bit random, one per phone, stored only as SHA-256 hashes in `data/devices.json`. Never logged.
- **Reach.** A phone can list jobs, start one, and read its *own* jobs and their outputs (another phone's job is a 404). There is no pass-through to ComfyUI's `/view`, `/prompt` or anything else.
- **Uploads.** Size-capped while streaming; decoded with Pillow, must be JPEG/PNG/WebP within the side limit; re-encoded to PNG (drops EXIF/location, applies orientation); stored in ComfyUI's input folder as `onegallery_<job id>.png`.
- **Errors.** ComfyUI failures are reduced to one line (node type + first line of the message). No stack traces leave the machine.

Things a reviewer may want to push on:
- The pin replaces CA validation and hostname checking by design. That is the trade for not needing a real domain. Changing the certificate (deleting `data/gateway-cert.pem`) un-pairs every phone.
- `data/gateway-key.pem` and `devices.json` are plain files protected only by the account's file permissions.
- Uploaded photos stay in ComfyUI's input folder unless `comfy_input_dir` is set.
- No rate limit beyond `max_active_jobs` and the pairing-attempt limit.
- State is JSON files rewritten whole on each change: fine for one household, not for volume.

## Tested

On Windows, Python 3.14, ComfyUI 0.45.0 in CPU mode, 2026-11-10:

- `python -m unittest discover -s tests` in `gateway/`: 25 tests (folder rules, building a job, outputs, pairing).
- `python tests/live_check.py <address> <code> <photo>`: plays the phone end to end. All checks passed: unpaired refused; pair and pin; code not reusable; four starter jobs ran and their results downloaded (three PNG, one MP4); idempotent retry returned the same job; key reuse with another photo refused; missing photo, non-picture, unknown job, wrong token all refused.
- A workflow naming a node that is not installed failed with `Node 'NoSuchNode' not found…`; an editor-format file was listed as invalid with the reason.
- The three templates open in ComfyUI's editor, and Export (API) of each produces what the gateway expects.

**Not tested:** the `filenames` input of Send to Phone with a real Video Helper Suite install; long jobs and a gateway restart mid-job (the resume code exists, it has not been exercised); macOS/Linux; a GPU; more than one phone at once; the real OneGallery app (it is being built against this).
