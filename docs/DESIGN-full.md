# Airport Investment Intelligence Agent: Design Document

Status: draft 1. Date of the data checks: 2026-10-14.

This document explains the full design. It lists each decision, each assumption and each limit.
Items marked **VERIFIED** were tested with real requests on 2026-10-14.
Items marked **PROPOSED** are design choices that the author can change.
Items marked **NOT VERIFIED** were not tested yet.

---

## 1. Purpose and scope

### 1.1 The problem
A firm invests in airport modernization projects in the US.
An analyst must find the airports where a renovation is most likely to pay back.
The brief says to judge this by the increase in flight and passenger capacity.
The agent helps the analyst with questions in a chat.

### 1.2 The four example questions
1. Which airports in New England are strong candidates for terminal expansion?
2. Compare LA and Santa Ana airport congestion levels.
3. What is the percentage of long haul flights out of Anchorage airport?
4. What is the unmet flight demand at SFO airport and why?

### 1.3 What the agent must do (from the brief)
| Requirement | Where this design meets it |
|---|---|
| Use public APIs to gather airport and aviation data | Section 5 (public data sources) and section 8.4 (live FAA call) |
| Rank or compare airports with a defined logic or KPI | Section 6 (scoring) |
| Explain the reasoning clearly | Section 6.9 (score trace) and section 8 (AI role) |
| Support follow-up questions | Section 8.6 (chat memory) |
| Deterministic scoring logic, not only LLM output | Section 6 and section 4.3 |
| Chat interface (voice is a bonus) | Section 10 |
| Communicate assumptions, uncertainty and scope | Section 9 (response envelope) and sections 13 and 14 |
| Short design document: scoring, trade-offs, use of AI | This document (sections 6, 16, 8) |

### 1.4 What the agent is not
- It is not an investment model. It does not calculate cost, revenue, profit or return.
- It is not a forecast tool.
- It does not cover airports outside the US.
- It does not replace the judgment of an analyst.

The agent gives an **opportunity index**. The index shows where traffic pressure and growth are high.
This is a proxy for the need of more capacity. It is not a proxy for profit.
The agent must say this in its answers when it is relevant.

---

## 2. Design principles

1. **The LLM never calculates a number.** Java code calculates every number. The LLM chooses tools and writes text.
2. **Save facts. Calculate opinions.** The data files hold facts. The code calculates the scores from the facts and the settings.
3. **Unknown is not zero.** A missing value is removed from the score. It is never replaced by zero or by a guess.
4. **Say what you do not know.** Each answer states its assumptions, its data year and its limits.
5. **One engine, two entry points.** The REST API and the chat use the same service. They give the same numbers.
6. **Keep it small.** One module. No database. No vector store. No framework that the task does not need.
7. **Make the logic visible.** Each score shows how many points each component gave.

---

## 3. Technology choices

| Item | Choice | Status | Reason |
|---|---|---|---|
| Language | Java 21 (the code compiles with `release 21`) | DECIDED | The author works in Java. The build machine has JDK 25. It builds Java 21 code with the `release` option. |
| Build | Maven, one module | DECIDED | Simple. Matches the existing project. |
| Web | Spring Boot 4.1.1 (`spring-boot-starter-web`) | DECIDED, **VERIFIED** | Current stable line on 2026-10-14. The earlier plan (Boot 3.5) was old. |
| LLM layer | Spring AI 2.0.1 (`ChatClient`, `@Tool`, `ChatMemory`) | DECIDED, **VERIFIED** | Spring AI 2.0.1 is built against Boot 4.1.1. The application starts and the settings bind. The tool code is not written yet. |
| LLM provider | Gemini (free tier) behind `ChatClient`, model `gemini-2.5-flash` | DECIDED | No cost. The key works (model list call returned 200). The daily limit is small. A change of provider is a configuration change. |
| Data storage | In memory, loaded from CSV files at start-up | DECIDED | About 100 to 500 rows. A database adds no value. |
| Data preparation | Python script (3.12 is installed) | DECIDED | Faster to write. It runs one time. |
| UI | One static HTML page served by Spring Boot | PROPOSED | No front-end build. |
| Voice | Browser Web Speech API | PROPOSED | The backend does not change. |
| Tests | JUnit 5, AssertJ, ArchUnit | PROPOSED | See section 12. |

Rejected: LangChain4j (same effort as Spring AI), a hand-written LLM loop (not best practice), DuckDB or Postgres (no need), a vector store or RAG (no documents to search).

---

## 4. Architecture

### 4.1 Overview

```
BUILD TIME (one time, on the developer computer)
  Raw public files ──> prepare script ──> small CSV files + manifest.json ──> committed in the repo

RUN TIME
  Browser chat page
        │  POST /chat
  ChatController ──> ChatService (Spring AI: ChatClient + ChatMemory + system prompt)
                          │  tool calls from the LLM (live LLM API)
                     AirportTools (@Tool methods, thin)
                          │
  REST endpoints ──> AirportQueryService  <── one engine
  /rank /compare        │              │
  /airport/{code}   scoring package   FaaStatusClient (live FAA call)
                    (pure Java)
                        │
                  in-memory data (from the CSV files)
```

### 4.2 Packages

```
com.example.airportagent
├── data/      Loaders for the CSV files, ExpertNotes, FaaStatusClient (the live call)
├── domain/    Java records: Airport, AirportMetrics, MetricValue, Score, Contribution, HubClass, Envelope
├── scoring/   Percentiles, WeightProfile, ScoreCalculator, UnmetDemandIndex, LongHaulCalculator
│              (no Spring, no AI)
├── service/   AirportQueryService (the single facade), AirportResolver, MetricsBuilder, RankedSet,
│              EnvelopeFactory, QueryResult, Views
├── agent/     AirportTools, ChatService, SystemPrompt, NumberCheck
└── api/       ChatController (/chat), ScoreController (/rank, /compare, /airport/{code})
```

Dependency rules:
- `scoring` and `domain` must not depend on `agent`, `api` or Spring AI.
- `agent` and `api` call `service` only. They do not call `scoring` or `data` directly.
- `AirportTools` contains no business logic. It converts the tool call to a service call.

An ArchUnit test enforces these rules. The test makes the claim "the scoring has no AI" testable.

### 4.3 Why two entry points use one engine
- The reviewer can check the numbers with the REST API and no LLM.
- A test compares the chat tool result with the REST result. They must be equal.
- If the LLM service is not available, the deterministic part still works.

### 4.4 Flow of one chat question
Example: "Compare LA and Santa Ana congestion."

