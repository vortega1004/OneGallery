"""What the gateway finds in real workflows, and what it does with the phone's settings.

The workflows are official ComfyUI templates exported with Export (API) - see fixtures/README.md.

    python -m unittest discover -s tests        (from the gateway folder)
"""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from onegallery_gateway import params as P  # noqa: E402
from onegallery_gateway import workflows as wf  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
INFO = P.NodeInfo(json.loads((FIXTURES / "object_info_subset.json").read_text(encoding="utf-8")))


def graph(name: str) -> dict:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


def analysed(name: str, info=INFO) -> P.Analysis:
    nodes = graph(name)
    return P.analyse(nodes, info, skip_nodes=wf.find_image_nodes(nodes))


def by_id(analysis: P.Analysis) -> dict:
    return {p.id: p for p in analysis.params}


class PromptTests(unittest.TestCase):
    def test_minimax_h3_video_prompt_inside_its_subgraph(self):
        params = by_id(analysed("video_minimax_h3_i2v"))
        self.assertEqual([("105:104", "prompt")], params["prompt"].targets)
        self.assertTrue(params["prompt"].default.startswith("Editorial tech product film"))
        self.assertNotIn("negative", params)

    def test_qwen_image_2_1_one_prompt_in_two_places_and_its_negative(self):
        # The subgraph's prompt box feeds both the prompt enhancer and the switch around it
        params = by_id(analysed("image_qwen_image_2_1_t2i"))
        self.assertEqual({("459:471", "prompt"), ("459:474", "on_false")}, set(params["prompt"].targets))
        self.assertEqual([("459:452", "negative_prompt")], params["negative"].targets)
        # The enhancer's system prompt is not offered
        self.assertFalse(any("System" in p.default for p in params.values() if p.kind == "text"))

    def test_minimax_h3_image_edit_the_user_prompt_not_the_enhancers_instructions(self):
        params = by_id(analysed("image_minimax_h3_image_edit"))
        texts = [p for p in params.values() if p.kind == "text"]
        self.assertEqual(["prompt"], [p.id for p in texts])
        self.assertTrue(params["prompt"].default.startswith("Replace the vintage yellow taxi"))

    def test_wan_negative_is_told_apart_through_image_to_video(self):
        params = by_id(analysed("video_wan2_2_14B_i2v"))
        self.assertTrue(params["prompt"].default.startswith("The white dragon warrior"))
        self.assertFalse(params["prompt"].negative)
        self.assertTrue(params["negative"].negative)

    def test_flux_kontext_a_prompt_also_zeroed_into_the_negative_is_still_positive(self):
        params = by_id(analysed("flux_kontext_dev_basic"))
        self.assertIn("prompt", params)
        self.assertNotIn("negative", params)

    def test_sdxl_base_and_refiner_share_one_prompt_and_one_negative(self):
        params = by_id(analysed("sdxl_simple_example"))
        self.assertEqual(2, len(params["prompt"].targets))
        self.assertEqual("text, watermark", params["negative"].default)
        self.assertEqual(2, len(params["negative"].targets))

    def test_hosted_api_nodes_take_their_prompt_directly(self):
        self.assertEqual([("33", "model.prompt")], by_id(analysed("api_wan2_7_i2v"))["prompt"].targets)
        # MiniMax Max: typed into a text node wired to the API node
        self.assertEqual([("14", "value")], by_id(analysed("api_minimax_h3_max_i2v"))["prompt"].targets)

    def test_found_by_name_when_comfyui_definitions_are_missing(self):
        params = by_id(analysed("sdxl_simple_example", info=P.NodeInfo()))
        self.assertEqual("text, watermark", params["negative"].default)
        self.assertTrue(params["prompt"].default.startswith("evening sunset"))


