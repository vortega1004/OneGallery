"""The jobs folder: which workflow files can be run from the phone, and how a job is built.

No network here, so every rule is unit-tested.

The folder *is* the list. Drop an API-format workflow in and its file name becomes a job on the
phone; delete it and the job goes. Nothing else has to be edited: what the phone may change
(prompt, seed, sampler settings, models, LoRAs) is read from the workflow itself - see
``params``. A ``<name>.options.json`` beside it can describe the job, limit its LoRAs to
some folders and hide settings.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import params as P

IMAGE_NODE = "OneGalleryImageInput"
PROMPT_NODE = "OneGalleryPrompt"
OUTPUT_NODE = "OneGallerySendBack"
OPTIONS_SUFFIX = ".options.json"

MAX_SEED = P.MAX_SEED


@dataclass
class Options:
    """``<job name>.options.json`` beside a workflow - everything in it is optional.

    ``description``   one line shown under the job's name on the phone
    ``lora_folders``  sub-folders of ``models/loras`` whose LoRAs this job offers ("wan", "qwen/edit")
    ``hide``          ids of settings the phone should not show (``check`` lists them)
    """

    description: str = ""
    lora_folders: list[str] | None = None
    hide: list[str] = field(default_factory=list)

    @staticmethod
    def read(path: Path) -> "Options":
        try:
            data = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            return Options()
        if not isinstance(data, dict):
            return Options()
        folders = data.get("lora_folders")
        return Options(
            description=str(data.get("description") or "")[:200],
            lora_folders=[str(f) for f in folders] if isinstance(folders, list) else None,
            hide=[h for h in data.get("hide") or [] if isinstance(h, str)],
        )


@dataclass
class Workflow:
    id: str
    name: str
    path: Path
    graph: dict
    description: str = ""
    #: Nodes that receive the phone's photo. Empty: the job takes no photo.
    image_nodes: list[str] = field(default_factory=list)
    #: Nodes whose results go back to the phone. Empty: everything the workflow saves.
    output_nodes: list[str] = field(default_factory=list)
    analysis: P.Analysis = field(default_factory=P.Analysis)
    options: Options = field(default_factory=Options)

    @property
    def requires_image(self) -> bool:
        return bool(self.image_nodes)

    @property
    def params(self) -> list[P.Param]:
        hidden = set(self.options.hide)
        return [p for p in self.analysis.params if p.id not in hidden]

    @property
    def prompt(self) -> P.Param | None:
        return next((p for p in self.params if p.id == "prompt"), None)

    def lora_folders(self) -> list[str] | None:
        """The folders this job's LoRAs come from; None for all of them."""
        if self.options.lora_folders is not None:
            return [f.replace("\\", "/").strip("/") for f in self.options.lora_folders]
        used = self.analysis.lora_folders_used
        # The LoRAs it already loads all sit in sub-folders: offer those folders
        if used and all(used):
            return list(used)
        return None

    def available_loras(self, all_loras: list[str]) -> list[str]:
        folders = self.lora_folders()
        if folders is None:
            return list(all_loras)
        wanted = [f.lower() for f in folders]
        found = []
        for name in all_loras:
            folder = P.lora_folder(name).lower()
            if any(folder == w or (w and folder.startswith(w + "/")) for w in wanted):
                found.append(name)
        return found

    def describe(
        self, models: P.ModelLists | None = None, all_loras: list[str] | None = None, randomize_seeds: bool = True
    ) -> dict:
        """What the phone is told about this job."""
        params = []
        for param in self.params:
            shown = param.describe(models)
            if param.kind == "seed":
                shown["mode"] = "random" if randomize_seeds else "fixed"
            params.append(shown)
        described = {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "requires_image": self.requires_image,
            "accepted_mime_types": ["image/jpeg", "image/png", "image/webp"] if self.requires_image else [],
            "params": params,
        }
        targets = self.analysis.lora_targets
        described["loras"] = {
            "can_add": bool(targets),
            "folders": self.lora_folders(),
            "available": self.available_loras(all_loras or []) if targets else [],
            "targets": [{"id": t.id, "label": t.label} for t in targets],
        }
        prompt = self.prompt
        if prompt is not None:
            # For a phone that predates "params": the workflow's own prompt, shown greyed out
            described["prompt"] = {"editable": True, "default": prompt.default}
        return described


@dataclass
class InvalidWorkflow:
    file: str
    reason: str


def slug(name: str) -> str:
    """A file name as an id: lower case, letters, digits and dashes."""
    cleaned = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return cleaned or "workflow"


def is_editor_format(data) -> bool:
    """True for a workflow saved with ComfyUI's ordinary Save: a graph for the editor to draw,
    which the server cannot run. Jobs have to be exported with Export (API)."""
    return isinstance(data, dict) and isinstance(data.get("nodes"), list) and "links" in data


def is_api_format(data) -> bool:
    if not isinstance(data, dict) or not data:
        return False
    return all(
        isinstance(node, dict) and isinstance(node.get("class_type"), str) and isinstance(node.get("inputs"), dict)
        for node in data.values()
    )


def _title(node: dict) -> str:
    meta = node.get("_meta")
    return (meta.get("title") or "") if isinstance(meta, dict) else ""


_by_number = P.by_number