1. The browser sends the text and a session ID to `/chat`.
2. `ChatService` sends the text, the chat history and the tool list to the LLM.
3. The LLM calls `resolveAirport("LA")`. The tool uses the alias table and returns `LAX`.
4. The LLM calls `resolveAirport("Santa Ana")`. The tool returns `SNA`.
5. The LLM calls `compareAirports(["LAX","SNA"], "congestion")`.
6. `AirportQueryService` asks `scoring` for the congestion components. It adds the hub class and the envelope.
7. The tool returns the structured result to the LLM.
8. The LLM writes the answer from the result.
9. `NumberCheck` compares each number in the answer with the tool results.
10. The API returns the answer and the structured data. The page shows both.

---

## 5. Data

### 5.1 Sources and checks

| Source | Gives | How to get it | Check result |
|---|---|---|---|
| OurAirports `airports.csv` | IDs (IATA, ICAO, FAA `local_code`), name, city, state, coordinates, `scheduled_service` | `https://davidmegginson.github.io/ourairports-data/airports.csv` | **VERIFIED.** Status 200, about 12.7 MB. No key. |
| OurAirports `runways.csv` | Runways per airport (`airport_ident`, `closed`, length) | `.../runways.csv` | **VERIFIED.** Status 200, about 4 MB. |
| FAA enplanement files | Enplanements, rank, state, hub class (`Hub` column) | Two xlsx files on the FAA passenger statistics page: `arp-cy2025-commercial-service-enplanements.xlsx` and `arp-cy2024-commercial-service-enplanements.xlsx` | **VERIFIED.** Each file has two years. CY23, CY24 and CY25 are available from the two files. |
| BTS T-100 Segment (US carriers) | Passengers, seats, departures, distance, carrier, class | The TranStats form. A script can submit it. | **VERIFIED.** The 2025 file has 455,857 rows (domestic table) and 517,764 rows (combined table). All 12 months are present. |
| BTS On-Time Performance | Delay, cancellation, taxi-out for each flight | Monthly ZIP files on `transtats.bts.gov/PREZIP/` | **VERIFIED.** One file per month, about 27 MB each. Data to July 2026. |
| FAA NAS Status | Active ground stops, delays and closures | `https://nasstatus.faa.gov/api/airport-status-information` | **VERIFIED.** Status 200, XML, no key. |

Not used:
- The NTAD ArcGIS T-100 table. It has only passengers and departures for 2024. It has no seats and no distance. **VERIFIED.**
- OpenSky historical flights. They need OAuth2 credentials.
- FAA ASPM and OPSNET. They need a login.
- The FAA CY22 file. The guessed URL gave 404. **VERIFIED.** The design uses CY23 to CY25.

### 5.2 Two T-100 tables
BTS has two T-100 segment tables for US carriers:

| Table | TranStats ID | Content |
|---|---|---|
| T-100 Domestic Segment (US Carriers) | `FIM` | Domestic flights only |
| T-100 Segment (US Carriers Only) | `GDM` | Domestic and international flights |

The results for 2025, passenger flights, long-haul limit 2,500 miles, **VERIFIED**:

| Airport | Domestic table | Combined table |
|---|---|---|
| ANC | 13.4% | 14.9% |
| SFO | 13.6% | 18.4% |

The domestic table leaves out the international flights. The question says "flights out of Anchorage". Thus, **the combined table is the default**.
The BTS site says the last months of the domestic data can be cut in the combined release. The 2025 file has all 12 months. Re-check this when you use a newer year.

Both tables cover US carriers only. Foreign airlines are not in the data. This is a limit.

### 5.3 Year of the data
- Traffic data (T-100) and delay data (On-Time) use **calendar year 2025**. It is the latest year with 12 months.
- Enplanement growth uses CY23 to CY25.
- The year is a setting and a field in the manifest. Each answer states the year.

### 5.4 Files in the repository

All files go in `src/main/resources/data/`.

**`airports.csv`** (one row for each airport)

| Column | Meaning |
|---|---|
| `iata` | The IATA code. It is the main key of the app. |
| `faa_id` | The FAA location ID (OurAirports `local_code`). |
| `name`, `city`, `state` | Text for display. |
| `region` | `NEW_ENGLAND` for CT, ME, MA, NH, RI, VT. Other regions are optional. |
| `lat`, `lon` | Coordinates. |
| `runway_count` | Number of open runways in `runways.csv`. |
| `hub_class` | `L`, `M`, `S` or `N` from the FAA `Hub` column. |

**`traffic.csv`** (one row for each airport, from T-100)

| Column | Meaning |
|---|---|
| `pax` | Passengers on passenger flights that leave the airport. |
| `seats` | Seats on those flights. |
| `departures_pax` | Departures performed on passenger flights. |
| `departures_cargo` | Departures performed on cargo-only flights. |
| `year` | Data year. |

**`distance_buckets.csv`** (several rows for each airport, from T-100)

| Column | Meaning |
|---|---|
| `iata` | Airport. |
| `bucket_min_miles` | Lower limit of the bucket. The step is 500 miles. |
| `departures_pax` | Passenger departures in the bucket. |
| `departures_cargo` | Cargo departures in the bucket. |

**`ontime.csv`** (one row for each airport, from On-Time)

| Column | Meaning |
|---|---|
| `flights` | Departing flights in the data (not cancelled and cancelled). |
| `delay15_rate` | Flights with a departure delay of 15 minutes or more ÷ flights that did not cancel. |
| `cancel_rate` | Cancelled flights ÷ flights. |
| `avg_taxi_out_min` | Mean taxi-out time. |

**`enplanements.csv`** (one row for each airport, from FAA)
`enpl_cy23`, `enpl_cy24`, `enpl_cy25`, `faa_rank_cy25`.

**`aliases.csv`** (written by hand)
`alias,iata`. Examples: `LA,LAX`, `Los Angeles,LAX`, `Santa Ana,SNA`, `PBI,DJT`.

**`manifest.json`** (written by the script and by hand)
For each source: the name, the URL or page, the download date, the data year, the filters and the known limits.

The app does not save scores, percentiles or ranks. It calculates them.