class SettingTests(unittest.TestCase):
    def test_wan_two_samplers_one_seed_one_sampler_and_the_template_knobs(self):
        params = by_id(analysed("video_wan2_2_14B_i2v"))
        self.assertEqual({"129:85", "129:86"}, {n for n, _ in params["seed"].targets})
        self.assertEqual(2, len(params["sampler_name"].targets))
        self.assertEqual("choice", params["sampler_name"].kind)
        self.assertIn("euler", params["sampler_name"].choices)
        self.assertEqual("bool", params["enable_4steps_lora"].kind)
        self.assertEqual("Steps (Enable 4steps LoRA? off)", params["steps_enable_4steps_lora_off"].label)
        self.assertEqual(20, params["steps_enable_4steps_lora_off"].default)

    def test_qwen_cfg_steps_sampler_scheduler_and_ranges_from_comfyui(self):
        params = by_id(analysed("image_qwen_image_2_1_t2i"))
        self.assertEqual(("int", 25, 1), (params["steps"].kind, params["steps"].default, params["steps"].minimum))
        self.assertEqual(("float", 1), (params["cfg"].kind, params["cfg"].default))
        self.assertEqual(100.0, params["cfg"].maximum)
        self.assertIn("scheduler", params)
        self.assertEqual("seed", params["seed"].kind)

    def test_minimax_duration_primitive_and_lightning_switch(self):
        params = by_id(analysed("video_minimax_h3_i2v"))
        self.assertEqual(("Duration", 5.0), (params["duration"].label, params["duration"].default))
        self.assertEqual("Enable Lightning LoRA", params["enable_lightning_lora"].label)

    def test_models_and_existing_loras(self):
        a = analysed("video_wan2_2_14B_i2v")
        params = by_id(a)
        self.assertEqual("models", params["unet_name"].group)
        self.assertIn("unet_name_2", params)  # the second (low-noise) model
        loras = [p for p in a.params if p.group == "loras"]
        self.assertEqual(4, len(loras))  # two LoRAs, each with its strength
        self.assertEqual(["m1", "m2"], [t.id for t in a.lora_targets])

    def test_the_photo_node_is_not_a_setting(self):
        params = analysed("video_minimax_h3_i2v").params
        self.assertFalse(any(node == "114" for p in params for node, _ in p.targets))


class ApplyTests(unittest.TestCase):
    def test_values_go_everywhere_the_setting_lives(self):
        nodes = graph("image_qwen_image_2_1_t2i")
        a = P.analyse(nodes, INFO)
        values = P.clean_values(a.params, {"prompt": "a red bicycle", "cfg": 2.5, "seed": {"mode": "fixed", "value": 77}})
        built, used = P.apply(nodes, a.params, values)
        self.assertEqual("a red bicycle", built["459:471"]["inputs"]["prompt"])
        self.assertEqual("a red bicycle", built["459:474"]["inputs"]["on_false"])
        self.assertEqual(2.5, built["459:458"]["inputs"]["cfg"])
        self.assertEqual(77, built["459:458"]["inputs"]["seed"])
        self.assertEqual(77, used["seed"])
        # The workflow itself is untouched
        self.assertNotEqual("a red bicycle", nodes["459:471"]["inputs"]["prompt"])

    def test_a_random_seed_is_one_number_for_every_seed_input_and_is_reported(self):
        nodes = graph("video_wan2_2_14B_i2v")
        a = P.analyse(nodes, INFO)
        built, used = P.apply(nodes, a.params, {}, pick_seed=lambda: 4242)
        self.assertEqual(4242, used["seed"])
        self.assertEqual({4242}, {built[n]["inputs"][i] for n, i in by_id(a)["seed"].targets})

    def test_an_empty_prompt_keeps_the_workflows_own(self):
        nodes = graph("video_minimax_h3_i2v")
        a = P.analyse(nodes, INFO)
        built, _ = P.apply(nodes, a.params, P.clean_values(a.params, {"prompt": "   "}))
        self.assertEqual(nodes["105:104"]["inputs"]["prompt"], built["105:104"]["inputs"]["prompt"])

    def test_refusals(self):
        a = analysed("image_qwen_image_2_1_t2i")
        for sent, words in (
            ({"cfg": "high"}, "number"),
            ({"steps": 0}, "at least"),
            ({"steps": 2.5}, "whole number"),
            ({"sampler_name": "nope"}, "not one of"),
            ({"unet_name.x": 1}, "no setting"),
            ({"seed": {"mode": "fixed", "value": -1}}, "whole number"),
            ({"prompt": "x" * (P.MAX_TEXT_CHARS + 1)}, "longer"),
        ):
            with self.assertRaises(P.InvalidValue) as raised:
                P.clean_values(a.params, sent)
            self.assertIn(words, str(raised.exception))


