import os
import asyncio
import threading
import time
import requests
import secrets
import urllib.parse
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, WebSocket
from fastapi.responses import RedirectResponse
from fyers_apiv3.FyersWebsocket import data_ws
from fyers_apiv3 import fyersModel


app = FastAPI(title="AlgoTrader Market Data API")

SYMBOLS = [
    "NSE:NIFTY50-INDEX",
    "NSE:NIFTYBANK-INDEX",
    "BSE:SENSEX-INDEX",
]

latest_quotes = {}
lock = threading.Lock()
socket = None
history_client = None
fyers_status = "not_configured"
last_fyers_error = None

# Historical candle cache.
#
# Key:
#   (symbol, resolution, days)
#
# Value:
#   {
#       "timestamp": <unix timestamp>,
#       "data": <successful /history response>
#   }
#
# The cache is intentionally in-memory for Stage 1.
# Render restarts/redeploys will clear it, which is acceptable.
history_cache = {}
history_cache_lock = threading.Lock()

# Refresh cached historical data after 5 minutes.
HISTORY_CACHE_TTL = 5 * 60

# Serializes live FYERS token replacement so two Android requests
# cannot replace the active connection at the same time.
token_update_lock = threading.Lock()

# ------------------------------------------------------------
# FYERS OAuth browser-flow state
# ------------------------------------------------------------
#
# The OAuth state is short-lived and single-use. It prevents an
# unsolicited/replayed FYERS callback from being accepted.
fyers_oauth_state_lock = threading.Lock()
fyers_oauth_state = None
fyers_oauth_state_created_at = 0.0
FYERS_OAUTH_STATE_TTL = 10 * 60

# Existing FYERS -100 app uses the FYERS-owned redirect URI.
# This must exactly match the redirect URI registered in the FYERS app.
FYERS_OAUTH_REDIRECT_URI_DEFAULT = (
    "https://trade.fyers.in/api-login/redirect-uri/index.html"
)

# The old -100 flow does not redirect back into the Android app.
# Android therefore receives the temporary auth_code manually.
ALTRIXA_OAUTH_CALLBACK_URI = "altrixa://fyers-auth"


def on_message(message):
    symbol = message.get("symbol")

    if symbol:
        with lock:
            latest_quotes[symbol] = {
                **message,
                "received_at": datetime.now(timezone.utc).isoformat(),
            }


AUTH_ERROR_CODES = {-8, -15, -16, -17, -99}

# REST authentication is authoritative.
# WebSocket "connected" only means the socket connection exists.
AUTH_REQUIRED_STATE = "auth_required"
AUTHENTICATED_STATE = "authenticated"

RENDER_API_BASE_URL = "https://api.render.com/v1"
RENDER_TOKEN_ENV_KEY = "FYERS_ACCESS_TOKEN"

def classify_fyers_error(error):
    code = None

    if isinstance(error, dict):
        code = error.get("code")
        try:
            code = int(code)
        except (TypeError, ValueError):
            pass

    return code


def on_error(error):
    global fyers_status, last_fyers_error

    print("FYERS ERROR:", error, flush=True)
    last_fyers_error = str(error)

    code = classify_fyers_error(error)

    if code in AUTH_ERROR_CODES:
        fyers_status = AUTH_REQUIRED_STATE

        # Prevent stale WebSocket prices from being presented as live
        # market data after FYERS authentication has expired.
        with lock:
            latest_quotes.clear()

        print(
            "FYERS authentication required. "
            "Generate a fresh access token through FYERS OAuth.",
            flush=True,
        )
    else:
        fyers_status = "error"


def on_close(message):
    global fyers_status, last_fyers_error

    print("FYERS CLOSED:", message, flush=True)

    # Never overwrite an authentication failure with "closed".
    if fyers_status == AUTH_REQUIRED_STATE:
        return

    fyers_status = "closed"

    if message:
        last_fyers_error = str(message)


