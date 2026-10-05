"""
Offline tests for fno_metadata. Every row below is a SYNTHETIC fixture with
deterministic made-up values (lot sizes 7, 11, 13). They are not real market
data and not real contract sizes.

Run from the backend directory:  python3 -m unittest discover -s tests -v
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import fno_metadata as fm  # noqa: E402

SYM = "NSE:NIFTY26OCTFUT"
SYM2 = "NSE:NIFTY26NOVFUT"


def row(ticker, lot, expiry):
    # Documented positional layout: lot at [3], expiry at [8], ticker at [9].
    cells = [""] * 12
    cells[3] = str(lot)
    cells[8] = str(expiry)
    cells[9] = ticker
    return ",".join(cells)


HEADER = "Fytoken,Symbol Details,Exchange Instrument type,Minimum lot size,Tick size,ISIN,Trading Session,Last update date,Expiry date,Symbol ticker,Exchange,Segment"


class ParseTests(unittest.TestCase):
    def test_valid_row_is_accepted(self):
        recs = fm.parse_futures_rows([row(SYM, 7, 1_000_000)])
        self.assertEqual({"lot_size": 7, "expiry": 1_000_000}, recs[SYM])

    def test_header_row_is_located_by_name(self):
        reordered = "Symbol ticker,Expiry date,Minimum lot size"
        recs = fm.parse_futures_rows([reordered, f"{SYM},1000000,7"])
        self.assertEqual({"lot_size": 7, "expiry": 1_000_000}, recs[SYM])

    def test_header_without_required_columns_is_unavailable(self):
        with self.assertRaises(fm.MetadataUnavailable):
            fm.parse_futures_rows(["Symbol ticker,Expiry date"])

    def test_zero_negative_and_non_numeric_lot_sizes_are_dropped(self):
        for bad in ("0", "-7", "abc", "", "7.5"):
            recs = fm.parse_futures_rows([row(SYM, bad, 1_000_000)])
            self.assertNotIn(SYM, recs, bad)

    def test_invalid_expiry_is_dropped(self):
        for bad in ("0", "-1", "x", ""):
            self.assertNotIn(SYM, fm.parse_futures_rows([row(SYM, 7, bad)]))

    def test_non_futures_and_short_rows_are_ignored(self):
        lines = [row("NSE:NIFTY26OCT24500CE", 7, 1_000_000), "1,2,3", ""]
        self.assertEqual({}, fm.parse_futures_rows(lines))

    def test_identical_duplicates_are_fine_conflicting_are_ambiguous(self):
        same = fm.parse_futures_rows([row(SYM, 7, 5), row(SYM, 7, 5)])
        self.assertEqual({"lot_size": 7, "expiry": 5}, same[SYM])
        conflict = fm.parse_futures_rows([row(SYM, 7, 5), row(SYM, 11, 5)])
        with self.assertRaises(fm.MetadataUnavailable):
            fm.lookup(conflict, SYM)

    def test_lookup_is_exact_ticker_only(self):
        recs = fm.parse_futures_rows([row(SYM, 7, 5)])
        with self.assertRaises(fm.MetadataUnavailable):
            fm.lookup(recs, SYM2)  # a different contract is never substituted


class ServiceTests(unittest.TestCase):
    def make(self, lines, clock):
        calls = {"n": 0}

        def fetch():
            calls["n"] += 1
            if isinstance(lines, Exception):
                raise lines
            return lines

        return fm.ContractMetadataService(fetch, clock), calls

    def test_ok_payload_has_lot_size_expiry_and_source(self):
        svc, _ = self.make([HEADER, row(SYM, 7, 1_000_000)], lambda: 100.0)
        out = svc.get(SYM)
        self.assertEqual("ok", out["status"])
        self.assertEqual(7, out["lot_size"])
        self.assertEqual(1_000_000, out["expiry"])
        self.assertEqual(SYM, out["symbol"])
        self.assertEqual(fm.SOURCE_NAME, out["source"])

    def test_invalid_symbol_is_rejected_without_fetching(self):
        svc, calls = self.make([row(SYM, 7, 1)], lambda: 0.0)
        for bad in ("NIFTY", "NSE:NIFTY50-INDEX", "", "NSE:NIFTY26OCT24500CE"):
            with self.assertRaises(fm.MetadataUnavailable):
                svc.get(bad)
        self.assertEqual(0, calls["n"])

    def test_fetch_failure_without_cache_is_unavailable(self):
        svc, _ = self.make(IOError("network down"), lambda: 0.0)
        with self.assertRaises(fm.MetadataUnavailable):
            svc.get(SYM)

    def test_missing_contract_is_unavailable(self):
        svc, _ = self.make([row(SYM2, 7, 5)], lambda: 0.0)
        with self.assertRaises(fm.MetadataUnavailable):
            svc.get(SYM)

    def test_cache_is_reused_within_ttl(self):
        now = {"t": 0.0}
        svc, calls = self.make([row(SYM, 7, 5)], lambda: now["t"])
        svc.get(SYM)
        now["t"] = fm.TTL_SECONDS - 1
        svc.get(SYM)
        self.assertEqual(1, calls["n"])

    def test_stale_cache_is_served_only_until_max_stale_then_unavailable(self):
        now = {"t": 0.0}
        state = {"fail": False}
        data = [row(SYM, 7, 5)]

        def fetch():
            if state["fail"]:
                raise IOError("down")
            return data

        svc = fm.ContractMetadataService(fetch, lambda: now["t"])
        svc.get(SYM)
        state["fail"] = True
        now["t"] = fm.TTL_SECONDS + 1            # expired but within max stale
        self.assertEqual(7, svc.get(SYM)["lot_size"])
        now["t"] = fm.MAX_STALE_SECONDS + 1      # too old: must not be served
        with self.assertRaises(fm.MetadataUnavailable):
            svc.get(SYM)


if __name__ == "__main__":
    unittest.main()
