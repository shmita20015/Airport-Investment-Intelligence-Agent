# Airport Investment Intelligence Agent

A chat agent for US airports. It helps to find where an investment in terminal capacity can be useful.

- **A scoring engine in Java** calculates every number from public data (BTS, FAA, OurAirports). The scores are percentile based and deterministic.
- **An LLM (Gemini, through Spring AI)** chooses the tools and explains the results. It does not calculate.
- **A number check** compares the numbers of each answer with the tool results. If a number is not in the results, the agent asks again, and then shows the raw results.
- **A REST API** gives the same numbers with no LLM.

The full design (scoring method, trade-offs, assumptions, limits, where AI is used) is in [`DESIGN.md`](DESIGN.md) (2 pages). The long version with all assumptions, limits and data checks is [`docs/DESIGN-full.md`](docs/DESIGN-full.md).

## Requirements
- Java 21 or later
- Maven 3.9 or later
- A Gemini API key for the chat (free tier is enough). The REST API needs no key.
- Python 3.10 or later, only to rebuild the data files

## Run

```
set GEMINI_API_KEY=your-key          (Windows cmd)
$env:GEMINI_API_KEY="your-key"       (PowerShell)
export GEMINI_API_KEY=your-key       (Linux, macOS)

mvn spring-boot:run
```

Open <http://localhost:8080>. The page has two parts:

- **Scoring engine (left, no LLM):** a ranked table with score and confidence, sliders for the weights, the breakdown of the score of the selected airport, the live FAA status of that airport, and the sources, assumptions and uncertainty. It calls the REST API, so it works without a Gemini key.
- **Chat (right):** example questions, a microphone button and a switch to read the answers aloud. Voice uses the speech functions of the browser. Chrome and Edge support them.

Move a slider to ask a what-if question: the table is calculated again with your weights. In the chat, ask for example "What if I ignore scale?" and the agent calls the same engine with custom weights.

If `GEMINI_API_KEY` is not set, the app still starts. The REST API works. The chat answers with a message that the key is missing.

Never write the key in a file of the project.

The model is a setting. The default is `gemini-3.8-flash`. If it answers 503 (busy), set `GEMINI_MODEL=gemini-3.1-flash-lite` before you start the app.

## Example questions
1. Which New England airports are strong candidates for terminal expansion?
2. Compare LAX and SNA congestion.
3. What is the percentage of long-haul flights out of Anchorage?
4. What is the unmet flight demand at SFO and why?

Follow-up questions work in the same chat. Example: "And at 1,500 miles?" after question 3.

## REST API (no LLM)

| Request | Result |
|---|---|
| `GET /api/resolve?q=Santa Ana` | Name to airport |
| `GET /api/rank?region=NEW_ENGLAND&profile=terminal&topN=10&weights=scale:0` | Ranking. The optional `weights` give custom weights (what-if): `component:weight`, separated by commas. The components that you do not name share the rest. The region can be a name (22 regions in `application.yml`), a state code (`TX`) or a list (`CA,OR,WA`). |
| `GET /api/compare?codes=LAX,SNA&focus=congestion` | Comparison. It says which airport is higher. |
| `GET /api/methodology` | Profiles, weights, regions, size limit and scope |
| `GET /api/airport/SFO?profile=general` | KPIs, components and score |
| `GET /api/airport/ANC/long-haul?minMiles=2500` | Long-haul share |
| `GET /api/airport/SFO/unmet-demand` | Unmet demand index (a proxy) |
| `GET /api/airport/LAX/live-status` | Current FAA status |

Each answer has an `envelope` with the assumptions, the uncertainties, the confidence, the sources and the data date.

## Protection
- Limits for each client address: 10 chat requests and 120 API requests a minute (HTTP 429). At most 4 chats run at the same time (HTTP 503). A chat request body is 4,096 bytes at most.
- Chat sessions are limited in number and in idle time. The app accepts only session ids that it gave.
- Against prompt injection: a hardened system prompt, cleaning of outside text, a number check against the tool results, and removal of links and images from answers.
- All limits are in `application.yml` under `airport.limits`. Details: section 7 of [`DESIGN.md`](DESIGN.md).
- Behind a reverse proxy, the proxy must pass the real client address, or all clients share one limit.

## Data
The data files are in `src/main/resources/data`. They are in Git, so the app runs without a download. To rebuild them (about 330 MB of downloads, some minutes):

```
python -I scripts/prepare_data.py --year 2025
```

The script saves facts only (sums, counts, rates). The scores are calculated at run time. The settings of the scoring (weights, size limit, long-haul limit) are in `src/main/resources/application.yml`.

## Test

```
mvn test
```

The tests use the real data files. The agent tests use a scripted model, so they make no call to Gemini. One test calls the live FAA service. It is skipped if the network is down.

## Structure

| Package | Content |
|---|---|
| `domain` | Records and enums. No logic of the app. |
| `scoring` | Percentiles, weights, score and long-haul calculation. No Spring. No AI. |
| `data` | Loaders of the data files and the live FAA client. |
| `service` | `AirportQueryService`. The REST API and the agent tools both use it. |
| `api` | REST controller. |
| `agent` | Spring AI tools, chat service, number check, chat controller. The only package that uses Spring AI. |

ArchUnit tests keep these rules.
