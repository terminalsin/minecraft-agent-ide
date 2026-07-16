# Minecraft Agent IDE

Control an AI coding agent — **Claude Code**, **Codex**, or any other agent that speaks the
[Agent Client Protocol (ACP)](https://agentclientprotocol.com) — from inside Minecraft by talking to
villager NPCs.

Right‑click a villager, type in chat, and the message is sent to a real coding agent running on your
desktop against a real workspace. The agent's replies stream back above the villager's head and into
chat; its tool calls, plans, and permission prompts show up as clickable messages you answer in game.

```
 ┌────────────────────┐        WebSocket          ┌───────────────────────┐   JSON-RPC / stdio   ┌──────────────────┐
 │  Minecraft (Paper) │  ───  bridge protocol ──▶ │   Desktop Linker      │ ─── ACP (NDJSON) ──▶ │   ACP Agent      │
 │  villager NPCs     │ ◀──  (plugin ⇄ linker) ── │  (ACP client + bridge)│ ◀─────────────────── │  Claude Code /   │
 │  mc-plugin         │                           │   linker              │                      │  Codex / …       │
 └────────────────────┘                           └───────────────────────┘                      └──────────────────┘
```

## Why two processes?

Minecraft servers are often not on the same machine as your code, and agents need real filesystem and
process access. So the system is split:

- **`mc-plugin`** — a Paper plugin. Knows only about villagers, chat and buttons. Talks to the linker
  over a small WebSocket JSON protocol. Bundles nothing agent-specific.
- **`linker`** — a small desktop app that runs where your *workspace* lives. It is the ACP **client**:
  it launches the agent as a subprocess, speaks ACP over its stdio, and translates ACP activity into
  the bridge protocol for the plugin (and answers the agent's filesystem/permission requests).

Everything the agent does happens on the desktop running the linker, against the working directory you
configure — the Minecraft server never touches your code.

## Modules

| Module            | What it is                                                                              |
|-------------------|-----------------------------------------------------------------------------------------|
| `acp-core`        | A dependency-light Java implementation of ACP: a bidirectional JSON-RPC/NDJSON peer plus a typed `AgentConnection` (initialize, `session/new`, `session/prompt`, `session/update`, `session/request_permission`, `fs/*`). |
| `bridge-protocol` | The JSON message contract between the plugin and the linker (`BridgeMessage`, `BridgeCodec`). |
| `linker`          | Desktop app: WebSocket bridge server + `SessionManager` that launches one ACP agent per villager and maps ACP ⇄ bridge. Optional Swing status window. |
| `mc-plugin`       | The Paper plugin: villager NPCs, chat capture, hologram rendering, clickable permission buttons, `/agent` command. |

## Requirements

- **Java 21** (Paper 1.21 and the linker both need it).
- A **Paper** server, 1.21+.
- **Node.js** on the linker machine (the default agents run via `npx`).
- Credentials for whichever agent you use (e.g. an Anthropic or OpenAI login / API key in the linker's
  environment — the same ones the agent CLI expects).

## Build

```bash
./gradlew build
```

Outputs:

- Linker fat jar: `linker/build/libs/minecraft-agent-linker-<version>.jar`
- Plugin jar:     `mc-plugin/build/libs/MinecraftAgentIDE-<version>.jar`

> The plugin compiles against `paper-api` from the PaperMC Maven repo
> (`https://repo.papermc.io`), so that host must be reachable when you build `mc-plugin`.

## Run

### 1. Start the linker (on your workstation)

```bash
java -jar linker/build/libs/minecraft-agent-linker-<version>.jar [path/to/linker.json]
```

On first run it writes a `linker.json` next to itself and starts listening on `127.0.0.1:8765`. Edit
it to set your workspace and pick agents:

```jsonc
{
  "host": "127.0.0.1",
  "port": 8765,
  "authToken": null,               // set a shared secret to require ?token= on connect
  "defaultProfile": "claude-code",
  "defaultCwd": "/path/to/your/project",
  "ui": true,                       // Swing status window (ignored when headless)
  "agents": [
    {
      "id": "claude-code",
      "displayName": "Claude Code",
      "description": "Anthropic Claude Code, wrapped for ACP",
      "command": ["npx", "-y", "@zed-industries/claude-code-acp"],
      "cwd": null, "env": {}, "authMethod": null
    },
    {
      "id": "codex",
      "displayName": "Codex",
      "description": "OpenAI Codex, wrapped for ACP",
      "command": ["npx", "-y", "@zed-industries/codex-acp"],
      "cwd": null, "env": {}, "authMethod": null
    }
  ]
}
```

Any ACP agent works — just add a profile with the right `command`. Examples:
`gemini --experimental-acp`, a locally built `codex-acp` binary, etc.

### 2. Install the plugin (on the Paper server)

Drop `MinecraftAgentIDE-<version>.jar` into `plugins/`, start the server once to generate
`plugins/MinecraftAgentIDE/config.yml`, then point it at the linker:

```yaml
linker:
  host: "127.0.0.1"   # the linker's address, reachable from the server
  port: 8765
  token: ""           # must match linker authToken if set
```

If the server and workstation are different machines, set `host` accordingly (and consider setting a
`token`).

## Use it in game

| Command                 | Effect                                                    |
|-------------------------|-----------------------------------------------------------|
| `/agent spawn [profile]`| Spawn an agent villager in front of you and start a session (profile optional). |
| `/agent talk`           | Start talking to the nearest agent villager.              |
| `/agent stop`           | Leave the conversation / cancel the current turn.         |
| `/agent list`           | List active agent villagers and their state.              |
| `/agent remove`         | Remove the nearest agent villager and end its session.    |
| `/agent status`         | Show linker connection status and available agents.       |

While you're talking to a villager, everything you type in chat is sent to its agent instead of being
broadcast. Responses stream above the villager's head; the full turn is printed to chat when it
finishes. When the agent asks to run a tool, you get an in‑chat prompt with clickable
**[Allow]** / **[Reject]** buttons.

Right‑clicking a villager also starts a conversation (no trade menu).

## How the ACP integration works

`acp-core` implements the client half of ACP:

1. **`initialize`** — advertises client capabilities (filesystem read/write) and negotiates the
   protocol version (currently `1`).
2. **`session/new`** — opens a session rooted at the configured workspace directory.
3. **`session/prompt`** — sends the player's chat as a text content block; the returned `stopReason`
   ends the turn.
4. **`session/update`** notifications stream back `agent_message_chunk`, `agent_thought_chunk`,
   `tool_call` / `tool_call_update`, and `plan` updates, which the linker normalizes and forwards.
5. **`session/request_permission`** is surfaced to the player as clickable buttons; the chosen option
   is returned to the agent.
6. **`fs/read_text_file`** / **`fs/write_text_file`** are served by the linker against the real
   workspace, so agents that delegate file I/O to the client work too.

See `acp-core/src/main/java/dev/ghast/acp/` — `AgentConnection` is the entry point and
`rpc/JsonRpcPeer` is the transport.

## Manual testing without Minecraft

The linker jar ships a terminal REPL that stands in for the plugin, so you can verify the linker and a
real agent end-to-end before touching a Minecraft server. With a linker running:

```bash
java -cp linker/build/libs/minecraft-agent-linker-<version>.jar \
     dev.ghast.linker.tools.ConsoleClient ws://127.0.0.1:8765 claude-code
```

Type a message to prompt the agent; agent output, tool calls, plans and permission prompts print
inline. Commands: `/cancel`, `/allow`, `/deny`, `/quit`. This drives the exact same bridge protocol
the plugin uses, so if it works here, the ACP side is good.

There are also fast unit tests for the protocol layers:

```bash
./gradlew test
```

## References

- Agent Client Protocol — <https://agentclientprotocol.com>
- ACP spec repo — <https://github.com/agentclientprotocol/agent-client-protocol>
- Claude Code ACP adapter — <https://www.npmjs.com/package/@zed-industries/claude-code-acp>
- Codex ACP adapter — <https://github.com/zed-industries/codex-acp>
- Paper API — <https://docs.papermc.io/paper/dev>
