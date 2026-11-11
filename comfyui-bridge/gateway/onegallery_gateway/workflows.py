"""The jobs folder: which workflow files can be run from the phone, and how a job is built.

Pure functions over plain dicts - no network, no ComfyUI - so every rule here is unit-tested.

The folder *is* the list. Drop an API-format workflow in and its file name becomes a job on the
phone; delete it and the job goes. Nothing else has to be edited.
"""

from __future__ import annotations

import copy
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

IMAGE_NODE = "OneGalleryImageInput"
PROMPT_NODE = "OneGalleryPrompt"
OUTPUT_NODE = "OneGallerySendBack"

#: Largest seed handed to ComfyUI. Below 2**53 so it survives a trip through JavaScript.
MAX_SEED = 2**48
SEED_INPUTS = ("seed", "noise_seed")


@dataclass
class Workflow:
    id: str
    name: str
    path: Path
    graph: dict
    description: str = ""
    #: Nodes that receive the phone's photo. Empty: the job takes no photo.
    image_nodes: list[str] = field(default_factory=list)
    #: The node whose text the phone may replace, if the workflow has one.
    prompt_node: str | None = None
    prompt_default: str = ""
    #: Nodes whose results go back to the phone. Empty: everything the workflow saves.
    output_nodes: list[str] = field(default_factory=list)

    @property
    def requires_image(self) -> bool:
        return bool(self.image_nodes)

    def describe(self) -> dict:
        """What the phone is told about this job."""
        described = {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "requires_image": self.requires_image,
            "accepted_mime_types": ["image/jpeg", "image/png", "image/webp"] if self.requires_image else [],
        }
        if self.prompt_node is not None:
            # The phone shows `default` greyed out in the text box; typing replaces it
            described["prompt"] = {"editable": True, "default": self.prompt_default}
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


def _by_number(node_id: str):
    """Node ids are numbers in practice; sort them as numbers so "10" comes after "9"."""
    return (0, int(node_id)) if node_id.isdigit() else (1, node_id)


def analyse(graph: dict) -> tuple[list[str], str | None, str, list[str]]:
    """Finds where the photo goes, where the prompt goes and what comes back.

    The OneGallery nodes are looked for first. A workflow without them still works: the photo
    goes to a Load Image titled "Phone ..." (or, failing that, the first Load Image), the prompt
    to a node titled "Phone Prompt ..." that has a text input, and everything saved comes back.
    """
    ids = sorted(graph, key=_by_number)

    image_nodes = [i for i in ids if graph[i]["class_type"] == IMAGE_NODE]
    if not image_nodes:
        loaders = [i for i in ids if graph[i]["class_type"] == "LoadImage"]
        titled = [i for i in loaders if _title(graph[i]).lower().startswith("phone")]
        image_nodes = titled or loaders[:1]

    prompt_node = next((i for i in ids if graph[i]["class_type"] == PROMPT_NODE), None)
    if prompt_node is None:
        prompt_node = next(
            (
                i
                for i in ids
                if _title(graph[i]).lower().startswith("phone prompt")
                and isinstance(graph[i]["inputs"].get("text"), str)
            ),
            None,
        )
    prompt_default = ""
    if prompt_node is not None:
        text = graph[prompt_node]["inputs"].get("text")
        prompt_default = text if isinstance(text, str) else ""

    output_nodes = [i for i in ids if graph[i]["class_type"] == OUTPUT_NODE]
    return image_nodes, prompt_node, prompt_default, output_nodes


def load_workflows(folder: Path) -> tuple[list[Workflow], list[InvalidWorkflow]]:
    """Every ``*.json`` in ``folder`` (not its sub-folders), as jobs or as reasons they are not."""
    workflows: list[Workflow] = []
    invalid: list[InvalidWorkflow] = []
    taken: set[str] = set()
    if not folder.is_dir():
        return workflows, invalid

    for path in sorted(folder.glob("*.json"), key=lambda p: p.name.lower()):
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

        image_nodes, prompt_node, prompt_default, output_nodes = analyse(data)
        note = path.with_suffix(".txt")
        description = ""
        if note.is_file():
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
                prompt_node=prompt_node,
                prompt_default=prompt_default,
                output_nodes=output_nodes,
            )
        )
    return workflows, invalid


def build_prompt(
    workflow: Workflow,
    image_name: str | None,
    prompt_text: str | None,
    randomize_seeds: bool,
    random_seed=None,
) -> dict:
    """The graph to queue for one job: a copy with the photo, the text and fresh seeds put in.

    Only those three kinds of input are ever touched. The phone cannot send a workflow, or a
    value for any other input.
    """
    graph = copy.deepcopy(workflow.graph)

    if image_name is not None:
        for node_id in workflow.image_nodes:
            graph[node_id]["inputs"]["image"] = image_name

    if workflow.prompt_node is not None and prompt_text is not None and prompt_text.strip():
        graph[workflow.prompt_node]["inputs"]["text"] = prompt_text

    if randomize_seeds:
        # An exported workflow carries the one seed it was exported with. In the editor the seed
        # changes after every run; here it has to be done for it, or every job from the phone
        # would be the same roll of the dice.
        import random

        pick = random_seed or (lambda: random.randrange(MAX_SEED))
        for node in graph.values():
            for name in SEED_INPUTS:
                value = node["inputs"].get(name)
                if isinstance(value, int) and not isinstance(value, bool):
                    node["inputs"][name] = pick()
    return graph


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
