package com.qualityops.worker.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only static HTTP fixture for the browser integration tests. No
 *  external calls — every handler returns a pure string. */
public final class BrowserTestFixtureServer {

    private static final String INDEX = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><title>fixture</title></head>
        <body>
          <label for="email">Email</label><input id="email" name="email" type="email">
          <button id="go" type="button"
                  onclick="document.getElementById('msg').style.display='block'">Go</button>
          <a id="home" href="/home">Home</a>
          <select id="opt"><option value="opt1">One</option><option value="opt2">Two</option></select>
          <div data-testid="msg" style="display:none">Saved</div>
        </body></html>
        """;

    private static final String HOME = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><title>home</title></head>
        <body><h1 data-testid="title">Home</h1></body></html>
        """;

    private static final String METADATA_IMG = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><title>meta</title></head>
        <body><img src="http://169.254.169.254/latest/meta-data" alt="m"><p data-testid="ok">loaded</p></body></html>
        """;

    private final AtomicInteger hitCount = new AtomicInteger();
    private HttpServer server;

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::handleRoot);
        server.createContext("/home", ex -> respond(ex, 200, HOME));
        server.createContext("/metadata-img", ex -> respond(ex, 200, METADATA_IMG));
        server.createContext("/slow", this::handleSlow);
        server.setExecutor(null);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int hitCount() {
        return hitCount.get();
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            respond(ex, 404, "not found");
            return;
        }
        hitCount.incrementAndGet();
        respond(ex, 200, INDEX);
    }

    private void handleSlow(HttpExchange ex) throws IOException {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        respond(ex, 200, "<html><body>slow</body></html>");
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