### 5.5 Rules of the prepare script
1. Download the files (T-100 form, On-Time months, FAA, OurAirports).
2. T-100: remove rows with `DEPARTURES_PERFORMED` equal to 0.
3. T-100: remove rows with `ORIGIN` equal to `DEST`.
4. T-100: a row is a **passenger row** if `SEATS` is more than 0. Other rows are cargo rows.
5. T-100: add up by origin. Put each row in a distance bucket. Weight by `DEPARTURES_PERFORMED`.
6. On-Time: for each origin, count flights and cancelled flights. Calculate the delay rate and the mean taxi-out time.
7. FAA: read the two xlsx files and join the years by `Locid`.
8. Join all sources to the OurAirports row. See section 5.6.
9. Write the CSV files and the manifest. Print a report of airports with missing values.

The raw downloads stay out of the repository (listed in `.gitignore`).
The script stays in the repository, in the `scripts/` folder, so a reviewer sees how the files were made.

### 5.6 ID matching
The sources use different IDs:
- T-100 and On-Time use codes like IATA codes.
- The FAA file uses the FAA location ID (`Locid`).
- OurAirports has `iata_code` and `local_code`.

Rules:
1. The OurAirports row is the master record for each airport.
2. FAA IDs match `local_code` first. T-100 and On-Time codes match `iata_code` first. The second try is the other code, then the alias table.
3. Write each unmatched row to a report. Do not drop it without a record.
4. A code can change over time. West Palm Beach is `PBI` in the BTS files and the FAA CY2024 file, and `DJT` in OurAirports and the FAA CY2025 file. The script has a fixed override (OurAirports ident `KPBI` to code `PBI`). It merges the two FAA entries into one airport.
5. The airport universe is the FAA commercial service list (CY2025 and CY2024 files). It includes the US territories (PR, VI, GU, MP, AS). Foreign airports are not in the universe.

**VERIFIED (2026-10-14, full run of the script):**
- The FAA lists 523 commercial service airports. After the PBI fix and the merge of two PBI and DJT entries, the universe has 522 airports. No FAA airport is lost.
- 519 of 522 airports have T-100 traffic. The three without traffic are tiny (Kake, Branson and Green River).
- 352 airports have On-Time data. 170 have none. These are mostly small airports.
- 71 T-100 origin codes match only an FAA ID. Most of them are foreign airports whose IATA code equals the FAA ID of a small US airfield (examples: PUJ, HND, DUB, PVG). None of them enters the universe. The exception is `1G4` (Grand Canyon West), which BTS uses as an origin code. A test checks that foreign hubs are not in the data.
- A total of 978 T-100 origin codes (about 65.7 million passengers) are outside the universe. They are mostly foreign airports. The script writes the largest ones in its report.

### 5.7 Data traps (and how the design handles each)

| # | Trap | Handling |
|---|---|---|
| 1 | One T-100 row for each carrier, route, aircraft type and month. | Add up by origin. |
| 2 | Rows with zero departures and rows where origin equals destination. | Remove them. |
| 3 | Distance is not a number to average. | Use distance buckets weighted by departures. |
| 4 | Cargo flights are a large share at Anchorage. | Pass the passenger and cargo values separately. The default is passenger only. The answer shows both. |
| 5 | The long-haul limit has no standard. | The limit is a parameter. The default is 2,500 miles. The answer states it. |
| 6 | Domestic and combined tables differ at ANC and SFO. | Use the combined table. State it. |
| 7 | ID mismatch between sources. | Section 5.6. |
| 8 | On-Time data is missing for some airports. | Section 5.8. Missing data is not zero. |
| 9 | T-100 counts segments, not trips. | State this in the assumptions. |
| 10 | "LA" means LAX. "Santa Ana" means SNA, not SAT. | Alias table. |

### 5.8 Coverage of On-Time data (full year 2025, **VERIFIED**)
- 352 of the 522 airports have On-Time data.
- The airports of the example questions have it: SFO 144,495 flights, LAX 190,472, SNA 44,997, ANC 18,860.
- New England airports with enough data: BOS (142,859 flights), BDL (23,108), PVD (15,528), PWM (10,750), BTV (6,551), MHT (5,979), BGR (3,656), ACK (1,582), MVY (1,070).
- Not enough data: ORH (962), PSM (506), HYA (380), PQI (365).
- **No data:** HVN (New Haven) and the smallest New England airports (BID, WST, BHB, LEB, RKD, PVC, RUT, AUG, EWB).

The reason is that On-Time covers only large reporting airlines.
Thus, some airports have T-100 traffic but no delay data.
The design handles this with the missing-data rule (section 6.5) and a minimum number of flights (setting `min-ontime-flights`, **PROPOSED** value 1,000 a year). With 1,000 flights, a delay rate of 20% has a sampling error of about 1.3 points. New England has 23 commercial airports. 10 of them are above the size limit of 100,000 enplanements: BOS, BDL, PVD, PWM, HVN, BTV, MHT, BGR, ACK and ORH. HVN and ORH have no delay KPIs, so their scores use lower coverage.

### 5.9 Size
All files together are a few hundred KB. The app loads them in less than a second.

---

## 6. Scoring methodology

All of this logic is in the `scoring` package. It uses no AI.

### 6.1 Raw KPIs (the facts)

| KPI | Formula | Source | Quality tag |
|---|---|---|---|
| `delay15Rate` | Delayed 15 minutes or more ÷ flights that did not cancel | On-Time | Direct |
| `taxiOut` | Mean taxi-out minutes | On-Time | Direct |
| `cancelRate` | Cancelled ÷ flights | On-Time | Direct |
| `opsPerRunway` | (2 × passenger departures) ÷ runway count | T-100, OurAirports | Proxy |
| `growth` | (enpl CY25 ÷ enpl CY23)^(1/2) − 1 | FAA | Direct |
| `loadFactor` | Passengers ÷ seats | T-100 | Proxy |
| `paxPerDeparture` | Passengers ÷ passenger departures | T-100 | Proxy |
| `longHaulShare(limit)` | Passenger departures in buckets at or above the limit ÷ all passenger departures. A share below 1% is set to 0 (section 6.8). | T-100 | Direct |
| `enplanements` | Enplanements of CY25 | FAA | Direct |

Notes:
- `opsPerRunway` assumes that arrivals equal departures. It counts runways, not independent runway operations. It is a weak KPI at airports with close-parallel runways. See section 14.
- `loadFactor` measures seat use. It is a proxy for pressure on the terminal.
- `paxPerDeparture` shows the size of the aircraft. A high value with flat traffic can show terminal strain. This is an assumption.
- `enplanements` shows the size of the airport. It is the input of the scale component.

### 6.2 Normalization: percentile rank
For each KPI, the value is converted to a percentile among the **ranked set** (section 6.6):

`pct(x) = 100 × (number of values below x + 0.5 × number of values equal to x) ÷ N`

