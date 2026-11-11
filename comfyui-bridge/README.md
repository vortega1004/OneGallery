# OneGallery ↔ ComfyUI bridge

Run ComfyUI workflows from the OneGallery app on a phone: pick a photo, pick a job, tune its prompt, seed, sampler settings and LoRAs, get the result back.

```text
OneGallery (phone) ──HTTPS, paired──> gateway (this folder, port 8443) ──loopback──> ComfyUI (127.0.0.1:8188)
```

The phone only ever talks to the gateway. ComfyUI stays on loopback; nothing here needs it opened to the network.

| Folder | What it is |
|---|---|
| `gateway/` | The gateway: one small Python program, and `start-with-comfyui.bat` to start it with ComfyUI |
| `custom_nodes/ComfyUI-OneGallery/` | Three optional ComfyUI nodes: Phone Image, Phone Prompt, Send to Phone |
| `workflows/` | **The jobs folder.** Every API-format `.json` here is a job on the phone, named after the file. Ships empty |
| `examples/test-jobs/` | Four tiny jobs for checking the gateway works. Not on the phone unless copied into `workflows/` |
| `templates/` | Starting points to open in ComfyUI's editor |

**Setting it up on the ComfyUI computer:** [SETUP-WITH-COMFYUI.md](SETUP-WITH-COMFYUI.md) — written for whoever (or whichever agent) edits the ComfyUI start-up script.

## How it starts

`gateway\start-with-comfyui.bat` is called from the script that starts ComfyUI, with `COMFYUI_DIR` set to the ComfyUI folder:

```bat
set "COMFYUI_DIR=C:\ComfyUI_windows_portable\ComfyUI"
call "C:\OneGallery\comfyui-bridge\gateway\start-with-comfyui.bat"
```

It starts the gateway **in the same window** and returns at once, so ComfyUI starts right after. The first time, it makes `gateway\.venv` and installs `aiohttp`, `cryptography` and `Pillow` into it. Closing the window stops both. A second start (ComfyUI restarted from its own menu, the script run twice) sees the port taken and leaves quietly.

| Variable | |
|---|---|
| `COMFYUI_DIR` | **Required.** The folder holding `models\`, `input\`, `output\`. Model and LoRA lists are read from its `models` folder; photos the gateway uploaded are deleted from its `input` folder after their job |
| `COMFYUI_URL` | Where ComfyUI answers, if not `http://127.0.0.1:8188` (ComfyUI started with another `--port`) |
| `ONEGALLERY_JOBS` | The jobs folder, if not `workflows\` here |
| `ONEGALLERY_PYTHON` | A Python 3.10+ that already has the three packages, instead of the `.venv` |

`start-gateway.bat` (or `sh start-gateway.sh`) still runs the gateway on its own, in the foreground; pass `--comfyui-dir`, `--comfy-url`, `--workflows`, `--port` as needed.

## Pairing a phone

When the gateway starts it prints, in the ComfyUI window:

```text
  ============================================================
   OneGallery pairing code:   4821
  ============================================================
