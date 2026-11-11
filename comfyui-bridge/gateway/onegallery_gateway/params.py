"""Which settings of a workflow the phone may change, found by reading the workflow itself.

Nothing has to be marked up for this. A workflow exported with Export (API) is a flat list of
nodes, each with its inputs, and an input is either a value or a link to another node's output.
That is enough to find, in any workflow:

* **the prompt(s)** - text that ends up in conditioning. Followed through links, so a prompt
  typed into a string node, passed through a switch or a prompt enhancer and only then into the
  text encoder is still found, and every place holding that same text is changed together. That
  is how one prompt box inside a subgraph appears in an export: several inputs, one text.
* **positive or negative** - from the input the conditioning finally arrives at.
* **the seed** - every ``seed``/``noise_seed``; one value for all of them, random or fixed.
* **sampler settings** - steps, CFG, sampler, scheduler, denoise, size, length, frame rate...,
  by input name. Equal values under the same name are one setting (a two-stage Wan workflow has
  one Steps, not two); different values are shown separately, named after their node.
* **knobs** - Int, Float and Boolean primitive nodes. Templates put their switches there
  ("Enable Lightning LoRA", "duration").
* **models** - every input that picks a model file, and the LoRA loaders already present.

ComfyUI's own node definitions (``/object_info``) give each input's type, range and choices
when the gateway can read them; without them the names and values above still work.

Pure functions over plain dicts: no network, no files.
"""

from __future__ import annotations

import copy
import random
import re
from collections import deque
from dataclasses import dataclass, field

MAX_SEED = 2**48
#: Largest seed a phone may type. Below 2**53 so it survives JSON in any language.
MAX_TYPED_SEED = 2**53 - 1
MAX_TEXT_CHARS = 12000

MODEL_EXTENSIONS = (".safetensors", ".ckpt", ".pt", ".pth", ".bin", ".gguf", ".sft", ".onnx")

#: Inputs that hold a prompt when ComfyUI's definitions are not to hand.
TEXT_INPUTS = {
    "text", "prompt", "prompt_text", "positive", "positive_prompt", "negative", "negative_prompt",
    "text_g", "text_l", "t5xxl", "clip_l", "string",
}
#: Text inputs that are never a prompt.
NOT_TEXT_INPUTS = {
    "expression", "filename_prefix", "format", "codec", "image", "video", "audio", "upload",
    "find", "replace", "regex_pattern", "pattern", "delimiter", "separator",
}
NOT_TEXT_CLASSES = {"ComfyMathExpression", "MathExpression", "Note", "MarkdownNote", "PreviewAny"}

SEED_INPUTS = {"seed", "noise_seed"}

#: Settings found by name: input -> (label, kind). Kind is a fallback; definitions win.
NAMED_SETTINGS = {
    "steps": ("Steps", "int"),
    "cfg": ("CFG", "float"),
    "sampler_name": ("Sampler", "choice"),
    "scheduler": ("Scheduler", "choice"),
    "denoise": ("Denoise", "float"),
    "guidance": ("Guidance", "float"),
    "shift": ("Shift", "float"),
    "width": ("Width", "int"),
    "height": ("Height", "int"),
    "length": ("Length (frames)", "int"),
    "num_frames": ("Frames", "int"),
    "frames": ("Frames", "int"),
    "batch_size": ("Batch size", "int"),
    "fps": ("FPS", "float"),
    "frame_rate": ("Frame rate", "float"),
    "duration": ("Duration", "float"),
    "megapixels": ("Megapixels", "float"),
    "aspect_ratio": ("Aspect ratio", "choice"),
}
#: Where each kind of model input's files live under ComfyUI's models folder.
MODEL_FOLDERS = {
    "ckpt_name": ("checkpoints",),
    "unet_name": ("diffusion_models", "unet"),
    "vae_name": ("vae",),
    "clip_name": ("text_encoders", "clip"),
    "clip_name1": ("text_encoders", "clip"),
    "clip_name2": ("text_encoders", "clip"),
    "clip_name3": ("text_encoders", "clip"),
    "clip_name4": ("text_encoders", "clip"),
    "lora_name": ("loras",),
    "control_net_name": ("controlnet",),
    "style_model_name": ("style_models",),
    "clip_vision_name": ("clip_vision",),
    "model_name": ("upscale_models",),
}
MODEL_LABELS = {
    "ckpt_name": "Checkpoint",
    "unet_name": "Diffusion model",
    "vae_name": "VAE",
    "clip_name": "Text encoder",
    "clip_name1": "Text encoder 1",
    "clip_name2": "Text encoder 2",
    "clip_name3": "Text encoder 3",
    "clip_name4": "Text encoder 4",
    "control_net_name": "ControlNet",
    "style_model_name": "Style model",
    "clip_vision_name": "CLIP vision",
    "model_name": "Model",
}
PRIMITIVES = {"PrimitiveInt": "int", "PrimitiveFloat": "float", "PrimitiveBoolean": "bool"}
SWITCHES = {"ComfySwitchNode"}
SAMPLER_FALLBACK = [
    "euler", "euler_ancestral", "heun", "dpm_2", "dpm_2_ancestral", "lms", "dpmpp_2s_ancestral",
    "dpmpp_sde", "dpmpp_2m", "dpmpp_2m_sde", "dpmpp_3m_sde", "ddpm", "lcm", "res_multistep", "uni_pc", "ddim",
]
SCHEDULER_FALLBACK = ["simple", "normal", "karras", "exponential", "sgm_uniform", "beta", "linear_quadratic", "ddim_uniform"]