Reasons:
- Traffic data has a long tail. Min-max normalization lets a few very large airports use all the range.
- With raw values, a KPI with a wide range can dominate the score. Then a small airport with fast growth can rank above a large, congested airport. The percentile on all components prevents this.
- A percentile limits the effect of outliers. No extra outlier cut is needed. This keeps the design simple.

For KPIs where a high value is **less** pressure (none at this time), the code uses `100 − pct`.

### 6.3 Components

| Component | Formula |
|---|---|
| Congestion | Mean of `pct(delay15Rate)`, `pct(taxiOut)`, `pct(cancelRate)`, `pct(opsPerRunway)`. Equal weights. Use only the available KPIs. |
| Growth | `pct(growth)` |
| Load factor | `pct(loadFactor)` |
| Terminal strain | `pct(paxPerDeparture)` |
| Long-haul | `pct(longHaulShare)` |
| Scale | `pct(enplanements)` |

The congestion score does not include traffic volume. Volume and congestion are different things. The size of the airport is the separate scale component.

**Why a scale component:** without it, the first run on the real data put Provo (531,000 enplanements, growth 13% a year) in 4th place of the national `terminal` ranking, above JFK. A larger airport gives a larger investment, and the brief asks where a renovation is most profitable. With the scale component (weight 0.15), the national top 10 are all large airports and Provo is 24th. See section 21.1.

### 6.4 Weight profiles (the opportunity score)

`score = Σ (weight × component) ÷ Σ (weights of the available components)`

| Component | `terminal` profile | `general` profile |
|---|---|---|
| Congestion | 0.25 | 0.35 |
| Growth | 0.20 | 0.25 |
| Load factor | 0.15 | 0.25 |
| Terminal strain | 0.15 | 0 |
| Long-haul | 0.10 | 0 |
| Scale | 0.15 | 0.15 |

**These weights are heuristics. They are PROPOSED start values.** They are not fitted to data. Nobody has tested them against real investment results.
The weights are in `application.yml`. The app checks at start-up that each profile sums to 1.0.
The answer states which profile it used.

### 6.5 Missing data
- If a KPI is missing, the code removes it from its component.
- If a whole component is missing, the code removes it from the score. Its weight goes to the other components in proportion.
- If all components are missing, the score is empty. It is never zero.
- Each score has a `coverage` value. The weight of a component is shared equally among its KPIs. The coverage is the weight of the KPIs that have data ÷ the total weight. Thus, a missing KPI inside a component lowers the coverage, but it does not change the score value. Example: New Haven (HVN) has no delay data. The congestion component has 4 KPIs and HVN has 1 of them. The coverage of HVN is 0.78.
- Each score lists the missing KPIs (`missingKpis`) and the missing components (`missingComponents`).
- Each score has a `confidence` value:
  - HIGH: coverage 0.9 or more.
  - MEDIUM: coverage from 0.7 to 0.9.
  - LOW: coverage below 0.7.
- Each answer shows the coverage and the missing items.

### 6.6 The ranked set and the eligibility rule
- The data files may hold all US airports with scheduled passenger service (522 airports, **VERIFIED**: the FAA commercial service list including US territories).
- The **ranked set** has only airports with at least `min-enplanements` enplanements in the latest year. The **PROPOSED** value is 100,000. It is a setting.
- Percentiles use the ranked set.
- Reason: a very small airport with high growth from a small base must not rank first for a terminal expansion. Also, a set with many tiny airports lowers the percentile of a medium airport.
- An airport that is in the files but not in the ranked set gets a message: "below the size limit". The tool returns the raw KPIs if the analyst asks for them.

The ranked set also gives the **scope**. The agent must say it in each ranking answer.

### 6.7 Ranking rules
1. Sort by score, high to low.
2. If two scores are equal, sort by passengers, high to low.
3. If they are still equal, sort by IATA code.

The result is the same on each run.

### 6.8 Special calculations

**Congestion comparison (LAX and SNA)**
- Show each KPI side by side, with the raw value and the percentile.
- Show the hub class of each airport. LAX is a large hub and SNA is a medium hub (the FAA file confirms this, **VERIFIED**).
- If the classes differ, add the warning: "The airports have different sizes. Compare absolute values with care."
- Optional: show the percentile inside each hub class.

**Long-haul share (Anchorage)**
- `share = Σ departures in buckets at or above the limit ÷ Σ departures`.
- The limit is a parameter. The default is 2,500 miles (**PROPOSED**). Other limits that an analyst can use: 1,500 miles and 4,000 km (about 2,500 miles).
- The limit must be a multiple of 500 miles, because of the bucket size. Otherwise the tool rounds it down and says so.
- The tool always returns both values: passenger flights only, and with cargo flights. It also returns the limit used, the cargo share of the departures and the table. The agent uses the passenger value as the main value unless the user asks for cargo.
- **Noise floor in the scoring:** a long-haul share below 1% is set to 0 in the KPI of the score (`MetricsBuilder.LONG_HAUL_NOISE_FLOOR`). Reason: SNA has 3 long-haul departures of 50,000 (0.006%). Many airports have exactly 0%, so the percentile of this noise was 70. With the floor it is 42 (the tie group). The long-haul tool still shows the real share.
- Values for ANC in 2025 (**VERIFIED**, domestic table): at 2,500 miles, 13.4% (passenger) and 25.1% (with cargo). At 1,500 miles, 21.1% and 32.3%. Cargo flights are about 40% of departures.
- Values for ANC in 2025 (**VERIFIED**, combined table, passenger only, 2,500 miles): 14.9%.

**Unmet demand index (SFO)**
- `unmetDemand = 0.4 × loadFactor + 0.3 × growth + 0.3 × congestion` (all are percentiles).
- It is a **proxy**. No public data measures demand that airlines did not serve.
- The result always has the label `PROXY` and a text that says what the index is and is not.
- The weights are **PROPOSED**. The analyst can change them.
- The "why" comes from the contributions of the components, sorted by points.
- The tool adds an expert note for SFO (file `airport_notes.csv`): the close-parallel runways cannot operate independently in low visibility. The `opsPerRunway` KPI does not show this. The result labels the note with its source text: "expert note, not calculated from the data files, check it with the FAA before use". It is never a score input.

### 6.9 Score trace (for the explanation)
Each score has a list of `Contribution` records:
`component`, `rawValue`, `percentile`, `weight`, `points`, `qualityTag`.
The points add up to the score. The LLM explains the score from this list. It must not add facts.

