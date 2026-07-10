"""Microsoft / Xbox Live authentication for the Bedrock Realms API.

Flow: live.com device-code login -> Xbox user token -> XSTS token for the
Realms relying party -> an ``XBL3.0 x=<uhs>;<token>`` header the Realms API
accepts.

Uses the live.com OAuth endpoints with the Minecraft (Nintendo Switch) title
client id — the same flow prismarine-auth uses for Bedrock, since the old
Azure-app device-code route no longer exists for consumer accounts.

The refresh token is cached so you only sign in once.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import requests

# Minecraft (Nintendo Switch edition) title id. Public client, supports the
# live.com device-code flow and yields tokens valid for user.auth.xboxlive.com.
CLIENT_ID = "00000000441cc96b"
SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"

DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
TOKEN_URL = "https://login.live.com/oauth20_token.srf"

XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"

# Relying party for the Bedrock Realms service (pocket.realms.minecraft.net).
REALMS_RELYING_PARTY = "https://pocket.realms.minecraft.net/"

CACHE_DIR = Path.home() / ".bedrock-realm-bridge"
MSA_CACHE = CACHE_DIR / "msa.json"


class AuthError(RuntimeError):
    pass


def _save_msa(data: dict) -> None:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    MSA_CACHE.write_text(json.dumps(data))
    MSA_CACHE.chmod(0o600)


def _load_msa() -> dict | None:
    if MSA_CACHE.exists():
        try:
            return json.loads(MSA_CACHE.read_text())
        except json.JSONDecodeError:
            return None
    return None


def _device_login() -> dict:
    """Run the device-code flow interactively. Returns the token response."""
    resp = requests.post(
        DEVICE_CODE_URL,
        data={
            "client_id": CLIENT_ID,
            "scope": SCOPE,
            "response_type": "device_code",
        },
        timeout=30,
    )
    resp.raise_for_status()
    flow = resp.json()

    print()
    print("=== Microsoft sign-in ===")
    print(f"1. Open: {flow['verification_uri']}")
    print(f"2. Enter code: {flow['user_code']}")
    print("Waiting for you to finish sign-in...")

    interval = int(flow.get("interval", 5))
    deadline = time.time() + int(flow.get("expires_in", 900))
    while time.time() < deadline:
        time.sleep(interval)
        poll = requests.post(
            TOKEN_URL,
            data={
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                "client_id": CLIENT_ID,
                "device_code": flow["device_code"],
            },
            timeout=30,
        )
        body = poll.json()
        if poll.status_code == 200:
            print("Signed in.\n")
            return body
        err = body.get("error")
        if err == "authorization_pending":
            continue
        if err == "slow_down":
            interval += 5
            continue
        raise AuthError(f"Device login failed: {err}: {body.get('error_description')}")
    raise AuthError("Device login timed out. Try again.")


def _refresh_msa(refresh_token: str) -> dict | None:
    resp = requests.post(
        TOKEN_URL,
        data={
            "grant_type": "refresh_token",
            "client_id": CLIENT_ID,
            "scope": SCOPE,
            "refresh_token": refresh_token,
        },
        timeout=30,
    )
    if resp.status_code != 200:
        return None
    return resp.json()


def get_msa_access_token(interactive: bool = True) -> str:
    """Return a valid MSA access token, refreshing or logging in as needed."""
    cached = _load_msa()
    if cached and cached.get("refresh_token"):
        refreshed = _refresh_msa(cached["refresh_token"])
        if refreshed:
            _save_msa(refreshed)
            return refreshed["access_token"]

    if not interactive:
        raise AuthError("Not signed in. Run: bedrock-realm login")

    token = _device_login()
    _save_msa(token)
    return token["access_token"]


def _xbl_authenticate(msa_access_token: str) -> str:
    body = {
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            # live.com-flow tokens use the "t=" prefix ("d=" is for Azure-app tokens).
            "RpsTicket": f"t={msa_access_token}",
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT",
    }
    resp = requests.post(XBL_AUTH_URL, json=body, timeout=30)
    if resp.status_code != 200:
        raise AuthError(f"Xbox Live auth failed ({resp.status_code}): {resp.text}")
    return resp.json()["Token"]


def _xsts_authorize(xbl_token: str, relying_party: str) -> tuple[str, str]:
    body = {
        "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl_token]},
        "RelyingParty": relying_party,
        "TokenType": "JWT",
    }
    resp = requests.post(XSTS_AUTH_URL, json=body, timeout=30)
    if resp.status_code == 401:
        xerr = resp.json().get("XErr")
        hints = {
            2148916233: "This Microsoft account has no Xbox profile. Sign in at xbox.com once to create one.",
            2148916235: "Xbox Live is not available in this account's country/region.",
            2148916236: "This account needs adult verification (South Korea).",
            2148916238: "This is a child account. It must be added to a Family group.",
        }
        raise AuthError(hints.get(xerr, f"XSTS denied (XErr={xerr})."))
    if resp.status_code != 200:
        raise AuthError(f"XSTS auth failed ({resp.status_code}): {resp.text}")
    data = resp.json()
    token = data["Token"]
    uhs = data["DisplayClaims"]["xui"][0]["uhs"]
    return token, uhs


def get_realms_xbl3(interactive: bool = True) -> str:
    """Return the ``XBL3.0 x=<uhs>;<token>`` header value for the Realms API."""
    msa = get_msa_access_token(interactive=interactive)
    xbl = _xbl_authenticate(msa)
    token, uhs = _xsts_authorize(xbl, REALMS_RELYING_PARTY)
    return f"XBL3.0 x={uhs};{token}"
