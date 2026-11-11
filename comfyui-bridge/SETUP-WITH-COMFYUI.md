# Setting up the OneGallery gateway to start with ComfyUI

For the agent (or person) setting this up on the computer that runs ComfyUI. The owner's wish: **starting ComfyUI starts the gateway too, and the pairing code appears by itself.** Nothing else should have to be launched.

You will:

1. put this folder in place (keeping what an earlier version left in `gateway\data`),
2. clear the old test jobs out of the jobs folder,
3. add three lines to the owner's existing ComfyUI start-up `.bat`,
4. open the firewall for the phone,
5. check it.

Ask the owner before anything that needs administrator rights (step 4) and before changing anything in the ComfyUI folder itself. Show them the edited `.bat` before saving it.

## 1. Put the folder in place

Unzip so that you have `...\comfyui-bridge\gateway\start-with-comfyui.bat`. A short path without spaces is best, e.g. `C:\OneGallery\comfyui-bridge`.

**Upgrading an earlier version?** Keep `gateway\data\` — it holds the certificate and the list of paired phones; with it, a phone paired before stays paired. Delete the old `gateway\.venv\` only if `start-with-comfyui.bat` later fails to start (it is re-made on first run). Stop any gateway still running from the old `start-gateway.bat` window or from a scheduled task: from now on it starts with ComfyUI.

Copy `custom_nodes\ComfyUI-OneGallery` into `<ComfyUI folder>\custom_nodes\` (replace an older copy). The nodes are optional — any workflow works without them — but jobs built with them keep working.

## 2. Clear the old test jobs

Earlier versions shipped four test jobs **in the jobs folder**, and they show at the top of the phone's list. The owner does not want them there. Delete these from the jobs folder (`comfyui-bridge\workflows\`, or wherever `gateway\data\config.json`'s `workflows_dir` points):

- `Echo test.json`, `Echo test.txt`
- `Freestyle test.json`, `Freestyle test.txt`
- `Invert test (stock nodes).json`, `Invert test (stock nodes).txt`
- `Video echo test.json`, `Video echo test.txt`

Delete any other job *you* created for testing. Leave every job the owner made. (The test jobs now live in `examples\test-jobs\`, which the phone never sees.)

## 3. Add the gateway to the ComfyUI start-up script

Find the `.bat` the owner starts ComfyUI with. On a portable install it is often `run_nvidia_gpu.bat`, `run_cpu.bat` or `run_amd_gpu.bat` in `ComfyUI_windows_portable\`; on an AMD card it may be a ZLUDA, DirectML or ROCm launcher of the owner's own. The line that starts ComfyUI looks like one of these:

```bat
.\python_embeded\python.exe -s ComfyUI\main.py --windows-standalone-build
python main.py --directml
```

Work out three things from it:

- **The ComfyUI folder** — the one containing `main.py`, `models\`, `input\` and `output\` (portable: `ComfyUI_windows_portable\ComfyUI`, *not* the portable folder above it).
- **The port** — `--port 8190` on that line means ComfyUI answers at `http://127.0.0.1:8190`. No `--port`: the default, nothing to set.
- **Whether `--listen` or `--output-directory` / `--input-directory` are used** — the gateway still talks to ComfyUI over loopback, which works either way. A moved input folder means uploaded photos are not cleaned up; set `comfy_input_dir` in `gateway\data\config.json` to it if the owner cares.

Insert these lines **before** the line that starts ComfyUI (after any `cd`, `set` or venv activation lines already there):

```bat
rem --- OneGallery gateway: starts with ComfyUI and prints a phone pairing code in this window ---
set "COMFYUI_DIR=C:\ComfyUI_windows_portable\ComfyUI"
call "C:\OneGallery\comfyui-bridge\gateway\start-with-comfyui.bat"
```

with both paths changed to the real ones. Add, only if they apply:

```bat
set "COMFYUI_URL=http://127.0.0.1:8190"
set "ONEGALLERY_JOBS=D:\ComfyUI jobs for the phone"
```