### 6.10 Worked example (numbers are for illustration only)
Airport X, `terminal` profile, all components available:

| Component | Percentile | Weight | Points |
|---|---|---|---|
| Congestion | 80 | 0.25 | 20.0 |
| Growth | 60 | 0.20 | 12.0 |
| Load factor | 90 | 0.15 | 13.5 |
| Terminal strain | 50 | 0.15 | 7.5 |
| Long-haul | 20 | 0.10 | 2.0 |
| Scale | 70 | 0.15 | 10.5 |
| **Score** | | 1.00 | **65.5** |

If the long-haul value is missing, the weights of the other five become 0.25 ÷ 0.90 and so on. The score is 63.5 ÷ 0.90 = 70.6. The coverage is 0.90. The confidence is HIGH or MEDIUM by the rule in section 6.5.

---|---|---|---|
| Congestion | 80 | 0.30 | 24.0 |
| Growth | 60 | 0.25 | 15.0 |
| Load factor | 90 | 0.20 | 18.0 |
| Terminal strain | 50 | 0.15 | 7.5 |
| Long-haul | 20 | 0.10 | 2.0 |
| **Score** | | 1.00 | **66.5** |

If the long-haul value is missing, the weights of the other four become 0.30 ÷ 0.90 and so on. The score is 64.5 ÷ 0.90 = 71.7. The coverage is 0.90 and the confidence is HIGH or MEDIUM by the rule in section 6.5.

---

## 7. How each example question is answered

| Question | Tools and data | Traps to state in the answer |
|---|---|---|
| New England expansion | `rankAirports(region=NEW_ENGLAND, profile=terminal, topN)` | Only airports above the size limit are ranked. Some have missing delay data (BGR, PSM, HVN). The score is an opportunity index, not a profit forecast. |
| LAX and SNA congestion | `resolveAirport` twice, then `compareAirports(codes, congestion)` | The airports have different hub classes. The runway KPI is simple. |
| ANC long-haul percentage | `getLongHaulShare(ANC, minMiles)` | The result depends on the limit, the cargo choice and the table. The tool returns both the passenger value and the value with cargo. International flights of foreign airlines are not in the data. |
| SFO unmet demand and why | `getUnmetDemandIndex(SFO)` and `getAirportProfile(SFO)` | It is an index, not a number of flights. The close-parallel runway effect is a note, not a calculation. |

---

## 8. Where and how AI is used

### 8.1 What the LLM does
1. It reads the user question and the chat history.
2. It chooses a tool and its inputs.
3. It reads the tool result.
4. It writes the answer in plain language.
5. It asks a short question if the user input is not clear.

### 8.2 What the LLM does not do
- It does not calculate any number.
- It does not rank airports.
- It does not read the data files.
- It does not call the public APIs. Java code calls them.
- It does not decide the scope. Code decides it.

### 8.3 Tools (Java methods with `@Tool`)

| Tool | Purpose |
|---|---|
| `resolveAirport(text)` | Convert a name or code to an airport. Returns the match, the confidence and alternatives. |
| `rankAirports(region, profile, topN)` | Rank the ranked set. |
| `compareAirports(codes, focus)` | Compare two or more airports. |
| `getAirportProfile(code)` | KPIs, components and the score trace of one airport. |
| `getLongHaulShare(code, minMiles)` | Long-haul share. Returns the passenger value and the value with cargo flights. |
| `getUnmetDemandIndex(code)` | The proxy index and its parts. |
| `getLiveStatus(code)` | Current FAA status. Not a score input. |
| `getMethodology()` | Weights, limits and scope, for questions about the method. |

Each tool returns a `QueryResult` (section 11.1). Each tool calls one method of `AirportQueryService`.

### 8.4 The live API call
- The `getLiveStatus` tool calls `https://nasstatus.faa.gov/api/airport-status-information`.
- **VERIFIED:** it works with no key. It returns XML. It listed a ground stop at LAX and a closure at SNA at the time of the test.
- The list has only active events. An airport that is not in the list has no active delay program.
- The list has many closure notices for small reasons (example: closed to general aviation). The client must filter by event type and show only ground stops, ground delays and full closures.
- The call has a timeout of 3 seconds. If it fails, the tool returns "live status not available".
- The result shows the update time of the FAA data.
- The live status does not change a score. The past data shows long-term pressure. The live status shows today's event.
- A second endpoint (`/api/airport-events`) returns JSON. Its format is not documented. The design does not use it.

### 8.5 System prompt rules
The system prompt says:
1. Use only numbers that are in the tool results.
2. If a tool returns `NotAvailable` or `OutOfScope`, say so. Do not use your own knowledge to fill the gap.
3. Always state the assumptions and the data year when you give a number.
4. State the profile, the limit and the table that the tool used.
5. If the user asks about a part that the app cannot answer, answer the other parts. Name the part that you cannot answer.
6. Always call `resolveAirport` for a name that the user gives.
7. Do not give investment advice. Say that the index is a screening tool.

The text of the prompt is in `src/main/resources/prompts/system.txt`. It has 14 rules and a short guide that maps the four example questions to tools.

### 8.6 Chat memory
- The app keeps the chat history in memory, for each session ID (`MessageWindowChatMemory`).
- The history has a limit of the last 20 messages.
- The agent also keeps the tool results of the last 30 calls of the session (`ToolLog`). A follow-up answer may use a number from an earlier turn without a new tool call.
- A restart of the app clears all history. This is acceptable for the task.
- The app keeps at most 200 sessions. The oldest session is removed first.

### 8.7 The number check
- After the LLM writes the answer, `NumberCheck` finds each number in the text.
- It compares the numbers with the numbers in the tool results of the session and with the numbers in the question of the user.
- A number is accepted if it equals a tool value after rounding to the decimals that the answer shows. A share (0.149) may appear as a percentage (14.9%). A large value may appear with "million" or "billion".
- Whole numbers below 10 are not checked. They are ranks, counts and list numbers.
- If a number is not in the results, the agent asks the LLM to write the answer again, one time. The new question names the numbers that failed.
- If it fails again, the app returns a template answer built from the tool data: the status, the assumptions, the uncertainties and the data of each tool call. The reply has `verified = false`.
- **Limit:** the check finds numbers only. It does not find wrong claims in words. It also rejects a correct number that the LLM calculates (for example a difference between two values). This is by design.