#: Loaders a LoRA may be added after, when the definitions are not to hand.
KNOWN_MODEL_LOADERS = {
    "CheckpointLoaderSimple": True,  # value: also gives a CLIP at output 1
    "UNETLoader": False,
    "UnetLoaderGGUF": False,
    "ImageOnlyCheckpointLoader": False,
}


def is_link(value) -> bool:
    return isinstance(value, list) and len(value) == 2 and isinstance(value[0], str) and isinstance(value[1], int)


def _number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def by_number(node_id: str):
    """Node ids sort as numbers, part by part: "9" < "10" < "105:9" < "105:10"."""
    return tuple((0, int(p)) if p.isdigit() else (1, p) for p in node_id.split(":"))


# --- ComfyUI's node definitions ------------------------------------------------------------------


@dataclass
class Spec:
    type: str
    options: dict
    choices: list | None = None


class NodeInfo:
    """``/object_info``, or nothing. Every question has an answer either way."""

    def __init__(self, object_info: dict | None = None):
        self.data = object_info or {}

    def __bool__(self) -> bool:
        return bool(self.data)

    def spec(self, class_type: str, name: str) -> Spec | None:
        node = self.data.get(class_type)
        if not isinstance(node, dict):
            return None
        inputs = node.get("input") or {}
        for section in ("required", "optional"):
            entry = (inputs.get(section) or {}).get(name)
            if entry is not None:
                return _spec(entry)
        return None

    def outputs(self, class_type: str) -> list[str] | None:
        node = self.data.get(class_type)
        if not isinstance(node, dict) or not isinstance(node.get("output"), list):
            return None
        return [str(t) for t in node["output"]]

    def display_name(self, class_type: str) -> str:
        node = self.data.get(class_type)
        return str(node.get("display_name") or class_type) if isinstance(node, dict) else class_type

    def choices(self, class_type: str, name: str) -> list | None:
        spec = self.spec(class_type, name)
        return spec.choices if spec else None


def _spec(entry) -> Spec | None:
    if not isinstance(entry, list) or not entry:
        return None
    head = entry[0]
    options = entry[1] if len(entry) > 1 and isinstance(entry[1], dict) else {}
    if isinstance(head, list):
        return Spec("COMBO", options, [c for c in head if isinstance(c, (str, int, float))])
    if head == "COMBO":
        listed = options.get("options")
        return Spec("COMBO", options, list(listed) if isinstance(listed, list) else [])
    return Spec(str(head), options)


# --- The graph -----------------------------------------------------------------------------------


class Graph:
    """An API-format workflow with its links indexed both ways."""

    def __init__(self, nodes: dict):
        self.nodes = nodes
        #: node id -> [(consumer id, consumer input, output index)]
        self.consumers: dict[str, list[tuple[str, str, int]]] = {}
        for node_id, node in nodes.items():
            for name, value in node["inputs"].items():
                if is_link(value) and value[0] in nodes:
                    self.consumers.setdefault(value[0], []).append((node_id, name, value[1]))

    def title(self, node_id: str) -> str:
        meta = self.nodes[node_id].get("_meta")
        return (meta.get("title") or "") if isinstance(meta, dict) else ""

    def ordered(self) -> list[str]:
        return sorted(self.nodes, key=by_number)

    def downstream(self, node_id: str, limit: int = 400):
        """(consumer, input, depth) breadth first, each consumer input once."""
        seen = {node_id}
        queue = deque([(node_id, 0)])
        steps = 0
        while queue and steps < limit:
            current, depth = queue.popleft()
            for consumer, name, _ in sorted(self.consumers.get(current, []), key=lambda c: by_number(c[0])):
                steps += 1
                yield consumer, name, depth + 1
                if consumer not in seen:
                    seen.add(consumer)
                    queue.append((consumer, depth + 1))


def custom_title(graph: Graph, info: NodeInfo, node_id: str) -> str:
    """The node's title when someone gave it one, else ''."""
    title = graph.title(node_id).strip()
    class_type = graph.nodes[node_id]["class_type"]
    if not title or title in (class_type, info.display_name(class_type)):
        return ""
    return title


def _inner(title: str) -> str:
    """ "Float (duration)" -> "duration": templates name a primitive inside the brackets."""
    match = re.fullmatch(r"(?:Int|Float|Boolean|String|Text)\s*\((.+)\)", title.strip())
    return match.group(1).strip() if match else title.strip()


