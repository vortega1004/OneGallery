Official ComfyUI workflow templates (Comfy-Org/workflow_templates, `comfyui-workflow-templates`
0.11.106, MIT licence), loaded in ComfyUI 0.45.0's editor and exported in API format with the
editor's own `graphToPrompt()` — exactly what **Workflow → Export (API)** writes, subgraphs
flattened to `outer:inner` node ids.

`object_info_subset.json` is ComfyUI 0.45.0's `/object_info` cut down to the node types these
use, tooltips removed. Model lists in it are empty: the test install had no models.

`test_params.py` checks what the gateway finds in each.
