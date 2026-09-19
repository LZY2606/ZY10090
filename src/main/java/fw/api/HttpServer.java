package fw.api;

import fw.core.AppService;
import fw.json.Json;
import fw.store.Store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDK-built-in HTTP server; all business logic lives behind {@link AppService}. */
public final class HttpServer {

    private final AppService app;
    private final com.sun.net.httpserver.HttpServer server;

    public HttpServer(AppService app, int port) throws IOException {
        this.app = app;
        this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/", new RootHandler());
        this.server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // --------------------------------------------------------------- handler

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String requestId = firstHeader(ex, "X-Request-Id");
            try {
                if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                    serveStatic(ex, "web/index.html", "text/html; charset=utf-8");
                    return;
                }
                if ("GET".equals(method) && path.equals("/app.js")) {
                    serveStatic(ex, "web/app.js", "application/javascript; charset=utf-8");
                    return;
                }
                if ("GET".equals(method) && path.equals("/styles.css")) {
                    serveStatic(ex, "web/styles.css", "text/css; charset=utf-8");
                    return;
                }
                if ("GET".equals(method) && path.equals("/api/overview")) {
                    send(ex, 200, app.overview(), requestId, method, path);
                    return;
                }
                if ("GET".equals(method) && path.equals("/api/events")) {
                    send(ex, 200, Map.of("events", app.events()), requestId, method, path);
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/api/matrix/")) {
                    send(ex, 200, app.matrix(decode(path.substring("/api/matrix/".length()))),
                            requestId, method, path);
                    return;
                }
                if ("GET".equals(method) && path.equals("/api/rollouts")) {
                    send(ex, 200, Map.of("rollouts", app.listRollouts()), requestId, method, path);
                    return;
                }
                if (method.startsWith("GET") && path.startsWith("/api/rollouts/")) {
                    handleRolloutGet(ex, path, requestId, method);
                    return;
                }
                if ("POST".equals(method)) {
                    handlePost(ex, path, requestId);
                    return;
                }
                sendError(ex, 404, "not_found", "no route for " + method + " " + path,
                        requestId, method, path);
            } catch (Errors.ApiException api) {
                sendError(ex, api.status(), api.code(), api.getMessage(), requestId, method, path);
            } catch (Throwable other) {
                other.printStackTrace(System.err);
                sendError(ex, 500, "internal_fault",
                        "unexpected failure: " + other.getClass().getSimpleName()
                                + ": " + other.getMessage(), requestId, method, path);
            }
        }

        private void handleRolloutGet(HttpExchange ex, String path, String requestId,
                                      String method) throws IOException {
            String rest = path.substring("/api/rollouts/".length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash + 1);
            if (action.isEmpty()) {
                send(ex, 200, app.getRollout(id), requestId, method, path);
            } else if (action.equals("export")) {
                send(ex, 200, app.exportRollout(id), requestId, method, path);
            } else {
                sendError(ex, 404, "not_found", "unknown rollout sub-resource " + action,
                        requestId, method, path);
            }
        }

        @SuppressWarnings("unchecked")
        private void handlePost(HttpExchange ex, String path, String requestId) throws IOException {
            String bodyText;
            try (InputStream in = ex.getRequestBody()) {
                bodyText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, Object> body;
            try {
                body = bodyText.isBlank() ? new LinkedHashMap<>() : Json.parseObject(bodyText);
            } catch (RuntimeException parseError) {
                sendError(ex, 400, "invalid_input",
                        "request body must be a JSON object: " + parseError.getMessage(),
                        requestId, "POST", path);
                return;
            }
            // Idempotency: the request id is claimed durably BEFORE the
            // business operation runs, so concurrent or retried requests with
            // the same id cannot create a second result. A completed claim
            // replays the stored response verbatim.
            String claimStatus = null;
            if (requestId != null) {
                Store.LedgerEntry prior = app.store().replay(requestId).orElse(null);
                if (prior != null && "POST".equals(prior.method()) && prior.path().equals(path)) {
                    if ("processing".equals(prior.body())) {
                        sendError(ex, 409, "request_in_progress",
                                "request " + requestId + " is already being processed",
                                requestId, "POST", path);
                        return;
                    }
                    byte[] payload = prior.body().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.getResponseHeaders().add("X-Replayed-Request", "true");
                    ex.sendResponseHeaders(prior.status(), payload.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(payload);
                    }
                    return;
                }
                claimStatus = "processing";
                app.store().recordResult(requestId, "POST", path, 202, claimStatus);
            }

            Object result;
            int status = 200;
            switch (path) {
                case "/api/families" -> {
                    result = app.importFamily(body);
                    status = 201;
                }
                case "/api/packages" -> {
                    result = app.importPackage(body);
                    status = 201;
                }
                case "/api/devices" -> {
                    result = app.upsertDevice(body);
                    status = 201;
                }
                case "/api/plans/evaluate" -> result = app.computePlan(body);
                case "/api/rollouts" -> {
                    result = app.createRollout(body);
                    status = 201;
                }
                default -> {
                    if (path.startsWith("/api/rollouts/")) {
                        result = handleRolloutPost(path, body);
                    } else {
                        sendError(ex, 404, "not_found", "unknown POST endpoint " + path,
                                requestId, "POST", path);
                        return;
                    }
                }
            }
            send(ex, status, result, requestId, "POST", path);
        }

        private Object handleRolloutPost(String path, Map<String, Object> body) {
            String rest = path.substring("/api/rollouts/".length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash + 1);
            return switch (action) {
                case "approve" -> app.approveRollout(id);
                case "receipts" -> app.receipt(id, body);
                case "recompute" -> app.recomputeRollout(id);
                case "freeze" -> {
                    String cohort = Json.str(body, "cohort", "default");
                    boolean frozen = Json.optBool(body, "frozen", true);
                    yield app.freezeCohort(id, cohort, frozen);
                }
                default -> throw new Errors.NotFound("unknown rollout action " + action);
            };
        }

        private void serveStatic(HttpExchange ex, String resource, String contentType)
                throws IOException {
            Path file = Path.of(resource);
            byte[] data;
            if (Files.exists(file)) {
                data = Files.readAllBytes(file);
            } else {
                try (InputStream in = HttpServer.class.getResourceAsStream("/" + resource)) {
                    if (in == null) {
                        sendError(ex, 404, "not_found", "missing UI resource " + resource,
                                null, "GET", ex.getRequestURI().getPath());
                        return;
                    }
                    data = in.readAllBytes();
                }
            }
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private void send(HttpExchange ex, int status, Object value,
                      String requestId, String method, String path) throws IOException {
        String json = Json.write(value);
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
        if (requestId != null) {
            app.store().recordResult(requestId, method, path, status, json);
        }
    }

    private void sendError(HttpExchange ex, int status, String code, String message,
                           String requestId, String method, String path) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("category", categoryFor(code));
        body.put("status", status);
        body.put("request_id", requestId);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", body);
        String json = Json.write(error);
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
        if (requestId != null) {
            app.store().recordResult(requestId, method, path, status, json);
        }
    }

    private static String categoryFor(String code) {
        return switch (code) {
            case "invalid_input", "invalid_family_manifest", "bad_signature",
                 "invalid_package_metadata", "invalid_device", "invalid_plan_request",
                 "unknown_family", "unknown_component", "unknown_requirement",
                 "unknown_revision", "unverified_package", "signature_check_error",
                 "signer_mismatch", "invalid_bootloader_min", "invalid_max_from_version",
                 "invalid_hw_compatibility" -> "input";
            case "state_conflict", "not_found" -> "state";
            default -> "internal";
        };
    }

    private static String firstHeader(HttpExchange ex, String name) {
        List<String> values = ex.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