def humanize(name: str) -> str:
    words = name.split(".")[-1].replace("_", " ").strip()
    return words[:1].upper() + words[1:] if words else name


# --- Parameters ----------------------------------------------------------------------------------


@dataclass
class Param:
    id: str
    kind: str  # text | int | float | choice | bool | seed
    label: str
    group: str  # prompt | settings | models | loras
    targets: list[tuple[str, str]]
    default: object
    minimum: float | None = None
    maximum: float | None = None
    step: float | None = None
    choices: list | None = None
    #: Where to list the choices from at describe time when the definitions had none:
    #: model sub-folders, or a built-in list.
    choices_from: tuple[str, ...] | None = None
    multiline: bool = False
    negative: bool = False

    def describe(self, models: "ModelLists | None" = None) -> dict:
        described = {"id": self.id, "kind": self.kind, "label": self.label, "group": self.group, "default": self.default}
        for key, value in (("min", self.minimum), ("max", self.maximum), ("step", self.step)):
            if value is not None:
                described[key] = value
        if self.kind == "choice":
            described["choices"] = self.resolved_choices(models)
        if self.multiline:
            described["multiline"] = True
        return described

    def resolved_choices(self, models: "ModelLists | None" = None) -> list:
        listed = list(self.choices or [])
        if self.choices_from and models is not None:
            for name in models.files(self.choices_from):
                if name not in listed:
                    listed.append(name)
        if self.default is not None and self.default not in listed:
            listed.insert(0, self.default)
        return listed


@dataclass
class LoraTarget:
    """A model loader a LoRA can be added after."""

    id: str
    label: str
    node_id: str
    model_output: int
    clip_output: int | None


@dataclass
class Analysis:
    params: list[Param] = field(default_factory=list)
    lora_targets: list[LoraTarget] = field(default_factory=list)
    #: Folders of the LoRAs the workflow already loads, "/"-separated, "" for the top folder.
    lora_folders_used: list[str] = field(default_factory=list)


class ModelLists:
    """Model files by sub-folder of ComfyUI's ``models`` folder, as ComfyUI names them."""

    def __init__(self, by_folder: dict[str, list[str]] | None = None):
        self.by_folder = by_folder or {}

    def files(self, folders) -> list[str]:
        found: list[str] = []
        for folder in folders:
            for name in self.by_folder.get(folder, []):
                if name not in found:
                    found.append(name)
        return found


def analyse(nodes: dict, info: NodeInfo | None = None, skip_nodes=()) -> Analysis:
    """Every setting of ``nodes`` the phone may change. ``skip_nodes``: the photo's nodes."""
    info = info or NodeInfo()
    graph = Graph(nodes)
    skip = set(skip_nodes)
    taken: set[tuple[str, str]] = set()
    params: list[Param] = []

    params += _prompts(graph, info, skip, taken)
    seed = _seed(graph, info, skip, taken)
    if seed:
        params.append(seed)
    params += _named_settings(graph, info, skip, taken)
    params += _primitives(graph, info, skip, taken)
    params += _switches(graph, info, skip, taken)
    loras, used_folders = _existing_loras(graph, info, skip, taken)
    params += _models(graph, info, skip, taken)
    params += loras

    params.sort(key=_display_order)
    ids: set[str] = set()
    for param in params:
        base, n = param.id, 2
        while param.id in ids:
            param.id, n = f"{base}_{n}", n + 1
        ids.add(param.id)
    _disambiguate_labels(params)
    return Analysis(params, _lora_targets(graph, info), used_folders)


#: The order settings are shown in: the ones people tune most first
SETTING_ORDER = [
    "seed", "steps", "cfg", "sampler_name", "scheduler", "denoise", "guidance", "shift",
    "aspect_ratio", "megapixels", "width", "height", "duration", "length", "num_frames", "frames",
    "fps", "frame_rate", "batch_size",
]
GROUP_ORDER = ["prompt", "settings", "models", "loras"]


def _display_order(param: Param):
    group = GROUP_ORDER.index(param.group) if param.group in GROUP_ORDER else len(GROUP_ORDER)
    if param.group != "settings":
        return group, 0, 0
    base = param.id.split("_enable")[0]
    name = next((n for n in SETTING_ORDER if base == n or param.id.startswith(n + "_")), None)
    # Settings by name in their usual order; primitives and switches after, as found
    rank = SETTING_ORDER.index(name) if name is not None else len(SETTING_ORDER) + (1 if param.kind == "bool" else 0)
    return group, rank, 0


def _disambiguate_labels(params: list[Param]) -> None:
    counts: dict[tuple[str, str], int] = {}
    for param in params:
        counts[(param.group, param.label)] = counts.get((param.group, param.label), 0) + 1
    seen: dict[tuple[str, str], int] = {}
    for param in params:
        key = (param.group, param.label)
        if counts[key] > 1:
            seen[key] = seen.get(key, 0) + 1
            param.label = f"{param.label} {seen[key]}"


