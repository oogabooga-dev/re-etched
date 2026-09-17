#!/usr/bin/env python3

import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path


STARTUP_TIMEOUT_SECONDS = 300
SHUTDOWN_TIMEOUT_SECONDS = 60
READY_PATTERN = re.compile(r'Done \([^)]+\)! For help, type "help"')


def stream_output(process: subprocess.Popen[str], lines: queue.Queue[str | None]) -> None:
    assert process.stdout is not None
    for line in process.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        lines.put(line)
    lines.put(None)


def stop_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGINT)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    run_dir = root / "run"
    run_dir.mkdir(exist_ok=True)

    managed_files = {
        run_dir / "eula.txt": "eula=true\n",
        run_dir / "server.properties": (
            "level-name=ci-smoke-world\n"
            "online-mode=false\n"
            "server-port=25566\n"
            "simulation-distance=2\n"
            "spawn-protection=0\n"
            "view-distance=2\n"
        ),
    }
    backups = {path: path.read_bytes() if path.exists() else None for path in managed_files}
    world_dir = run_dir / "ci-smoke-world"
    world_existed = world_dir.exists()

    process = None
    try:
        for path, contents in managed_files.items():
            path.write_text(contents, encoding="utf-8")

        process = subprocess.Popen(
            [str(root / "gradlew"), "runServer", "--no-daemon", "--args=nogui"],
            cwd=root,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        lines: queue.Queue[str | None] = queue.Queue()
        reader = threading.Thread(target=stream_output, args=(process, lines), daemon=True)
        reader.start()

        deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
        ready = False
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                if process.poll() is not None:
                    break
                continue
            if line is None:
                break
            if READY_PATTERN.search(line):
                ready = True
                break

        if not ready:
            print("Dedicated server did not reach the ready state", file=sys.stderr)
            return 1

        assert process.stdin is not None
        process.stdin.write("stop\n")
        process.stdin.flush()
        try:
            return_code = process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            print("Dedicated server started but did not stop cleanly", file=sys.stderr)
            return 1

        if return_code != 0:
            print(f"Dedicated server exited with status {return_code}", file=sys.stderr)
            return 1

        print("Dedicated server smoke test passed")
        return 0
    finally:
        if process is not None:
            stop_process(process)
        for path, contents in backups.items():
            if contents is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(contents)
        if not world_existed:
            shutil.rmtree(world_dir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
