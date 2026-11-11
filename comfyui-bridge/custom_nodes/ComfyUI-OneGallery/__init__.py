"""OneGallery nodes for ComfyUI: where a photo sent from the phone comes in, where its prompt
comes in, and what is sent back.

These three nodes are markers as much as they are nodes. The OneGallery gateway reads a workflow
and looks for them by type:

* ``OneGalleryImageInput`` - the photo chosen on the phone is put here.
* ``OneGalleryPrompt``     - the text typed on the phone is put here. The text saved in the
                             workflow is the default, shown greyed out on the phone.
* ``OneGallerySendBack``   - only what reaches this node is sent back to the phone.

A workflow with none of them still runs through the gateway (it finds the prompt by itself, falls
back to the first Load Image node and returns every saved output), so these are a convenience,
not a requirement - but with them there is nothing to guess. A Phone Prompt node is always the
prompt the phone edits first.

Everything between them is yours.
"""

import os
import shutil

import folder_paths
import nodes

CATEGORY = "OneGallery"


class OneGalleryImageInput(nodes.LoadImage):
    """Load Image under another name, so the gateway knows which image is the phone's.

    While building a workflow it behaves exactly like Load Image: pick or upload any picture to
    test with. When a job arrives from the phone the gateway replaces the file name.
    """

    CATEGORY = CATEGORY


class OneGalleryPrompt:
    """A piece of text the phone may replace.

    Wire the output into the text input of a CLIP Text Encode node (right-click that node and
    convert its text widget to an input). What is typed here is used whenever the phone sends
    no text of its own.
    """

    CATEGORY = CATEGORY
    RETURN_TYPES = ("STRING",)
    RETURN_NAMES = ("text",)
    FUNCTION = "run"

    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "text": ("STRING", {"multiline": True, "default": "", "dynamicPrompts": False}),
            }
        }

    def run(self, text):
        return (text,)


class OneGallerySendBack:
    """Marks what goes back to the phone.

    Connect whichever applies:

    * ``images``    - an IMAGE (or batch). Saved here as PNG, one file per image.
    * ``video``     - a VIDEO from ComfyUI's own video nodes (Create Video and friends). Saved
                      here as an MP4.
    * ``filenames`` - the Filenames output of Video Helper Suite's Video Combine. That node has
                      already written the file; this one only points the gateway at it.
    """

    CATEGORY = CATEGORY
    RETURN_TYPES = ()
    FUNCTION = "run"
    OUTPUT_NODE = True

    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "filename_prefix": ("STRING", {"default": "OneGallery/phone"}),
            },
            "optional": {
                "images": ("IMAGE",),
                "video": ("VIDEO",),
                "filenames": ("VHS_FILENAMES",),
            },
            "hidden": {"prompt": "PROMPT", "extra_pnginfo": "EXTRA_PNGINFO"},
        }

    def run(self, filename_prefix, images=None, video=None, filenames=None, prompt=None, extra_pnginfo=None):
        sent = []
        previews = []

        if images is not None:
            # The stock saver does the numbering, the sub-folders and the PNG encoding
            saved = nodes.SaveImage().save_images(images, filename_prefix, prompt, extra_pnginfo)
            previews = list(saved["ui"]["images"])
            sent.extend(previews)

        if video is not None:
            sent.append(self._save_video(video, filename_prefix))

        if filenames is not None:
            sent.extend(self._existing_files(filenames, filename_prefix))

        if not sent:
            raise ValueError("Send to Phone has nothing connected: wire up images, video or filenames.")

        # "images" is what ComfyUI's own interface previews; "onegallery" is the full list
        return {"ui": {"images": previews, "onegallery": sent}}

    @staticmethod
    def _save_video(video, filename_prefix):
        width, height = 0, 0
        try:
            width, height = video.get_dimensions()
        except Exception:
            pass
        folder, name, counter, subfolder, _ = folder_paths.get_save_image_path(
            filename_prefix, folder_paths.get_output_directory(), width, height
        )
        filename = f"{name}_{counter:05}_.mp4"
        video.save_to(os.path.join(folder, filename))
        return {"filename": filename, "subfolder": subfolder, "type": "output"}

    @staticmethod
    def _existing_files(filenames, filename_prefix):
        # Video Helper Suite hands over (save_output, [absolute paths]). The list starts with a
        # PNG of the first frame (it carries the workflow) and ends with the finished video.
        paths = filenames[1] if isinstance(filenames, (list, tuple)) and len(filenames) == 2 else filenames
        media = [p for p in paths if os.path.splitext(p)[1].lower() in _MOVING]
        chosen = media[-1:] or list(paths)[-1:]

        results = []
        for path in chosen:
            located = _locate(path)
            if located is None:
                # Written somewhere ComfyUI cannot serve from: bring a copy into the output folder
                folder, name, counter, subfolder, _ = folder_paths.get_save_image_path(
                    filename_prefix, folder_paths.get_output_directory()
                )
                filename = f"{name}_{counter:05}_{os.path.splitext(path)[1]}"
                shutil.copyfile(path, os.path.join(folder, filename))
                located = {"filename": filename, "subfolder": subfolder, "type": "output"}
            results.append(located)
        return results


_MOVING = {".mp4", ".webm", ".mov", ".mkv", ".gif", ".webp"}


def _locate(path):
    """Where ``path`` sits in terms ComfyUI's /view understands, or None if it is elsewhere."""
    absolute = os.path.abspath(path)
    for kind, root in (
        ("output", folder_paths.get_output_directory()),
        ("temp", folder_paths.get_temp_directory()),
        ("input", folder_paths.get_input_directory()),
    ):
        root = os.path.abspath(root)
        if os.path.commonpath([absolute, root]) == root if _same_drive(absolute, root) else False:
            relative = os.path.relpath(absolute, root)
            subfolder = os.path.dirname(relative).replace("\\", "/")
            return {"filename": os.path.basename(relative), "subfolder": subfolder, "type": kind}
    return None


def _same_drive(a, b):
    return os.path.splitdrive(a)[0].lower() == os.path.splitdrive(b)[0].lower()


NODE_CLASS_MAPPINGS = {
    "OneGalleryImageInput": OneGalleryImageInput,
    "OneGalleryPrompt": OneGalleryPrompt,
    "OneGallerySendBack": OneGallerySendBack,
}

NODE_DISPLAY_NAME_MAPPINGS = {
    "OneGalleryImageInput": "Phone Image (OneGallery)",
    "OneGalleryPrompt": "Phone Prompt (OneGallery)",
    "OneGallerySendBack": "Send to Phone (OneGallery)",
}