# Prompts ----------------------------------------------------------------------------------------


def _reaches_conditioning(graph: Graph, info: NodeInfo, node_id: str) -> bool:
    def conditions(nid: str) -> bool:
        class_type = graph.nodes[nid]["class_type"]
        outputs = info.outputs(class_type)
        if outputs is not None:
            return "CONDITIONING" in outputs
        return "Encode" in class_type or "Conditioning" in class_type or class_type.endswith("ToVideo")

    if conditions(node_id):
        return True
    for consumer, name, _ in graph.downstream(node_id):
        if name in ("positive", "negative", "conditioning") or conditions(consumer):
            return True
    return False


def _makes_media(graph: Graph, info: NodeInfo, node_id: str, name: str) -> bool:
    """A prompt typed straight into a node that makes the picture itself - a hosted model such
    as MiniMax's or Wan's API nodes, which take text and return video with no conditioning."""
    def media(nid: str) -> bool:
        outputs = info.outputs(graph.nodes[nid]["class_type"])
        return outputs is not None and any(t in ("IMAGE", "VIDEO", "LATENT", "AUDIO") for t in outputs)

    if name.split(".")[-1] in TEXT_INPUTS and media(node_id):
        return True
    # ...or typed into a text node wired to such a node's prompt
    return any(
        consumer_input.split(".")[-1] in TEXT_INPUTS and media(consumer)
        for consumer, consumer_input, _ in graph.downstream(node_id, limit=60)
    )


def _is_negative(graph: Graph, info: NodeInfo, node_id: str, name: str) -> bool:
    """Negative only when every path ends at a ``negative`` input. A text that is also fed,
    zeroed out, to the negative side (Flux Kontext does this) is still the positive prompt."""
    if "neg" in name.lower() or "negative" in graph.title(node_id).lower():
        return True
    if "pos" in name.lower():
        return False
    found_positive = found_negative = False
    seen = {node_id}
    queue = deque([node_id])
    while queue and len(seen) < 400:
        current = queue.popleft()
        for consumer, consumer_input, _ in graph.consumers.get(current, []):
            if "ZeroOut" in graph.nodes[consumer]["class_type"]:
                continue
            lowered = consumer_input.lower()
            # A positive/negative input decides its branch; nothing past it is followed (a node
            # taking both, like Wan's ImageToVideo, passes each on under the same name)
            if "negative" in lowered:
                found_negative = True
            elif "positive" in lowered:
                found_positive = True
            elif consumer not in seen:
                seen.add(consumer)
                queue.append(consumer)
    return found_negative and not found_positive


def _text_candidate(graph: Graph, info: NodeInfo, node_id: str, name: str, value) -> bool:
    if not isinstance(value, str):
        return False
    node = graph.nodes[node_id]
    class_type = node["class_type"]
    if class_type in NOT_TEXT_CLASSES or name.split(".")[-1] in NOT_TEXT_INPUTS:
        return False
    # Text plumbing (find/replace, joining, regex) holds templates, not what to make
    if class_type.startswith(("String", "Regex")) or "Regex" in class_type:
        return False
    # The instructions of a prompt enhancer are not the prompt
    words = (name + " " + graph.title(node_id)).lower()
    if "system" in words or "task" in words:
        return False
    spec = info.spec(class_type, name)
    if spec is not None:
        if spec.type == "STRING":
            return bool(spec.options.get("multiline")) or name in TEXT_INPUTS
        # A switch passes along whatever it is given: text in it is text
        return spec.type.startswith("COMFY_MATCHTYPE") or spec.type == "*"
    if name.split(".")[-1] in TEXT_INPUTS:
        return True
    return class_type in SWITCHES or (class_type.startswith("PrimitiveString") and name == "value")


def _prompts(graph: Graph, info: NodeInfo, skip: set, taken: set) -> list[Param]:
    groups: dict[tuple[bool, str], list[tuple[str, str]]] = {}
    order: list[tuple[bool, str]] = []
    for node_id in graph.ordered():
        if node_id in skip:
            continue
        node = graph.nodes[node_id]
        explicit = node["class_type"] == "OneGalleryPrompt"
        for name, value in node["inputs"].items():
            if not _text_candidate(graph, info, node_id, name, value):
                continue
            if not explicit and not _reaches_conditioning(graph, info, node_id) and not _makes_media(
                graph, info, node_id, name
            ):
                continue
            key = (_is_negative(graph, info, node_id, name), value)
            if key not in groups:
                groups[key] = []
                order.append(key)
            groups[key].append((node_id, name))

    # Positive first, in the order they appear; negatives after. A text marked as the one to
    # type - a Phone Prompt node, or a title saying "user" or "phone" - leads its side.
    def marked(key) -> bool:
        for node_id, name in groups[key]:
            words = (name + " " + graph.title(node_id)).lower()
            if graph.nodes[node_id]["class_type"] == "OneGalleryPrompt" or "user" in words or "phone" in words:
                return True
        return False

    order.sort(key=lambda k: (k[0], not marked(k)))
    params: list[Param] = []
    counts = {False: 0, True: 0}
    for negative, text in order:
        counts[negative] += 1
        n = counts[negative]
        base = "negative" if negative else "prompt"
        targets = groups[(negative, text)]
        taken.update(targets)
        title = next((custom_title(graph, info, t[0]) for t in targets if custom_title(graph, info, t[0])), "")
        label = "Negative prompt" if negative else "Prompt"
        if n > 1:
            label = f"{label} · {title}" if title else f"{label} {n}"
        params.append(
            Param(
                id=base if n == 1 else f"{base}_{n}",
                kind="text",
                label=label,
                group="prompt",
                targets=targets,
                default=text,
                multiline=True,
                negative=negative,
            )
        )
    return params


