"""Plays the phone against a running gateway, start to finish. Standard library only.

    python tests/live_check.py https://192.168.1.50:8443 ABCD-EFGH-12345-6789A photo.jpg

It pairs exactly the way the app does (checking the certificate against the code before sending
the secret, then pinning the whole fingerprint), lists the jobs, runs every job that takes a
photo with the photo given, fetches the results, and tries the things that must be refused.
Exits 0 only if everything behaved.
"""

import hashlib
import http.client
import json
import ssl
import sys
import time
import uuid
from urllib.parse import urlparse


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
        typed = "".join(ch for ch in code.upper() if ch.isalnum())
        secret, prefix = typed[:8], typed[8:]
        connection, seen = self._connect()
        if len(prefix) != 10 or not seen.startswith(prefix):
            connection.close()
            raise SystemExit("FAIL: the certificate does not match the pairing code - not sending the secret")
        connection.request(
            "POST", "/v1/pair", json.dumps({"code": secret, "device_name": "live_check"}),
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

    def submit(self, workflow_id, image=None, prompt=None, key=None, image_name="photo.jpg"):
        boundary = uuid.uuid4().hex
        parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="workflow_id"\r\n\r\n{workflow_id}\r\n'.encode()]
        if prompt is not None:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="prompt"\r\n\r\n{prompt}\r\n'.encode())
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


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    address, code, photo_path = sys.argv[1:]
    photo = open(photo_path, "rb").read()
    failures = []

    def check(label, ok, detail=""):
        print(f"  {'ok  ' if ok else 'FAIL'}  {label}{('  - ' + str(detail)) if detail and not ok else ''}")
        if not ok:
            failures.append(label)

    phone = Phone(address)
    status, body = phone.json("GET", "/v1/workflows", token=None)
    check("an unpaired phone is refused", status == 401, body)

    phone.pair(code)
    check("paired; certificate pinned", phone.token is not None)
    status, body = Phone(address).json("POST", "/v1/pair", body=json.dumps({"code": code[:9]}),
                                       headers={"Content-Type": "application/json"}, token=None)
    check("the pairing code cannot be used twice", status == 401, body)

    status, listing = phone.json("GET", "/v1/workflows")
    check("the job list is served", status == 200 and listing["workflows"], listing)
    check("ComfyUI is reachable from the gateway", listing.get("comfyui_reachable") is True)
    for workflow in listing["workflows"]:
        extras = "photo" if workflow["requires_image"] else "no photo"
        if "prompt" in workflow:
            extras += f', prompt (default: "{workflow["prompt"]["default"]}")'
        print(f"        job: {workflow['name']}  [{extras}]")
    for bad in listing.get("invalid", []):
        print(f"        not runnable: {bad['file']} - {bad['reason']}")

    for workflow in listing["workflows"]:
        if not workflow["requires_image"]:
            continue
        key = str(uuid.uuid4())
        prompt = "typed on the phone" if "prompt" in workflow else None
        status, started = phone.submit(workflow["id"], photo, prompt, key)
        check(f"{workflow['name']}: accepted", status == 202, started)
        if status != 202:
            continue
        status, again = phone.submit(workflow["id"], photo, prompt, key)
        check(f"{workflow['name']}: the same key gives the same job", status == 202 and again["job_id"] == started["job_id"], again)
        status, clash = phone.submit(workflow["id"], photo + b"x", prompt, key)
        check(f"{workflow['name']}: the same key with another photo is refused", status == 409, clash)
        job, seen = phone.wait(started["job_id"])
        check(f"{workflow['name']}: completed ({' > '.join(seen)})", job["status"] == "completed", job.get("error"))
        for output in job.get("outputs", []):
            status, content_type, data = phone.call("GET", output["download_path"])
            check(f"{workflow['name']}: result {output['id']} downloads as {output['media_type']} ({len(data)} bytes)",
                  status == 200 and content_type == output["media_type"] and len(data) > 100)

    first = next((w for w in listing["workflows"] if w["requires_image"]), None)
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
