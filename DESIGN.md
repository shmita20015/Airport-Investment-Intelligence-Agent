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
| LAX and SNA congestion | `compareAirports(LAX, SNA, congestion)` | KPIs side by side. The congestion values are close (about 50 and 49). A warning shows that LAX is a large hub and SNA a medium hub. |
| Long-haul share at Anchorage | `getLongHaulShare(ANC, 2500)` | 14.9% of passenger departures. The tool also gives the share with cargo flights. The limit is a parameter, because there is no standard definition. |
| Unmet demand at SFO and why | `getUnmetDemandIndex(SFO)` | An index of 67.0, labeled **PROXY**. The parts are load factor 0.40, growth 0.30 and congestion 0.30. An expert note says that the close-parallel runways limit capacity in low visibility. The note is not a score input. |

## 5. Where AI is used (and where it is not)
- **The LLM does:** read the question and the chat history, choose a tool and its inputs, and write the answer in plain language.
- **The LLM does not:** calculate, rank, read data files or call public APIs.
- **Tools:** 8 `@Tool` methods. Each calls one method of `AirportQueryService`. A turn may use 5 tool calls at most.
- **Number check:** after the LLM writes an answer, `NumberCheck` looks for numbers that no tool result has. It allows rounding and percentage form. If it finds one, the agent asks again one time. If it fails again, the app shows the raw tool results and sets `verified = false`.
- **Follow-ups:** the chat memory keeps 20 messages per session. The agent may reuse numbers of earlier tool results.
- **System prompt:** 14 rules (see `prompts/system.txt`). Examples: use only tool numbers, say when a tool has no data, state assumptions, never give investment advice.

## 6. Assumptions, uncertainty and scope
Each result has an **envelope**: assumptions, uncertainties, what is out of scope, confidence, sources and the data date. The agent must state the main ones. Important cases:
- The scores are screening indexes. They have no cost, revenue or profit.
- **Unmet demand cannot be measured with public data.** The index is a proxy. The result says so.
- T-100 has US airlines only. On-Time has large reporting airlines only. Small airports can have no delay data. Their score uses the other components, and the confidence is lower.
- The score favors large airports by design (size filter and scale component).
- Foreign airports are out of scope. The tool answers OUT_OF_SCOPE with the reason.

## 7. Main trade-offs
| Decision | Why | Cost |
|---|---|---|
| Percentiles, not raw values | Different units can be mixed. The result is easy to explain. | The size of a gap is lost. |
| Saved facts, calculated scores | A change of weights needs no new data run. | The app calculates all airports at start-up (fast, 522 airports). |
| The LLM never calculates | Numbers are repeatable and testable. | The agent cannot do new math (for example a difference). The number check rejects it. |
| Spring AI | Standard tool calling, memory and model change by configuration. | One more framework to learn. |
| Browser voice | No server cost, no extra dependency. | Chrome and Edge only. |

## 8. Tests (117, no real LLM call)
- Scoring: hand-calculated values, ordering, missing data, ties.
- Real data: the data files are consistent (for example no airport code collisions).
- Engine and REST API give the same numbers.
- Agent: a scripted model calls the real tools. Tests cover the number check, the retry, the template answer, memory, the call limit and a model failure.
- The app starts with no API key.
- ArchUnit: `scoring` has no Spring and no AI code. Only the `agent` package uses Spring AI.

## 9. Real LLM check (2026-10-14)
I ran the four example questions and one follow-up with real Gemini (`gemini-3.1-flash-lite`). Each answer used the right tools, and the number check passed.
- Gemini often returned 503 (high demand) for the larger models, and the old model `gemini-2.5-flash` was not available to new keys. The model name is a setting: `GEMINI_MODEL` (default `gemini-3.8-flash`). Set `GEMINI_MODEL=gemini-3.1-flash-lite` if the default model is busy.
- The number check found one real problem (a size limit "100,000" in a tool text was read as two numbers). It is fixed and has a test.
- The check cannot see wrong words. Example: one answer called raw values "percentiles". The numbers were correct.

## 10. Not verified
- Voice in a real browser.
- The behavior of the larger Gemini models with the tools (they were overloaded during the check).
