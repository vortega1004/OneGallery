"""Plays the phone against a running gateway, start to finish. Standard library only.

    python tests/live_check.py https://192.168.1.50:8443 1234 photo.jpg [http://127.0.0.1:8188]

It finds the gateway the way the app does (a UDP broadcast question), pairs with the four-digit
code and pins the certificate it was shown, lists the jobs, runs every job with the photo given -
with settings and a LoRA where the job offers them - fetches the results, and tries the things
that must be refused. Given ComfyUI's address as well, it reads back from ComfyUI what was
actually queued and checks that the settings arrived. Exits 0 only if everything behaved.

A job that loads models which are not really there (an official template on a computer without
them) cannot finish; for those it checks that ComfyUI *accepted* the job as built and failed
only when it came to run it.
"""

import hashlib
import http.client
import json
import socket
import ssl
import sys
import time
import urllib.request
import uuid
from urllib.parse import urlparse

FIXED_SEED = 123456789


class Phone:
    def __init__(self, address: str):
        url = urlparse(address)
        self.host, self.port = url.hostname, url.port or 443
        self.pin = None  # full SHA-256 fingerprint, once paired
        self.token = None

    def _connect(self) -> tuple[http.client.HTTPSConnection, str]:
        # Verification is done here by hand, against the fingerprint: a self-signed certificate
        # has no authority to check it against, and the pin is stricter than one would be.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = http.client.HTTPSConnection(self.host, self.port, context=context, timeout=60)
        connection.connect()
        seen = hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest().upper()
        if self.pin is not None and seen != self.pin:
            connection.close()
            raise ssl.SSLError("The gateway's certificate is not the one this phone paired with")
        return connection, seen

    def pair(self, code: str) -> None:
        # Trust on first use: the certificate shown now is the only one accepted from now on
        connection, seen = self._connect()
        connection.request(
            "POST", "/v1/pair", json.dumps({"code": code, "device_name": "live_check"}),
            {"Content-Type": "application/json"},
        )
        response = connection.getresponse()
        body = json.loads(response.read())
        connection.close()
        if response.status != 200:
            raise SystemExit(f"FAIL: pairing refused: {body}")
        if body["fingerprint"] != seen:
            raise SystemExit("FAIL: the gateway reports a different fingerprint from the one presented")
        self.pin, self.token = seen, body["token"]

    def call(self, method, path, body=None, headers=None, token="default"):
        connection, _ = self._connect()
        sent = dict(headers or {})
        bearer = self.token if token == "default" else token
        if bearer:
            sent["Authorization"] = f"Bearer {bearer}"
        connection.request(method, path, body, sent)
        response = connection.getresponse()
        data = response.read()
        connection.close()
        return response.status, response.getheader("Content-Type", ""), data

    def json(self, method, path, **kwargs):
        status, _, data = self.call(method, path, **kwargs)
        return status, json.loads(data)

    def submit(self, workflow_id, image=None, prompt=None, key=None, image_name="photo.jpg", params=None, loras=None):
        boundary = uuid.uuid4().hex

        def field(name, value: bytes) -> bytes:
            return f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode() + value + b"\r\n"

        parts = [field("workflow_id", workflow_id.encode())]
        if prompt is not None:
            parts.append(field("prompt", prompt.encode()))
        if params is not None:
            parts.append(field("params", json.dumps(params).encode()))
        if loras is not None:
            parts.append(field("loras", json.dumps(loras).encode()))
        if image is not None:
            parts.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="{image_name}"\r\n'
                f"Content-Type: application/octet-stream\r\n\r\n".encode() + image + b"\r\n"
            )
        parts.append(f"--{boundary}--\r\n".encode())
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
        if key:
            headers["Idempotency-Key"] = key
        return self.json("POST", "/v1/jobs", body=b"".join(parts), headers=headers)

    def wait(self, job_id, seconds=180):
        deadline = time.time() + seconds
        seen = []
        while time.time() < deadline:
            _, job = self.json("GET", f"/v1/jobs/{job_id}")
            if not seen or seen[-1] != job["status"]:
                seen.append(job["status"])
            if job["status"] in ("completed", "failed"):
                return job, seen
            time.sleep(0.5)
        raise SystemExit(f"FAIL: job {job_id} did not finish in {seconds} s")


