#!/usr/bin/env python3
"""
mc_smoke.py — boot a Minecraft dev server via Gradle (ModDevGradle `runServer`
or Loom `runServer`), wait for the first-tick marker ("Done (Xs)!"), then
shut it down cleanly.

Used by .gitea/workflows/mc-smoke.yml. Exits 0 on clean first-tick shutdown,
1 on timeout, 2 on fatal-log match (e.g. Invalid package name, ResolutionException).

Why this script exists rather than inline shell: the MC server prints the
first-tick marker, but runServer has no flag to self-exit after it. We spawn
the gradle task, tail stdout, and terminate once we see the marker.

Cross-platform: uses `subprocess` + a signal-agnostic terminate() call, so
it runs identically on Linux + Windows runners.
"""

from __future__ import annotations

import argparse
import os
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

DEFAULT_SERVER_MARKER = r'Done \([\d.]+s\)! For help, type "help"'
# For runClient: the title screen loader reaches this marker a few seconds after
# "Setting user:" when assets are already cached. Good enough for a boot smoke.
DEFAULT_CLIENT_MARKER = r"Created: \d+x\d+x\d+ minecraft:textures/atlas"

# Log lines that indicate mcdepprovider is broken. Any match triggers exit 2
# even if the first-tick marker eventually appears.
FATAL_PATTERNS = [
    re.compile(r"Invalid package name"),
    re.compile(r"java\.lang\.module\.ResolutionException"),
    re.compile(r"java\.lang\.NoClassDefFoundError"),
    re.compile(r"java\.lang\.ClassCastException"),
    re.compile(r"mcdepprovider: .*failed to"),
]


def main() -> int:
    parser = argparse.ArgumentParser(description="Boot MC server + stop on first tick.")
    parser.add_argument("--cwd", required=True, help="Project directory with gradlew")
    parser.add_argument("--task", default=":runServer", help="Gradle task to invoke")
    parser.add_argument("--timeout", type=int, default=600, help="Hard timeout in seconds")
    parser.add_argument("--log-file", help="Copy all output here for CI artifact upload")
    parser.add_argument(
        "--marker",
        help="Regex; shutdown when matched. Defaults to the server first-tick marker; "
        "pass a client-visible marker for runClient.",
    )
    args = parser.parse_args()

    marker_re = re.compile(
        args.marker if args.marker else
        (DEFAULT_CLIENT_MARKER if "runClient" in args.task else DEFAULT_SERVER_MARKER)
    )

    project = Path(args.cwd).resolve()
    # Composite-built test-mods inherit the wrapper from the parent repo; walk up
    # to the nearest ancestor that has one so both monorepo-rooted and subproject
    # invocations work.
    wrapper_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    wrapper_dir = project
    while wrapper_dir != wrapper_dir.parent:
        if (wrapper_dir / wrapper_name).exists():
            break
        wrapper_dir = wrapper_dir.parent
    if not (wrapper_dir / wrapper_name).exists():
        print(f"error: no {wrapper_name} at or above {project}", file=sys.stderr)
        return 1

    gradlew = str(wrapper_dir / wrapper_name)
    cmd = [gradlew, args.task, "--no-daemon", "--stacktrace"]
    print(f"[mc-smoke] cwd={project}", flush=True)
    print(f"[mc-smoke] cmd={' '.join(cmd)}", flush=True)

    log_fh = open(args.log_file, "w", encoding="utf-8") if args.log_file else None

    # Accept the EULA silently — the first server boot writes eula.txt=false and
    # stops; we pre-set it to true so the boot progresses. Both ModDevGradle and
    # Loom resolve the server CWD to <project>/run/, but Loom doesn't pre-create
    # that directory until runServer actually fires — so on a cold CI cache the
    # earlier `if (run/).exists()` check fell through and eula.txt landed in the
    # project root, where MC never looks. Always create run/ first.
    run_dir = project / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")

    # On Windows, file locks are mandatory: a stale session.lock from a previous
    # boot whose MC JVM was orphaned (see _terminate) will block this boot's
    # DirectoryLock.create with "another process has locked a portion of the
    # file." Unlinking the file is cheap and only touches lock state.
    lock = run_dir / "world" / "session.lock"
    if lock.exists():
        try:
            lock.unlink()
        except OSError as e:
            print(f"[mc-smoke] warning: could not unlink stale {lock}: {e}", flush=True)

    popen_kwargs = {}
    if sys.platform == "win32":
        # Spawn into a new process group so taskkill /T can walk the tree on
        # shutdown. proc.terminate() alone only kills the gradlew wrapper and
        # leaves the forked MC server JVM orphaned — which then holds the world
        # session.lock past boot 1, breaking boot 2.
        popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        # New session -> new pgid == pid; lets _terminate killpg() the whole tree.
        popen_kwargs["start_new_session"] = True

    proc = subprocess.Popen(
        cmd,
        cwd=str(project),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=1,
        text=True,
        **popen_kwargs,
    )

    state = {"first_tick": False, "fatal": None, "start": time.monotonic()}

    def reader():
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            if log_fh:
                log_fh.write(line)
                log_fh.flush()
            if marker_re.search(line):
                state["first_tick"] = True
                print("[mc-smoke] shutdown marker seen — stopping", flush=True)
                _terminate(proc)
            for pat in FATAL_PATTERNS:
                if pat.search(line):
                    state["fatal"] = line.strip()
                    print(f"[mc-smoke] fatal log match: {line.strip()}", flush=True)
                    _terminate(proc)
                    break

    t = threading.Thread(target=reader, daemon=True)
    t.start()

    try:
        proc.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        print(f"[mc-smoke] timeout after {args.timeout}s — killing", flush=True)
        proc.kill()
        proc.wait()
        if log_fh:
            log_fh.close()
        return 1

    if log_fh:
        log_fh.close()

    if state["fatal"]:
        return 2
    if state["first_tick"]:
        return 0
    # Gradle exited without marker and no fatal — treat as failure.
    print(f"[mc-smoke] gradle exited ({proc.returncode}) without shutdown marker", flush=True)
    return 1


def _terminate(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    if sys.platform == "win32":
        # Walk the child tree: gradlew.bat -> Gradle JVM -> forked MC server JVM.
        # /F is forceful; the MC server's shutdown hooks take longer than our
        # timeout budget and we don't need a clean save for a smoke boot.
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        return
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        try:
            proc.terminate()
        except Exception as e:  # noqa: BLE001
            print(f"[mc-smoke] terminate failed: {e}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
