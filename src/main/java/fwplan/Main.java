package fwplan;

import java.nio.file.Path;

import fwplan.store.Store;
import fwplan.web.AppService;
import fwplan.web.HttpServer;

/** 服务入口：--port 指定端口，--data 指定数据目录（默认 ./data）。 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = 5217;
        Path dataDir = Path.of("data");
        boolean seed = true;
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String arg : args) for (String part : arg.split("\s+")) tokens.add(part);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String inlineValue = null;
            int eq = token.indexOf('=');
            if (eq >= 0) { inlineValue = token.substring(eq + 1); token = token.substring(0, eq); }
            switch (token) {
                case "--port" -> port = Integer.parseInt(inlineValue != null ? inlineValue : tokens.get(++i));
                case "--data" -> dataDir = Path.of(inlineValue != null ? inlineValue : tokens.get(++i));
                case "--no-seed" -> seed = false;
                default -> System.err.println("未知参数: " + token);
            }
        }
        Store store = new Store(dataDir);
        AppService service = new AppService(store);
        if (seed) fwplan.demo.SeedData.seedIfEmpty(service);
        HttpServer server = new HttpServer(service, port);
        server.start();
        System.out.println("fwplan 已启动: http://127.0.0.1:" + port);
        System.out.println("数据目录: " + dataDir.toAbsolutePath());
    }
}
