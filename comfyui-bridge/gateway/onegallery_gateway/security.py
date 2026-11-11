"""Settings, the gateway's certificate, and which phones may talk to it.

**Trust model.** The gateway makes its own certificate the first time it runs. A phone learns to
trust that one certificate when it is paired, and from then on accepts nothing else: no
certificate authority is involved, and after pairing there is no "accept anything" mode.

Pairing is a **four-digit code** printed when the gateway starts (and by the ``pair`` command).
It is good for one phone and ``pairing_minutes`` minutes, and five wrong tries close pairing
altogether until the gateway is started again or ``pair`` is run - so a guesser on the network
gets five chances in ten thousand, once. The phone pins the certificate it was shown while
pairing ("trust on first use"); the fingerprint is printed here and shown on the phone for
anyone who wants to compare. On success the phone gets its own long random token.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import socket
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

PAIRING_DIGITS = 4
PAIRING_MAX_ATTEMPTS = 5


@dataclass
class Config:
    comfy_url: str = "http://127.0.0.1:8188"
    host: str = "0.0.0.0"
    port: int = 8443
    #: The jobs folder. Relative paths are taken from the folder holding config.json, which is
    #: gateway/data - so this default is the `workflows` folder beside `gateway`.
    workflows_dir: str = "../../workflows"
    max_upload_bytes: int = 20 * 1024 * 1024
    #: Longest side, in pixels, of a photo the gateway will pass on.
    max_image_side: int = 8192
    #: Jobs that may be waiting or running at once, across every phone.
    max_active_jobs: int = 4
    randomize_seeds: bool = True
    #: Finished jobs are forgotten after this long.
    job_retention_hours: int = 72
    #: ComfyUI's input folder, if the gateway should delete the photos it uploaded once their
    #: jobs are done. Leave null to leave them there (or to use ``comfyui_dir``/input).
    comfy_input_dir: str | None = None
    #: The ComfyUI folder (the one holding ``models``, ``input``, ``output``). Model and LoRA
    #: lists are read from its ``models`` folder. The start-up script passes it.
    comfyui_dir: str | None = None
    #: Open pairing with a fresh code every time the gateway starts.
    pair_on_start: bool = True
    #: How long a pairing code works.
    pairing_minutes: int = 30
    #: Answer phones looking for the gateway on the local network (UDP, same port number).
    discovery: bool = True

    @staticmethod
    def load(data_dir: Path) -> "Config":
        path = data_dir / "config.json"
        config = Config()
        if path.is_file():
            stored = json.loads(path.read_text(encoding="utf-8"))
            for key, value in stored.items():
                if hasattr(config, key):
                    setattr(config, key, value)
        else:
            data_dir.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(asdict(config), indent=2) + "\n", encoding="utf-8")
        return config

    def workflows_path(self, data_dir: Path) -> Path:
        path = Path(self.workflows_dir)
        return path if path.is_absolute() else (data_dir / path).resolve()

    def input_dir(self) -> Path | None:
        if self.comfy_input_dir:
            return Path(self.comfy_input_dir)
        if self.comfyui_dir:
            return Path(self.comfyui_dir) / "input"
        return None

    def models_dir(self) -> Path | None:
        return Path(self.comfyui_dir) / "models" if self.comfyui_dir else None


def write_json(path: Path, value) -> None:
    """Whole-file replace, so a crash mid-write leaves the old file, not half a new one."""
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


# --- Certificate ---------------------------------------------------------------------------------


def lan_addresses() -> list[str]:
    """This computer's own IPv4 addresses, the ones a phone on the same network could reach."""
    found: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if not address.startswith("127.") and address not in found:
                found.append(address)
    except OSError:
        pass
    # The address the default route uses, which the lookup above can miss
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("10.255.255.255", 1))
            address = probe.getsockname()[0]
            if not address.startswith("127.") and address not in found:
                found.insert(0, address)
    except OSError:
        pass
    return found