def on_open():
    global fyers_status, last_fyers_error

    # WebSocket connectivity does NOT prove REST authentication.
    # Do not clear an existing authentication failure here.
    if fyers_status != AUTH_REQUIRED_STATE:
        fyers_status = "connected"

    print("FYERS WebSocket connected", flush=True)

    socket.subscribe(
        symbols=SYMBOLS,
        data_type="SymbolUpdate",
    )

    print("Subscribed:", SYMBOLS, flush=True)

    socket.keep_running()


def mark_rest_auth_success():
    global fyers_status, last_fyers_error

    fyers_status = AUTHENTICATED_STATE
    last_fyers_error = None


def mark_rest_auth_failure(response):
    global fyers_status, last_fyers_error

    code = classify_fyers_error(response)

    if code in AUTH_ERROR_CODES:
        fyers_status = AUTH_REQUIRED_STATE
        last_fyers_error = (
            f"FYERS authentication required (code {code})"
        )

        print(
            f"FYERS REST authentication failed: code={code}",
            flush=True,
        )

        return True

    return False


def connect_fyers(access_token, close_existing=False):
    global socket, history_client, fyers_status, last_fyers_error

    app_id = os.getenv("FYERS_APP_ID")

    if not app_id:
        fyers_status = "not_configured"
        last_fyers_error = "FYERS_APP_ID is not configured"
        print(last_fyers_error, flush=True)
        return False

    if not access_token:
        fyers_status = AUTH_REQUIRED_STATE
        last_fyers_error = "FYERS_ACCESS_TOKEN is not configured"
        print(last_fyers_error, flush=True)
        return False

    if close_existing and socket is not None:
        try:
            socket.close()
        except Exception:
            pass

        socket = None

    # REST client uses the raw access token.
    new_history_client = fyersModel.FyersModel(
        client_id=app_id,
        token=access_token,
        log_path=""
    )

    # FYERS Data WebSocket authentication requires:
    # APP_ID:ACCESS_TOKEN
    websocket_token = f"{app_id}:{access_token}"

    new_socket = data_ws.FyersDataSocket(
        access_token=websocket_token,
        log_path="",
        litemode=False,
        write_to_file=False,
        reconnect=True,
        on_connect=on_open,
        on_close=on_close,
        on_error=on_error,
        on_message=on_message,
    )

    history_client = new_history_client
    socket = new_socket

    fyers_status = "connecting"
    last_fyers_error = None

    print(
        "Starting FYERS connection using configured OAuth access token.",
        flush=True,
    )

    threading.Thread(
        target=new_socket.connect,
        daemon=True,
    ).start()

    return True


def validate_fyers_access_token(access_token):
    """
    Validate a candidate FYERS access token without disturbing the
    currently active connection.

    A lightweight quotes request is used because successful REST
    authentication is authoritative for this service.
    """
    app_id = os.getenv("FYERS_APP_ID")

    if not app_id:
        return False, "FYERS_APP_ID is not configured"

    if not access_token:
        return False, "Access token is empty"

    try:
        candidate = fyersModel.FyersModel(
            client_id=app_id,
            token=access_token,
            log_path=""
        )

        response = candidate.quotes(
            data={
                "symbols": ",".join(SYMBOLS),
            }
        )

        if isinstance(response, dict) and response.get("s") == "ok":
            return True, None

        code = classify_fyers_error(response)

        if code is not None:
            return False, f"FYERS rejected access token (code {code})"

        return False, "FYERS rejected access token"

    except Exception as error:
        return False, f"FYERS token validation failed: {error}"