# Seed ---------------------------------------------------------------------------------------------


def _seed(graph: Graph, info: NodeInfo, skip: set, taken: set) -> Param | None:
    targets: list[tuple[str, str]] = []
    default = None
    for node_id in graph.ordered():
        if node_id in skip:
            continue
        for name, value in graph.nodes[node_id]["inputs"].items():
            if name.split(".")[-1] in SEED_INPUTS and _number(value) and isinstance(value, int):
                targets.append((node_id, name))
                default = value if default is None else default
    if not targets:
        return None
    taken.update(targets)
    return Param(id="seed", kind="seed", label="Seed", group="settings", targets=targets, default=default,
                 minimum=0, maximum=MAX_TYPED_SEED)


# Settings by name -----------------------------------------------------------------------------------


def _kind_of(spec: Spec | None, fallback: str, value) -> str | None:
    if spec is not None:
        if spec.type == "INT":
            return "int"
        if spec.type == "FLOAT":
            return "float"
        if spec.type == "BOOLEAN":
            return "bool"
        if spec.type == "COMBO":
            return "choice"
        if spec.type == "STRING":
            return None
    if fallback == "choice":
        return "choice" if isinstance(value, str) else None
    if isinstance(value, bool):
        return "bool"
    if fallback == "int" and isinstance(value, int):
        return "int"
    if fallback in ("int", "float") and _number(value):
        return "float" if fallback == "float" or isinstance(value, float) else "int"
    return None


def _range(spec: Spec | None, kind: str) -> tuple:
    if spec is None or kind not in ("int", "float"):
        return None, None, None
    o = spec.options
    low, high, step = o.get("min"), o.get("max"), o.get("step")
    # The definitions say "any 64-bit number" for many inputs; the phone needs nothing that wide
    if isinstance(high, (int, float)) and high > MAX_TYPED_SEED:
        high = None
    if isinstance(low, (int, float)) and low < -MAX_TYPED_SEED:
        low = None
    return low, high, step


def _setting_param(graph, info, node_id, name, value, label, kind, group="settings") -> Param:
    class_type = graph.nodes[node_id]["class_type"]
    spec = info.spec(class_type, name)
    low, high, step = _range(spec, kind)
    choices = choices_from = None
    if kind == "choice":
        choices = list(spec.choices) if spec and spec.choices else None
        short = name.split(".")[-1]
        if not choices:
            if short == "sampler_name":
                choices = list(SAMPLER_FALLBACK)
            elif short == "scheduler":
                choices = list(SCHEDULER_FALLBACK)
            elif short in MODEL_FOLDERS:
                choices_from = MODEL_FOLDERS[short]
    return Param(
        id=re.sub(r"[^a-z0-9]+", "_", (label or name).lower()).strip("_") or "setting",
        kind=kind, label=label, group=group, targets=[(node_id, name)], default=value,
        minimum=low, maximum=high, step=step, choices=choices, choices_from=choices_from,
    )


def _named_settings(graph: Graph, info: NodeInfo, skip: set, taken: set) -> list[Param]:
    found: dict[tuple[str, object], Param] = {}
    by_name: dict[str, list[Param]] = {}
    for node_id in graph.ordered():
        if node_id in skip:
            continue
        node = graph.nodes[node_id]
        if node["class_type"] in PRIMITIVES:
            continue
        for name, value in node["inputs"].items():
            short = name.split(".")[-1]
            if short not in NAMED_SETTINGS or is_link(value) or (node_id, name) in taken:
                continue
            label, fallback = NAMED_SETTINGS[short]
            kind = _kind_of(info.spec(node["class_type"], name), fallback, value)
            if kind is None:
                continue
            key = (short, value)
            if key in found:
                found[key].targets.append((node_id, name))
            else:
                param = _setting_param(graph, info, node_id, name, value, label, kind)
                param.id = short
                found[key] = param
                by_name.setdefault(short, []).append(param)
            taken.add((node_id, name))
    params: list[Param] = []
    for short, same_name in by_name.items():
        if len(same_name) > 1:
            # Different values under one name: say which node each belongs to
            for param in same_name:
                where = custom_title(graph, info, param.targets[0][0]) or info.display_name(
                    graph.nodes[param.targets[0][0]]["class_type"]
                )
                param.label = f"{param.label} · {where}"
        params += same_name
    return params


