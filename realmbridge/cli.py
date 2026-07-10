"""Command-line interface for the Bedrock Realm bridge.

Subcommands:
  login            Sign in to your Microsoft/Xbox account (device code).
  accept <code>    Accept a realm invite code so your account becomes a member.
  realms           List realms your account owns or has joined.
  setup            Download Java + ViaProxy without launching anything.
  play [<code>]    Full flow: sign in, accept the code, launch ViaProxy.
"""

from __future__ import annotations

import argparse
import sys

from . import auth, realms, viaproxy

LOCAL_PORT = 25568  # ViaProxy's default Java bind port.


def _print_realm(info: dict) -> None:
    name = info.get("name") or info.get("motd") or "(unnamed)"
    owner = info.get("owner", "?")
    state = info.get("state", "?")
    print(f"  • {name}  (owner: {owner}, state: {state})")


def cmd_login(_args) -> int:
    xbl3 = auth.get_realms_xbl3(interactive=True)
    print("Authenticated for the Realms API." if xbl3 else "Auth failed.")
    return 0


def cmd_logout(_args) -> int:
    if auth.MSA_CACHE.exists():
        auth.MSA_CACHE.unlink()
        print("Signed out. Next command will prompt for sign-in.")
    else:
        print("No cached sign-in.")
    return 0


def cmd_accept(args) -> int:
    xbl3 = auth.get_realms_xbl3(interactive=True)
    code = realms.normalize_code(args.code)
    try:
        info = realms.get_invite_info(xbl3, code)
        print("Realm for this code:")
        _print_realm(info)
    except realms.RealmsError as e:
        print(f"(info lookup: {e})")
    realms.accept_invite(xbl3, code)
    print(f"Accepted. Your account is now a member of realm code {code}.")
    return 0


def cmd_wake(args) -> int:
    xbl3 = auth.get_realms_xbl3(interactive=True)
    servers = realms.list_realms(xbl3)
    if not servers:
        print("No realms on this account.")
        return 1
    target = None
    if args.name:
        needle = args.name.lower()
        target = next((s for s in servers if needle in (s.get("name") or "").lower()), None)
        if not target:
            print(f"No realm matching '{args.name}'. Have:")
            for s in servers:
                _print_realm(s)
            return 1
    elif len(servers) == 1:
        target = servers[0]
    else:
        print("Multiple realms — pass a name:")
        for s in servers:
            _print_realm(s)
        return 1
    print(f"Waking '{target.get('name')}' (id {target['id']})... may take up to a minute.")
    info = realms.wake_realm(xbl3, target["id"])
    print(f"Realm is up. Connection info: {info}")
    return 0


def cmd_realms(_args) -> int:
    xbl3 = auth.get_realms_xbl3(interactive=True)
    servers = realms.list_realms(xbl3)
    if not servers:
        print("No realms found for this account.")
        return 0
    print(f"{len(servers)} realm(s):")
    for s in servers:
        _print_realm(s)
    return 0


def cmd_setup(_args) -> int:
    java_bin = viaproxy.ensure_java()
    jar = viaproxy.ensure_viaproxy()
    print(f"\nReady.\n  java: {java_bin}\n  jar:  {jar}")
    return 0


def _instructions() -> None:
    print(
        "\n"
        "============================================================\n"
        " ViaProxy is starting. In its window:\n"
        "  1. Accounts tab  -> add your Microsoft account (same one\n"
        "     you signed in with here) and select it.\n"
        "  2. Main tab -> click 'Realms' -> pick your friend's realm.\n"
        "  3. Set the client version to your Java version (e.g. 1.21.x).\n"
        "  4. Click 'Start'.\n"
        f"\n Then open Minecraft Java and connect to:  127.0.0.1:{LOCAL_PORT}\n"
        "============================================================\n"
    )


def cmd_play(args) -> int:
    xbl3 = auth.get_realms_xbl3(interactive=True)

    if args.code:
        code = realms.normalize_code(args.code)
        try:
            info = realms.get_invite_info(xbl3, code)
            print("Joining realm:")
            _print_realm(info)
        except realms.RealmsError as e:
            print(f"(info lookup: {e})")
        try:
            realms.accept_invite(xbl3, code)
            print(f"Membership confirmed for code {code}.")
        except realms.RealmsError as e:
            # Already a member is fine; surface anything else but keep going.
            print(f"(accept: {e})")

    servers = realms.list_realms(xbl3)
    if servers:
        print(f"\n{len(servers)} realm(s) available in ViaProxy:")
        for s in servers:
            _print_realm(s)
    else:
        print("\nWarning: no realms visible on this account yet.")

    java_bin = viaproxy.ensure_java()
    jar = viaproxy.ensure_viaproxy()
    _instructions()
    return viaproxy.launch(java_bin, jar)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="bedrock-realm",
        description="Join a Bedrock Minecraft Realm from Java on macOS via ViaProxy.",
    )
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("login", help="Sign in to Microsoft/Xbox").set_defaults(func=cmd_login)
    sub.add_parser("logout", help="Forget the cached sign-in").set_defaults(func=cmd_logout)

    ap = sub.add_parser("accept", help="Accept a realm invite code")
    ap.add_argument("code", help="Realm invite code or realms.gg link")
    ap.set_defaults(func=cmd_accept)

    sub.add_parser("realms", help="List your realms").set_defaults(func=cmd_realms)

    wp = sub.add_parser("wake", help="Start a sleeping realm server")
    wp.add_argument("name", nargs="?", help="Realm name (substring match)")
    wp.set_defaults(func=cmd_wake)
    sub.add_parser("setup", help="Download Java + ViaProxy").set_defaults(func=cmd_setup)

    pp = sub.add_parser("play", help="Sign in, accept code, launch ViaProxy")
    pp.add_argument("code", nargs="?", help="Realm invite code (optional if already a member)")
    pp.set_defaults(func=cmd_play)

    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except (auth.AuthError, realms.RealmsError) as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
