package dev.ghast.linker;

import dev.ghast.linker.config.ConfigLoader;
import dev.ghast.linker.config.LinkerConfig;
import dev.ghast.linker.ui.StatusWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

/**
 * Entry point for the desktop linker. Loads config, starts the WebSocket bridge, and wires it to a
 * {@link SessionManager} that launches ACP agents on demand. Optionally shows a Swing status window.
 *
 * <p>Usage: {@code java -jar minecraft-agent-linker.jar [path-to-config.json]}
 * (defaults to {@code ./linker.json}).
 */
public final class LinkerMain {

    private static final Logger log = LoggerFactory.getLogger(LinkerMain.class);

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "linker.json");
        LinkerConfig config = ConfigLoader.load(configPath);

        BridgeServer server = new BridgeServer(config.host(), config.port(), config.authToken());

        boolean headless = GraphicsEnvironment.isHeadless() || !config.ui();
        StatusWindow window = headless ? null : createWindow(config);

        // Holder breaks the construction cycle: the refresh callback needs the manager, and the
        // manager needs the callback.
        SessionManager[] holder = new SessionManager[1];
        Runnable refresh = () -> {
            if (window != null) {
                SessionManager m = holder[0];
                window.setStatus(server.connectionCount(), m == null ? 0 : m.activeSessions());
                if (m != null) {
                    window.setSessions(m.sessions());
                }
            }
        };

        SessionManager manager = new SessionManager(config, server::broadcast, refresh);
        holder[0] = manager;
        server.setHandler(manager::handle);
        server.setOnConnectionsChanged(refresh);

        server.start();
        if (window != null) {
            window.show();
        }

        log.info("Linker ready. Configured agents: {}",
                config.agents().stream().map(a -> a.id()).toList());
        printBanner(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down linker…");
            manager.shutdown();
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        // Keep the JVM alive (WebSocket server threads are daemon-managed internally).
        Thread.currentThread().join();
    }

    private static StatusWindow createWindow(LinkerConfig config) {
        try {
            return new StatusWindow(config.host() + ":" + config.port());
        } catch (Throwable t) {
            log.warn("Could not open status window, continuing headless: {}", t.getMessage());
            return null;
        }
    }

    private static void printBanner(LinkerConfig config) {
        String line = "=".repeat(58);
        System.out.println(line);
        System.out.println("  Minecraft Agent IDE — Linker");
        System.out.println("  Bridge:  ws://" + config.host() + ":" + config.port()
                + (config.authToken() != null ? "?token=…" : ""));
        System.out.println("  Agents:  " + config.agents().stream()
                .map(a -> a.displayNameOrId()).toList());
        System.out.println("  Point the Minecraft plugin at the bridge address above.");
        System.out.println(line);
    }
}
