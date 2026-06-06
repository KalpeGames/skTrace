// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * POSTs a rendered HTML report to a configured endpoint and returns the share URL.
 *
 * The endpoint contract:
 *   POST <endpoint>  body: text/html; charset=utf-8
 *   → 200 with JSON body containing a "url" field, e.g. {"code":"abc","url":"https://.../abc"}
 *
 * Default endpoint is the public sktrace worker; servers can override via config.yml.
 */
public final class ReportUploader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "Sktrace/0.1.3";

    private final String endpoint;
    private final HttpClient client;

    public ReportUploader(String endpoint) {
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public String upload(String html, boolean includeSources) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/html; charset=utf-8")
                .header("User-Agent", USER_AGENT)
                .header("X-Sktrace-Include-Sources", includeSources ? "true" : "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("upload interrupted", ie);
        }

        if (resp.statusCode() / 100 != 2) {
            String snippet = resp.body();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "…";
            throw new UploadException(resp.statusCode(),
                    "upload failed: HTTP " + resp.statusCode() + " — " + snippet);
        }

        return extractUrl(resp.body());
    }

    /**
     * Thrown on a non-2xx response, carrying the HTTP status so callers can special-case it —
     * notably 413 (the report body exceeded the endpoint's size limit), which is exactly the
     * case where we steer the user toward uploading the smaller JSON sidecar by hand.
     */
    public static final class UploadException extends IOException {
        private final int status;
        public UploadException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }

    // Tiny purpose-built JSON field extractor — we control both ends of this contract,
    // so pulling in a JSON dep just for one field would be overkill.
    private static String extractUrl(String json) throws IOException {
        int key = json.indexOf("\"url\"");
        if (key < 0) throw new IOException("response missing 'url' field: " + json);
        int colon = json.indexOf(':', key);
        if (colon < 0) throw new IOException("malformed response: " + json);
        int open = json.indexOf('"', colon + 1);
        int close = open < 0 ? -1 : json.indexOf('"', open + 1);
        if (open < 0 || close < 0) throw new IOException("malformed 'url' field: " + json);
        return json.substring(open + 1, close);
    }
}