### 8.7.1 Limits of the agent
- A turn may use at most 5 tool calls (`ToolLog.MAX_CALLS_PER_TURN`). The next calls get a NOT_AVAILABLE result that tells the LLM to answer with the data that it has.
- The message of the user may have at most 1,000 characters.
- If the LLM call fails (no network, quota, error), the reply says so and points to the REST API under `/api`.
- If the key is not set, the app still starts. Spring AI refuses to start with an empty key, so `application.yml` uses the value `not-set`. The chat then answers with a message and makes no LLM call. A test checks this.

### 8.8 Fallback without the LLM
- The REST API works with no LLM.
- Optional (**PROPOSED**, only if time remains): a keyword router for the four example questions. It calls the same service and returns a template text. It makes a demo possible if the LLM key fails.

---

## 9. Response envelope
Each tool result and each REST response has an envelope:

| Field | Meaning |
|---|---|
| `assumptions` | The list of assumptions that apply to this result. |
| `uncertainties` | Data gaps, proxies and age of the data. |
| `outOfScope` | Parts that the app cannot answer. |
| `confidence` | HIGH, MEDIUM or LOW. |
| `sources` | Source names and data years. |
| `asOf` | Date of the data files (from the manifest). |
| `profile`, `parameters` | Weight profile and parameters used (limit, cargo choice, table). |

Rule: if the manifest date is more than 90 days old, the envelope adds the uncertainty "data may be old". (**PROPOSED**.)

---

## 10. User interface and voice
- One HTML page at `/`. It has a message list and a text box.
- The page keeps a session ID in the browser memory and sends it with each message.
- The page shows the answer text and a collapsible panel with the assumptions, the confidence and the score trace.
- Voice input: `SpeechRecognition` of the browser. Voice output: `speechSynthesis`. A button turns each on or off.
- Support depends on the browser. Chrome and Edge support it. If the browser does not support it, the page hides the buttons. **NOT VERIFIED** for each browser.
- The backend has no voice code.

---

## 11. Errors, gaps and edge cases

### 11.1 Result types (`QueryResult`)

```java
sealed interface QueryResult<T> {
    record Ok<T>(T data, Envelope envelope) implements QueryResult<T> {}
    record Partial<T>(T data, List<String> missing, Envelope envelope) implements QueryResult<T> {}
    record NotAvailable<T>(String reason, List<String> whatIsAvailable) implements QueryResult<T> {}
    record OutOfScope<T>(String reason, String scopeRule) implements QueryResult<T> {}
}
```

### 11.2 Kinds of gaps

| Gap | Result | Example |
|---|---|---|
| Airport is not in the data | `OutOfScope` | "Analyze Boise" if Boise is not in the files. |
| Airport is below the size limit | `Partial`: the KPIs and a score against the ranked airports, with the flag `inRankedSet = false`. The airport is never in a ranking. | A very small airport. |
| One KPI is missing | `Partial`, lower coverage | HVN has no delay data. |
| The question needs data that does not exist | `NotAvailable` or a labeled proxy | "Exact unmet demand". |
| Year or detail not in the data | `NotAvailable` with the list of what exists | "Traffic in 2019". |
| Unclear airport name | A question to the user, or a list of options | "Portland". |
| Live API fails | `NotAvailable` for that tool only | FAA timeout. |
| LLM API fails or the key is missing | A clear error message. The REST API still works. | No key. |

### 11.3 Scope control in code
`resolveAirport` returns `OutOfScope` for an airport that is not in the ranked set. The agent must not name an airport that is outside the set. A rule in the prompt is not enough, because the LLM can still name such an airport.

---

## 12. Configuration
All settings are in `application.yml`:
- LLM provider, model and API key (from an environment variable).
- Weight profiles.
- Unmet demand weights.
- `min-enplanements`, `min-ontime-flights`.
- Default long-haul limit and cargo choice.
- Data year.
- FAA call timeout.
- Chat memory size.

Do not put the API key in the repository.

---

## 13. Test strategy

| Level | What | Why |
|---|---|---|
| Unit: scoring | Golden values from a small fixed data set, calculated by hand. | Proves the formulas. |
| Unit: order | A higher KPI never gives a lower score. | Catches sign errors. |
| Unit: missing data | A missing component reduces coverage and does not give zero. | Proves the rule. |
| Unit: weights | Each profile sums to 1.0. | Catches setting errors. |
| Unit: size trap | A small airport with high growth does not outrank a large, congested airport. | Regression test for the size problem (section 6.2). |
| Unit: long-haul | Bucket sums for known inputs. The limit rounds down to a multiple of 500. | Proves the calculation. |
| Data: loaders | Each airport of the four example questions and all New England commercial airports have the fields that the answers need. | A gap here is the worst failure. |
| Integration: same numbers | The REST result and the chat tool result are equal. | Proves the single engine. |
| Agent: fake LLM | A fake `ChatModel` returns tool calls. Check the tool use and the "not available" text. | No real LLM call. |
| Architecture | `scoring` and `domain` do not depend on `agent`, `api` or Spring AI. | Makes the claim testable. |
| Number check | An answer with a number that is not in the results is rejected. | Proves the check. |

The four example questions also run by hand at the end, and the results go in the README.

---

## 14. Assumptions

| # | Assumption | Effect if wrong |
|---|---|---|
| A1 | Higher traffic pressure and growth mean a higher need of capacity. | The index shows the wrong airports. |
| A2 | The opportunity index is a proxy for investment attractiveness. | The index is not a profit forecast. |
| A3 | Calendar year 2025 is a good picture of the current state. | Changes after 2025 are not seen. |
| A4 | Arrivals equal departures at an airport. | `opsPerRunway` is wrong by a factor. |
| A5 | Each open runway has the same capacity. | The runway KPI is wrong at airports with close-parallel or short runways. |
| A6 | Load factor and passengers per departure are proxies for terminal pressure. | They can also show only airline choices. |
| A7 | On-Time data for the reporting airlines shows the delay of the airport. | Small carriers are not in the data. |
| A8 | T-100 passenger rows are rows with seats above 0. | A row with a data error is in the wrong group. |
| A9 | The 2,500-mile limit is a good default for long-haul. | The share changes with the limit. |
| A10 | The percentile among ranked airports is a fair scale. | The set changes the percentiles. |
| A11 | The weights are reasonable start values. | The ranking can change with other weights. |
| A12 | Growth from CY23 to CY25 shows a trend. | Two years can be a short-term effect. |
| A13 | T-100 segment counts are correct for departures. | They count segments, not trips. |
| A14 | The FAA hub class from the file is correct for the year. | Peer groups are wrong. |
| A15 | The names "LA" and "Santa Ana" mean LAX and SNA. | The wrong airport is analyzed. |
| A16 | A long-haul share below 1% is noise (for example positioning flights). | An airport with a real but very small long-haul service gets a share of 0 in the score. |
| A17 | A closure notice with words like EXC, GA, PPR, HEL or RWY restricts only some flights. It is not a full closure. | A full closure can be counted as a restriction. The live status shows the count of restriction notices. |
| A18 | The size of the airport (enplanements) is a positive signal for the value of an investment. | Small airports with a good case rank lower than they should. |