def persist_fyers_access_token_to_render(access_token):
    """
    Persist the validated FYERS access token to Render and trigger
    a deployment so future restarts use the new token.
    """
    render_api_key = os.getenv("RENDER_API_KEY")
    service_id = os.getenv("RENDER_SERVICE_ID")

    if not render_api_key:
        return False, "RENDER_API_KEY is not configured"

    if not service_id:
        return False, "RENDER_SERVICE_ID is not configured"

    env_url = (
        f"{RENDER_API_BASE_URL}/services/"
        f"{service_id}/env-vars/{RENDER_TOKEN_ENV_KEY}"
    )

    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "Authorization": f"Bearer {render_api_key}",
    }

    try:
        env_response = requests.put(
            env_url,
            headers=headers,
            json={"value": access_token},
            timeout=15,
        )

        if not env_response.ok:
            return (
                False,
                "Render environment update failed "
                f"(HTTP {env_response.status_code})",
            )

        deploy_url = (
            f"{RENDER_API_BASE_URL}/services/"
            f"{service_id}/deploys"
        )

        deploy_response = requests.post(
            deploy_url,
            headers=headers,
            json={
                "clearCache": "do_not_clear",
                "deployMode": "build_and_deploy",
            },
            timeout=15,
        )

        if not deploy_response.ok:
            return (
                False,
                "Render deploy trigger failed "
                f"(HTTP {deploy_response.status_code})",
            )

        return True, None

    except requests.RequestException as error:
        return False, f"Render API request failed: {error}"


def replace_fyers_access_token(access_token):
    """
    Atomically validate and switch the running FYERS connection.

    The current token/client/socket remain untouched when validation
    fails. On success the running REST client and WebSocket are replaced.
    """
    global fyers_status, last_fyers_error

    with token_update_lock:
        valid, error_message = validate_fyers_access_token(access_token)

        if not valid:
            return False, error_message

        app_id = os.getenv("FYERS_APP_ID")

        try:
            # Only after validation succeeds do we modify live state.
            os.environ["FYERS_ACCESS_TOKEN"] = access_token

            # Remove stale market prices immediately.
            with lock:
                latest_quotes.clear()

            # In-memory historical responses were obtained under the
            # previous authentication session. Force fresh data.
            with history_cache_lock:
                history_cache.clear()

            connected = connect_fyers(
                access_token,
                close_existing=True,
            )

            if not connected:
                return False, "FYERS connection could not be started"

            fyers_status = "connecting"
            last_fyers_error = None

            print(
                "FYERS access token replaced successfully.",
                flush=True,
            )

            persisted, persistence_error = (
                persist_fyers_access_token_to_render(access_token)
            )

            if not persisted:
                print(
                    "FYERS token accepted, but Render persistence "
                    f"failed: {persistence_error}",
                    flush=True,
                )
                return True, (
                    "Token accepted and FYERS reconnect started, "
                    f"but Render persistence failed: {persistence_error}"
                )

            print(
                "FYERS access token persisted to Render; "
                "deploy triggered.",
                flush=True,
            )

            return True, None

        except Exception as error:
            fyers_status = AUTH_REQUIRED_STATE
            last_fyers_error = str(error)

            print(
                f"FYERS token replacement failed: {error}",
                flush=True,
            )

            return False, str(error)


def auth_manager():
    global fyers_status, last_fyers_error

    # Use the current FYERS OAuth access token configured in Render.
    access_token = os.getenv("FYERS_ACCESS_TOKEN")

    if not os.getenv("FYERS_APP_ID"):
        fyers_status = "not_configured"
        last_fyers_error = "FYERS_APP_ID is not configured"
        print(last_fyers_error, flush=True)
        return

    if not access_token:
        fyers_status = "auth_expired"
        last_fyers_error = "FYERS_ACCESS_TOKEN is not configured"
        print(
            "FYERS access token is missing. "
            "Generate a fresh access token through FYERS OAuth.",
            flush=True,
        )
        return

    connect_fyers(access_token)


@app.on_event("startup")
def startup():
    threading.Thread(
        target=auth_manager,
        daemon=True,
    ).start()


@app.get("/health")
def health():
    token_configured = bool(
        os.getenv("FYERS_ACCESS_TOKEN")
    )

    return {
        "status": "ok",
        "service": "AlgoTrader Market Data API",
        "fyers": fyers_status,
        "auth_mode": "oauth_access_token",
        "access_token_configured": token_configured,
        "authenticated": fyers_status == AUTHENTICATED_STATE,
        "auth_required": fyers_status == AUTH_REQUIRED_STATE,
        "last_error": last_fyers_error,
    }