def ensure_certificate(data_dir: Path) -> tuple[Path, Path]:
    """Returns (certificate, key), creating a self-signed pair on first use."""
    cert_path, key_path = data_dir / "gateway-cert.pem", data_dir / "gateway-key.pem"
    if cert_path.is_file() and key_path.is_file():
        return cert_path, key_path

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "OneGallery gateway")])
    names: list[x509.GeneralName] = [x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
    hostname = socket.gethostname()
    if hostname:
        names.append(x509.DNSName(hostname))
    names += [x509.IPAddress(ipaddress.ip_address(a)) for a in lan_addresses()]
    now = _dt.datetime.now(_dt.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - _dt.timedelta(days=1))
        .not_valid_after(now + _dt.timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName(names), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    data_dir.mkdir(parents=True, exist_ok=True)
    key_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
        )
    )
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    return cert_path, key_path


def fingerprint(cert_path: Path) -> str:
    """SHA-256 of the certificate, as 64 upper-case hex digits. This is what a phone pins."""
    import ssl

    der = ssl.PEM_cert_to_DER_cert(cert_path.read_text(encoding="ascii"))
    return hashlib.sha256(der).hexdigest().upper()


def grouped(text: str, size: int) -> str:
    return "-".join(text[i : i + size] for i in range(0, len(text), size))


# --- Devices and pairing ---------------------------------------------------------------------------


def _hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def normalise_code(code: str) -> str:
    """A typed code with anything but its digits taken out."""
    return "".join(ch for ch in code if ch.isdigit())


@dataclass
class Device:
    id: str
    name: str
    token_hash: str
    created: float
    last_seen: float = 0.0


@dataclass
class Devices:
    """Paired phones and the one pairing code that may be outstanding, kept in ``devices.json``.

    Read from disk on every use: pairing is started from a second process (the ``pair``
    command) while the server is running, and this is how the server hears about it.
    """

    path: Path
    devices: list[Device] = field(default_factory=list)
    pairing: dict | None = None

    @staticmethod
    def open(data_dir: Path) -> "Devices":
        store = Devices(data_dir / "devices.json")
        stored = read_json(store.path, {})
        store.devices = [Device(**d) for d in stored.get("devices", [])]
        store.pairing = stored.get("pairing")
        return store

    def save(self) -> None:
        write_json(self.path, {"devices": [asdict(d) for d in self.devices], "pairing": self.pairing})

    def start_pairing(self, minutes: float = 30, now: float | None = None) -> str:
        """Makes a new four-digit code (replacing any earlier one) and returns it."""
        code = f"{secrets.randbelow(10**PAIRING_DIGITS):0{PAIRING_DIGITS}d}"
        self.pairing = {
            "secret_hash": _hash(code),
            "expires": (now or time.time()) + minutes * 60,
            "attempts": 0,
        }
        self.save()
        return code

    def pairing_open(self, now: float | None = None) -> bool:
        return bool(self.pairing) and (now or time.time()) <= self.pairing["expires"]

    def finish_pairing(self, secret: str, device_name: str, now: float | None = None) -> tuple[Device, str] | None:
        """Checks a typed secret. On success returns the new device and its token - the only
        time the token exists in the clear on this side."""
        pending = self.pairing
        current = now or time.time()
        if not pending or current > pending["expires"]:
            return None
        if not hmac.compare_digest(pending["secret_hash"], _hash(normalise_code(secret))):
            pending["attempts"] = pending.get("attempts", 0) + 1
            if pending["attempts"] >= PAIRING_MAX_ATTEMPTS:
                self.pairing = None  # guessed at too often: closed until a new code is made
            self.save()
            return None
        token = secrets.token_urlsafe(32)
        device = Device(
            id=secrets.token_hex(4),
            name=(device_name or "Phone").strip()[:60] or "Phone",
            token_hash=_hash(token),
            created=current,
        )
        self.devices.append(device)
        self.pairing = None
        self.save()
        return device, token

    def authenticate(self, token: str) -> Device | None:
        wanted = _hash(token)
        for device in self.devices:
            if hmac.compare_digest(device.token_hash, wanted):
                return device
        return None

    def revoke(self, device_id: str) -> bool:
        before = len(self.devices)
        self.devices = [d for d in self.devices if d.id != device_id]
        if len(self.devices) != before:
            self.save()
            return True
        return False