def find_image_nodes(graph: dict) -> list[str]:
    """Where the photo goes: Phone Image nodes; else a Load Image titled "Phone...", else the
    first Load Image. None at all: the job takes no photo."""
    ids = sorted(graph, key=_by_number)
    image_nodes = [i for i in ids if graph[i]["class_type"] == IMAGE_NODE]
    if not image_nodes:
        loaders = [i for i in ids if graph[i]["class_type"] == "LoadImage"]
        titled = [i for i in loaders if _title(graph[i]).lower().startswith("phone")]
        image_nodes = titled or loaders[:1]
    return image_nodes


def load_workflows(folder: Path, info: P.NodeInfo | None = None) -> tuple[list[Workflow], list[InvalidWorkflow]]:
    """Every ``*.json`` in ``folder`` (not its sub-folders), as jobs or as reasons they are not."""
    workflows: list[Workflow] = []
    invalid: list[InvalidWorkflow] = []
    taken: set[str] = set()
    if not folder.is_dir():
        return workflows, invalid

    for path in sorted(folder.glob("*.json"), key=lambda p: p.name.lower()):
        if path.name.lower().endswith(OPTIONS_SUFFIX):
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError) as error:
            invalid.append(InvalidWorkflow(path.name, f"Not readable as JSON ({type(error).__name__})"))
            continue
        if is_editor_format(data):
            invalid.append(
                InvalidWorkflow(
                    path.name,
                    "Saved in the editor's own format. In ComfyUI use Workflow > Export (API) and put that file here.",
                )
            )
            continue
        if not is_api_format(data):
            invalid.append(InvalidWorkflow(path.name, "Not a ComfyUI API-format workflow"))
            continue

        base = slug(path.stem)
        workflow_id, n = base, 2
        while workflow_id in taken:
            workflow_id, n = f"{base}-{n}", n + 1
        taken.add(workflow_id)

        image_nodes = find_image_nodes(data)
        output_nodes = [i for i in sorted(data, key=_by_number) if data[i]["class_type"] == OUTPUT_NODE]
        options = Options.read(path.with_name(path.stem + OPTIONS_SUFFIX))
        description = options.description
        note = path.with_suffix(".txt")
        if not description and note.is_file():
            try:
                description = note.read_text(encoding="utf-8-sig").strip().splitlines()[0][:200]
            except (OSError, IndexError):
                description = ""
        workflows.append(
            Workflow(
                id=workflow_id,
                name=path.stem,
                path=path,
                graph=data,
                description=description,
                image_nodes=image_nodes,
                output_nodes=output_nodes,
                analysis=P.analyse(data, info, skip_nodes=image_nodes + output_nodes),
                options=options,
            )
        )
    return workflows, invalid


def build_prompt(
    workflow: Workflow,
    image_name: str | None,
    values: dict | None = None,
    loras: list | None = None,
    randomize_seeds: bool = True,
    random_seed=None,
) -> tuple[dict, dict]:
    """The graph to queue for one job: a copy with the photo, the phone's settings and its
    LoRAs put in. ``values`` must already have been checked (``params.clean_values``).

    Returns (graph, used); ``used["seed"]`` is the seed it ran with. The phone cannot send a
    workflow, or a value for anything the job does not offer as a setting.
    """
    graph, used = P.apply(
        workflow.graph,
        workflow.params,
        values or {},
        loras or [],
        workflow.analysis.lora_targets,
        randomize_unset_seed=randomize_seeds,
        pick_seed=random_seed,
    )
    if image_name is not None:
        for node_id in workflow.image_nodes:
            graph[node_id]["inputs"]["image"] = image_name
    return graph, used


_MEDIA_TYPES = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".mp4": "video/mp4",
    ".webm": "video/webm",
    ".mov": "video/quicktime",
    ".mkv": "video/x-matroska",
}


def media_type_of(filename: str) -> str | None:
    """The type the phone is told, or None for a file it could not show (audio, text, latents)."""
    return _MEDIA_TYPES.get(Path(filename).suffix.lower())


def collect_outputs(history_outputs: dict, output_nodes: list[str]) -> list[dict]:
    """The files of a finished job that go back to the phone.

    ``history_outputs`` is ComfyUI's ``history[prompt_id]["outputs"]``: per node, lists of
    ``{"filename", "subfolder", "type"}`` under keys that vary by node ("images", "gifs", ...).
    Send to Phone nodes decide when there are any; otherwise everything saved is returned, and
    previews (type "temp") only if nothing was saved at all.
    """
    chosen = [n for n in output_nodes if n in history_outputs] or sorted(history_outputs, key=_by_number)

    found: list[dict] = []
    seen: set[tuple] = set()
    for node_id in chosen:
        node_output = history_outputs.get(node_id) or {}
        # A Send to Phone node lists everything under "onegallery" and repeats its images under
        # "images" for the editor's preview; read the full list only
        keys = ["onegallery"] if "onegallery" in node_output else list(node_output)
        for key in keys:
            items = node_output.get(key)
            if not isinstance(items, list):
                continue
            for item in items:
                if not isinstance(item, dict) or not isinstance(item.get("filename"), str):
                    continue
                media_type = media_type_of(item["filename"])
                if media_type is None:
                    continue
                entry = (item["filename"], item.get("subfolder") or "", item.get("type") or "output")
                if entry in seen:
                    continue
                seen.add(entry)
                found.append(
                    {"filename": entry[0], "subfolder": entry[1], "type": entry[2], "media_type": media_type}
                )

    kept = [f for f in found if f["type"] != "temp"] or found
    for number, item in enumerate(kept):
        item["id"] = f"o{number}"
    return kept