def discover(port: int, timeout: float = 1.5) -> list[dict]:
    """What the app's Find button does: ask the network who is a gateway."""
    answers = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        probe.settimeout(timeout)
        for target in ("255.255.255.255", "127.0.0.1"):
            try:
                probe.sendto(b"ONEGALLERY_DISCOVER 1", (target, port))
            except OSError:
                pass
        try:
            while True:
                data, (host, _) = probe.recvfrom(4096)
                answers.append({**json.loads(data), "address": host})
        except (socket.timeout, OSError):
            pass
    return answers


def queued_graph(comfy: str, job_id: str, marker) -> dict | None:
    """The graph ComfyUI was given for a job, found in its history by the job's upload name or,
    for a job without a photo, by a value only that job sent."""
    with urllib.request.urlopen(f"{comfy}/history?max_items=40", timeout=10) as response:
        history = json.loads(response.read())
    for entry in history.values():
        graph = entry["prompt"][2]
        values = [v for node in graph.values() for v in node["inputs"].values()]
        if any(isinstance(v, str) and v.startswith(f"onegallery_{job_id}") for v in values):
            return graph
        if marker is not None and marker in values:
            return graph
    return None


def main() -> int:
    if len(sys.argv) not in (4, 5):
        print(__doc__)
        return 2
    address, code, photo_path = sys.argv[1:4]
    comfy = sys.argv[4].rstrip("/") if len(sys.argv) == 5 else None
    photo = open(photo_path, "rb").read()
    failures = []

    def check(label, ok, detail=""):
        print(f"  {'ok  ' if ok else 'FAIL'}  {label}{('  - ' + str(detail)[:300]) if detail and not ok else ''}")
        if not ok:
            failures.append(label)

    phone = Phone(address)
    found = discover(phone.port)
    check("the gateway answers a phone looking for it", any(a.get("service") == "onegallery-gateway" for a in found), found)
    if found:
        print(f"        found: {found[0].get('name')} port {found[0].get('port')}, pairing open: {found[0].get('pairing')}")
    status, body = phone.json("GET", "/v1/workflows", token=None)
    check("an unpaired phone is refused", status == 401, body)

    phone.pair(code)
    check("paired with the four-digit code; certificate pinned", phone.token is not None)
    status, body = Phone(address).json("POST", "/v1/pair", body=json.dumps({"code": code}),
                                       headers={"Content-Type": "application/json"}, token=None)
    check("the pairing code cannot be used twice", status == 401, body)

    status, listing = phone.json("GET", "/v1/workflows")
    check("the job list is served", status == 200 and listing["workflows"], listing)
    check("ComfyUI is reachable from the gateway", listing.get("comfyui_reachable") is True)
    for workflow in listing["workflows"]:
        extras = "photo" if workflow["requires_image"] else "no photo"
        settings = [p["id"] for p in workflow.get("params", [])]
        print(f"        job: {workflow['name']}  [{extras}; settings: {', '.join(settings) or 'none'}]")
        loras = workflow.get("loras", {})
        if loras.get("can_add"):
            print(f"             LoRAs: folders {loras.get('folders')}, offered {loras.get('available')}, "
                  f"targets {[t['label'] for t in loras.get('targets', [])]}")
    for bad in listing.get("invalid", []):
        print(f"        not runnable: {bad['file']} - {bad['reason']}")

    for workflow in listing["workflows"]:
        params = {p["id"]: p for p in workflow.get("params", [])}
        needs_models = any(p["group"] in ("models", "loras") for p in params.values())
        image = photo if workflow["requires_image"] else None
        sent = {}
        if "prompt" in params:
            sent["prompt"] = f"typed on the phone: a lighthouse at dusk ({workflow['id']})"
        if "seed" in params:
            sent["seed"] = {"mode": "fixed", "value": FIXED_SEED}
        for name, value in (("cfg", 2.5), ("steps", 9), ("denoise", 0.75)):
            if name in params:
                sent[name] = value
        loras = workflow.get("loras", {})
        added = [{"name": loras["available"][0], "strength": 0.6}] if loras.get("can_add") and loras.get("available") else []

        key = str(uuid.uuid4())
        status, started = phone.submit(workflow["id"], image, None, key, params=sent, loras=added)
        check(f"{workflow['name']}: accepted with {sorted(sent) or 'no settings'} and {len(added)} LoRA(s)", status == 202, started)
        if status != 202:
            continue
        status, again = phone.submit(workflow["id"], image, None, key, params=sent, loras=added)
        check(f"{workflow['name']}: the same key gives the same job", status == 202 and again["job_id"] == started["job_id"], again)
        if image is not None:
            status, clash = phone.submit(workflow["id"], image + b"x", None, key, params=sent, loras=added)
            check(f"{workflow['name']}: the same key with another photo is refused", status == 409, clash)

        job, seen = phone.wait(started["job_id"])
        if needs_models:
            error = job.get("error") or {}
            check(f"{workflow['name']}: ComfyUI took the job as built ({' > '.join(seen)}; {error.get('message', '')[:80]})",
                  job["status"] == "completed" or error.get("code") == "workflow_failed", error)
        else:
            check(f"{workflow['name']}: completed ({' > '.join(seen)})", job["status"] == "completed", job.get("error"))
        if "seed" in params:
            check(f"{workflow['name']}: reports the seed it ran with", job.get("seed") == FIXED_SEED, job.get("seed"))

        if comfy:
            graph = queued_graph(comfy, started["job_id"], sent.get("prompt"))
            check(f"{workflow['name']}: found in ComfyUI's history", graph is not None)
            if graph is not None:
                values = [v for node in graph.values() for v in node["inputs"].values()]
                if "prompt" in sent:
                    check(f"{workflow['name']}: ComfyUI got the typed prompt", sent["prompt"] in values)
                if "seed" in sent:
                    check(f"{workflow['name']}: ComfyUI got the fixed seed", FIXED_SEED in values)
                for name in ("cfg", "steps", "denoise"):
                    if name in sent:
                        targets = [n for n in graph.values() if n["inputs"].get(name) == sent[name]]
                        check(f"{workflow['name']}: ComfyUI got {name} = {sent[name]}", bool(targets))
                if added:
                    lora_nodes = [n for i, n in graph.items() if i.startswith("og_lora_")]
                    check(f"{workflow['name']}: ComfyUI got the added LoRA ({len(lora_nodes)} node(s))",
                          bool(lora_nodes) and all(n["inputs"]["lora_name"] == added[0]["name"] for n in lora_nodes), lora_nodes)

        for output in job.get("outputs", []):
            status, content_type, data = phone.call("GET", output["download_path"])
            check(f"{workflow['name']}: result {output['id']} downloads as {output['media_type']} ({len(data)} bytes)",
                  status == 200 and content_type == output["media_type"] and len(data) > 100)

    first = next((w for w in listing["workflows"] if w["requires_image"]), None)
    with_steps = next((w for w in listing["workflows"] if any(p["id"] == "steps" for p in w.get("params", []))), None)
    if with_steps:
        image = photo if with_steps["requires_image"] else None
        status, body = phone.submit(with_steps["id"], image, params={"steps": 0})
        check("a setting out of range is refused", status == 400 and body["error"]["code"] == "invalid_settings", body)
        status, body = phone.submit(with_steps["id"], image, params={"not_a_setting": 1})
        check("a setting the job does not offer is refused", status == 400, body)
        status, body = phone.submit(with_steps["id"], image, params={}, loras=[{"name": "..\\..\\secrets.safetensors"}])
        check("a LoRA the job does not offer is refused", status == 400, body)
    if first:
        status, body = phone.submit(first["id"], None)
        check("a photo job without a photo is refused", status == 400, body)
        status, body = phone.submit(first["id"], b"this is not a picture")
        check("a file that is not a picture is refused", status in (415, 422), body)
    status, body = phone.submit("no-such-job", photo)
    check("an unknown job is refused", status == 404, body)
    status, body = phone.json("GET", f"/v1/jobs/{uuid.uuid4()}")
    check("an unknown job id is not found", status == 404, body)
    status, body = phone.json("GET", "/v1/workflows", token="not-a-real-token")
    check("a wrong token is refused", status == 401, body)
    status, body = phone.json("GET", "/v1/jobs")
    check("this phone's jobs are listed", status == 200 and len(body["jobs"]) >= 1, body)

    print()
    print("ALL PASSED" if not failures else f"{len(failures)} FAILED: {failures}")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
