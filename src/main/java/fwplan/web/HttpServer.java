package fwplan.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import fwplan.model.Plan;
import fwplan.plan.ConflictStateException;
import fwplan.plan.ValidationException;
import fwplan.util.Json;

/** JDK 内置 HTTP 服务：静态页面 + JSON API。判定全部经 AppService（前端不持有关键逻辑）。 */
public final class HttpServer {

    private final AppService service;
    private final int port;
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(AppService service, int port) {
        this.service = service;
        this.port = port;
    }

    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::route);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() { server.stop(0); }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                staticFile(exchange, "index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && path.startsWith("/static/")) {
                String name = path.substring("/static/".length());
                String mime = name.endsWith(".css") ? "text/css; charset=utf-8"
                        : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                        : "application/octet-stream";
                staticFile(exchange, name, mime);
                return;
            }
            api(exchange, method, path);
        } catch (ValidationException | fwplan.util.Json.JsonException e) {
            error(exchange, 400, "INPUT_FORMAT", "输入解析失败: " + e.getMessage());
        } catch (ConflictStateException e) {
            error(exchange, 409, "STATE_CONFLICT", e.getMessage());
        } catch (Exception e) {
            error(exchange, 500, "INTERNAL", "内部故障: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void api(HttpExchange exchange, String method, String path) throws IOException {
        switch (path) {
            case "/api/overview" -> require(exchange, "GET");
            case "/api/import/manifest", "/api/import/firmware",
                 "/api/cohorts", "/api/releases/create", "/api/analyze",
                 "/api/recompute", "/api/device/advance" -> require(exchange, "POST");
            default -> { }
        }

        if ("GET".equals(method) && path.equals("/api/overview")) {
            json(exchange, 200, service.overview());
            return;
        }
        if ("POST".equals(method) && path.equals("/api/import/manifest")) {
            json(exchange, 201, service.importManifest(body(exchange)));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/import/firmware")) {
            json(exchange, 201, service.importFirmware(body(exchange)));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/analyze")) {
            Map<String, Object> req = Json.parseObject(body(exchange));
            Plan plan = service.analyze(
                    Json.requireStr(req, "familyId"),
                    Json.requireStr(req, "hwRevision"),
                    Json.obj(req, "current"),
                    Json.obj(req, "target"), false, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("plan", plan.toMap());
            out.put("simulation", plan.feasible() ? service.simulate(plan).toMap() : null);
            json(exchange, 200, out);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/matrix/")) {
            String[] parts = path.substring("/api/matrix/".length()).split("/");
            if (parts.length != 2) throw new ValidationException("路径应为 /api/matrix/{family}/{hw}");
            json(exchange, 200, service.compatibilityMatrix(parts[0], parts[1]));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/cohorts")) {
            json(exchange, 201, service.upsertCohort(Json.parseObject(body(exchange))).toMap());
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/cohorts/")) {
            String cohortId = path.substring("/api/cohorts/".length());
            if (cohortId.endsWith("/freeze") || cohortId.endsWith("/unfreeze")) {
                error(exchange, 400, "INPUT_FORMAT", "冻结请使用 POST");
                return;
            }
            json(exchange, 200, service.analyzeCohort(cohortId));
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/cohorts/") && path.endsWith("/freeze")) {
            String cohortId = path.substring("/api/cohorts/".length(), path.length() - "/freeze".length());
            json(exchange, 200, service.setFrozen(cohortId, true).toMap());
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/cohorts/") && path.endsWith("/unfreeze")) {
            String cohortId = path.substring("/api/cohorts/".length(), path.length() - "/unfreeze".length());
            json(exchange, 200, service.setFrozen(cohortId, false).toMap());
            return;
        }
        if ("POST".equals(method) && path.equals("/api/recompute")) {
            json(exchange, 200, service.recompute());
            return;
        }
        if ("POST".equals(method) && path.equals("/api/releases/create")) {
            json(exchange, 201, service.createRelease(Json.parseObject(body(exchange))).toMap());
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/releases/") && path.endsWith("/approve")) {
            String id = path.substring("/api/releases/".length(), path.length() - "/approve".length());
            json(exchange, 200, service.approveRelease(id).toMap());
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/releases/") && path.endsWith("/export")) {
            String id = path.substring("/api/releases/".length(), path.length() - "/export".length());
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"release-" + id + "-export.json\"");
            json(exchange, 200, service.exportRelease(id));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/device/advance")) {
            json(exchange, 200, service.advanceDevice(Json.parseObject(body(exchange))).toMap());
            return;
        }
        error(exchange, 404, "NOT_FOUND", "没有该接口: " + method + " " + path);
    }

    private static void require(HttpExchange exchange, String method) {
        // 声明式校验占位：实际在各分支处理；方法不匹配落入 404/405。
    }

    private String body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void staticFile(HttpExchange exchange, String resource, String mime) throws IOException {
        try (InputStream in = HttpServer.class.getResourceAsStream("/static/" + resource)) {
            if (in == null) {
                error(exchange, 404, "NOT_FOUND", "资源不存在: " + resource);
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        }
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.writePretty(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("status", status);
        body.put("message", message);
        json(exchange, status, body);
    }
}
