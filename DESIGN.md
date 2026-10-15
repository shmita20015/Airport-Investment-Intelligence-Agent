# Design: Airport Investment Intelligence Agent

This is the short version. The long version has all assumptions, limits, data checks and decisions: [`docs/DESIGN-full.md`](docs/DESIGN-full.md).

## 1. Main idea
The LLM chooses tools and explains results. **Java code calculates every number.** One engine (`AirportQueryService`) serves both the REST API and the chat tools, so both give the same numbers. If a value is unknown, the engine removes it. It never scores it as zero.

```
Browser chat ──> ChatController ──> ChatService ──> Gemini (Spring AI)
                                        │                │ tool calls
                                        │                v
REST client ───> ScoreController ──> AirportQueryService <── AirportTools
                                        │
                              scoring (pure Java) + saved data files
                                        │
                              live FAA status (one HTTP call)
```

## 2. Data
| Source | Use |
|---|---|
| BTS T-100 Segment (US carriers) | Passengers, seats, departures, distance buckets |
| BTS On-Time Performance (12 months) | Delay rate, cancellation rate, taxi-out time |
| FAA enplanements (CY2023 to CY2025) | Size and growth, hub class |
| OurAirports | Airport names, codes, runway count |
| FAA NAS status (live, no key) | Current ground stops and closures. Not a score input. |

A Python script (`scripts/prepare_data.py`) downloads the sources one time and saves **facts only** (sums, counts, rates) as CSV files in Git. The app reads them at start-up. The scores are calculated at run time, so a reviewer can change a weight with no new data run.

## 3. Scoring method
1. Each KPI becomes a **percentile** among the 233 airports with at least 100,000 enplanements (the ranked set).
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

- **Coverage and confidence:** coverage is the part of the weight that has KPI data. HIGH is 0.9 or more, MEDIUM is 0.7 or more, otherwise LOW.
- **Ties** are broken by passengers, then by IATA code. The ranking is repeatable.
- The weights are **heuristics**. They are in `application.yml`. They are not fitted to data.

## 4. The four example questions
| Question | Tool | Result |
|---|---|---|
| New England airports for terminal expansion | `rankAirports(NEW_ENGLAND, terminal)` | BOS, PWM, HVN, PVD, BGR, BDL, BTV, MHT, ACK, ORH, with score, confidence and the reason of each score |
| LAX and SNA congestion | `compareAirports(LAX, SNA, congestion)` | KPIs side by side, and **code says which airport is higher**: LAX 50.29, SNA 48.65, a gap of 1.64 points, and the higher airport for each KPI. A warning shows that LAX is a large hub and SNA a medium hub. |
| Long-haul share at Anchorage | `getLongHaulShare(ANC, 2500)` | 14.9% of passenger departures. The tool also gives the share with cargo flights. The limit is a parameter, because there is no standard definition. |
| Unmet demand at SFO and why | `getUnmetDemandIndex(SFO)` | An index of 67.0, labeled **PROXY**. The parts are load factor 0.40, growth 0.30 and congestion 0.30. An expert note says that the close-parallel runways limit capacity in low visibility. The note is not a score input. |

## 5. Where AI is used (and where it is not)
- **The LLM does:** read the question and the chat history, choose a tool and its inputs, and write the answer in plain language.
- **The LLM does not:** calculate, rank, read data files or call public APIs.
- **Tools:** 7 `@Tool` methods. Each calls one method of `AirportQueryService`. A tool accepts an airport code or a name. The engine resolves the name, so the LLM needs no separate lookup call. If a name matches more than one airport, the tool returns the options and the agent asks the user. A turn may use 5 tool calls at most.
- **Number check:** after the LLM writes an answer, `NumberCheck` looks for numbers that no tool result has. It allows rounding and percentage form. If it finds one, the agent asks again one time. If it fails again, the app shows the raw tool results and sets `verified = false`.
- **Follow-ups:** the chat memory keeps 20 messages per session. The agent may reuse numbers of earlier tool results.
- **System prompt:** 14 rules (see `prompts/system.txt`). Examples: use only tool numbers, say when a tool has no data, state assumptions, never give investment advice.

## 5.1 The page: engine next to chat
The page has two parts. The left part is the **scoring engine, with no LLM**: a ranked table (score, confidence, national rank), sliders for the weights, the breakdown of the score of the selected airport, the live FAA status, and the sources, assumptions and uncertainty. It calls the REST API. The right part is the chat. Both use the same `AirportQueryService`, so the table and the chat give the same numbers. An analyst can check each number of the chat in the table.