# Primitive nodes and switches -------------------------------------------------------------------------


def _switch_note(graph: Graph, info: NodeInfo, node_id: str) -> str:
    """ "Enable Lightning LoRA on" when ``node_id`` only counts while a switch is one way."""
    for consumer, name, _ in graph.downstream(node_id, limit=40):
        node = graph.nodes[consumer]
        if node["class_type"] in SWITCHES and name in ("on_true", "on_false"):
            flag = node["inputs"].get("switch")
            if is_link(flag) and flag[0] in graph.nodes:
                title = custom_title(graph, info, flag[0])
                if title:
                    return f"{_inner(title)} {'on' if name == 'on_true' else 'off'}"
            return ""
    return ""


def _primitives(graph: Graph, info: NodeInfo, skip: set, taken: set) -> list[Param]:
    params: list[Param] = []
    for node_id in graph.ordered():
        node = graph.nodes[node_id]
        kind = PRIMITIVES.get(node["class_type"])
        if kind is None or node_id in skip or (node_id, "value") in taken:
            continue
        value = node["inputs"].get("value")
        if is_link(value) or value is None:
            continue
        if not graph.consumers.get(node_id):
            continue  # feeds nothing: changing it would change nothing
        # What the value is used as: the first input downstream that is not a switch or a sum
        used_as, used_spec = "", None
        for consumer, name, _ in graph.downstream(node_id, limit=60):
            consumer_type = graph.nodes[consumer]["class_type"]
            if consumer_type in SWITCHES or consumer_type in NOT_TEXT_CLASSES:
                continue
            used_as, used_spec = name, info.spec(consumer_type, name)
            break
        title = custom_title(graph, info, node_id)
        if title:
            label = humanize(_inner(title))
        elif used_as.split(".")[-1] in NAMED_SETTINGS:
            label = NAMED_SETTINGS[used_as.split(".")[-1]][0]
        elif used_as:
            label = humanize(used_as)
        else:
            continue
        note = _switch_note(graph, info, node_id)
        if note:
            label = f"{label} ({note})"
        spec = used_spec if used_spec is not None and used_spec.type in ("INT", "FLOAT") else info.spec(node["class_type"], "value")
        low, high, step = _range(spec, kind)
        if kind == "float" and isinstance(value, int):
            value = float(value)
        params.append(
            Param(id=re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_") or "value", kind=kind, label=label,
                  group="settings", targets=[(node_id, "value")], default=value, minimum=low, maximum=high, step=step)
        )
        taken.add((node_id, "value"))
    return params


def _switches(graph: Graph, info: NodeInfo, skip: set, taken: set) -> list[Param]:
    """A switch whose position is typed into it (not wired from a Boolean) is a setting too,
    when it has been given a title saying what it switches."""
    params: list[Param] = []
    for node_id in graph.ordered():
        node = graph.nodes[node_id]
        if node["class_type"] not in SWITCHES or node_id in skip:
            continue
        flag = node["inputs"].get("switch")
        if not isinstance(flag, bool) or (node_id, "switch") in taken:
            continue
        # Only a switch someone named: "If/Else Switch" on its own says nothing about what it does
        title = custom_title(graph, info, node_id)
        if not title:
            continue
        params.append(Param(id="switch", kind="bool", label=humanize(_inner(title)), group="settings",
                            targets=[(node_id, "switch")], default=flag))
        taken.add((node_id, "switch"))
    return params


# Models and LoRAs ----------------------------------------------------------------------------------


def _looks_like_model(value) -> bool:
    return isinstance(value, str) and value.lower().endswith(MODEL_EXTENSIONS)


def lora_folder(name: str) -> str:
    """The folder part of a LoRA's name as ComfyUI gives it, "/"-separated ('' at the top)."""
    normal = name.replace("\\", "/")
    return normal.rsplit("/", 1)[0] if "/" in normal else ""


def _existing_loras(graph: Graph, info: NodeInfo, skip: set, taken: set) -> tuple[list[Param], list[str]]:
    params: list[Param] = []
    folders: list[str] = []
    for node_id in graph.ordered():
        inputs = graph.nodes[node_id]["inputs"]
        name = inputs.get("lora_name")
        if node_id in skip or not _looks_like_model(name):
            continue
        folder = lora_folder(name)
        if folder not in folders:
            folders.append(folder)
        file = name.replace("\\", "/").rsplit("/", 1)[-1]
        short = re.sub(r"\.[a-z0-9]+$", "", file, flags=re.I)
        lora = _setting_param(graph, info, node_id, "lora_name", name, f"LoRA · {short}", "choice", group="loras")
        lora.id = "lora"
        lora.choices_from = ("loras",)
        if not lora.choices:
            lora.choices = None
        params.append(lora)
        taken.add((node_id, "lora_name"))
        for strength, label in (("strength_model", "strength"), ("strength_clip", "CLIP strength")):
            value = inputs.get(strength)
            if _number(value):
                param = _setting_param(graph, info, node_id, strength, float(value), f"LoRA · {short} · {label}", "float", group="loras")
                param.id = "lora_strength" if strength == "strength_model" else "lora_clip_strength"
                params.append(param)
                taken.add((node_id, strength))
    return params, folders


