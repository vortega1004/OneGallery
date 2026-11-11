"""Rules of the gateway that need no network: the jobs folder, building a job, pairing.

    python -m unittest discover -s tests        (from the gateway folder)
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from onegallery_gateway import workflows as wf  # noqa: E402
from onegallery_gateway.security import Devices, normalise_code  # noqa: E402

ECHO = {
    "1": {"class_type": "OneGalleryImageInput", "inputs": {"image": "example.png"}},
    "2": {"class_type": "OneGallerySendBack", "inputs": {"filename_prefix": "x", "images": ["1", 0]}},
}
STOCK = {
    "4": {"class_type": "LoadImage", "inputs": {"image": "a.png"}, "_meta": {"title": "Load Image"}},
    "9": {"class_type": "KSampler", "inputs": {"seed": 7, "steps": 20, "model": ["1", 0]}},
    "10": {"class_type": "SaveImage", "inputs": {"filename_prefix": "x", "images": ["9", 0]}},
}
EDITOR = {"last_node_id": 1, "nodes": [{"id": 1, "type": "LoadImage"}], "links": [], "version": 0.4}


def folder_with(files: dict) -> Path:
    folder = Path(tempfile.mkdtemp())
    for name, content in files.items():
        text = content if isinstance(content, str) else json.dumps(content)
        (folder / name).write_text(text, encoding="utf-8")
    return folder


class FolderTests(unittest.TestCase):
    def test_the_file_name_is_the_job_name(self):
        found, invalid = wf.load_workflows(folder_with({"Portrait Edit v2.json": ECHO}))
        self.assertEqual([], invalid)
        self.assertEqual("Portrait Edit v2", found[0].name)
        self.assertEqual("portrait-edit-v2", found[0].id)

    def test_a_workflow_saved_from_the_editor_is_refused_with_the_reason(self):
        found, invalid = wf.load_workflows(folder_with({"Saved.json": EDITOR}))
        self.assertEqual([], found)
        self.assertIn("Export (API)", invalid[0].reason)

    def test_other_json_and_broken_files_are_reported_not_fatal(self):
        found, invalid = wf.load_workflows(
            folder_with({"a.json": "{not json", "b.json": {"settings": True}, "c.json": ECHO})
        )
        self.assertEqual(["c"], [w.name for w in found])
        self.assertEqual(["a.json", "b.json"], [i.file for i in invalid])

    def test_two_names_that_slug_alike_get_different_ids(self):
        found, _ = wf.load_workflows(folder_with({"My job.json": ECHO, "my_job.json": ECHO}))
        self.assertEqual(2, len({w.id for w in found}))

    def test_a_text_file_beside_the_workflow_is_its_description(self):
        found, _ = wf.load_workflows(folder_with({"Job.json": ECHO, "Job.txt": "Makes it nicer\nsecond line"}))
        self.assertEqual("Makes it nicer", found[0].description)

    def test_a_missing_folder_is_an_empty_list(self):
        self.assertEqual(([], []), wf.load_workflows(Path(tempfile.mkdtemp()) / "nowhere"))


class AnalyseTests(unittest.TestCase):
    def test_onegallery_nodes_are_found_by_type(self):
        graph = {**ECHO, "3": {"class_type": "OneGalleryPrompt", "inputs": {"text": "hello"}}}
        workflow = wf.load_workflows(folder_with({"t.json": graph}))[0][0]
        self.assertEqual((["1"], ["2"]), (workflow.image_nodes, workflow.output_nodes))
        self.assertEqual(("3", "text"), workflow.prompt.targets[0])
        self.assertEqual("hello", workflow.prompt.default)

    def test_without_them_the_first_load_image_takes_the_photo(self):
        graph = {**STOCK, "12": {"class_type": "LoadImage", "inputs": {"image": "mask.png"}}}
        self.assertEqual(["4"], wf.find_image_nodes(graph))

    def test_a_load_image_titled_phone_wins_over_the_first(self):
        graph = {
            **STOCK,
            "12": {"class_type": "LoadImage", "inputs": {"image": "x.png"}, "_meta": {"title": "Phone photo"}},
        }
        self.assertEqual(["12"], wf.find_image_nodes(graph))

    def test_any_text_encoder_is_a_prompt_without_marking_it(self):
        graph = {
            **STOCK,
            "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "a cat", "clip": ["1", 1]}},
            "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry", "clip": ["1", 1]}},
        }
        graph["9"] = {**graph["9"], "inputs": {**graph["9"]["inputs"], "positive": ["6", 0], "negative": ["7", 0]}}
        workflow = wf.load_workflows(folder_with({"t.json": graph}))[0][0]
        found = {p.id: p.default for p in workflow.params if p.kind == "text"}
        self.assertEqual({"prompt": "a cat", "negative": "blurry"}, found)

    def test_a_workflow_with_no_image_node_needs_no_photo(self):
        graph = {"1": {"class_type": "OneGalleryPrompt", "inputs": {"text": "x"}}}
        found, _ = wf.load_workflows(folder_with({"t.json": graph}))
        described = found[0].describe()
        self.assertFalse(described["requires_image"])
        self.assertEqual({"editable": True, "default": "x"}, described["prompt"])
        self.assertEqual(["prompt"], [p["id"] for p in described["params"]])

    def test_a_job_without_a_prompt_offers_no_prompt(self):
        found, _ = wf.load_workflows(folder_with({"t.json": ECHO}))
        self.assertNotIn("prompt", found[0].describe())

    def test_an_options_file_describes_hides_and_limits_loras_and_is_not_a_job(self):
        options = {"description": "Night look", "hide": ["steps"], "lora_folders": ["wan"]}
        found, invalid = wf.load_workflows(folder_with({"t.json": STOCK, "t.options.json": options}))
        self.assertEqual((1, []), (len(found), invalid))
        self.assertEqual("Night look", found[0].description)
        self.assertNotIn("steps", [p.id for p in found[0].params])
        self.assertEqual(["wan"], found[0].lora_folders())


class BuildTests(unittest.TestCase):
    def workflow(self, graph):
        return wf.load_workflows(folder_with({"t.json": graph}))[0][0]

    def test_the_photo_and_the_text_are_put_in_and_nothing_else_changes(self):
        graph = {**ECHO, "3": {"class_type": "OneGalleryPrompt", "inputs": {"text": "default"}}}
        built, _ = wf.build_prompt(self.workflow(graph), "onegallery_1.png", {"prompt": "typed"}, randomize_seeds=False)
        self.assertEqual("onegallery_1.png", built["1"]["inputs"]["image"])
        self.assertEqual("typed", built["3"]["inputs"]["text"])
        self.assertEqual(graph["2"], built["2"])
        # The file on disk is never altered
        self.assertEqual("example.png", graph["1"]["inputs"]["image"])

    def test_blank_text_keeps_the_workflows_own_prompt(self):
        graph = {**ECHO, "3": {"class_type": "OneGalleryPrompt", "inputs": {"text": "default"}}}
        for sent in ({}, {"prompt": ""}, {"prompt": "   "}):
            built, _ = wf.build_prompt(self.workflow(graph), "p.png", sent, randomize_seeds=False)
            self.assertEqual("default", built["3"]["inputs"]["text"])

    def test_seeds_are_rolled_and_links_are_left_alone(self):
        graph = {**STOCK, "11": {"class_type": "X", "inputs": {"noise_seed": 1, "seed": ["9", 0]}}}
        built, used = wf.build_prompt(self.workflow(graph), "p.png", {}, randomize_seeds=True, random_seed=lambda: 42)
        self.assertEqual(42, built["9"]["inputs"]["seed"])
        self.assertEqual(42, built["11"]["inputs"]["noise_seed"])
        self.assertEqual(["9", 0], built["11"]["inputs"]["seed"])
        self.assertEqual(20, built["9"]["inputs"]["steps"])
        self.assertEqual(42, used["seed"])

    def test_seeds_can_be_left_as_exported(self):
        built, used = wf.build_prompt(self.workflow(STOCK), "p.png", {}, randomize_seeds=False)
        self.assertEqual(7, built["9"]["inputs"]["seed"])
        self.assertEqual(7, used["seed"])

    def test_a_fixed_seed_from_the_phone(self):
        built, _ = wf.build_prompt(self.workflow(STOCK), "p.png", {"seed": {"mode": "fixed", "value": 123}})
        self.assertEqual(123, built["9"]["inputs"]["seed"])


class OutputTests(unittest.TestCase):
    def test_send_to_phone_decides_what_comes_back(self):
        history = {
            "2": {"images": [{"filename": "a.png", "subfolder": "og", "type": "output"}],
                  "onegallery": [{"filename": "a.png", "subfolder": "og", "type": "output"},
                                 {"filename": "v.mp4", "subfolder": "og", "type": "output"}]},
            "7": {"images": [{"filename": "other.png", "subfolder": "", "type": "output"}]},
        }
        outputs = wf.collect_outputs(history, ["2"])
        self.assertEqual(["a.png", "v.mp4"], [o["filename"] for o in outputs])
        self.assertEqual(["image/png", "video/mp4"], [o["media_type"] for o in outputs])
        self.assertEqual(["o0", "o1"], [o["id"] for o in outputs])

    def test_without_it_everything_saved_comes_back_but_not_previews(self):
        history = {
            "3": {"images": [{"filename": "preview.png", "subfolder": "", "type": "temp"}]},
            "9": {"gifs": [{"filename": "clip.mp4", "subfolder": "", "type": "output", "format": "video/h264-mp4"}]},
        }
        self.assertEqual(["clip.mp4"], [o["filename"] for o in wf.collect_outputs(history, [])])

    def test_previews_come_back_when_nothing_was_saved(self):
        history = {"3": {"images": [{"filename": "preview.png", "subfolder": "", "type": "temp"}]}}
        self.assertEqual(["preview.png"], [o["filename"] for o in wf.collect_outputs(history, [])])

    def test_files_the_phone_cannot_show_are_left_out(self):
        history = {"3": {"audio": [{"filename": "a.flac", "subfolder": "", "type": "output"}],
                         "text": ["hello"], "animated": [True]}}
        self.assertEqual([], wf.collect_outputs(history, []))


class PairingTests(unittest.TestCase):
    def store(self):
        return Devices.open(Path(tempfile.mkdtemp()))

    def test_the_code_is_four_digits_and_pairs_once(self):
        devices = self.store()
        code = devices.start_pairing()
        self.assertRegex(code, r"^[0-9]{4}$")
        device, token = devices.finish_pairing(code, "Pixel")
        self.assertEqual("Pixel", device.name)
        self.assertIsNone(devices.finish_pairing(code, "Pixel"))
        self.assertEqual(device.id, Devices.open(devices.path.parent).authenticate(token).id)

    def test_spaces_and_dashes_do_not_matter(self):
        devices = self.store()
        code = devices.start_pairing()
        typed = f" {code[:2]}-{code[2:]} "
        self.assertEqual(code, normalise_code(typed))
        self.assertIsNotNone(devices.finish_pairing(typed, ""))

    def test_an_expired_code_is_refused(self):
        devices = self.store()
        code = devices.start_pairing(minutes=30, now=1000.0)
        self.assertTrue(devices.pairing_open(now=1000.0 + 60))
        self.assertIsNone(devices.finish_pairing(code, "", now=1000.0 + 30 * 60 + 1))

    def test_five_wrong_guesses_close_pairing(self):
        devices = self.store()
        code = devices.start_pairing()
        wrong = f"{(int(code) + 1) % 10000:04d}"
        for _ in range(5):
            self.assertIsNone(devices.finish_pairing(wrong, ""))
        self.assertFalse(devices.pairing_open())
        self.assertIsNone(devices.finish_pairing(code, ""))

    def test_a_revoked_phone_is_locked_out_and_the_token_is_not_stored(self):
        devices = self.store()
        device, token = devices.finish_pairing(devices.start_pairing(), "")
        self.assertNotIn(token, devices.path.read_text(encoding="utf-8"))
        self.assertTrue(devices.revoke(device.id))
        self.assertIsNone(Devices.open(devices.path.parent).authenticate(token))


if __name__ == "__main__":
    unittest.main()