@app.get("/auth/fyers/start")
def start_fyers_oauth():
    """
    Start the server-side FYERS OAuth browser flow.

    No FYERS secret or access token is exposed to Android.
    """
    global fyers_oauth_state, fyers_oauth_state_created_at

    app_id = os.getenv("FYERS_APP_ID")
    # For the existing -100 FYERS app, always use the registered
    # FYERS-owned redirect URI. Do not use the Render callback.
    redirect_uri = FYERS_OAUTH_REDIRECT_URI_DEFAULT

    if not app_id:
        return {
            "status": "error",
            "message": "FYERS_APP_ID is not configured",
        }

    state = secrets.token_urlsafe(32)

    with fyers_oauth_state_lock:
        fyers_oauth_state = state
        fyers_oauth_state_created_at = time.time()

    params = {
        "client_id": app_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "state": state,
    }

    auth_url = (
        "https://api-t1.fyers.in/api/v3/generate-authcode?"
        + urllib.parse.urlencode(params)
    )

    return {
        "status": "ok",
        "auth_url": auth_url,
        "state": state,
    }


@app.post("/auth/fyers/exchange-code")
def exchange_fyers_auth_code(payload: dict):
    """
    Exchange a temporary FYERS authorization code generated by the
    existing -100 app.

    Android sends only:
      - auth_code
      - state

    FYERS App Secret and the resulting access token remain server-side.
    """

    global fyers_oauth_state, fyers_oauth_state_created_at

    supplied_state = str(payload.get("state") or "").strip()
    auth_code_value = str(payload.get("auth_code") or "").strip()

    if not supplied_state:
        return {
            "status": "error",
            "message": "OAuth state is required",
        }

    if not auth_code_value:
        return {
            "status": "error",
            "message": "FYERS authorization code is required",
        }

    # Validate the short-lived state before exchanging.
    # Keep it available if the user enters a bad/expired auth_code so
    # the current renewal session can be retried without another login.
    with fyers_oauth_state_lock:
        expected_state = fyers_oauth_state
        created_at = fyers_oauth_state_created_at

    if (
        not expected_state
        or supplied_state != expected_state
        or time.time() - created_at > FYERS_OAUTH_STATE_TTL
    ):
        return {
            "status": "error",
            "message": "OAuth session expired or invalid. Start FYERS authentication again.",
        }

    app_id = os.getenv("FYERS_APP_ID")
    secret_key = os.getenv("FYERS_APP_SECRET")

    if not app_id or not secret_key:
        print(
            "FYERS manual OAuth exchange unavailable: "
            "FYERS_APP_ID or FYERS_APP_SECRET is not configured.",
            flush=True,
        )
        return {
            "status": "error",
            "message": "FYERS authentication is not configured on the server.",
        }

    # Must exactly match the redirect URI registered for the -100 app.
    redirect_uri = FYERS_OAUTH_REDIRECT_URI_DEFAULT

    try:
        session = fyersModel.SessionModel(
            client_id=app_id,
            secret_key=secret_key,
            redirect_uri=redirect_uri,
            response_type="code",
            grant_type="authorization_code",
        )

        session.set_token(auth_code_value)
        response = session.generate_token()

        print(
            "FYERS manual OAuth token exchange completed: "
            f"status={response.get('s')} "
            f"code={response.get('code')}",
            flush=True,
        )

        access_token = response.get("access_token")

        if not access_token:
            return {
                "status": "error",
                "message": str(
                    response.get("message") or "FYERS token exchange failed"
                ),
            }

        success, error_message = replace_fyers_access_token(access_token)

        if not success:
            return {
                "status": "error",
                "message": error_message or "FYERS token replacement failed",
            }

        # Consume the OAuth state only after the new access token has
        # been successfully accepted by ALTRIXA.
        with fyers_oauth_state_lock:
            if fyers_oauth_state == supplied_state:
                fyers_oauth_state = None
                fyers_oauth_state_created_at = 0.0

        return {
            "status": "ok",
            "message": "FYERS authentication renewed successfully.",
            "auth_required": False,
        }

    except Exception as error:
        print(
            f"FYERS manual OAuth exchange failed: {error}",
            flush=True,
        )

        return {
            "status": "error",
            "message": "FYERS authorization code exchange failed.",
        }


