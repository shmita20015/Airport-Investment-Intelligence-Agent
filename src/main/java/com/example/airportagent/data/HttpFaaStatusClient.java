package com.example.airportagent.data;

import com.example.airportagent.domain.LiveStatus;
import com.example.airportagent.domain.LiveStatus.Event;
import com.example.airportagent.domain.LiveStatus.EventType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Calls https://nasstatus.faa.gov/api/airport-status-information (XML, no key).
 * The list holds only active events. An airport that is not in the list has no active event.
 */
public final class HttpFaaStatusClient implements FaaStatusClient {

    /** A closure notice with these words restricts some flights only. It is not a full closure of the airport. */
    private static final Pattern RESTRICTION = Pattern.compile(
            "\\b(EXC|EXCEPT|TO NON|NON SKED|GA|TRANSIENT|PPR|HEL|RWY|TWY|TAXIWAY|RUNWAY)\\b");

    /** A call to the FAA service. A test can replace it. */
    @FunctionalInterface
    interface Fetcher {
        /** Calls the FAA service and returns the XML text. */
        String fetch() throws IOException;
    }

    private final Fetcher fetcher;
    /** The answer of the FAA service has one entry. It expires after the cache time. A failed call is not kept. */
    private final Cache<String, String> answer;

    /**
     * Creates the client of the live FAA status service.
     *
     * @param cacheTtl the answer of the FAA service is kept for this time. A heavy load of questions
     *                 then makes one call to the FAA, and not one call for each question.
     */
    public HttpFaaStatusClient(String url, Duration timeout, Duration cacheTtl) {
        this(httpFetcher(URI.create(url), timeout), cacheTtl, Ticker.systemTicker());
    }

    /** Creates the client with a fetcher and a clock that a test can replace. */
    HttpFaaStatusClient(Fetcher fetcher, Duration cacheTtl, Ticker ticker) {
        this.fetcher = fetcher;
        this.answer = Caffeine.newBuilder().expireAfterWrite(cacheTtl).ticker(ticker).build();
    }

    /** The real fetcher: one HTTP call to the FAA service, with a time limit. */
    private static Fetcher httpFetcher(URI url, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        return () -> {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(timeout).GET().build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("The FAA status service answered with HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("The call to the FAA status service was interrupted", e);
            }
        };
    }

    /** The live status of an airport, from the cached answer or from a new call to the FAA service. */
    @Override
    public LiveStatus statusOf(String airportCode) throws IOException {
        return parse(body(), airportCode);
    }

    /** The answer of the FAA service, from the cache or from a new call. */
    private String body() throws IOException {
        try {
            return answer.get("faa", key -> {
                try {
                    return fetcher.fetch();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Read the FAA XML. This method has no network call, so a test can use it with a fixed text. */
    static LiveStatus parse(String xml, String airportCode) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            String updateTime = text(document.getDocumentElement(), "Update_Time");

            List<Event> events = new ArrayList<>();
            int restrictions = 0;
            NodeList delayTypes = document.getElementsByTagName("Delay_type");
            for (int i = 0; i < delayTypes.getLength(); i++) {
                Element delayType = (Element) delayTypes.item(i);
                String name = text(delayType, "Name");
                for (Element entry : entriesWithAirport(delayType)) {
                    if (!airportCode.equalsIgnoreCase(text(entry, "ARPT"))) {
                        continue;
                    }
                    String reason = UntrustedText.clean(text(entry, "Reason"));
                    EventType type = typeOf(name);
                    if (type == EventType.CLOSURE && RESTRICTION.matcher(reason.toUpperCase()).find()) {
                        restrictions++;
                        continue;
                    }
                    events.add(new Event(type, reason, details(entry)));
                }
            }
            return new LiveStatus(airportCode.toUpperCase(), UntrustedText.clean(updateTime), events, restrictions);
        } catch (Exception e) {
            throw new IOException("Cannot read the FAA status data", e);
        }
    }

    /** The event type for the name of an FAA list. */
    private static EventType typeOf(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("ground stop")) {
            return EventType.GROUND_STOP;
        }
        if (lower.contains("ground delay")) {
            return EventType.GROUND_DELAY;
        }
        if (lower.contains("closure")) {
            return EventType.CLOSURE;
        }
        return EventType.DELAY;
    }

    /** The XML elements of a list that name an airport. */
    private static List<Element> entriesWithAirport(Element delayType) {
        List<Element> result = new ArrayList<>();
        NodeList all = delayType.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element childElement && childElement.getTagName().equals("ARPT")) {
                    result.add(element);
                    break;
                }
            }
        }
        return result;
    }

    /** The extra fields of an FAA entry, as cleaned text. */
    private static String details(Element entry) {
        StringBuilder details = new StringBuilder();
        for (Node child = entry.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && !element.getTagName().equals("ARPT")
                    && !element.getTagName().equals("Reason")) {
                if (!details.isEmpty()) {
                    details.append("; ");
                }
                details.append(element.getTagName()).append(": ").append(element.getTextContent().trim());
            }
        }
        return UntrustedText.clean(details.toString());
    }

    /** The text of the first child element with this tag, or an empty text. */
    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