---

## 15. Limitations

### 15.1 Data
- US airlines only in T-100. Foreign airlines are not in the data.
- On-Time data covers only large reporting airlines. Some airports have no data.
- T-100 counts segments, not full trips. An Anchorage to Seattle to New York trip counts as two segments.
- One year of traffic and delay data.
- Two years of growth data.
- The FAA CY2023 values are as published. The LAX value is higher than the later years, so the growth of LAX is negative. The growth KPI uses two years only.
- Foreign airports are not in the data, and foreign airlines are not in T-100.
- A foreign IATA code can equal the FAA ID of a small US airfield. The join is checked for this. The check covers the data of 2025 only.
- No data on gates, terminal area, runway length, pavement condition, land, cost or ownership.
- No international passenger data by airport from a source other than T-100.
- No weather data.

### 15.2 Method
- No public data measures unmet demand. The index is a proxy.
- The runway KPI treats each runway as independent. At SFO, close-parallel runways cannot work independently in low visibility. The KPI shows less constraint than the real one.
- The weights are heuristics. They are not fitted or tested.
- The score favors large airports by design (scale component and size filter). A small airport with a strong case ranks lower.
- The index does not include construction cost, airline agreements, politics, environment or financing.
- The index is a screening tool. It is not an investment decision.
- The percentile scale depends on the size of the ranked set.
- Small differences between scores are not significant. The tool does not calculate an error range.

### 15.3 System
- The chat memory is in the app memory. A restart clears it.
- The app has no user login, no rate limit and no database. It is a demo.
- The quality of the answer text depends on the LLM.
- The number check does not find wrong claims in words.
- The live FAA call depends on a service that has no guarantee.
- The TranStats download uses a web form. BTS can change it. The prepare script can then fail.
- The free LLM tier has limits. The limits can change.

---

## 16. Decisions and trade-offs

| # | Decision | Alternatives | Reason | Trade-off |
|---|---|---|---|---|
| D1 | The LLM only chooses tools and explains. | The LLM calculates. | Numbers must be exact and testable. | The LLM cannot answer a question that has no tool. |
| D2 | One engine, REST and chat entry points. | Two separate paths. | Same numbers. Easy to test. | One more layer of code. |
| D3 | Percentile rank for all components. | Min-max, z-score. | Robust to a long tail. | A percentile loses the size of the difference. |
| D4 | Remove missing components and share the weights. | Fill with the median or zero. | No invented values. | Scores with different coverage are not exactly comparable. A coverage value is shown. |
| D5 | Save facts offline. Calculate scores at start-up. | Save scores. Call APIs at run time. | The scores follow the code and the settings. The app is fast and does not depend on big downloads. | The data can be old. The manifest shows the date. |
| D6 | In-memory data. | Database. | About 500 rows. | No query language. Not for larger data. |
| D7 | Spring AI. | LangChain4j, hand-written loop. | Best practice in Java. Tested tool loop. | The author must learn it. It changes fast. |
| D8 | Keep Spring AI only in the `agent` package. | Use it everywhere. | The core stays clean and testable. | A small layer of conversion code. |
| D9 | Combined T-100 table as the default. | Domestic table. | "Flights out of Anchorage" includes international flights. | The data can lose the last months. Foreign airlines are still missing. |
| D10 | Long-haul limit as a parameter. | A fixed limit. | No standard limit exists. | The user must know that the answer depends on it. |
| D11 | Passenger flights as the default. | With cargo. | The question is about passenger service. | Cargo is a large part of the traffic at ANC. The answer shows both. |
| D12 | Size filter for the ranked set. | Rank all airports. | A tiny airport with fast growth is not a good candidate. | The limit is a choice. Small airports are not ranked. |
| D13 | The live FAA data is an overlay only. | Use it in the score. | It shows one day. It is not a trend. | The "public API" use is small. |
| D14 | The prepare script is in Python. | Java. | Faster to write. It runs once. | The repo has two languages. |
| D15 | No feasibility factors (land, pavement, ownership). | Add them. | Simple. The brief asks about capacity. | The index does not say if a project can be built. |
| D16 | A number check on the answer. | Trust the LLM. | Catches wrong numbers. | It does not find wrong words. |
| D17 | Use the 2025 data year. | 2024. | Latest year with 12 months. | The FAA file for CY22 does not exist. Growth uses two years. |
| D18 | One module with packages. | Multi-module. | Less complexity. | Weaker enforcement of boundaries. ArchUnit covers it. |
| D19 | A scale component (percentile of enplanements) with weight 0.15. | No size component. | Without it, a mid-size airport with fast growth ranks above the largest airports. A larger airport gives a larger investment. | The score favors large airports. The size filter and the scale component both do this. A small airport with a good case ranks lower. |

---

## 17. Open decisions (the author must decide)

Decided on 2026-10-14: Java 21, Gemini free tier, Python for the prepare script, and the weights of section 6.4 and section 6.8 (accepted by the author). The scale component (weight 0.15) was added after the first run on the real data, with the agreement of the author.

Still open:
1. **Optional fallback router.** Build it only if time remains.
2. **Key rotation.** The Gemini key was shared in a chat. Replace it after the assignment.
3. **Model name.** Check at the first real LLM call that `gemini-2.5-flash` works with Spring AI tool calling and that the limit is enough. All agent tests use a scripted model. They do not call Gemini.

---

## 18. Risks

| Risk | Likelihood | Effect | Action |
|---|---|---|---|
| The ID matching loses airports. | Medium | Missing airports in rankings. | Measure the match rate. Fix with the alias table. |
| The TranStats form changes. | Low | The prepare script fails. | Keep the generated files in the repo. |
| The LLM free tier fails during the review. | Medium | No chat. | REST API. Optional fallback router. A short demo recording. |
| The weights seem arbitrary to the reviewer. | Medium | Weak argument. | Show the profile, the trace and the sensitivity. State that they are heuristics. |
| Spring AI version problems with Java 25. | Medium | Build fails. | Use Java 21. |
| The LLM breaks the number rule. | Medium | A wrong number in an answer. | The number check and the prompt rules. |
| The data has a gap for a key airport. | Low | A failed example question. | Data loader tests for the key airports. |