```

In OneGallery: ⋮ → ComfyUI connection. The app looks for the gateway on the network by itself (**Find gateway**); if nothing answers, type the `Listening` address from the same window. Enter the four digits, **Pair**.

The code works for one phone, for 30 minutes (`pairing_minutes`). Five wrong tries close pairing until the gateway is started again or `gateway\pair.bat` is run — that also makes a fresh code whenever one is needed. Phones stay paired across restarts.

Other commands (`start-gateway.bat <command>`): `pair`, `devices` (list paired phones), `revoke <id>` (un-pair one), `check` (look over ComfyUI, the models folder and every job, listing the settings found in each, then exit).

## Adding a job

1. Build and test the workflow in ComfyUI as usual.
2. **Workflow → Export (API)** and save the file into `workflows\`. The file name is the job's name on the phone.
3. That's all. No restart: the folder is read each time the phone asks. No special nodes are needed.

The ordinary **Save** writes the editor's format, which ComfyUI's server cannot run. Such a file is not offered; `check` and the gateway's start-up listing say why.

### What the phone can change

The gateway reads the workflow and offers, on the phone, everything below that it finds. Each shows the workflow's own value greyed out; only what is changed is sent.

| Found | How |
|---|---|
| **Prompt** and **negative prompt** | Text that ends up in conditioning, followed through links: a prompt typed into a String node, passed through a switch or a prompt enhancer (Qwen Image 2.1, MiniMax H3) is found, and every place holding that same text is changed together. Hosted API nodes' prompts too. Positive or negative is read from where it ends up. A prompt enhancer's own instructions ("system", "task" in the title) are not offered |
| **Seed** | Every `seed`/`noise_seed` — one value for all of them. Random by default (`randomize_seeds`) or fixed from the phone. The seed a job ran with is shown on the phone, so a good result can be made again |
| **Sampler settings** | By input name: steps, CFG, sampler, scheduler, denoise, guidance, shift, width, height, length, frames, FPS, duration, megapixels, aspect ratio, batch size. Equal values under one name are one setting (Wan 2.2's two samplers have one Steps); different values are shown separately, named after their node |
| **Knobs** | Int, Float and Boolean primitive nodes, named by their title — the templates put "Enable Lightning LoRA" and "duration" there. A value that only counts when a switch is one way says so: "Steps (Enable Lightning LoRA on)" |
| **Models** | Every input that picks a model file (checkpoint, diffusion model, VAE, text encoders…), with the files in `COMFYUI_DIR\models` and whatever ComfyUI itself lists |
| **LoRAs in the workflow** | Each LoRA loader's file and strength |
| **Added LoRAs** | Up to eight, each with a strength, put straight after the model loader (both model and CLIP for a checkpoint). With two models — Wan 2.2's high and low noise — each LoRA can go on one or both |
| **The photo** | Phone Image nodes; else a Load Image titled "Phone…"; else the first Load Image. None: the job takes no photo |
| **What comes back** | Send to Phone nodes; else every image and video the workflow saved |

Types, ranges and choices come from ComfyUI's own node definitions (`/object_info`), kept in `gateway\data\object_info.json` for when ComfyUI is not up. Everything the phone sends is checked against this list: a value for anything else, a number out of range, a choice not offered or a LoRA file not on offer is refused. The phone cannot send a workflow.

Tested against the official templates (Comfy-Org `workflow_templates`, 645 of them exported with Export (API)): MiniMax H3 (t2i, i2v, t2v, image edit), Qwen Image 2.1 (t2i, image edit), Wan 2.1/2.2, Flux, SDXL, HiDream, Ernie and hosted API nodes. The ones with no prompt found are those that have none (upscalers, depth, 3D, audio).

### `<job name>.options.json` — optional, beside the workflow

```json
{
  "description": "MiniMax H3 photo to video, 5 s",
  "lora_folders": ["minimax"],
  "hide": ["batch_size", "megapixels_2"]
}
```

| Key | |
|---|---|
| `description` | One line under the job's name on the phone (a `<job name>.txt` does the same) |
| `lora_folders` | Sub-folders of `models\loras` whose LoRAs this job offers, sub-folders included. `[""]` is the top folder only. Without it: the folders of the LoRAs the workflow already loads, when they are all in sub-folders; otherwise every LoRA |
| `hide` | Ids of settings not to show. `check` lists each job's ids |

## Settings (`gateway/data/config.json`)

| Key | Default | Meaning |
|---|---|---|
| `comfy_url` | `http://127.0.0.1:8188` | Where ComfyUI is (`COMFYUI_URL` overrides it) |
| `comfyui_dir` | `null` | The ComfyUI folder (`COMFYUI_DIR` overrides it) |
| `host`, `port` | `0.0.0.0`, `8443` | Where the gateway listens. Discovery uses the same port number, UDP |
| `workflows_dir` | `../../workflows` | The jobs folder: relative to `gateway/data/`, or absolute (`ONEGALLERY_JOBS` overrides it) |
| `pair_on_start` | `true` | Print a fresh pairing code at every start |
| `pairing_minutes` | `30` | How long a code works |
| `discovery` | `true` | Answer phones looking for the gateway |
| `max_upload_bytes` | 20 MB | Largest photo accepted |
| `max_image_side` | 8192 | Largest photo side, in pixels |
| `max_active_jobs` | 4 | Jobs waiting or running at once, all phones together |
| `randomize_seeds` | `true` | A new seed per job unless the phone fixes it |
| `job_retention_hours` | 72 | How long finished jobs are remembered |
| `comfy_input_dir` | `null` | ComfyUI's `input` folder, if not `comfyui_dir\input` |

## API (version 1, as built)

All routes need `Authorization: Bearer <token>` except `/v1/pair`. Errors are `{"error":{"code","message"}}`.

| Route | Purpose |
|---|---|
| UDP `ONEGALLERY_DISCOVER 1` to port 8443 | Answer `{"service":"onegallery-gateway","name","port","pairing"}` |
| `POST /v1/pair` `{"code","device_name"}` | Four-digit code → `{"device_id","token","fingerprint"}` |
| `GET /v1/workflows` | `{"max_upload_bytes","comfyui_reachable","workflows":[…],"invalid":[{"file","reason"}]}` |
| `POST /v1/jobs` multipart: `workflow_id`, `image`?, `params`? (JSON), `loras`? (JSON), `prompt`? + `Idempotency-Key` | `202 {"job_id","status"}` |
| `GET /v1/jobs` | This phone's last 50 jobs |
| `GET /v1/jobs/{id}` | `{"job_id","workflow_id","workflow_name","status","created","seed"?,"outputs"?,"error"?}` |
| `GET /v1/jobs/{id}/outputs/{output_id}` | The file, with its `Content-Type` |

