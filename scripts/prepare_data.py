"""Prepare the data files of the app from public sources. Run this script one time.

Usage:  python -I scripts/prepare_data.py [--year 2025] [--raw raw] [--out src/main/resources/data]

Sources (see docs/DESIGN-full.md, section 5):
  OurAirports airports.csv and runways.csv, FAA enplanement files (xlsx), BTS T-100 Segment (US carriers),
  BTS On-Time Performance (12 monthly files).

The script saves facts only: sums, counts and rates. It does not calculate scores.
Raw downloads go in the folder given by --raw. That folder is not in Git.
It needs no packages that are not in the Python standard library.
"""
import argparse
import csv
import datetime
import html
import http.cookiejar
import io
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

OURAIRPORTS = "https://davidmegginson.github.io/ourairports-data/"
FAA_BASE = "https://www.faa.gov/airports/planning_capacity/passenger_allcargo_stats/passenger/"
FAA_FILES = {2025: "arp-cy2025-commercial-service-enplanements.xlsx",
             2024: "arp-cy2024-commercial-service-enplanements.xlsx"}
ONTIME_URL = ("https://transtats.bts.gov/PREZIP/"
              "On_Time_Reporting_Carrier_On_Time_Performance_1987_present_{year}_{month}.zip")
# TranStats table GDM: T-100 Segment (US Carriers Only), domestic and international flights.
T100_URL = "https://www.transtats.bts.gov/DL_SelectFields.aspx?gnoyr_VQ=GDM&QO_fu146_anzr=Nv4+Pn44vr45"
T100_FIELDS = ["YEAR", "MONTH", "ORIGIN", "DEST", "UNIQUE_CARRIER", "AIRCRAFT_TYPE", "CLASS", "DISTANCE",
               "PASSENGERS", "SEATS", "DEPARTURES_PERFORMED", "DEPARTURES_SCHEDULED"]

NEW_ENGLAND = {"CT", "ME", "MA", "NH", "RI", "VT"}
# The 50 states and DC have the country code US. These territories are US airports too.
US_COUNTRIES = {"US", "PR", "VI", "GU", "MP", "AS"}
# The BTS data and the FAA CY2024 file use PBI for West Palm Beach. OurAirports and the FAA CY2025 file use DJT.
# The app code follows the BTS data, because T-100 and On-Time are the main sources.
CODE_BY_OURAIRPORTS_IDENT = {"KPBI": "PBI"}
BUCKET_MILES = 500


def log(message):
    print(message, flush=True)


# ---------------------------------------------------------------- download

def download(url, target, expected_min_bytes=1000, attempts=8):
    """Download a file with resume. Check the size against the server size."""
    target.parent.mkdir(parents=True, exist_ok=True)
    head = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(head, timeout=60) as response:
        total = int(response.headers.get("Content-Length", "0"))
    for attempt in range(1, attempts + 1):
        have = target.stat().st_size if target.exists() else 0
        if total and have == total:
            return
        if total and have > total:
            target.unlink()
            have = 0
        headers = {"User-Agent": USER_AGENT}
        if have:
            headers["Range"] = f"bytes={have}-"
        try:
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=120) as response:
                mode = "ab" if have and response.status == 206 else "wb"
                with open(target, mode) as out:
                    while True:
                        chunk = response.read(1 << 20)
                        if not chunk:
                            break
                        out.write(chunk)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            log(f"  attempt {attempt} failed for {target.name}: {error}")
    size = target.stat().st_size if target.exists() else 0
    if size < expected_min_bytes or (total and size != total):
        raise RuntimeError(f"Download incomplete: {url} ({size} of {total} bytes)")