---

## 19. Build plan (about 24 hours)

| Hours | Work |
|---|---|
| 0 to 2 | Final check of the data. Choose the set. Fix the Java and LLM decisions. |
| 2 to 6 | The prepare script and the data files. The ID match report. |
| 6 to 11 | `domain`, `data` loaders, `scoring`, unit tests. |
| 11 to 14 | `AirportQueryService`, REST endpoints, same-number test. |
| 14 to 18 | Spring AI tools, `ChatService`, memory, prompt, number check. **Done.** |
| 18 to 20 | Chat page and voice. **Done** (the voice is not verified in a browser). |
| 20 to 22 | Run the four questions. Fix the problems. |
| 22 to 24 | README and final edit of this document. |

A small and clear set with clear assumptions is better than a large set with unclear ones.

---

## 20. Glossary

| Term | Meaning |
|---|---|
| BTS | Bureau of Transportation Statistics, part of the US Department of Transportation. |
| T-100 | BTS data on flights, seats and passengers reported by airlines. |
| Segment | One non-stop flight between two airports. |
| On-Time Performance | BTS data with the delay and cancellation of each flight. |
| FAA | Federal Aviation Administration. |
| NAS | National Airspace System. |
| Enplanement | One passenger who boards an aircraft at an airport. |
| Hub class | FAA size class: large (L), medium (M), small (S), non-hub (N). |
| Load factor | Passengers ÷ seats. |
| Percentile rank | The position of a value among all values, from 0 to 100. |
| Coverage | The share of the score weight that had data. |
| Proxy | A measure that stands in for a thing that has no direct measure. |
| Ranked set | The airports that the app ranks, after the size filter. |
| Tool | A Java method that the LLM can call. |
| Envelope | The part of a result that holds the assumptions, the uncertainties and the sources. |

---

## 21. Record of data checks (2026-10-14)

| Check | Result |
|---|---|
| OurAirports `airports.csv` and `runways.csv` | Status 200. Columns confirmed. |
| FAA enplanement xlsx CY2025 and CY2024 | Status 200. Columns: Rank, RO, ST, Locid, City, Airport Name, S/L, Hub, two enplanement years, % Change. |
| FAA CY23 and CY22 file guesses | Status 404. |
| On-Time monthly ZIP | Available from 2025-01 to 2026-07. 2026-08 gave 404. |
| On-Time columns | Include `Origin`, `DepDel15`, `TaxiOut`, `Cancelled`, `Flights`, `Distance`, `NASDelay`. |
| T-100 Domestic Segment 2025, scripted download | Status 200. 455,857 rows. Months 1 to 12. |
| T-100 combined segment 2025, scripted download | Status 200. 517,764 rows. Months 1 to 12. |
| T-100 columns | `DEPARTURES_SCHEDULED`, `DEPARTURES_PERFORMED`, `SEATS`, `PASSENGERS`, `DISTANCE`, `UNIQUE_CARRIER`, `ORIGIN`, `DEST`, `AIRCRAFT_TYPE`, `YEAR`, `MONTH`, `CLASS`. |
| ANC long-haul (2,500 miles, domestic table) | 13.4% passenger, 25.1% with cargo. |
| ANC long-haul (1,500 miles, domestic table) | 21.1% passenger, 32.3% with cargo. |
| ANC and SFO long-haul (2,500 miles, combined table, passenger) | 14.9% and 18.4%. |
| FAA NAS status | Status 200, XML, no key. |
| NTAD ArcGIS T-100 table | Has no seats and no distance. Not used. |
| Full run of `scripts/prepare_data.py` | 522 airports, 519 with traffic, 352 with On-Time data. The 12 On-Time files take about 12 minutes to download with 6 parallel connections (one connection was about 5 times slower). |
| Hub class values | L, M, S and N all appear in the FAA file. LAX is L and SNA is M. |
| Runway counts | Every airport of the example questions has runways. One tiny airport (PQS) has none. |
| Real-data tests | 11 tests in `RealDataIntegrityTest` pass. They check the airports of the four example questions, the New England airports, the Anchorage values and the PBI merge. |
| FAA CY2023 value of LAX | 40,956,673 in the FAA CY2024 file. This is higher than CY2024 (37,760,834) and CY2025 (36,497,303). The data is as the FAA publishes it. Thus, the growth of LAX is negative. |
| Service tests | `AirportQueryServiceTest` (23 tests, real data and real `application.yml`), `RestApiTest` (5 tests, a real server on a free port, same numbers as the service), `HttpFaaStatusClientTest` (6 tests, includes one call to the real FAA service). |
| Real FAA call | The call to the NAS status service worked in the test run (the test allows 5 seconds). The answer time was not measured. |
| New England terminal ranking (with scale) | BOS, PWM, HVN, PVD, BGR, BDL, BTV, MHT, ACK, ORH. The scores depend on the weights. HVN and ORH have coverage 0.81. |

### 21.1 Observations from the first run
- **Scale component (resolved).** The first run had no size component. Provo (PVU, 531,000 enplanements, growth 13% a year, load factor 0.86) was 4th in the national `terminal` ranking, above JFK. After the scale component (weight 0.15) the national top 10 is DFW, ORD, MIA, PHL, SFO, EWR, JFK, BOS, DEN and ATL, and Provo is 24th. A test checks this.
- **Bangor (BGR)** was 2nd in New England in the first run (high growth of 10.6% a year, load factor 0.80, delay rate 21%). With the scale component it is 5th in the `terminal` profile and 2nd in the `general` profile.
- **LAX growth is negative** (-5.6% a year) because the FAA CY2023 value is high (see section 15.1).

### 21.2 Observations from the agent build
- **Spring AI 2.0.1 runs the tool loop in the chat client** (an advisor), not in the model. The advisor works only if the options of the model can carry tools. The chat client takes its options from `ChatModel.getOptions()`. A real model gives such options. The test model must do the same.
- **An empty key stops the whole app.** The start with `api-key=` failed with "Incomplete Google GenAI configuration". The REST API was down too. The default value `not-set` fixes this.
- **A tool result is a JSON text.** The tools return `ApiResponse` (the same type as the REST API). The agent records its JSON for the number check.

Checks that are still open: browser support for voice, and the real behavior of Gemini with the tool calls (no real LLM call was made in the tests).