def _models(graph: Graph, info: NodeInfo, skip: set, taken: set) -> list[Param]:
    params: list[Param] = []
    for node_id in graph.ordered():
        if node_id in skip:
            continue
        node = graph.nodes[node_id]
        for name, value in node["inputs"].items():
            if (node_id, name) in taken or not _looks_like_model(value):
                continue
            spec = info.spec(node["class_type"], name)
            if spec is not None and spec.type != "COMBO":
                continue
            short = name.split(".")[-1]
            label = MODEL_LABELS.get(short, humanize(short))
            title = custom_title(graph, info, node_id)
            if title:
                label = f"{label} · {title}"
            param = _setting_param(graph, info, node_id, name, value, label, "choice", group="models")
            param.id = short
            param.choices_from = MODEL_FOLDERS.get(short)
            params.append(param)
            taken.add((node_id, name))
    return params


def _lora_targets(graph: Graph, info: NodeInfo) -> list[LoraTarget]:
    """Loaders whose model is used: each is a place a LoRA can be added."""
    targets: list[LoraTarget] = []
    for node_id in graph.ordered():
        node = graph.nodes[node_id]
        class_type = node["class_type"]
        outputs = info.outputs(class_type)
        if outputs is not None:
            if "MODEL" not in outputs:
                continue
            takes_model = any(
                is_link(v) and (s := info.spec(class_type, k)) is not None and s.type == "MODEL"
                for k, v in node["inputs"].items()
            )
            if takes_model or any(
                (s := info.spec(class_type, k)) is not None and s.type == "MODEL" for k in node["inputs"]
            ):
                continue
            model_output = outputs.index("MODEL")
            clip_output = outputs.index("CLIP") if "CLIP" in outputs else None
        elif class_type in KNOWN_MODEL_LOADERS:
            model_output, clip_output = 0, (1 if KNOWN_MODEL_LOADERS[class_type] else None)
        else:
            continue
        if not any(index == model_output for _, _, index in graph.consumers.get(node_id, [])):
            continue
        file = next((v for v in node["inputs"].values() if _looks_like_model(v)), "")
        label = custom_title(graph, info, node_id) or re.sub(r"\.[a-z0-9]+$", "", file.replace("\\", "/").rsplit("/", 1)[-1], flags=re.I) or info.display_name(class_type)
        targets.append(LoraTarget(id=f"m{len(targets) + 1}", label=label, node_id=node_id,
                                  model_output=model_output, clip_output=clip_output))
    return targets


# --- Checking and applying what the phone sent ------------------------------------------------------


class InvalidValue(ValueError):
    """What the phone sent cannot be used; the message says why, for the phone to show."""


def clean_values(params: list[Param], sent: dict, models: ModelLists | None = None) -> dict:
    """``sent`` checked against ``params``: unknown names, wrong types and out-of-range numbers
    are refused. Returns {param id: value}; a seed comes back as {"mode", "value"}."""
    if not isinstance(sent, dict):
        raise InvalidValue("Settings must be sent as a JSON object")
    known = {p.id: p for p in params}
    cleaned: dict = {}
    for key, value in sent.items():
        param = known.get(key)
        if param is None:
            raise InvalidValue(f"This job has no setting called {key!r}")
        cleaned[key] = _clean(param, value, models)
    return cleaned


def _clean(param: Param, value, models):
    def in_range(number):
        if param.minimum is not None and number < param.minimum:
            raise InvalidValue(f"{param.label} must be at least {param.minimum}")
        if param.maximum is not None and number > param.maximum:
            raise InvalidValue(f"{param.label} must be at most {param.maximum}")
        return number

    if param.kind == "text":
        if not isinstance(value, str):
            raise InvalidValue(f"{param.label} must be text")
        if len(value) > MAX_TEXT_CHARS:
            raise InvalidValue(f"{param.label} is longer than {MAX_TEXT_CHARS} characters")
        return value
    if param.kind == "seed":
        if _number(value):
            value = {"mode": "fixed", "value": value}
        if not isinstance(value, dict) or value.get("mode") not in ("random", "fixed"):
            raise InvalidValue("The seed is sent as {\"mode\": \"random\" or \"fixed\", \"value\": number}")
        if value["mode"] == "random":
            return {"mode": "random"}
        number = value.get("value")
        if not isinstance(number, int) or isinstance(number, bool) or not 0 <= number <= MAX_TYPED_SEED:
            raise InvalidValue("A fixed seed is a whole number from 0")
        return {"mode": "fixed", "value": number}
    if param.kind == "int":
        if isinstance(value, float) and value.is_integer():
            value = int(value)
        if not isinstance(value, int) or isinstance(value, bool):
            raise InvalidValue(f"{param.label} must be a whole number")
        return in_range(value)
    if param.kind == "float":
        if not _number(value):
            raise InvalidValue(f"{param.label} must be a number")
        return in_range(float(value))
    if param.kind == "bool":
        if not isinstance(value, bool):
            raise InvalidValue(f"{param.label} must be on or off")
        return value
    if param.kind == "choice":
        choices = param.resolved_choices(models)
        if value not in choices:
            raise InvalidValue(f"{value!r} is not one of the choices for {param.label}")
        return value
    raise InvalidValue(f"{param.label} cannot be changed")


