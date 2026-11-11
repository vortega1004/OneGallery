"""Command line for the OneGallery gateway.

    python -m onegallery_gateway            start the gateway (prints a pairing code)
    python -m onegallery_gateway pair       a new pairing code, while the gateway is running
    python -m onegallery_gateway devices    list paired phones
    python -m onegallery_gateway revoke ID  un-pair a phone
    python -m onegallery_gateway check      look over the jobs folder and ComfyUI, then exit

Options for any of them (they override ``data/config.json`` for this run only):

    --comfyui-dir PATH   the ComfyUI folder - the one holding models/, input/ and output/
    --comfy-url URL      where ComfyUI answers (default http://127.0.0.1:8188)
    --workflows PATH     the jobs folder
    --port N             where the gateway listens (default 8443)

Settings, the certificate and the list of phones live in ``data/`` next to this package
(``--data`` to put them elsewhere).
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import socket
import ssl
import sys
import time
from pathlib import Path

from . import workflows as wf
from .security import Config, Devices, ensure_certificate, fingerprint, grouped, lan_addresses

DEFAULT_DATA = Path(__file__).resolve().parent.parent / "data"


def _addresses(config: Config) -> list[str]:
    hosts = lan_addresses() if config.host in ("0.0.0.0", "") else [config.host]
    return [f"https://{host}:{config.port}" for host in hosts] or [f"https://127.0.0.1:{config.port}"]


def _print_code(config: Config, code: str) -> None:
    line = "=" * 60
    print()
    print(f"  {line}")
    print(f"   OneGallery pairing code:   {code}")
    print(f"  {line}")
    print("   On the phone: ComfyUI connection > Find gateway (or type")
    print(f"   {_addresses(config)[0]}), then enter the code.")
    print(f"   Works once, for {config.pairing_minutes} minutes. Five wrong tries close pairing.")
    print()


def cmd_pair(config: Config, data_dir: Path) -> int:
    ensure_certificate(data_dir)
    code = Devices.open(data_dir).start_pairing(config.pairing_minutes)
    _print_code(config, code)
    print("  The gateway must be running for the phone to use it.")
    return 0


def cmd_devices(data_dir: Path) -> int:
    devices = Devices.open(data_dir).devices
    if not devices:
        print("No phones are paired. Start the gateway; it prints a pairing code.")
        return 0
    for device in devices:
        made = time.strftime("%Y-%m-%d %H:%M", time.localtime(device.created))
        print(f"  {device.id}  {device.name:<24}  paired {made}")
    return 0


def cmd_revoke(data_dir: Path, device_id: str) -> int:
    if Devices.open(data_dir).revoke(device_id):
        print(f"Un-paired {device_id}. It can no longer reach the gateway.")
        return 0
    print(f"No paired phone has the id {device_id}.")
    return 1


def _report_folder(config: Config, data_dir: Path, info=None, detailed: bool = False) -> None:
    folder = config.workflows_path(data_dir)
    found, invalid = wf.load_workflows(folder, info)
    print(f"  Jobs folder   {folder}")
    if not folder.is_dir():
        print("                (does not exist yet)")
    for workflow in found:
        params = workflow.params
        needs = ["photo" if workflow.requires_image else "no photo"]
        prompt = workflow.prompt
        needs.append("prompt" if prompt is not None else "no prompt")
        needs.append(f"{len(params)} settings")
        if workflow.analysis.lora_targets:
            folders = workflow.lora_folders()
            needs.append("LoRAs from " + (", ".join(f or "(top folder)" for f in folders) if folders else "any folder"))
        print(f"    + {workflow.name}   [{'; '.join(needs)}]")
        if detailed:
            for param in params:
                where = ", ".join(f"{n}.{i}" for n, i in param.targets[:3])
                print(f"        {param.id:<28} {param.label[:40]:<40} ({where})")
    for bad in invalid:
        print(f"    ! {bad.file}: {bad.reason}")
    if not found and not invalid:
        print("    (empty - in ComfyUI use Workflow > Export (API) and save the .json here)")


def cmd_check(config: Config, data_dir: Path) -> int:
    import aiohttp

    from .params import NodeInfo
    from .server import Comfy

    async def probe():
        async with aiohttp.ClientSession() as session:
            comfy = Comfy(config.comfy_url, session)
            if not await comfy.reachable():
                return False, None
            try:
                return True, await comfy.object_info()
            except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
                return True, None

    reachable, object_info = asyncio.run(probe())
    print(f"  ComfyUI       {config.comfy_url}  {'answering' if reachable else 'NOT answering'}")
    models = config.models_dir()
    if models is not None:
        print(f"  Models        {models}" + ("" if models.is_dir() else "  (NOT FOUND)"))
    else:
        print("  Models        (no ComfyUI folder given: --comfyui-dir)")
    _report_folder(config, data_dir, NodeInfo(object_info), detailed=True)
    return 0 if reachable else 1


def _port_taken(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind((host or "0.0.0.0", port))
            return False
        except OSError:
            return True


def cmd_run(config: Config, data_dir: Path) -> int:
    from aiohttp import web

    from .server import Gateway

    if _port_taken(config.host, config.port):
        # Started twice (ComfyUI restarted from its own menu, the start-up script run again)
        print(f"  OneGallery gateway: port {config.port} is already in use - it is probably running already.")
        return 0

    logging.basicConfig(level=logging.INFO, format="%(asctime)s  [OneGallery] %(message)s", datefmt="%H:%M:%S")
    cert_path, key_path = ensure_certificate(data_dir)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(cert_path, key_path)

    gateway = Gateway(config, data_dir, fingerprint(cert_path))
    print()
    print("  OneGallery gateway")
    for address in _addresses(config):
        print(f"  Listening     {address}")
    print(f"  Certificate   {grouped(fingerprint(cert_path)[:16], 4)}...")
    print(f"  ComfyUI       {config.comfy_url}")
    if config.models_dir() is not None:
        print(f"  Models        {config.models_dir()}" + ("" if config.models_dir().is_dir() else "  (NOT FOUND)"))
    _report_folder(config, data_dir)
    paired = len(Devices.open(data_dir).devices)
    print(f"  Phones        {paired} paired")
    if config.pair_on_start:
        _print_code(config, Devices.open(data_dir).start_pairing(config.pairing_minutes))
    print()
    web.run_app(gateway.app(), host=config.host, port=config.port, ssl_context=tls, print=None, access_log=None)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="onegallery_gateway", description="OneGallery gateway for ComfyUI")
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="folder for settings, certificate and state")
    parser.add_argument("--comfyui-dir", help="the ComfyUI folder (holds models/, input/, output/)")
    parser.add_argument("--comfy-url", help="where ComfyUI answers, e.g. http://127.0.0.1:8188")
    parser.add_argument("--workflows", help="the jobs folder")
    parser.add_argument("--port", type=int, help="where the gateway listens")
    commands = parser.add_subparsers(dest="command")
    commands.add_parser("run", help="start the gateway (the default)")
    commands.add_parser("pair", help="a new pairing code")
    commands.add_parser("devices", help="list paired phones")
    commands.add_parser("revoke", help="un-pair a phone").add_argument("device_id")
    commands.add_parser("check", help="look over the jobs folder and ComfyUI")
    args = parser.parse_args(argv)

    data_dir = args.data.resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    config = Config.load(data_dir)
    # Given on the command line (by the start-up script): for this run, not written back
    if args.comfyui_dir:
        config.comfyui_dir = str(Path(args.comfyui_dir).resolve())
    if args.comfy_url:
        config.comfy_url = args.comfy_url
    if args.workflows:
        config.workflows_dir = str(Path(args.workflows).resolve())
    if args.port:
        config.port = args.port

    if args.command == "pair":
        return cmd_pair(config, data_dir)
    if args.command == "devices":
        return cmd_devices(data_dir)
    if args.command == "revoke":
        return cmd_revoke(data_dir, args.device_id)
    if args.command == "check":
        return cmd_check(config, data_dir)
    return cmd_run(config, data_dir)


if __name__ == "__main__":
    sys.exit(main())
