"""
Reads the mod's socket the way the companion app would, and dumps what it sees.

The point is verifying the *mod* half without the Android app in the loop:
connect, capture the asset bundle, a HUD snapshot, and a map tile, write the
PNGs to disk, and print the field values. If a protocol change breaks
something, this says so in one run instead of after a rebuild-reinstall-relaunch
cycle on the device.

Usage:
    # against a dev client on this machine
    ./gradlew runClient -PquickPlay="New World"
    python tools/capture_protocol.py --out captures

    # against the device (the app must not also be connected on some builds)
    adb forward tcp:48291 tcp:48291
    python tools/capture_protocol.py --out captures

    # chat round trip: say it, then look for it in the captured chat lines
    python tools/capture_protocol.py --out captures --say "hello from the harness"
"""
import argparse
import base64
import json
import os
import socket
import sys
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=48291)
    ap.add_argument("--out", default="captures")
    ap.add_argument("--seconds", type=float, default=25.0,
                    help="how long to listen before giving up")
    ap.add_argument("--connect-timeout", type=float, default=180.0,
                    help="keep retrying the connect for this long, so this can "
                         "be started before the client finishes loading")
    ap.add_argument("--say", default=None,
                    help="send this as a CHAT: command once connected. The "
                         "server echoes chat back, so seeing it in the captured "
                         "chat lines below is the round-trip test for sending. "
                         "A leading / makes it a command, exactly as in game.")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    deadline = time.time() + args.connect_timeout
    sock = None
    while time.time() < deadline:
        try:
            sock = socket.create_connection((args.host, args.port), timeout=5)
            break
        except OSError:
            time.sleep(2)
    if sock is None:
        print(f"could not connect to {args.host}:{args.port}")
        return 1

    print(f"connected to {args.host}:{args.port}")

    assets = {}
    states = 0
    maps = 0
    bindings = 0
    chats = []
    first_state = None
    last_map_meta = None

    if args.say:
        sock.sendall(("CHAT:" + args.say + "\n").encode("utf-8"))
        print(f"sent CHAT:{args.say}")

    sock.settimeout(2.0)
    buf = b""
    end = time.time() + args.seconds

    while time.time() < end:
        try:
            chunk = sock.recv(65536)
        except socket.timeout:
            continue
        except OSError as e:
            print(f"socket error: {e}")
            break
        if not chunk:
            print("mod closed the connection")
            break
        buf += chunk

        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            if not line.strip():
                continue
            try:
                msg = json.loads(line)
            except ValueError as e:
                print(f"unparseable line ({len(line)} bytes): {e}")
                continue

            kind = msg.get("type")
            if kind == "asset":
                assets[msg["assetId"]] = msg.get("data")
            elif kind == "map":
                maps += 1
                last_map_meta = {k: v for k, v in msg.items() if k != "data"}
                if msg.get("data"):
                    path = os.path.join(args.out, f"map_{maps:02d}.png")
                    with open(path, "wb") as f:
                        f.write(base64.b64decode(msg["data"]))
            elif kind == "icon":
                if msg.get("data"):
                    name = msg["itemId"].replace(":", "_")
                    with open(os.path.join(args.out, f"icon_{name}.png"), "wb") as f:
                        f.write(base64.b64decode(msg["data"]))
            elif kind == "chat":
                chats.append(msg.get("segments") or [])
            elif kind == "bindings":
                # Counted rather than dumped -- it's a hundred-odd entries and
                # the app's picker is where they're actually checked. Counting
                # it here at all is what keeps it out of the state tally below.
                bindings = len(msg.get("bindings") or [])
            else:
                states += 1
                if first_state is None:
                    first_state = msg

        # Everything interesting has arrived; no reason to sit out the timer.
        # With --say, "everything" includes the echo of what we said, which is
        # the whole point of that run -- a round trip through the server takes
        # longer than the first three map tiles.
        if maps >= 3 and assets and states and (not args.say or chats):
            break

    print()
    print("=" * 60)
    print(f"HUD state lines : {states}")
    print(f"map tiles       : {maps}")
    print(f"key bindings    : {bindings}")
    print(f"chat messages   : {len(chats)}")
    print(f"assets          : {len(assets)} "
          f"({sum(1 for v in assets.values() if v)} with data)")

    missing = sorted(k for k, v in assets.items() if not v)
    if missing:
        print(f"assets MISSING  : {missing}")

    if chats:
        print()
        print("chat:")
        for segments in chats:
            # Colours are the only styling that survives the mod's flattening,
            # so they're what's worth printing beside the text -- a message that
            # arrives as one uncoloured run when it should be several is the
            # failure this catches.
            text = "".join(s.get("text", "") for s in segments)
            colors = [s.get("color") for s in segments]
            shown = ", ".join("default" if c is None else f"#{c:06X}" for c in colors)
            print(f"  {text!r}")
            print(f"      {len(segments)} run(s): {shown}")

    if first_state:
        print()
        print("first HUD state:")
        for k, v in first_state.items():
            if k == "hotbar":
                filled = [s["itemId"] for s in v if s["itemId"] != "minecraft:air"]
                print(f"  hotbar        = {len(v)} slots, {len(filled)} filled: {filled}")
            else:
                print(f"  {k:<13} = {v}")

    if last_map_meta:
        print()
        print("last map tile:")
        for k, v in last_map_meta.items():
            print(f"  {k:<13} = {v}")
        # The app derives the marker from these, so a mismatch here is a bug
        # that would show up as a marker sitting in the wrong place.
        px = last_map_meta["playerX"] - last_map_meta["originX"]
        pz = last_map_meta["playerZ"] - last_map_meta["originZ"]
        size = last_map_meta["size"]
        print(f"  marker px     = ({px:.1f}, {pz:.1f}) of {size}"
              f"{'  <-- OUT OF BOUNDS' if not (0 <= px <= size and 0 <= pz <= size) else ''}")

    print()
    print(f"wrote to {os.path.abspath(args.out)}")
    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
