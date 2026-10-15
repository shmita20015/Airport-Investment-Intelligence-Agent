# Design: Airport Investment Intelligence Agent

This is the short design document. The long version is the appendix: [`docs/DESIGN-full.md`](docs/DESIGN-full.md). It has all assumptions, data checks and decisions.

## 1. Main idea and the four answers
The LLM chooses tools and explains results. **Java code calculates every number.** One engine (`AirportQueryService`) serves the chat tools, the REST API and the web page, so all three give the same numbers. If a value is unknown, the engine removes it. It never scores it as zero.

| Question | Tool call | Result |
|---|---|---|
| New England airports for terminal expansion | `rankAirports(NEW_ENGLAND, terminal)` | BOS, PWM, HVN, PVD, BGR, BDL, BTV, MHT, ACK, ORH, with score, confidence and the reason of each score |
| LAX and SNA congestion | `compareAirports(LAX, SNA, congestion)` | KPIs side by side. **Code says which airport is higher**: LAX 50.29, SNA 48.65, a gap of 1.64 points. A warning says that LAX is a large hub and SNA a medium hub. |
| Long-haul share at Anchorage | `getLongHaulShare(ANC, 2500)` | 14.9% of passenger departures. The tool also gives the share with cargo flights. The distance limit is a parameter. |
| Unmet demand at SFO and why | `getUnmetDemandIndex(SFO)` | An index of 67.0, labeled **PROXY**. An expert note (close-parallel runways in low visibility) gives the reason. The note is not a score input. |

## 2. Scoring method
1. Each KPI becomes a **percentile** among the 233 airports with at least 100,000 enplanements.
2. A **component** is the mean of the percentiles of its KPIs.
3. The **score** is the weighted mean of the components that have data. The weights of missing components go to the others.

| Component | KPIs | `terminal` | `general` |
|---|---|---|---|
| Congestion | delay rate, taxi-out time, cancellation rate, operations per runway | 0.25 | 0.35 |
| Growth | enplanements, CY23 to CY25 (yearly rate) | 0.20 | 0.25 |
| Load factor | passengers / seats | 0.15 | 0.25 |
| Terminal strain | passengers per departure | 0.15 | 0 |
| Long-haul | share of departures of 2,500 miles or more | 0.10 | 0 |
| Scale | enplanements CY25 | 0.15 | 0.15 |

- **Confidence:** coverage is the part of the weight that has KPI data. HIGH is 0.9 or more, MEDIUM is 0.7 or more, otherwise LOW.
- **Ties** are broken by passengers, then by IATA code. The ranking is repeatable.
- The weights are **heuristics**. They are in `application.yml`. Data do not fit them.
- **What-if:** a user can change the weights with a slider or in the chat. Java checks the input, and the result is marked `custom`.

**Data:** BTS T-100 and On-Time Performance, FAA enplanements, OurAirports (saved as CSV files by `scripts/prepare_data.py`), and the live FAA status (not a score input). The scores are calculated at run time.

**Data refresh:** the data are bulk files of public sources, not API calls. The only call at run time is the FAA status. An optional monthly job (`airport.refresh`, off by default) runs the script again and restarts the app with the new files. It has a simple retry (3 tries) and a basic check (the files can be read and the data are not empty). If a check fails, the app keeps the old data.

## 3. Where AI is used (and where it is not)
- **The LLM does:** read the question and the chat history, choose a tool and its inputs, and write the answer.
- **The LLM does not:** calculate, rank, read data files or call public APIs.
- **Tools:** 8 `@Tool` methods. Each calls one method of `AirportQueryService`. A turn may use 5 tool calls at most.
- **Number check:** `NumberCheck` looks for numbers in the answer that no tool result has. If it finds one, the agent asks again one time. Then it shows the raw tool results.
- **Follow-ups:** the chat memory keeps 20 messages per session.

## 4. Key design trade-offs
| Decision | Why | Cost |
|---|---|---|
| **Saved data files, not live API calls.** A script downloads the public files one time. The app reads the CSV files at start-up. | The scores are repeatable. The app starts fast and needs no network. A reviewer can run it with no setup. | The data are as old as the last script run. An optional monthly job (`airport.refresh`) runs the script again. |
| **Save facts, calculate scores at run time.** The files hold sums, counts and rates. They do not hold scores. | A change of a weight or a size limit needs no new data run. The what-if sliders work with the same data. | The app calculates all scores at start-up. The whole app starts in about 2 seconds. |
| **Data in memory, no database.** | About 500 rows. A database adds no value. | No query language. This design does not fit a large data set. |
| **One engine for the chat, the REST API and the page.** | The three give the same numbers. A reviewer can check each chat number in the table. | One more layer. `AirportQueryService` is a large class. |
| **Chat sessions and memory in a bounded cache.** At most 200 sessions. An idle session is deleted after 60 minutes, with its history. | The memory use has a limit. | Sessions are lost at a restart. The app runs as one instance only. |
| **Rate limits in memory.** A token bucket for each client address. | Simple, with no extra service. | The buckets are not shared between instances. Behind a proxy, the proxy must pass the real address. |
| **Refresh by restart.** The job replaces the data folder, checks it, and restarts the app. | The data set stays immutable. There is no hot swap and no locking. | About 1 second without service. The chat sessions are cleared. |
| **The LLM never calculates.** It chooses tools and explains. | The numbers are repeatable and testable. | The agent cannot do new math. `NumberCheck` rejects it. |
| **`NumberCheck` as a safety net.** | It catches invented numbers. | It checks that a number is in some tool result. It does not check that the number belongs to the right airport. It does not check words. |

## 5. Assumptions, uncertainty and scope
Each result has an **envelope**: assumptions, uncertainties, out-of-scope items, confidence, sources and the data date. The agent must state the main ones.
- T-100 has US airlines only. On-Time has large reporting airlines only. Small airports can have no delay data. Their confidence is lower.
- The long-haul share depends on a definition (2,500 miles). There is no standard.
- The score favors large airports (size filter and scale component).
- The score is a screening index, not a profit model. Public data have no cost, revenue or forecast. It shows where pressure is high.
- Unmet demand is a proxy (load factor, growth, congestion). The "why" for SFO comes from one expert note, not from data.
- Percentiles lose the size of a gap. The weights are heuristics, and scale, load factor and congestion are correlated.
- A missing KPI is dropped and the weights are renormalized. An airport with few KPIs can rank high on the rest. The confidence label shows this.
- Foreign airports are out of scope. The tool answers OUT_OF_SCOPE with the reason.

## 6. Extras (short)
These are outside the core of the brief. Details are in the appendix.
- **Page:** the left part is the scoring engine with no LLM (ranked table, sliders, score breakdown, sources). The right part is the chat with voice (browser speech functions).
- **Regions:** 23 regions (Census regions and informal groups) are lists of state codes in `application.yml`.
- **Protection:** per-client rate limits (Bucket4j), 4 concurrent chats, size limits, session limits, cleaning of outside text, removal of links and images from answers. All limits are in `airport.limits`. The tools only read data, so a successful attack can cause a wrong answer, but not a changed score or file. Section 22 of the appendix has the full table.
- **Tests:** 190 tests, with no real LLM call. They cover scoring, real data checks, the REST API, the agent (with a scripted model) and architecture rules (ArchUnit).
