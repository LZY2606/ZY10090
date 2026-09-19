package fw;

import fw.api.HttpServer;
import fw.core.AppService;
import fw.crypto.SignatureVerifier;
import fw.store.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiTest {

    private HttpServer server;
    private HttpClient client;
    private int port;
    private Path data;

    @BeforeEach
    void setUp() throws Exception {
        data = Files.createTempDirectory("fw-http-");
        Store store = new Store(data);
        SignatureVerifier verifier = new SignatureVerifier()
                .registerKey(fw.Main.DEMO_KEY_ID, fw.Main.DEMO_KEY_SECRET);
        AppService app = new AppService(store, verifier);
        fw.core.DemoData.seed(app, fw.Main.DEMO_KEY_ID, fw.Main.DEMO_KEY_SECRET);
        server = new HttpServer(app, 0);
        server.start();
        port = server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String requestId)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesUiAndOverview() throws Exception {
        var ui = get("/");
        assertEquals(200, ui.statusCode());
        assertTrue(ui.body().contains("<html") || ui.body().contains("<!doctype"));
        var overview = get("/api/overview");
        assertEquals(200, overview.statusCode());
        assertTrue(overview.body().contains("families"));
    }

    @Test
    void errorCategoriesAreDistinguished() throws Exception {
        // malformed JSON -> input
        var malformed = post("/api/packages", "{not json", null);
        assertEquals(400, malformed.statusCode());
        assertTrue(malformed.body().contains("\"category\":\"input\""));
        // unknown route -> state/not found
        var missing = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/api/nope"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());
    }

    @Test
    void sameRequestIdReplaysWithoutSecondResult() throws Exception {
        var body = """
                {"family":"tiny","name":"Tiny","revisions":[{"id":"r1","label":"r1",
                "bootloader_min":"1.0.0"}],"components":[{"id":"main","label":"main"}]}""";
        String uniqueRequest = "req-" + java.util.UUID.randomUUID();
        var first = post("/api/families", body, uniqueRequest);
        assertEquals(201, first.statusCode(), first.body());
        var replay = post("/api/families", body, uniqueRequest);
        assertEquals(201, replay.statusCode());
        assertEquals("true", replay.headers().firstValue("X-Replayed-Request").orElse(null));
        assertEquals(first.body(), replay.body());
    }

    @Test
    void badSignatureIs400Not500() throws Exception {
        var response = post("/api/packages",
                "{\"package_id\":\"x\",\"signature\":\"k:bad\"}", null);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("bad_signature"));
    }
}