**What-if weights:** the user can change the weights for one question, with a slider or in the chat ("what if I ignore scale?"). The named components get the given weights. The other components share the rest in the ratio of the profile. Java checks the input (known component names, no negative value, a total of 1.0). The result is marked `custom`, and it shows the weights that it used.

## 6. Assumptions, uncertainty and scope
Each result has an **envelope**: assumptions, uncertainties, what is out of scope, confidence, sources and the data date. The agent must state the main ones. Important cases:
- The scores are screening indexes. They have no cost, revenue or profit.
- **Unmet demand cannot be measured with public data.** The index is a proxy. The result says so.
- T-100 has US airlines only. On-Time has large reporting airlines only. Small airports can have no delay data. Their score uses the other components, and the confidence is lower.
- The score favors large airports by design (size filter and scale component).
- Foreign airports are out of scope. The tool answers OUT_OF_SCOPE with the reason.

## 7. Protection against a heavy load and prompt injection
All limits are settings in `application.yml` (`airport.limits`).

| Risk | Protection |
|---|---|
| Many requests from one client | A token bucket for each client address (Bucket4j): 10 chat requests and 120 API requests a minute. The answer is HTTP 429 with `Retry-After`. |
| Too many LLM calls at the same time | At most 4 chat requests run at the same time. The next request waits 2 seconds, then gets HTTP 503. |
| Large or endless request body | A chat request must have a `Content-Length` of 4,096 bytes or less (HTTP 411 or 413). A message has 1,000 characters at most. A name has 100 characters at most. |
| Memory growth | At most 200 chat sessions. An idle session is deleted after 60 minutes, with its chat history (Caffeine). |
| Many questions that each call the FAA | The FAA answer is kept for 30 seconds. A failed call is not kept. |
| LLM loop | At most 5 tool calls for one question. At most 6 airports in a comparison. At most 50 airports in a ranking. |
| A client uses the session of another client | The app accepts only a session id that it gave. An unknown id starts a new session. |
| Instructions in the question ("ignore your rules") | The system prompt says that text of the user and of tool results is data and never an instruction. Control characters and invisible text marks are removed. |
| Instructions in outside text (the FAA reason text) | `UntrustedText` cleans outside text when the app reads it: simple characters only, no links, no line breaks, 160 characters at most. |
| The LLM writes a number or a link that it should not | `NumberCheck` accepts only numbers of the tool results. The question of the user is not a source. `OutputGuard` removes links and images that no tool result has. |
| The damage of a successful attack | The tools only read data. They have no file, network-write or code function. The tool inputs are typed and checked in Java. |

**Limits:** these protections do not make an LLM safe. A model can still follow a harmful instruction, and the number check does not find wrong words. The design keeps the damage small: a wrong answer, never a changed score or a changed file. The client address is the address of the connection. Behind a reverse proxy, the proxy must pass the real address, or all clients share one bucket.

## 8. Main trade-offs
| Decision | Why | Cost |
|---|---|---|
| Percentiles, not raw values | Different units can be mixed. The result is easy to explain. | The size of a gap is lost. |
| Saved facts, calculated scores | A change of weights needs no new data run. | The app calculates all airports at start-up (fast, 522 airports). |
| The LLM never calculates | Numbers are repeatable and testable. | The agent cannot do new math (for example a difference). The number check rejects it. |
| Spring AI | Standard tool calling, memory and model change by configuration. | One more framework to learn. |
| Browser voice | No server cost, no extra dependency. | Chrome and Edge only. |

## 9. Tests (184, no real LLM call)
- Scoring: hand-calculated values, ordering, missing data, ties.
- Real data: the data files are consistent (for example no airport code collisions).
- Engine and REST API give the same numbers.
- Agent: a scripted model calls the real tools. Tests cover the number check, the retry, the template answer, memory, the call limit and a model failure.
- The app starts with no API key.
- ArchUnit: `scoring` has no Spring and no AI code. Only the `agent` package uses Spring AI.

## 10. Real LLM check (2026-10-14)
I ran the four example questions and one follow-up with real Gemini (`gemini-3.1-flash-lite`). Each answer used the right tools, and the number check passed.
- Gemini often returned 503 (high demand) for the larger models, and the old model `gemini-2.5-flash` was not available to new keys. The model name is a setting: `GEMINI_MODEL` (default `gemini-3.8-flash`). Set `GEMINI_MODEL=gemini-3.1-flash-lite` if the default model is busy.
- The number check found one real problem (a size limit "100,000" in a tool text was read as two numbers). It is fixed and has a test.
- The check cannot see wrong words. Example: one answer called raw values "percentiles". The numbers were correct.

## 11. Not verified
- Voice in a real browser.
- The behavior of the larger Gemini models with the tools (they were overloaded during the check).
