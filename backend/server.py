import os
import asyncio
import threading
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

def on_error(error):
    global fyers_status, last_fyers_error

    print("FYERS ERROR:", error, flush=True)
    last_fyers_error = str(error)

    code = None

    if isinstance(error, dict):
        code = error.get("code")
        try:
            code = int(code)
        except (TypeError, ValueError):
            pass

    if code in AUTH_ERROR_CODES:
        fyers_status = "auth_expired"
        print(
            "FYERS authentication expired/invalid. "
            "Generate a new access token through FYERS OAuth.",
            flush=True,
        )
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
    global socket, history_client, fyers_status

    app_id = os.getenv("FYERS_APP_ID")

    if not app_id or not access_token:
        fyers_status = "not_configured"
        return

    history_client = fyersModel.FyersModel(
        client_id=app_id,
        token=access_token,
        log_path=""
    )

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
    global fyers_status, last_fyers_error

    # FYERS refresh-token API is disabled for this setup.
    # Use the access token generated through the normal OAuth flow.
    access_token = os.getenv("FYERS_ACCESS_TOKEN")

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
    return {
        "status": "ok",
        "service": "AlgoTrader Market Data API",
        "fyers": fyers_status,
        "auth_mode": "access_token",
        "access_token_configured": bool(os.getenv("FYERS_ACCESS_TOKEN")),
        "last_error": last_fyers_error,
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
                return {
                    "status": "error",
                    "fyers": fyers_status,
                    "response": response,
                    "failed_range_from": data["range_from"],
                    "failed_range_to": data["range_to"],
                }

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
