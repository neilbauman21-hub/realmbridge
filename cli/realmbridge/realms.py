"""Thin client for the Bedrock Realms API (pocket.realms.minecraft.net).

Enough of it to look up an invite code, accept it (so your account becomes a
member of the realm), and list the realms you belong to. Actually connecting to
the realm is handled by ViaProxy, not here.
"""

from __future__ import annotations

import requests

REALMS_HOST = "https://pocket.realms.minecraft.net"

# Bedrock game version sent in the Client-Version header. The Realms API rejects
# requests whose version it considers too old (errorCode 6020
# "Unknown client version"), so bump this to the current Bedrock version if
# calls start failing after a game update.
CLIENT_VERSION = "1.26.30"


class RealmsError(RuntimeError):
    pass


def _headers(xbl3: str) -> dict:
    return {
        "Authorization": xbl3,
        "Client-Version": CLIENT_VERSION,
        "User-Agent": "MCPE/UWP",
        "Content-Type": "application/json",
        "Accept": "*/*",
    }


def normalize_code(code: str) -> str:
    """Accept a bare code or a realms.gg/realms link and return the bare code."""
    code = code.strip()
    if "/" in code:
        code = code.rstrip("/").rsplit("/", 1)[-1]
    return code


def get_invite_info(xbl3: str, code: str) -> dict:
    """GET /worlds/v1/link/{code} — realm + owner info for an invite code."""
    code = normalize_code(code)
    resp = requests.get(
        f"{REALMS_HOST}/worlds/v1/link/{code}",
        headers=_headers(xbl3),
        timeout=30,
    )
    if resp.status_code == 404:
        raise RealmsError(f"Invite code '{code}' not found or expired.")
    if resp.status_code != 200:
        raise RealmsError(f"Lookup failed ({resp.status_code}): {resp.text}")
    return resp.json()


def accept_invite(xbl3: str, code: str) -> dict:
    """POST /invites/v1/link/accept/{code} — join the realm as a member.

    Returns the realm/world payload. Raises on failure; caller may treat an
    'already a member' response as success.
    """
    code = normalize_code(code)
    resp = requests.post(
        f"{REALMS_HOST}/invites/v1/link/accept/{code}",
        headers=_headers(xbl3),
        timeout=30,
    )
    if resp.status_code in (200, 204):
        try:
            return resp.json()
        except ValueError:
            return {}
    if resp.status_code == 404:
        raise RealmsError(f"Invite code '{code}' not found or expired.")
    if resp.status_code == 403:
        raise RealmsError(
            "Accept refused (403). Usually means the realm is full, you were "
            "removed, or the owner's realm subscription lapsed."
        )
    raise RealmsError(f"Accept failed ({resp.status_code}): {resp.text}")


def wake_realm(xbl3: str, realm_id: int, tries: int = 12, delay: float = 5.0) -> dict:
    """GET /worlds/{id}/join — asks the service to start the realm server.

    Returns the connection payload once the realm is up. While it is still
    starting the API answers 503; poll until it comes up or tries run out.
    """
    import time

    last = ""
    for _ in range(tries):
        resp = requests.get(
            f"{REALMS_HOST}/worlds/{realm_id}/join",
            headers=_headers(xbl3),
            timeout=30,
        )
        if resp.status_code == 200:
            return resp.json()
        last = f"{resp.status_code}: {resp.text[:200]}"
        if resp.status_code != 503:
            break
        time.sleep(delay)
    raise RealmsError(f"Realm did not come up ({last})")


def list_realms(xbl3: str) -> list[dict]:
    """GET /worlds — realms this account owns or has joined."""
    resp = requests.get(
        f"{REALMS_HOST}/worlds",
        headers=_headers(xbl3),
        timeout=30,
    )
    if resp.status_code != 200:
        raise RealmsError(f"List failed ({resp.status_code}): {resp.text}")
    return resp.json().get("servers", [])
