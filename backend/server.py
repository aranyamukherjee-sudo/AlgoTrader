import os
import threading
from datetime import datetime, timezone

from fastapi import FastAPI
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


def on_message(message):
    symbol = message.get("symbol")

    if symbol:
        with lock:
            latest_quotes[symbol] = {
                **message,
                "received_at": datetime.now(timezone.utc).isoformat(),
            }


def on_error(error):
    global fyers_status
    fyers_status = "error"
    print("FYERS ERROR:", error)


def on_close(message):
    global fyers_status
    fyers_status = "closed"
    print("FYERS CLOSED:", message)


def on_open():
    global fyers_status

    fyers_status = "connected"

    socket.subscribe(
        symbols=SYMBOLS,
        data_type="SymbolUpdate",
    )

    print("FYERS WebSocket connected")
    print("Subscribed:", SYMBOLS)


def start_fyers():
    global socket
    global fyers_status

    app_id = os.getenv("FYERS_APP_ID")
    access_token = os.getenv("FYERS_ACCESS_TOKEN")

    if not app_id or not access_token:
        fyers_status = "not_configured"
        print("FYERS credentials not configured")
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


@app.on_event("startup")
def startup():
    start_fyers()


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "AlgoTrader Market Data API",
        "fyers": fyers_status,
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
