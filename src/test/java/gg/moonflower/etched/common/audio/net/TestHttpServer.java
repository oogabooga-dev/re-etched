package gg.moonflower.etched.common.audio.net;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TestHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;

    public TestHttpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(this.executor);
        this.server.start();
    }

    public void handle(String path, HttpHandler handler) {
        this.server.createContext(path, handler);
    }

    public URI uri(String path) {
        return URI.create("http://" + this.server.getAddress().getHostString() + ":"
                + this.server.getAddress().getPort() + path);
    }

    public InetSocketAddress address() {
        return this.server.getAddress();
    }

    @Override
    public void close() {
        this.server.stop(0);
        this.executor.shutdownNow();
    }
}