- `COMFYUI_URL` when ComfyUI runs on another port.
- `ONEGALLERY_JOBS` when the owner keeps the jobs elsewhere than `comfyui-bridge\workflows\`.

Leave the rest of the script alone. In particular:

- **Do not** put the lines after the ComfyUI line: ComfyUI does not return until it stops, so they would run only then.
- **Do not** use `start` to open the gateway in its own window: `start-with-comfyui.bat` deliberately runs it in ComfyUI's window, so the code shows where the owner is looking and closing the window stops both.
- If the script ends with `pause`, keep it.

### Python for the gateway

The gateway needs Python 3.10 or newer with `aiohttp`, `cryptography` and `Pillow`. On first start the `.bat` makes `gateway\.venv` with the `python` (or `py`) on PATH and installs them — that takes a minute once, and needs the internet.

No Python on PATH (common with portable ComfyUI)? Either install Python 3.12 from python.org with "Add python.exe to PATH" ticked, or use ComfyUI's own Python — it already has `aiohttp` and `Pillow`:

```bat
C:\ComfyUI_windows_portable\python_embeded\python.exe -m pip install cryptography
```

and add before the `call` line:

```bat
set "ONEGALLERY_PYTHON=C:\ComfyUI_windows_portable\python_embeded\python.exe"
```

Ask the owner before installing anything into ComfyUI's Python.

## 4. Firewall

The phone reaches the gateway on **TCP 8443** (the connection) and **UDP 8443** (so the app can find it by itself). Windows usually asks the first time `python.exe` listens — tick *Private networks*. To be sure, in an **administrator** prompt (ask the owner):

```bat
netsh advfirewall firewall add rule name="OneGallery gateway (TCP)" dir=in action=allow protocol=TCP localport=8443 profile=private
netsh advfirewall firewall add rule name="OneGallery gateway (UDP)" dir=in action=allow protocol=UDP localport=8443 profile=private
```

The home Wi-Fi has to be a *Private* network in Windows for these to apply.

## 5. Check it

Start ComfyUI the usual way. Before ComfyUI's own start-up lines, the window shows:

```text
  OneGallery gateway
  Listening     https://192.168.1.50:8443
  Certificate   3F2A-91C0-...
  ComfyUI       http://127.0.0.1:8188
  Models        C:\ComfyUI_windows_portable\ComfyUI\models
  Jobs folder   C:\OneGallery\comfyui-bridge\workflows
    + Wan i2v   [photo; prompt; 14 settings; LoRAs from any folder]
  Phones        0 paired

  ============================================================
   OneGallery pairing code:   4821
  ============================================================
```

Check that `Models` is not marked `(NOT FOUND)` and that each of the owner's jobs is listed with `prompt`, not `no prompt` (unless the job really has none). Then, in a second window:

```bat
C:\OneGallery\comfyui-bridge\gateway\start-gateway.bat check --comfyui-dir "C:\ComfyUI_windows_portable\ComfyUI"
```

lists every setting found in every job, with its id (for `hide` in an options file — see the README).

On the phone: OneGallery → ⋮ → **ComfyUI connection** → the computer appears under *Find gateway* (if not, type the `Listening` address) → enter the four digits → **Pair**. The code works for 30 minutes; `gateway\pair.bat` makes a new one at any time while ComfyUI is running.

## The owner's jobs

- Workflows go in with **Workflow → Export (API)** — the ordinary Save will not run. The file name is the job's name.
- The prompt, negative prompt, seed, steps, CFG, sampler, scheduler, models and LoRAs are found by themselves; no special nodes are needed.
- LoRAs for a job come from `models\loras`. To limit a job to some folders — the owner keeps LoRAs for different models apart — put `<job name>.options.json` beside it:

  ```json
  { "lora_folders": ["minimax_h3"] }
  ```

  The folder names are those under `models\loras` (sub-folders included).

## If something is wrong

| Seen | Do |
|---|---|
| `[OneGallery] COMFYUI_DIR is not set` | The `set "COMFYUI_DIR=..."` line is missing or after the `call` |
| `There is no models folder in COMFYUI_DIR` | `COMFYUI_DIR` points at the portable folder; point it at `...\ComfyUI` inside it |
| `Could not set up the gateway` | No usable Python: see *Python for the gateway* |
| `port 8443 is already in use` | A gateway is already running (fine), or something else uses 8443: set `"port"` in `gateway\data\config.json` and tell the owner the new address |
| Phone: *No gateway answered* | UDP 8443 blocked, or a guest/isolated Wi-Fi. Type the address instead |
| Phone: *could not be reached* | TCP 8443 blocked, the network is *Public* in Windows, or the phone is on another network |
| Phone: *That code is not valid* | Expired (30 min), used, or five wrong tries: run `gateway\pair.bat` for a new code |
| A job shows `no prompt` but has one | Its prompt is typed into a node the gateway does not recognise as a prompt. Add a **Phone Prompt (OneGallery)** node, wire its output into that node's text input, move the text into it, and export again |
| A job offers the wrong text as its prompt | Same fix: a Phone Prompt node always wins. Or `hide` the wrong one in the job's options file |