@app.get("/auth/fyers/callback")
def fyers_oauth_callback(
    auth_code: str = "",
    code: str = "",
    state: str = "",
    s: str = "",
    message: str = "",
):
    """
    Receive the FYERS OAuth callback, exchange the authorization code
    server-side, replace the active access token, and redirect the
    browser back into ALTRIXA.

    The FYERS App Secret and access token never leave the backend.
    """
    global fyers_oauth_state, fyers_oauth_state_created_at

    callback_state = str(state or "").strip()
    auth_code_value = str(auth_code or code or "").strip()

    with fyers_oauth_state_lock:
        expected_state = fyers_oauth_state
        created_at = fyers_oauth_state_created_at

        # Consume the state immediately so it cannot be replayed.
        fyers_oauth_state = None
        fyers_oauth_state_created_at = 0.0

    if (
        not expected_state
        or not callback_state
        or callback_state != expected_state
        or time.time() - created_at > FYERS_OAUTH_STATE_TTL
    ):
        return RedirectResponse(
            url=ALTRIXA_OAUTH_CALLBACK_URI
            + "?status=error&reason=invalid_state"
        )

    if not auth_code_value:
        reason = urllib.parse.quote(
            str(message or s or "authorization_code_missing")
        )
        return RedirectResponse(
            url=ALTRIXA_OAUTH_CALLBACK_URI
            + f"?status=error&reason={reason}"
        )

    app_id = os.getenv("FYERS_APP_ID")
    secret_key = os.getenv("FYERS_APP_SECRET")
    redirect_uri = FYERS_OAUTH_REDIRECT_URI_DEFAULT

    if not app_id or not secret_key:
        print(
            "FYERS OAuth exchange unavailable: "
            "FYERS_APP_ID or FYERS_APP_SECRET is not configured.",
            flush=True,
        )
        return RedirectResponse(
            url=ALTRIXA_OAUTH_CALLBACK_URI
            + "?status=error&reason=server_auth_not_configured"
        )

    try:
        session = fyersModel.SessionModel(
            client_id=app_id,
            secret_key=secret_key,
            redirect_uri=redirect_uri,
            response_type="code",
            grant_type="authorization_code",
        )

        session.set_token(auth_code_value)
        response = session.generate_token()

        print(
            "FYERS OAuth token exchange completed: "
            f"status={response.get('s')} "
            f"code={response.get('code')}",
            flush=True,
        )

        access_token = response.get("access_token")

        if not access_token:
            reason = urllib.parse.quote(
                str(response.get("message") or "token_exchange_failed")
            )
            return RedirectResponse(
                url=ALTRIXA_OAUTH_CALLBACK_URI
                + f"?status=error&reason={reason}"
            )

        success, error_message = replace_fyers_access_token(access_token)

        if not success:
            reason = urllib.parse.quote(
                str(error_message or "token_replacement_failed")
            )
            return RedirectResponse(
                url=ALTRIXA_OAUTH_CALLBACK_URI
                + f"?status=error&reason={reason}"
            )

        return RedirectResponse(
            url=ALTRIXA_OAUTH_CALLBACK_URI
            + "?status=success"
        )

    except Exception as error:
        print(
            f"FYERS OAuth exchange failed: {error}",
            flush=True,
        )

        reason = urllib.parse.quote(str(error))
        return RedirectResponse(
            url=ALTRIXA_OAUTH_CALLBACK_URI
            + f"?status=error&reason={reason}"
        )