class LoraTests(unittest.TestCase):
    def test_added_after_the_loader_and_everything_moved_onto_it(self):
        nodes = graph("video_minimax_h3_i2v")
        a = P.analyse(nodes, INFO)
        loras = P.clean_loras([{"name": "style/neon.safetensors", "strength": 0.8}], ["style/neon.safetensors"], a.lora_targets)
        built, _ = P.apply(nodes, a.params, {}, loras, a.lora_targets)
        added = [i for i in built if i.startswith("og_lora_")]
        self.assertEqual(1, len(added))
        lora = built[added[0]]
        self.assertEqual("LoraLoaderModelOnly", lora["class_type"])
        self.assertEqual(["105:6", 0], lora["inputs"]["model"])
        self.assertEqual(0.8, lora["inputs"]["strength_model"])
        # Both branches of the Lightning switch, and the template's own LoRA, now get it
        self.assertEqual([added[0], 0], built["105:122"]["inputs"]["on_false"])
        self.assertEqual([added[0], 0], built["105:121"]["inputs"]["model"])
        self.assertFalse(any(v == ["105:6", 0] for n, x in built.items() if n != added[0] for v in x["inputs"].values()))

    def test_two_loras_chain_and_a_target_picks_one_model(self):
        nodes = graph("video_wan2_2_14B_i2v")
        a = P.analyse(nodes, INFO)
        names = ["wan/high.safetensors", "wan/low.safetensors", "wan/both.safetensors"]
        loras = P.clean_loras(
            [{"name": names[0], "strength": 1, "target": "m1"}, {"name": names[1], "target": "m2"}, {"name": names[2]}],
            names, a.lora_targets,
        )
        built, _ = P.apply(nodes, a.params, {}, loras, a.lora_targets)
        added = sorted(i for i in built if i.startswith("og_lora_"))
        self.assertEqual(["og_lora_1_m1", "og_lora_2_m2", "og_lora_3_m1", "og_lora_3_m2"], added)
        self.assertEqual(["og_lora_1_m1", 0], built["og_lora_3_m1"]["inputs"]["model"])
        # The analysis is not changed by applying it
        self.assertEqual("129:95", a.lora_targets[0].node_id)

    def test_a_checkpoint_gets_a_lora_on_its_clip_as_well(self):
        nodes = graph("sdxl_simple_example")
        a = P.analyse(nodes, INFO)
        loras = P.clean_loras([{"name": "x.safetensors", "target": "m1"}], ["x.safetensors"], a.lora_targets)
        built, _ = P.apply(nodes, a.params, {}, loras, a.lora_targets)
        self.assertEqual("LoraLoader", built["og_lora_1_m1"]["class_type"])
        clip_users = [n for n, x in built.items() if x["inputs"].get("clip") == ["og_lora_1_m1", 1]]
        self.assertTrue(clip_users)

    def test_refusals(self):
        a = analysed("video_minimax_h3_i2v")
        with self.assertRaises(P.InvalidValue):
            P.clean_loras([{"name": "elsewhere.safetensors"}], ["mine.safetensors"], a.lora_targets)
        with self.assertRaises(P.InvalidValue):
            P.clean_loras([{"name": "mine.safetensors", "target": "m9"}], ["mine.safetensors"], a.lora_targets)
        with self.assertRaises(P.InvalidValue):
            P.clean_loras([{"name": "mine.safetensors", "strength": 99}], ["mine.safetensors"], a.lora_targets)
        with self.assertRaises(P.InvalidValue):  # a hosted model has nothing to put a LoRA on
            P.clean_loras([{"name": "mine.safetensors"}], ["mine.safetensors"], analysed("api_wan2_7_i2v").lora_targets)


class FolderTests(unittest.TestCase):
    def test_lora_folders_limit_what_a_job_offers(self):
        workflow = wf.Workflow("w", "w", Path("w.json"), {}, options=wf.Options(lora_folders=["wan"]))
        everything = ["wan\\a.safetensors", "wan/deep/b.safetensors", "wanx/c.safetensors", "qwen/d.safetensors", "top.safetensors"]
        self.assertEqual(["wan\\a.safetensors", "wan/deep/b.safetensors"], workflow.available_loras(everything))

    def test_without_options_the_folders_of_its_own_loras_or_everything(self):
        nodes = graph("video_wan2_2_14B_i2v")
        workflow = wf.Workflow("w", "w", Path("w.json"), nodes, analysis=P.analyse(nodes, INFO))
        self.assertIsNone(workflow.lora_folders())  # its LoRAs sit at the top: no limit


if __name__ == "__main__":
    unittest.main()