def download_t100(year, target):
    """Submit the TranStats form and save the zip. BTS can change this form. Then this function fails."""
    if target.exists() and zipfile.is_zipfile(target):
        return
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    opener.addheaders = [("User-Agent", USER_AGENT)]
    page = opener.open(T100_URL, timeout=60).read().decode("utf-8", "ignore")

    def hidden(name):
        match = re.search(r'name="%s"[^>]*value="([^"]*)"' % name, page)
        if not match:
            raise RuntimeError(f"TranStats form changed: field {name} not found.")
        return html.unescape(match.group(1))

    data = {"__EVENTTARGET": "", "__EVENTARGUMENT": "", "__LASTFOCUS": "",
            "__VIEWSTATE": hidden("__VIEWSTATE"), "__VIEWSTATEGENERATOR": hidden("__VIEWSTATEGENERATOR"),
            "__EVENTVALIDATION": hidden("__EVENTVALIDATION"),
            "cboGeography": "All", "cboYear": str(year), "cboPeriod": "All", "btnDownload": "Download"}
    for field in T100_FIELDS:
        data[field] = "on"
    request = urllib.request.Request(
        T100_URL, data=urllib.parse.urlencode(data).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded", "Referer": T100_URL})
    response = opener.open(request, timeout=600)
    blob = response.read()
    if not blob.startswith(b"PK"):
        raise RuntimeError("TranStats did not return a zip file. The form may have changed.")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(blob)


# ---------------------------------------------------------------- readers

def read_xlsx_rows(path):
    """Read sheet 1 of an xlsx file. Returns a list of dicts (column letter to text)."""
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            xml = z.read("xl/sharedStrings.xml").decode("utf-8")
            for item in re.findall(r"<si[^>]*>(.*?)</si>", xml, re.S):
                shared.append(html.unescape("".join(re.findall(r"<t[^>]*>(.*?)</t>", item, re.S))))
        sheet = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
    rows = []
    for row in re.findall(r"<row[^>]*>(.*?)</row>", sheet, re.S):
        cells = {}
        for column, attributes, body in re.findall(r'<c r="([A-Z]+)\d+"([^>]*?)(?:/>|>(.*?)</c>)', row, re.S):
            value = re.search(r"<v>(.*?)</v>", body or "")
            if value is None:
                inline = re.findall(r"<t[^>]*>(.*?)</t>", body or "", re.S)
                text = html.unescape("".join(inline))
            elif 't="s"' in attributes:
                text = shared[int(value.group(1))]
            else:
                text = html.unescape(value.group(1))
            cells[column] = text.strip()
        rows.append(cells)
    return rows


def parse_faa(path):
    """Return {locid: {...}} with enplanements for the two years in the file."""
    rows = read_xlsx_rows(path)
    header = next(row for row in rows if row.get("A") == "Rank")
    year_columns = {}
    for column, text in header.items():
        match = re.match(r"CY\s*(\d{2})\s+Enplanements", text)
        if match:
            year_columns[2000 + int(match.group(1))] = column
    result = {}
    for row in rows[rows.index(header) + 1:]:
        locid = row.get("D", "")
        if not locid or not row.get("A", "").replace(".", "").isdigit():
            continue
        result[locid] = {
            "rank": int(float(row["A"])), "state": row.get("C", ""), "city": row.get("E", ""),
            "name": row.get("F", ""), "hub": row.get("H", ""),
            "enplanements": {year: int(float(row[column])) for year, column in year_columns.items()
                             if row.get(column, "").replace(".", "").replace("E-", "").isdigit()}}
    return result


def read_csv_from_zip(path):
    z = zipfile.ZipFile(path)
    name = next(n for n in z.namelist() if n.lower().endswith(".csv"))
    return io.TextIOWrapper(z.open(name), encoding="utf-8", newline="")


def number(text):
    try:
        return float(text)
    except ValueError:
        return 0.0


# ---------------------------------------------------------------- aggregation

def aggregate_t100(path):
    """Add up T-100 rows by origin. Rules: docs/DESIGN-full.md, section 5.5."""
    traffic = defaultdict(lambda: {"pax": 0.0, "seats": 0.0, "dep_pax": 0.0, "dep_cargo": 0.0})
    buckets = defaultdict(lambda: defaultdict(lambda: [0.0, 0.0]))
    rows = used = 0
    for row in csv.DictReader(read_csv_from_zip(path)):
        rows += 1
        departures = number(row["DEPARTURES_PERFORMED"])
        if departures <= 0 or row["ORIGIN"] == row["DEST"]:
            continue
        used += 1
        origin = row["ORIGIN"]
        bucket = int(number(row["DISTANCE"]) // BUCKET_MILES) * BUCKET_MILES
        if number(row["SEATS"]) > 0:  # passenger row
            traffic[origin]["pax"] += number(row["PASSENGERS"])
            traffic[origin]["seats"] += number(row["SEATS"])
            traffic[origin]["dep_pax"] += departures
            buckets[origin][bucket][0] += departures
        else:  # cargo row
            traffic[origin]["dep_cargo"] += departures
            buckets[origin][bucket][1] += departures
    log(f"  T-100: {rows} rows read, {used} rows used, {len(traffic)} origin codes")
    return traffic, buckets


def aggregate_ontime(paths):
    stats = defaultdict(lambda: {"flights": 0, "cancelled": 0, "dep_known": 0, "delayed": 0,
                                 "taxi_sum": 0.0, "taxi_n": 0})
    for path in paths:
        reader = csv.reader(read_csv_from_zip(path))
        header = next(reader)
        origin_i, cancelled_i = header.index("Origin"), header.index("Cancelled")
        delay_i, taxi_i = header.index("DepDel15"), header.index("TaxiOut")
        for row in reader:
            if len(row) <= taxi_i:
                continue
            entry = stats[row[origin_i]]
            entry["flights"] += 1
            if row[cancelled_i] == "1.00":
                entry["cancelled"] += 1
                continue
            if row[delay_i] != "":
                entry["dep_known"] += 1
                if row[delay_i] == "1.00":
                    entry["delayed"] += 1
            if row[taxi_i] != "":
                entry["taxi_sum"] += float(row[taxi_i])
                entry["taxi_n"] += 1
        log(f"  On-Time: {path.name} done, {len(stats)} origins so far")
    return stats


# ---------------------------------------------------------------- joins

class AirportIndex:
    """Match source codes to the OurAirports master record (docs/DESIGN-full.md, section 5.6)."""

    def __init__(self, airports, aliases):
        self.by_iata, self.by_local = {}, {}
        for airport in airports:
            if airport["iata_code"]:
                self.by_iata.setdefault(airport["iata_code"], airport)
            if airport["local_code"]:
                self.by_local.setdefault(airport["local_code"], airport)
            override = CODE_BY_OURAIRPORTS_IDENT.get(airport["ident"])
            if override:
                self.by_iata[override] = airport
                self.by_local[override] = airport
        self.aliases = aliases

    def find(self, code, faa_id=False):
        """Find an airport. An FAA ID is matched to the FAA code first. Other codes to the IATA code first."""
        for table in ((self.by_local, self.by_iata) if faa_id else (self.by_iata, self.by_local)):
            if code in table:
                return table[code]
        alias = self.aliases.get(code.lower())
        if alias:
            return self.by_iata.get(alias) or self.by_local.get(alias)
        return None


def load_aliases(path):
    aliases = {}
    if path.exists():
        with open(path, encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                aliases[row["alias"].strip().lower()] = row["iata"].strip()
    return aliases


def code_of(airport):
    """The code of the app: a fixed override, else the IATA code, else the FAA ID."""
    return CODE_BY_OURAIRPORTS_IDENT.get(airport["ident"]) or airport["iata_code"] or airport["local_code"]


def write_csv(path, header, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def fmt(value, digits=6):
    return f"{value:.{digits}f}".rstrip("0").rstrip(".") if isinstance(value, float) else str(value)


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--year", type=int, default=2025)
    parser.add_argument("--raw", default="raw")
    parser.add_argument("--out", default="src/main/resources/data")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    raw, out = (root / args.raw), (root / args.out)
    year = args.year
    report = []

    log("1. OurAirports")
    for name in ("airports.csv", "runways.csv"):
        download(OURAIRPORTS + name, raw / name)
    with open(raw / "airports.csv", encoding="utf-8", newline="") as handle:
        us_airports = [r for r in csv.DictReader(handle) if r["iso_country"] in US_COUNTRIES]
    runway_count = defaultdict(int)
    with open(raw / "runways.csv", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            if row["closed"] != "1":
                runway_count[row["airport_ref"]] += 1

    log("2. FAA enplanements")
    faa = {}
    for file_year, file_name in FAA_FILES.items():
        download(FAA_BASE + file_name, raw / file_name)
        for locid, entry in parse_faa(raw / file_name).items():
            merged = faa.setdefault(locid, {"enplanements": {}})
            if file_year == max(FAA_FILES) or "rank" not in merged:
                merged.update({k: v for k, v in entry.items() if k != "enplanements"})
            merged["enplanements"].update(entry["enplanements"])
    log(f"  {len(faa)} FAA commercial service airports")

    aliases = load_aliases(out / "aliases.csv")
    index = AirportIndex(us_airports, aliases)

    log("3. Build the airport universe (FAA commercial service airports matched to OurAirports)")
    universe = {}  # code -> (oa row, faa entry)
    for locid, entry in faa.items():
        airport = index.find(locid, faa_id=True)
        if airport is None:
            report.append(f"UNMATCHED FAA airport {locid} ({entry.get('name')}), "
                          f"enplanements {entry['enplanements']}")
            continue
        code = code_of(airport)
        if code in universe:  # the same airport under two FAA IDs (example: PBI and DJT)
            known = universe[code][1]
            for enpl_year, value in entry["enplanements"].items():
                known["enplanements"].setdefault(enpl_year, value)
            continue
        universe[code] = (airport, {**entry, "enplanements": dict(entry["enplanements"])})
    log(f"  universe: {len(universe)} airports")

    log("4. T-100 Segment")
    t100_zip = raw / f"t100_segment_{year}.zip"
    download_t100(year, t100_zip)
    traffic_raw, buckets_raw = aggregate_t100(t100_zip)

    log("5. On-Time Performance (12 monthly files, about 330 MB)")
    # The BTS server is slow for one connection. Several downloads at the same time are much faster.
    ontime_zips = [raw / f"ontime_{year}_{month}.zip" for month in range(1, 13)]
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [pool.submit(download, ONTIME_URL.format(year=year, month=month), target, 1_000_000)
                   for month, target in enumerate(ontime_zips, start=1)]
        for future in futures:
            future.result()  # raises an error if a download failed
    log("  all On-Time files downloaded")
    ontime_raw = aggregate_ontime(ontime_zips)

    log("6. Join and write")
    traffic, buckets, ontime = {}, defaultdict(lambda: defaultdict(lambda: [0.0, 0.0])), {}
    outside = []  # T-100 origins that are not in the universe (mostly foreign airports)
    for source, label in ((traffic_raw, "T-100"), (ontime_raw, "On-Time")):
        for origin, values in source.items():
            airport = index.find(origin)
            code = code_of(airport) if airport else None
            if code not in universe:
                size = values.get("pax", values.get("flights", 0))
                if label == "T-100" and size:
                    outside.append((size, origin, airport is not None))
                continue
            if label == "T-100":
                total = traffic.setdefault(code, {"pax": 0.0, "seats": 0.0, "dep_pax": 0.0, "dep_cargo": 0.0})
                for key in total:
                    total[key] += values[key]
                for bucket, pair in buckets_raw[origin].items():
                    buckets[code][bucket][0] += pair[0]
                    buckets[code][bucket][1] += pair[1]
            else:
                total = ontime.setdefault(code, {k: 0 for k in values})
                for key in total:
                    total[key] += values[key]

    outside.sort(reverse=True)
    report.append(f"T-100 origins outside the universe: {len(outside)} codes, "
                  f"{sum(s for s, _, _ in outside):.0f} passengers (mostly foreign airports).")
    for size, origin, matched in outside[:15]:
        report.append(f"  largest outside: {origin} {size:.0f} passengers, "
                      f"{'US airport not in the FAA list' if matched else 'no US airport with this code'}")

    airports_rows, enpl_rows = [], []
    for code, (airport, entry) in sorted(universe.items()):
        state = airport["iso_region"].replace("US-", "") if airport["iso_country"] == "US" else airport["iso_country"]
        airports_rows.append([
            code, airport["local_code"], airport["name"], airport["municipality"], state,
            "NEW_ENGLAND" if state in NEW_ENGLAND else "OTHER", airport["latitude_deg"],
            airport["longitude_deg"], runway_count.get(airport["id"], 0), entry.get("hub", "")])
        enplanements = entry["enplanements"]
        enpl_rows.append([code, enplanements.get(2023, ""), enplanements.get(2024, ""),
                          enplanements.get(2025, ""), entry.get("rank", "")])
        if code not in traffic:
            report.append(f"NO T-100 traffic for {code} ({airport['name']})")
        if code not in ontime:
            report.append(f"NO On-Time data for {code} ({airport['name']})")
        if runway_count.get(airport["id"], 0) == 0:
            report.append(f"NO runway count for {code} ({airport['name']})")

    write_csv(out / "airports.csv",
              ["iata", "faa_id", "name", "city", "state", "region", "lat", "lon", "runway_count", "hub_class"],
              airports_rows)
    write_csv(out / "enplanements.csv", ["iata", "enpl_cy23", "enpl_cy24", "enpl_cy25", "faa_rank_cy25"], enpl_rows)
    write_csv(out / "traffic.csv", ["iata", "year", "pax", "seats", "departures_pax", "departures_cargo"],
              [[c, year, int(t["pax"]), int(t["seats"]), int(t["dep_pax"]), int(t["dep_cargo"])]
               for c, t in sorted(traffic.items())])
    write_csv(out / "distance_buckets.csv", ["iata", "bucket_min_miles", "departures_pax", "departures_cargo"],
              [[c, b, int(p), int(g)] for c, per in sorted(buckets.items()) for b, (p, g) in sorted(per.items())])
    ontime_rows = []
    for code, s in sorted(ontime.items()):
        delay_rate = s["delayed"] / s["dep_known"] if s["dep_known"] else ""
        taxi = s["taxi_sum"] / s["taxi_n"] if s["taxi_n"] else ""
        ontime_rows.append([code, year, s["flights"], fmt(delay_rate) if delay_rate != "" else "",
                            fmt(s["cancelled"] / s["flights"]) if s["flights"] else "",
                            fmt(taxi, 3) if taxi != "" else ""])
    write_csv(out / "ontime.csv",
              ["iata", "year", "flights", "delay15_rate", "cancel_rate", "avg_taxi_out_min"], ontime_rows)

    manifest = {
        "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "dataYear": year,
        "sources": [
            {"name": "OurAirports airports.csv and runways.csv", "url": OURAIRPORTS,
             "use": "airport identity, location, open runway count"},
            {"name": "FAA commercial service enplanements CY2024 and CY2025", "url": FAA_BASE,
             "files": list(FAA_FILES.values()), "use": "enplanements CY23 to CY25, hub class, rank, airport universe"},
            {"name": "BTS T-100 Segment (US Carriers Only), domestic and international",
             "url": "https://www.transtats.bts.gov/ (table GDM)", "use": "passengers, seats, departures, distance buckets"},
            {"name": "BTS On-Time Performance, 12 monthly files", "url": "https://transtats.bts.gov/PREZIP/",
             "use": "departure delay rate, cancellation rate, taxi-out time"}],
        "counts": {"airports": len(airports_rows), "withTraffic": len(traffic), "withOnTime": len(ontime),
                   "reportLines": len(report)},
        "rules": ["T-100: rows with zero departures performed and rows with origin equal to destination are removed",
                  "T-100: a row with seats above 0 is a passenger row. Other rows are cargo rows",
                  f"Distance buckets are {BUCKET_MILES} miles wide, weighted by departures performed",
                  "On-Time: delay rate = departures delayed 15 minutes or more / flights that did not cancel",
                  "Codes are matched to OurAirports by IATA code, then FAA code, then aliases.csv"],
        "limits": ["T-100 covers US carriers only", "On-Time covers large reporting carriers only",
                   "T-100 counts segments, not trips", "Some airports have no On-Time data"]}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    report_path = raw / "match_report.txt"
    report_path.write_text("\n".join(report) + "\n", encoding="utf-8")
    log(f"Done. {len(airports_rows)} airports, {len(traffic)} with traffic, {len(ontime)} with On-Time data.")
    log(f"Report with {len(report)} lines: {report_path}")


if __name__ == "__main__":
    sys.exit(main())