@app.post("/auth/update-token")
def update_auth_token(payload: dict):
    """
    Replace the active FYERS OAuth access token.

    Authentication for this endpoint is intentionally handled by a
    server-side ALTRIXA_TOKEN_UPDATE_KEY. The key is never embedded
    in the Android application.
    """
    update_key = os.getenv("ALTRIXA_TOKEN_UPDATE_KEY")

    if not update_key:
        return {
            "status": "error",
            "message": "Token update endpoint is not configured",
        }

    supplied_key = str(payload.get("update_key") or "")
    new_token = str(payload.get("access_token") or "").strip()

    if not supplied_key or supplied_key != update_key:
        return {
            "status": "error",
            "message": "Unauthorized token update request",
        }

    if not new_token:
        return {
            "status": "error",
            "message": "Access token is required",
        }

    success, error_message = replace_fyers_access_token(new_token)

    if not success:
        return {
            "status": "error",
            "message": error_message or "Token replacement failed",
            "auth_required": fyers_status == AUTH_REQUIRED_STATE,
        }

    response = {
        "status": "ok",
        "message": (
            "FYERS access token accepted; reconnecting "
            "and persisting to Render"
        ),
        "fyers": fyers_status,
        "authenticated": False,
        "auth_required": False,
    }

    if error_message:
        response["persistence_warning"] = error_message

    return response


@app.get("/auth/status")
def auth_status():
    token_configured = bool(
        os.getenv("FYERS_ACCESS_TOKEN")
    )

    return {
        "status": "ok",
        "fyers": fyers_status,
        "authenticated": fyers_status == AUTHENTICATED_STATE,
        "auth_required": fyers_status == AUTH_REQUIRED_STATE,
        "access_token_configured": token_configured,
        "auth_mode": "oauth_access_token",
        "message": (
            "FYERS authentication required"
            if fyers_status == AUTH_REQUIRED_STATE
            else "FYERS authentication state available"
        ),
    }


# ============================================================
# F&O market-data boundary
# ============================================================
#
# These endpoints intentionally keep F&O discovery separate from
# the existing index /history endpoint.
#
# No futures symbol, expiry, lot size, margin, or contract is
# fabricated here. The exact F&O symbol must come from FYERS.
# ============================================================

@app.get("/futures/expiry-dates")
def futures_expiry_dates(
    symbol: str,
    range_from: str = "",
    range_to: str = "",
    date_format: int = 1,
):
    """
    Return FYERS expiry dates for an underlying/index symbol.

    The symbol is passed explicitly to FYERS. No contract is
    inferred or fabricated by the backend.
    """
    if not history_client:
        return {
            "status": "error",
            "message": "FYERS F&O client not ready",
        }

    if not symbol.strip():
        return {
            "status": "error",
            "message": "Underlying symbol is required",
        }

    if date_format not in {0, 1}:
        return {
            "status": "error",
            "message": "Unsupported date_format",
        }

    data = {
        "symbol": symbol.strip(),
        "date_format": date_format,
    }

    if range_from:
        data["range_from"] = range_from

    if range_to:
        data["range_to"] = range_to

    try:
        response = history_client.expiry_dates(data=data)

        if not isinstance(response, dict):
            return {
                "status": "error",
                "message": "Invalid FYERS expiry-dates response",
            }

        if response.get("s") != "ok":
            mark_rest_auth_failure(response)
            return {
                "status": "error",
                "fyers": fyers_status,
                "response": response,
            }

        mark_rest_auth_success()

        return {
            "status": "ok",
            "symbol": symbol.strip(),
            "response": response,
        }

    except Exception as error:
        return {
            "status": "error",
            "message": str(error),
        }