A workflow entry: `{"id","name","description","requires_image","accepted_mime_types","params":[…],"loras":{…},"prompt"?}`.

- A param: `{"id","kind","label","group","default","min"?,"max"?,"step"?,"choices"?,"multiline"?,"mode"?}`. `kind`: `text`, `int`, `float`, `choice`, `bool`, `seed`. `group`: `prompt`, `settings`, `models`, `loras`. `mode` (seed only): `random` or `fixed`, what happens when the phone does not say.
- `loras`: `{"can_add","folders","available":[file names as ComfyUI names them],"targets":[{"id","label"}]}`.
- `prompt` (`{"editable":true,"default"}`) is kept for phones from before `params`, and a bare `prompt` multipart field is still accepted.

`params` sent: `{"<param id>": value, "seed": {"mode": "random"} | {"mode": "fixed", "value": 123}}` — only what was changed. `loras` sent: `[{"name","strength","target"?}]`; no `target` means every model.

Status codes: 400 bad request / photo missing / `invalid_settings`, 401 not paired / `pairing_failed`, 404 unknown job/workflow/output, 409 idempotency key reused with a different payload, 413 too large, 415 not JPEG/PNG/WebP, 422 unreadable or oversized photo / `workflow_invalid` (ComfyUI refused it), 429 too many jobs waiting, 503 ComfyUI not answering.

## Security, for the reviewer

- **Transport.** TLS 1.2+ only; there is no plain-HTTP listener. The certificate is self-signed (ECDSA P-256), made on first run, kept in `data/`.
- **Pairing.** A four-digit code, printed at start-up: one phone, 30 minutes, five wrong tries close pairing, each wrong try answered after 1.5 s. A guesser on the network has 5 in 10,000, once per start. The phone pins the certificate it sees while pairing (trust on first use) and accepts no other afterwards; the fingerprint is printed in the ComfyUI window and shown on the phone for comparison. The earlier 18-character code bound the certificate into the code itself; four digits cannot, and that is the trade made for typing four digits.
- **Discovery.** Answers a fixed question on UDP 8443 with the computer's name, the port and whether pairing is open. Nothing else; it never pairs.
- **Tokens.** 256-bit random, one per phone, stored only as SHA-256 hashes in `data/devices.json`. Never logged.
- **Reach.** A phone can list jobs, start one with the settings the job offers, and read its *own* jobs and their outputs (another phone's job is a 404). There is no pass-through to ComfyUI's `/view`, `/prompt` or anything else. Settings are checked by type and range; choices and LoRA names must be on the list the gateway made.
- **Uploads.** Size-capped while streaming; decoded with Pillow, must be JPEG/PNG/WebP within the side limit; re-encoded to PNG (drops EXIF/location, applies orientation); stored in ComfyUI's input folder as `onegallery_<job id>.png` and deleted after the job when the ComfyUI folder is known.
- **Errors.** ComfyUI failures are reduced to one line (node type + first line of the message). No stack traces leave the machine.

Things a reviewer may want to push on:
- Trust on first use: a machine in the middle *at the moment of pairing* would be pinned instead of the gateway. Comparing the fingerprint closes that; nothing forces it.
- The model and LoRA lists are sent to the paired phone — every file name under `models`.
- `data/gateway-key.pem` and `devices.json` are plain files protected only by the account's file permissions.
- No rate limit beyond `max_active_jobs` and the pairing limits.
- State is JSON files rewritten whole on each change: fine for one household, not for volume.

## Tested

On Windows, Python 3.14, ComfyUI 0.45.0 in CPU mode, 2026-11-11:

- `python -m unittest discover -s tests` in `gateway/`: 50 tests — the jobs folder, finding settings in nine official templates (`tests/fixtures`), building jobs, LoRAs, refusals, pairing.
- Started through `start-with-comfyui.bat` from a stand-in ComfyUI launcher: first-run `.venv` set-up, gateway up on TCP and UDP 8443.
- `python tests/live_check.py <address> <code> <photo> http://127.0.0.1:8188` — plays the phone end to end, then reads back from ComfyUI what was queued. All passed: found by discovery; paired with four digits; code not reusable; Echo test ran and its result downloaded; MiniMax H3 i2v, Qwen Image 2.1 t2i and Wan 2.2 i2v sent with a typed prompt, a fixed seed, CFG/steps/denoise and an added LoRA, and ComfyUI received exactly those, and accepted each job as built (the test computer has placeholder model files, so they stopped at loading the model); the seed came back; out-of-range values, unknown settings and LoRAs not on offer refused.
- `python tests/concurrency_check.py <address> <code> <photo A> <photo B>` (needs Pillow; run against `examples/test-jobs`): two photos at the same instant never swap.

**Not tested:** a real generation with real models (no GPU here); UDP discovery across a real Wi-Fi network (on one computer only); macOS/Linux; the `filenames` input of Send to Phone with a real Video Helper Suite install.
