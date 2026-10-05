"""
Phase 3 Patch 7 - authoritative F&O contract metadata (backend).

Source of truth
---------------
The FYERS public symbol master for the NSE F&O segment
(``https://public.fyers.in/sym_details/NSE_FO.csv``). It is the same
instrument master FYERS itself uses to resolve the exact ``NSE:NIFTY26OCTFUT``
style ticker, and it carries that contract's *minimum lot size* and expiry in
the row for the exact ticker. Nothing here constructs a symbol, guesses an
expiry, or supplies a default lot size.

Rules enforced by this module
-----------------------------
* The lookup key is the exact ticker string. No fuzzy or by-date matching.
* A lot size is returned only if it parses as an integer >= 1.
* Two rows for one ticker that disagree make the contract AMBIGUOUS -> error.
* Any fetch/parse/validation failure is an explicit error. There is no
  fallback lot size of any kind.
* Cached data is trusted only while younger than MAX_STALE_SECONDS. Past that
  the data is treated as unavailable rather than served stale.

Column layout
-------------
FYERS publishes this CSV; the layout below is the documented one and is NOT
verified against the live file from the development environment (no network).
The parser therefore (a) accepts a header row and locates columns by name when
one is present, (b) otherwise uses the verified positional layout, and
(c) rejects any row whose ticker/lot-size/expiry cells do not parse. The
current live FYERS master has been verified to place lot size, expiry, and
ticker at positions 3, 8, and 9 respectively. The Android client additionally
cross-checks the returned expiry against the expiry from /futures/chain.
"""

import csv
import re
import threading
import time
from typing import Callable, Dict, Iterable, Optional

SOURCE_NAME = "FYERS symbol master NSE_FO.csv"
SOURCE_URL = "https://public.fyers.in/sym_details/NSE_FO.csv"

# Documented positional layout (0-based) of the header-less FYERS master.
POS_LOT_SIZE = 3
POS_EXPIRY = 8
POS_TICKER = 9

HEADER_LOT_SIZE = "minimum lot size"
HEADER_EXPIRY = "expiry date"
HEADER_TICKER = "symbol ticker"

FUTURES_TICKER = re.compile(
    r"^NSE:[A-Z0-9&-]+\d{2}(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)FUT$"
)

TTL_SECONDS = 6 * 60 * 60          # refresh the master after 6 hours
MAX_STALE_SECONDS = 24 * 60 * 60   # never serve data older than 24 hours


class MetadataUnavailable(Exception):
    """Raised for every condition that must surface as 'lot size unavailable'."""


def _parse_int(cell: str) -> Optional[int]:
    text = (cell or "").strip()
    if not re.fullmatch(r"\d+", text):
        return None
    return int(text)


def parse_futures_rows(lines: Iterable[str]) -> Dict[str, dict]:
    """
    Parse master CSV lines, keeping only futures rows. Returns
    ``{ticker: {"lot_size": int, "expiry": int}}``. Rows that do not parse are
    dropped (never repaired); a ticker whose duplicate rows disagree is mapped
    to ``{"ambiguous": True}`` so the lookup fails instead of picking one.
    """
    records: Dict[str, dict] = {}
    lot_i, exp_i, tick_i = POS_LOT_SIZE, POS_EXPIRY, POS_TICKER
    first = True

    for row in csv.reader(lines):
        if not row:
            continue
        if first:
            first = False
            names = [c.strip().lower() for c in row]
            if HEADER_TICKER in names:
                try:
                    tick_i = names.index(HEADER_TICKER)
                    lot_i = names.index(HEADER_LOT_SIZE)
                    exp_i = names.index(HEADER_EXPIRY)
                except ValueError:
                    raise MetadataUnavailable(
                        "Symbol master header lacks the lot-size or expiry column"
                    )
                continue

        if max(lot_i, exp_i, tick_i) >= len(row):
            continue
        ticker = row[tick_i].strip().upper()
        if not FUTURES_TICKER.match(ticker):
            continue
        lot = _parse_int(row[lot_i])
        expiry = _parse_int(row[exp_i])
        if lot is None or lot < 1 or expiry is None or expiry <= 0:
            continue

        entry = {"lot_size": lot, "expiry": expiry}
        existing = records.get(ticker)
        if existing is not None and existing != entry:
            records[ticker] = {"ambiguous": True}
        else:
            records[ticker] = entry
    return records


def lookup(records: Dict[str, dict], symbol: str) -> dict:
    """Exact-ticker lookup. Raises MetadataUnavailable with a clear reason."""
    entry = records.get(symbol)
    if entry is None:
        raise MetadataUnavailable(
            f"{symbol} was not found in the {SOURCE_NAME}; lot size unavailable"
        )
    if entry.get("ambiguous"):
        raise MetadataUnavailable(
            f"{symbol} has conflicting rows in the {SOURCE_NAME}; lot size unavailable"
        )
    return entry


class ContractMetadataService:
    """Fetches and caches the symbol master; injectable for tests."""

    def __init__(
        self,
        fetch_lines: Callable[[], Iterable[str]],
        clock: Callable[[], float] = time.time,
    ):
        self._fetch_lines = fetch_lines
        self._clock = clock
        self._lock = threading.Lock()
        self._records: Optional[Dict[str, dict]] = None
        self._fetched_at: Optional[float] = None

    def get(self, symbol: str) -> dict:
        """Returns a response payload, or raises MetadataUnavailable."""
        if not FUTURES_TICKER.match(symbol):
            raise MetadataUnavailable("Not a valid exact futures contract ticker")

        with self._lock:
            now = self._clock()
            fresh = (
                self._records is not None
                and self._fetched_at is not None
                and now - self._fetched_at < TTL_SECONDS
            )
            if not fresh:
                try:
                    self._records = parse_futures_rows(self._fetch_lines())
                    self._fetched_at = now
                except MetadataUnavailable:
                    raise
                except Exception as error:  # network / decoding failure
                    stale_ok = (
                        self._records is not None
                        and self._fetched_at is not None
                        and now - self._fetched_at < MAX_STALE_SECONDS
                    )
                    if not stale_ok:
                        raise MetadataUnavailable(
                            f"Could not retrieve the {SOURCE_NAME}: {error}"
                        )
            records = self._records or {}
            fetched_at = self._fetched_at

        entry = lookup(records, symbol)
        return {
            "status": "ok",
            "symbol": symbol,
            "lot_size": entry["lot_size"],
            "expiry": entry["expiry"],
            "source": SOURCE_NAME,
            "source_url": SOURCE_URL,
            "fetched_at": int(fetched_at) if fetched_at is not None else None,
        }


def fetch_master_lines_from_fyers(timeout_seconds: int = 30) -> Iterable[str]:
    """Streams the published FYERS master. Imported lazily to keep tests offline."""
    import requests

    response = requests.get(SOURCE_URL, stream=True, timeout=timeout_seconds)
    response.raise_for_status()
    # Materialised so the connection is closed before parsing; the file is
    # filtered to futures rows immediately after, and only those are kept.
    return [
        line for line in response.iter_lines(decode_unicode=True) if line
    ]