@app.get("/futures/chain")
def futures_chain(
    symbol: str,
):
    """
    Return the FYERS futures chain for an explicit underlying symbol.

    The FYERS response is intentionally preserved rather than
    reverse-engineering contract fields into local defaults.
    """
    if not history_client:
        return {
            "status": "error",
            "message": "FYERS F&O client not ready",
        }

    if not symbol.strip():
        return {
            "status": "error",
            "message": "Underlying symbol is required",
        }

    try:
        response = history_client.futures_chain(
            data={
                "symbol": symbol.strip(),
            }
        )

        if not isinstance(response, dict):
            return {
                "status": "error",
                "message": "Invalid FYERS futures-chain response",
            }

        if response.get("s") != "ok":
            mark_rest_auth_failure(response)
            return {
                "status": "error",
                "fyers": fyers_status,
                "response": response,
            }

        mark_rest_auth_success()

        return {
            "status": "ok",
            "symbol": symbol.strip(),
            "response": response,
        }

    except Exception as error:
        return {
            "status": "error",
            "message": str(error),
        }


@app.get("/futures/history")
def futures_history(
    symbol: str,
    resolution: str = "5",
    range_from: str = "",
    range_to: str = "",
    date_format: int = 0,
    include_oi: int = 0,
    include_greeks: int = 0,
):
    """
    Fetch historical candles for an exact FYERS F&O symbol.

    IMPORTANT:
    - symbol must be an actual FYERS F&O contract symbol.
    - The backend does not construct contract symbols.
    - Index /history candles are never substituted.
    - No lot size, expiry, margin, or contract metadata is inferred.
    """
    if not history_client:
        return {
            "status": "error",
            "message": "FYERS F&O client not ready",
        }

    exact_symbol = symbol.strip()

    if not exact_symbol:
        return {
            "status": "error",
            "message": "Exact FYERS F&O symbol is required",
        }

    if resolution not in {"1", "5", "15", "30", "60", "120", "240", "D"}:
        return {
            "status": "error",
            "message": "Unsupported resolution",
        }

    if date_format not in {0, 1}:
        return {
            "status": "error",
            "message": "Unsupported date_format",
        }

    if include_oi not in {0, 1}:
        return {
            "status": "error",
            "message": "include_oi must be 0 or 1",
        }

    if include_greeks not in {0, 1}:
        return {
            "status": "error",
            "message": "include_greeks must be 0 or 1",
        }

    if not range_from or not range_to:
        return {
            "status": "error",
            "message": (
                "range_from and range_to are required for "
                "F&O historical data"
            ),
        }

    data = {
        "symbol": exact_symbol,
        "resolution": resolution,
        "date_format": date_format,
        "range_from": range_from,
        "range_to": range_to,
        "include_oi": include_oi,
        "include_greeks": include_greeks,
    }

    try:
        response = history_client.fno_historical_data(data=data)

        if not isinstance(response, dict):
            return {
                "status": "error",
                "message": "Invalid FYERS F&O historical response",
            }

        if response.get("s") != "ok":
            mark_rest_auth_failure(response)
            return {
                "status": "error",
                "fyers": fyers_status,
                "response": response,
            }

        mark_rest_auth_success()

        return {
            "status": "ok",
            "symbol": exact_symbol,
            "resolution": resolution,
            "date_format": date_format,
            "range_from": range_from,
            "range_to": range_to,
            "include_oi": include_oi,
            "include_greeks": include_greeks,
            "response": response,
        }

    except Exception as error:
        return {
            "status": "error",
            "message": str(error),
        }


