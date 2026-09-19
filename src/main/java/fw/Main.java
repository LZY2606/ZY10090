package fw;

import fw.api.HttpServer;
import fw.core.AppService;
import fw.core.DemoData;
import fw.crypto.SignatureVerifier;
import fw.store.Store;

import java.nio.file.Path;

/**
 * Service entry point.
 *
 * <p>Usage: {@code run --port 5217 [--data ./data]}. The demo HMAC signing key
 * {@code demo-2026-key} seeds a fully signed reference dataset on first start,
 * so the UI is immediately explorable without running the signing tool.</p>
 */
public final class Main {

    public static final String DEMO_KEY_ID = "demo-2026";
    public static final String DEMO_KEY_SECRET = "demo-2026-key";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 5217;
        Path data = Path.of("data");
        boolean seed = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> data = Path.of(args[++i]);
                case "--no-seed" -> seed = false;
                case "--help", "-h" -> {
                    System.out.println("usage: Main [--port 5217] [--data ./data] [--no-seed]");
                    return;
                }
                default -> System.err.println("ignoring unknown argument: " + args[i]);
            }
        }
        Store store = new Store(data);
        SignatureVerifier verifier = new SignatureVerifier()
                .registerKey(DEMO_KEY_ID, DEMO_KEY_SECRET);
        AppService app = new AppService(store, verifier);
        if (seed && app.catalog().families().isEmpty()) {
            DemoData.seed(app, DEMO_KEY_ID, DEMO_KEY_SECRET);
            System.out.println("Seeded demo device families, signed packages and devices.");
        }
        HttpServer server = new HttpServer(app, port);
        server.start();
        System.out.println("Firmware rollout service listening on http://127.0.0.1:"
                + server.port());
        System.out.println("Data root: " + data.toAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
