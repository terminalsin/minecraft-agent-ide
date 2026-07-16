package dev.ghast.linker.tools;

import dev.ghast.bridge.BridgeCodec;
import dev.ghast.bridge.BridgeMessage;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A terminal stand-in for the Minecraft plugin, for manually testing the linker and a real ACP
 * agent without a Minecraft server. It connects to a running linker, opens one session, and lets
 * you chat with the agent from the terminal; agent output, tool calls, plans and permission
 * prompts are printed inline.
 *
 * <p>Usage (with a linker already running):
 * <pre>
 *   java -cp minecraft-agent-linker-&lt;version&gt;.jar dev.ghast.linker.tools.ConsoleClient \
 *        [ws://127.0.0.1:8765] [profileId]
 * </pre>
 * Type a message and press enter to prompt the agent. Special commands:
 * {@code /cancel} cancels the current turn, {@code /quit} exits, and when the agent asks for
 * permission, type {@code /allow} or {@code /deny}.
 */
public final class ConsoleClient {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "ws://127.0.0.1:8765";
        String profile = args.length > 1 ? args[1] : null;

        AtomicReference<String> sessionId = new AtomicReference<>();
        AtomicReference<PendingPermission> pending = new AtomicReference<>();

        WebSocketClient client = new WebSocketClient(URI.create(url)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("[connected to linker at " + url + "]");
                send(new BridgeMessage.Hello("console-0.1"));
            }

            @Override
            public void onMessage(String raw) {
                handle(BridgeCodec.INSTANCE.decode(raw), this, sessionId, pending, profile);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("[disconnected: " + reason + "]");
            }

            @Override
            public void onError(Exception ex) {
                System.out.println("[socket error: " + ex.getMessage() + "]");
            }

            void send(BridgeMessage message) {
                send(BridgeCodec.INSTANCE.encode(message));
            }
        };

        if (!client.connectBlocking()) {
            System.err.println("Could not connect to " + url + " — is the linker running?");
            return;
        }

        System.out.println("Type a message to talk to the agent. /cancel, /allow, /deny, /quit.");
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            String sid = sessionId.get();
            switch (line) {
                case "/quit" -> {
                    client.close();
                    return;
                }
                case "/cancel" -> {
                    if (sid != null) {
                        client.send(BridgeCodec.INSTANCE.encode(new BridgeMessage.Cancel(sid)));
                    }
                }
                case "/allow", "/deny" -> answerPermission(client, pending, line.equals("/allow"));
                default -> {
                    if (sid == null) {
                        System.out.println("[session not ready yet…]");
                    } else {
                        client.send(BridgeCodec.INSTANCE.encode(new BridgeMessage.Prompt(sid, line)));
                    }
                }
            }
        }
    }

    private static void handle(BridgeMessage message,
                               WebSocketClient client,
                               AtomicReference<String> sessionId,
                               AtomicReference<PendingPermission> pending,
                               String profile) {
        switch (message) {
            case BridgeMessage.Welcome welcome -> {
                System.out.println("[linker " + welcome.linkerVersion() + " agents="
                        + welcome.agents().stream().map(BridgeMessage.AgentProfileInfo::id).toList() + "]");
                client.send(BridgeCodec.INSTANCE.encode(
                        new BridgeMessage.CreateSession("console", profile, null)));
                System.out.println("[starting agent" + (profile != null ? " '" + profile + "'" : "") + "…]");
            }
            case BridgeMessage.SessionCreated created -> {
                sessionId.set(created.sessionId());
                System.out.println("[session ready: " + created.agentName() + "]");
            }
            case BridgeMessage.Message msg -> {
                if ("assistant".equals(msg.role())) {
                    System.out.print(msg.text());
                    System.out.flush();
                } else if ("thought".equals(msg.role())) {
                    System.out.println("\n  (thinking: " + oneLine(msg.text()) + ")");
                }
            }
            case BridgeMessage.ToolCall tool ->
                    System.out.println("\n  [tool] " + tool.title() + " (" + tool.status() + ")");
            case BridgeMessage.Plan plan -> {
                System.out.println("\n  [plan]");
                for (BridgeMessage.PlanItem item : plan.entries()) {
                    System.out.println("    - " + item.status() + ": " + item.content());
                }
            }
            case BridgeMessage.PermissionRequest req -> {
                System.out.println("\n  [permission] " + req.title() + " — type /allow or /deny");
                pending.set(new PendingPermission(req.sessionId(), req.requestId(), req.options()));
            }
            case BridgeMessage.TurnEnd end -> System.out.println("\n[turn ended: " + end.stopReason() + "]");
            case BridgeMessage.AgentError error -> System.out.println("\n[error] " + error.message());
            default -> { /* ignore */ }
        }
    }

    private static void answerPermission(WebSocketClient client,
                                         AtomicReference<PendingPermission> pending,
                                         boolean allow) {
        PendingPermission p = pending.getAndSet(null);
        if (p == null) {
            System.out.println("[no pending permission request]");
            return;
        }
        String optionId = p.options().stream()
                .filter(o -> o.kind() != null && (allow ? o.kind().startsWith("allow") : o.kind().startsWith("reject")))
                .map(BridgeMessage.PermissionOptionDto::optionId)
                .findFirst()
                .orElseGet(() -> p.options().isEmpty() ? null : p.options().get(0).optionId());
        client.send(BridgeCodec.INSTANCE.encode(
                new BridgeMessage.PermissionResponse(p.sessionId(), p.requestId(), optionId)));
        System.out.println("[sent " + (allow ? "allow" : "deny") + "]");
    }

    private static String oneLine(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }

    private record PendingPermission(String sessionId, String requestId,
                                     List<BridgeMessage.PermissionOptionDto> options) {
    }
}
