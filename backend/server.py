import os
import asyncio
import threading
import hashlib
import time
import requests
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, WebSocket
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

FYERS_REFRESH_URL = (
    "https://api-t1.fyers.in/api/v3/validate-refresh-token"
)

refresh_lock = threading.Lock()
refresh_in_progress = False


def classify_fyers_error(error):
    code = None

    if isinstance(error, dict):
        code = error.get("code")
        try:
            code = int(code)
        except (TypeError, ValueError):
            pass

    return code


def refresh_access_token():
    """
    Obtain a fresh FYERS access token using the Render-stored
    refresh token, App Secret, and PIN.

    Secrets and tokens are never printed or written to source files.
    """
    global last_fyers_error

    app_id = os.getenv("FYERS_APP_ID")
    app_secret = os.getenv("FYERS_APP_SECRET")
    refresh_token = os.getenv("FYERS_REFRESH_TOKEN")
    pin = os.getenv("FYERS_PIN")

    missing = [
        name
        for name, value in (
            ("FYERS_APP_ID", app_id),
            ("FYERS_APP_SECRET", app_secret),
            ("FYERS_REFRESH_TOKEN", refresh_token),
            ("FYERS_PIN", pin),
        )
        if not value
    ]

    if missing:
        last_fyers_error = (
            "FYERS refresh configuration incomplete: "
            + ", ".join(missing)
            + " missing"
        )
        print(last_fyers_error, flush=True)
        return None

    # FYERS v3 requires SHA-256 of:
    # APP_ID:APP_SECRET
    app_id_hash = hashlib.sha256(
        f"{app_id}:{app_secret}".encode("utf-8")
    ).hexdigest()

    payload = {
        "grant_type": "refresh_token",
        "appIdHash": app_id_hash,
        "refresh_token": refresh_token,
        "pin": pin,
    }

    try:
        response = requests.post(
            FYERS_REFRESH_URL,
            headers={"Content-Type": "application/json"},
            json=payload,
            timeout=20,
        )

        data = response.json()

    except Exception as error:
        last_fyers_error = (
            "FYERS token refresh request failed: "
            f"{type(error).__name__}"
        )
        print(last_fyers_error, flush=True)
        return None

    if data.get("s") == "ok" and data.get("access_token"):
        access_token = data["access_token"]

        # Keep the new token only in the running Render process.
        # Do not print it and do not write it to source control.
        os.environ["FYERS_ACCESS_TOKEN"] = access_token

        print(
            "FYERS access token refreshed successfully.",
            flush=True,
        )

        return access_token

    code = classify_fyers_error(data)
    message = data.get("message", "unknown refresh error")

    last_fyers_error = (
        f"FYERS token refresh failed "
        f"(code {code}): {message}"
    )

    print(last_fyers_error, flush=True)
    return None


def try_refresh_and_reconnect():
    global refresh_in_progress, fyers_status

    with refresh_lock:
        if refresh_in_progress:
            return
        refresh_in_progress = True

    try:
        access_token = refresh_access_token()

        if access_token:
            connect_fyers(access_token)
        else:
            fyers_status = AUTH_REQUIRED_STATE

    finally:
        with refresh_lock:
            refresh_in_progress = False


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
            "FYERS authentication failure detected. "
            "Attempting automatic token refresh.",
            flush=True,
        )

        threading.Thread(
            target=try_refresh_and_reconnect,
            daemon=True,
        ).start()
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

        threading.Thread(
            target=try_refresh_and_reconnect,
            daemon=True,
        ).start()

        return True

    return False


def connect_fyers(access_token):
    global socket, history_client, fyers_status, last_fyers_error

    app_id = os.getenv("FYERS_APP_ID")

    if not app_id:
        fyers_status = "not_configured"
        last_fyers_error = "FYERS_APP_ID is not configured"
        print(last_fyers_error, flush=True)
        return

    if not access_token:
        fyers_status = "auth_expired"
        last_fyers_error = "FYERS_ACCESS_TOKEN is not configured"
        print(last_fyers_error, flush=True)
        return

    # REST client uses the raw access token.
    history_client = fyersModel.FyersModel(
        client_id=app_id,
        token=access_token,
        log_path=""
    )

    # FYERS Data WebSocket authentication requires:
    # APP_ID:ACCESS_TOKEN
    websocket_token = f"{app_id}:{access_token}"

    socket = data_ws.FyersDataSocket(
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

    fyers_status = "connecting"
    last_fyers_error = None

    print(
        "Starting FYERS connection using configured OAuth access token.",
        flush=True,
    )

    threading.Thread(
        target=socket.connect,
        daemon=True,
    ).start()


def auth_manager():
    global fyers_status, last_fyers_error

    if not os.getenv("FYERS_APP_ID"):
        fyers_status = "not_configured"
        last_fyers_error = "FYERS_APP_ID is not configured"
        print(last_fyers_error, flush=True)
        return

    # Prefer the Render-stored refresh token on startup.
    # This means a Render restart does not depend on the old
    # access token remaining valid.
    if (
        os.getenv("FYERS_REFRESH_TOKEN")
        and os.getenv("FYERS_APP_SECRET")
        and os.getenv("FYERS_PIN")
    ):
        print(
            "Attempting FYERS access-token refresh from "
            "configured refresh token.",
            flush=True,
        )

        refreshed_token = refresh_access_token()

        if refreshed_token:
            connect_fyers(refreshed_token)
            return

        print(
            "FYERS refresh failed; falling back to "
            "configured access token.",
            flush=True,
        )

    access_token = os.getenv("FYERS_ACCESS_TOKEN")

    if not access_token:
        fyers_status = "auth_expired"
        last_fyers_error = (
            "FYERS_ACCESS_TOKEN is not configured"
        )
        print(last_fyers_error, flush=True)
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
