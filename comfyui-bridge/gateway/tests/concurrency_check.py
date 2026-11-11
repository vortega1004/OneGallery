"""Two jobs at the same moment must each get their own photo back, never each other's.

    python tests/concurrency_check.py https://127.0.0.1:8443 <pairing code> <photo A> <photo B>

Sends both photos to the first photo job that has a Send to Phone node (use "Echo test": it
returns the photo unchanged), several rounds, started together from two threads, and compares
the size of each result with the size of the photo that was sent for it. Use two photos of
different dimensions. Needs Pillow. Exits 0 only if no result ever came back for the wrong job.
"""

import io
import sys
import threading
import uuid

from PIL import Image

from live_check import Phone

ROUNDS = 6


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__)
        return 2
    address, code, path_a, path_b = sys.argv[1:]
    photos = {"A": open(path_a, "rb").read(), "B": open(path_b, "rb").read()}
    sizes = {name: Image.open(io.BytesIO(data)).size for name, data in photos.items()}
    if sizes["A"] == sizes["B"]:
        print("Use two photos of different dimensions, or a swap could not be told apart.")
        return 2

    phone = Phone(address)
    phone.pair(code)
    _, listing = phone.json("GET", "/v1/workflows")
    workflow = next(w for w in listing["workflows"] if w["id"] == "echo-test")

    wrong = []
    for round_number in range(1, ROUNDS + 1):
        started = {}
        barrier = threading.Barrier(2)

        def send(name):
            barrier.wait()  # both threads submit at the same instant
            status, body = phone.submit(workflow["id"], photos[name], None, str(uuid.uuid4()))
            started[name] = body["job_id"] if status == 202 else None

        threads = [threading.Thread(target=send, args=(name,)) for name in photos]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        for name, job_id in started.items():
            if job_id is None:
                wrong.append(f"round {round_number}: job {name} was not accepted")
                continue
            job, _ = phone.wait(job_id)
            if job["status"] != "completed":
                wrong.append(f"round {round_number}: job {name} ended {job['status']}: {job.get('error')}")
                continue
            _, _, data = phone.call("GET", job["outputs"][0]["download_path"])
            got = Image.open(io.BytesIO(data)).size
            ok = got == sizes[name]
            print(f"  round {round_number}  photo {name} {sizes[name]} -> result {got}  {'ok' if ok else 'SWAPPED'}")
            if not ok:
                wrong.append(f"round {round_number}: photo {name} came back as {got}")

    print()
    print("NO SWAPS" if not wrong else f"FAILED: {wrong}")
    return 0 if not wrong else 1


if __name__ == "__main__":
    sys.exit(main())
