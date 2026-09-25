import os
import asyncio
import hashlib
import threading
import time
import requests
from datetime import datetime, timezone

from fastapi import FastAPI, WebSocket
from fyers_apiv3.FyersWebsocket import data_ws


app = FastAPI(title="AlgoTrader Market Data API")

SYMBOLS = [
    "NSE:NIFTY50-INDEX",
    "NSE:NIFTYBANK-INDEX",
    "BSE:SENSEX-INDEX",
]

latest_quotes = {}
lock = threading.Lock()
socket = None
fyers_status = "not_configured"
last_fyers_error = None


def refresh_access_token():
    global last_fyers_error

    app_id = os.getenv("FYERS_APP_ID")
    secret = os.getenv("FYERS_APP_SECRET")
    refresh_token = os.getenv("FYERS_REFRESH_TOKEN")
    pin = os.getenv("FYERS_PIN")

    if not all([app_id, secret, refresh_token, pin]):
        print("FYERS refresh credentials incomplete", flush=True)
        return None

    app_id_hash = hashlib.sha256(
        f"{app_id}:{secret}".encode()
    ).hexdigest()

    try:
        response = requests.post(
            "https://api-t1.fyers.in/api/v3/validate-refresh-token",
            json={
                "grant_type": "refresh_token",
                "appIdHash": app_id_hash,
                "refresh_token": refresh_token,
                "pin": pin,
            },
            timeout=15,
        )

        data = response.json()

        if data.get("s") == "ok" and data.get("access_token"):
            print("FYERS access token refreshed automatically", flush=True)
            last_fyers_error = None
            return data["access_token"]

        print(
            f"FYERS refresh failed: HTTP {response.status_code} "
            f"code={data.get('code')} message={data.get('message')}",
            flush=True,
        )

        last_fyers_error = str(data)
        return None

    except Exception as error:
        print(f"FYERS refresh exception: {error}", flush=True)
        last_fyers_error = str(error)
        return None


def on_message(message):
    symbol = message.get("symbol")

    if symbol:
        with lock:
            latest_quotes[symbol] = {
                **message,
                "received_at": datetime.now(timezone.utc).isoformat(),
            }


def on_error(error):
    global fyers_status, last_fyers_error

    print("FYERS ERROR:", error, flush=True)
    last_fyers_error = str(error)

    if isinstance(error, dict) and error.get("code") == -99:
        fyers_status = "auth_expired"
    else:
        fyers_status = "error"


def on_close(message):
    global fyers_status

    print("FYERS CLOSED:", message, flush=True)

    if fyers_status != "auth_expired":
        fyers_status = "closed"


def on_open():
    global fyers_status

    fyers_status = "connected"

    print("FYERS WebSocket connected", flush=True)

    socket.subscribe(
        symbols=SYMBOLS,
        data_type="SymbolUpdate",
    )

    print("Subscribed:", SYMBOLS, flush=True)

    socket.keep_running()


def connect_fyers(access_token):
    global socket, fyers_status

    app_id = os.getenv("FYERS_APP_ID")

    if not app_id or not access_token:
        fyers_status = "not_configured"
        return

    socket = data_ws.FyersDataSocket(
        access_token=f"{app_id}:{access_token}",
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

    threading.Thread(
        target=socket.connect,
        daemon=True,
    ).start()


def auth_manager():
    global fyers_status

    # Always try refresh first after a restart.
    access_token = refresh_access_token()

    # Fall back to the currently configured token if refresh fails.
    if not access_token:
        access_token = os.getenv("FYERS_ACCESS_TOKEN")

    if not access_token:
        fyers_status = "auth_expired"
        return

    connect_fyers(access_token)

    while True:
        # Refresh before the access token normally expires.
        time.sleep(45 * 60)

        new_token = refresh_access_token()

        if new_token:
            try:
                if socket:
                    socket.close()
            except Exception:
                pass

            connect_fyers(new_token)


@app.on_event("startup")
def startup():
    threading.Thread(
        target=auth_manager,
        daemon=True,
    ).start()


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "AlgoTrader Market Data API",
        "fyers": fyers_status,
        "last_error": last_fyers_error,
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