@app.get("/history")
def history(
    symbol: str = "NSE:NIFTY50-INDEX",
    resolution: str = "5",
    days: int = 5,
):
    allowed_symbols = set(SYMBOLS)

    if symbol not in allowed_symbols:
        return {
            "status": "error",
            "message": "Unsupported symbol",
        }

    # Supported app resolutions:
    # 5m / 15m / 30m / 1h / 1D
    #
    # FYERS resolution values:
    # 5 / 15 / 30 / 60 / D
    if resolution not in {"5", "15", "30", "60", "D"}:
        return {
            "status": "error",
            "message": "Unsupported resolution",
        }

    if not history_client:
        return {
            "status": "error",
            "message": "FYERS history client not ready",
        }

    days = max(1, min(days, 365))

    # --------------------------------------------------------
    # Backend historical cache
    # --------------------------------------------------------

    cache_key = (symbol, resolution, days)
    now = time.time()

    with history_cache_lock:
        cached = history_cache.get(cache_key)

    if cached:
        cache_age = now - cached["timestamp"]

        if cache_age < HISTORY_CACHE_TTL:
            print(
                f"[history-cache] HIT "
                f"symbol={symbol} resolution={resolution} "
                f"days={days} age={cache_age:.1f}s",
                flush=True,
            )

            return cached["data"]

        print(
            f"[history-cache] EXPIRED "
            f"symbol={symbol} resolution={resolution} "
            f"days={days} age={cache_age:.1f}s",
            flush=True,
        )
    else:
        print(
            f"[history-cache] MISS "
            f"symbol={symbol} resolution={resolution} days={days}",
            flush=True,
        )

    # --------------------------------------------------------
    # FYERS historical-data chunking
    # --------------------------------------------------------

    if resolution == "D":
        chunk_days = 365
    elif resolution == "60":
        chunk_days = 30
    elif resolution == "30":
        chunk_days = 15
    elif resolution == "15":
        chunk_days = 10
    else:  # 5 minute
        chunk_days = 5

    end_date = datetime.now(timezone.utc)
    start_date = end_date - timedelta(days=days)

    all_candles = {}

    try:
        chunk_start = start_date

        while chunk_start < end_date:
            chunk_end = min(
                chunk_start + timedelta(days=chunk_days),
                end_date,
            )

            data = {
                "symbol": symbol,
                "resolution": resolution,
                "date_format": "0",
                "range_from": str(int(chunk_start.timestamp())),
                "range_to": str(int(chunk_end.timestamp())),
                "cont_flag": "1",
            }

            print(
                f"[history-cache] FYERS fetch "
                f"symbol={symbol} resolution={resolution} "
                f"from={data['range_from']} "
                f"to={data['range_to']}",
                flush=True,
            )

            response = history_client.history(data=data)

            if response.get("s") != "ok":
                mark_rest_auth_failure(response)

                return {
                    "status": "error",
                    "fyers": fyers_status,
                    "response": response,
                    "failed_range_from": data["range_from"],
                    "failed_range_to": data["range_to"],
                }

            mark_rest_auth_success()

            for candle in response.get("candles", []):
                if candle and len(candle) >= 6:
                    all_candles[int(candle[0])] = candle

            # Move forward without leaving a gap.
            chunk_start = chunk_end

        candles = [
            all_candles[timestamp]
            for timestamp in sorted(all_candles)
        ]

        result = {
            "status": "ok",
            "symbol": symbol,
            "resolution": resolution,
            "days": days,
            "candles": candles,
        }

        # ----------------------------------------------------
        # Store only successful responses in cache.
        # ----------------------------------------------------

        with history_cache_lock:
            history_cache[cache_key] = {
                "timestamp": time.time(),
                "data": result,
            }

        print(
            f"[history-cache] STORED "
            f"symbol={symbol} resolution={resolution} "
            f"days={days} candles={len(candles)}",
            flush=True,
        )

        return result

    except Exception as error:
        return {
            "status": "error",
            "message": str(error),
        }


@app.get("/quotes")
def quotes():
    with lock:
        data = dict(latest_quotes)

    return {
        "status": "ok",
        "fyers": fyers_status,
        "quotes": data,
    }


@app.websocket("/ws/quotes")
async def quotes_websocket(websocket: WebSocket):
    await websocket.accept()

    try:
        while True:
            with lock:
                data = dict(latest_quotes)

            await websocket.send_json({
                "status": "ok",
                "fyers": fyers_status,
                "quotes": data,
            })

            await asyncio.sleep(0.25)

    except Exception as error:
        print(f"Android WebSocket disconnected: {error}", flush=True)