@dataclass
class LoraRequest:
    name: str
    strength: float
    target: str | None = None


def clean_loras(sent, available: list[str], targets: list[LoraTarget], limit: int = 8) -> list[LoraRequest]:
    """LoRAs to add, checked: known files only, a sane strength, a target that exists."""
    if sent in (None, ""):
        return []
    if not isinstance(sent, list):
        raise InvalidValue("LoRAs are sent as a list")
    if sent and not targets:
        raise InvalidValue("LoRAs cannot be added to this job: it loads no model they could go on")
    if len(sent) > limit:
        raise InvalidValue(f"At most {limit} LoRAs can be added")
    known_targets = {t.id for t in targets}
    cleaned = []
    for item in sent:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise InvalidValue("Each LoRA is sent as {\"name\", \"strength\"}")
        if item["name"] not in available:
            raise InvalidValue(f"{item['name']!r} is not a LoRA this job can use")
        strength = item.get("strength", 1.0)
        if not _number(strength) or not -10 <= strength <= 10:
            raise InvalidValue("A LoRA's strength is a number from -10 to 10")
        target = item.get("target")
        if target is not None and target not in known_targets:
            raise InvalidValue("That LoRA is aimed at a model this job does not have")
        cleaned.append(LoraRequest(item["name"], float(strength), target))
    return cleaned


def apply(
    nodes: dict,
    params: list[Param],
    values: dict,
    loras: list[LoraRequest] = (),
    targets: list[LoraTarget] = (),
    randomize_unset_seed: bool = True,
    pick_seed=None,
) -> tuple[dict, dict]:
    """A copy of ``nodes`` with ``values`` put in and ``loras`` added. Returns (graph, used):
    ``used`` holds what was actually chosen where the phone left it to chance (the seed)."""
    graph = copy.deepcopy(nodes)
    used: dict = {}
    pick = pick_seed or (lambda: random.randrange(MAX_SEED))
    for param in params:
        value = values.get(param.id)
        if param.kind == "seed":
            if value is None:
                value = {"mode": "random" if randomize_unset_seed else "fixed", "value": param.default}
            number = pick() if value["mode"] == "random" else value["value"]
            used["seed"] = number
            value = number
        elif value is None:
            continue
        elif param.kind == "text" and not value.strip():
            continue  # an empty box means "the workflow's own text"
        for node_id, name in param.targets:
            graph[node_id]["inputs"][name] = value

    # Copies: adding a LoRA moves its target along the chain, and the analysis is shared
    chain = [copy.copy(t) for t in targets]
    for number, lora in enumerate(loras, start=1):
        for target in chain:
            if lora.target is None or lora.target == target.id:
                _add_lora(graph, target, lora, f"og_lora_{number}_{target.id}")
    return graph, used


def _add_lora(graph: dict, target: LoraTarget, lora: LoraRequest, new_id: str) -> None:
    """Puts a LoRA loader straight after ``target`` and moves everything that used the loader's
    model (and CLIP, for a checkpoint) onto it. Earlier LoRAs stay in the chain."""
    model_link = [target.node_id, target.model_output]
    clip_link = [target.node_id, target.clip_output] if target.clip_output is not None else None
    for node in graph.values():
        for name, value in node["inputs"].items():
            if value == model_link:
                node["inputs"][name] = [new_id, 0]
            elif clip_link is not None and value == clip_link:
                node["inputs"][name] = [new_id, 1]
    if clip_link is not None:
        graph[new_id] = {
            "class_type": "LoraLoader",
            "inputs": {"model": model_link, "clip": clip_link, "lora_name": lora.name,
                       "strength_model": lora.strength, "strength_clip": lora.strength},
            "_meta": {"title": "LoRA from the phone"},
        }
    else:
        graph[new_id] = {
            "class_type": "LoraLoaderModelOnly",
            "inputs": {"model": model_link, "lora_name": lora.name, "strength_model": lora.strength},
            "_meta": {"title": "LoRA from the phone"},
        }
    # Re-point the targets for the next LoRA on the same model: it goes after this one
    target.node_id, target.model_output = new_id, 0
    target.clip_output = 1 if clip_link is not None else None
