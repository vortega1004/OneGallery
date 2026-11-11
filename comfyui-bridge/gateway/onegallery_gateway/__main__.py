"""Command line for the OneGallery gateway.

    python -m onegallery_gateway            start the gateway
    python -m onegallery_gateway pair       make a pairing code for a phone
    python -m onegallery_gateway devices    list paired phones
    python -m onegallery_gateway revoke ID  un-pair a phone
    python -m onegallery_gateway check      look over the jobs folder and ComfyUI, then exit

Settings, the certificate and the list of phones live in ``data/`` next to this package
(``--data`` to put them elsewhere).
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import ssl
import sys
import time
from pathlib import Path

from . import workflows as wf
from .security import (
    PAIRING_FINGERPRINT_DIGITS,
    PAIRING_LIFETIME_S,
    Config,
    Devices,
    ensure_certificate,
    fingerprint,
    grouped,
    lan_addresses,
)

DEFAULT_DATA = Path(__file__).resolve().parent.parent / "data"


def _addresses(config: Config) -> list[str]:
    hosts = lan_addresses() if config.host in ("0.0.0.0", "") else [config.host]
    return [f"https://{host}:{config.port}" for host in hosts] or [f"https://127.0.0.1:{config.port}"]


def cmd_pair(data_dir: Path) -> int:
    config = Config.load(data_dir)
    cert_path, _ = ensure_certificate(data_dir)
    secret = Devices.open(data_dir).start_pairing()
    code = grouped(secret, 4) + "-" + grouped(fingerprint(cert_path)[:PAIRING_FINGERPRINT_DIGITS], 5)
    print()
    print("  In OneGallery on the phone: Settings > ComfyUI connection")
    print()
    for address in _addresses(config):
        print(f"    Address       {address}")
    print(f"    Pairing code  {code}")
    print()
    print(f"  The code works once and for {PAIRING_LIFETIME_S // 60} minutes. The gateway must be running.")
    print()
    return 0


def cmd_devices(data_dir: Path) -> int:
    devices = Devices.open(data_dir).devices
    if not devices:
        print("No phones are paired. Run:  python -m onegallery_gateway pair")
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


def _report_folder(config: Config, data_dir: Path) -> None:
    folder = config.workflows_path(data_dir)
    found, invalid = wf.load_workflows(folder)
    print(f"  Jobs folder   {folder}")
    if not folder.is_dir():
        print("                (does not exist yet)")
    for workflow in found:
        needs = []
        needs.append("photo" if workflow.requires_image else "no photo")
        if workflow.prompt_node is not None:
            needs.append("prompt")
        sends = "Send to Phone node" if workflow.output_nodes else "everything it saves"
        print(f"    + {workflow.name}   [{', '.join(needs)}; sends back {sends}]")
    for bad in invalid:
        print(f"    ! {bad.file}: {bad.reason}")
    if not found and not invalid:
        print("    (empty - export a workflow with Export (API) and put the .json here)")


def cmd_check(data_dir: Path) -> int:
    import aiohttp

    from .server import Comfy

    config = Config.load(data_dir)
    _report_folder(config, data_dir)

    async def probe() -> bool:
        async with aiohttp.ClientSession() as session:
            return await Comfy(config.comfy_url, session).reachable()

    reachable = asyncio.run(probe())
    print(f"  ComfyUI       {config.comfy_url}  {'answering' if reachable else 'NOT answering'}")
    return 0 if reachable else 1


def cmd_run(data_dir: Path) -> int:
    from aiohttp import web

    from .server import Gateway

    logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s", datefmt="%H:%M:%S")
    config = Config.load(data_dir)
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
    _report_folder(config, data_dir)
    paired = len(Devices.open(data_dir).devices)
    print(f"  Phones        {paired} paired" + ("" if paired else "  -  run `python -m onegallery_gateway pair` in another window"))
    print()
    web.run_app(gateway.app(), host=config.host, port=config.port, ssl_context=tls, print=None, access_log=None)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="onegallery_gateway", description="OneGallery gateway for ComfyUI")
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="folder for settings, certificate and state")
    commands = parser.add_subparsers(dest="command")
    commands.add_parser("run", help="start the gateway (the default)")
    commands.add_parser("pair", help="make a pairing code for a phone")
    commands.add_parser("devices", help="list paired phones")
    commands.add_parser("revoke", help="un-pair a phone").add_argument("device_id")
    commands.add_parser("check", help="look over the jobs folder and ComfyUI")
    args = parser.parse_args(argv)

    data_dir = args.data.resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    if args.command == "pair":
        return cmd_pair(data_dir)
    if args.command == "devices":
        return cmd_devices(data_dir)
    if args.command == "revoke":
        return cmd_revoke(data_dir, args.device_id)
    if args.command == "check":
        return cmd_check(data_dir)
    return cmd_run(data_dir)


if __name__ == "__main__":
    sys.exit(main())
