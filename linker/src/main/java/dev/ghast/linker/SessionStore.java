package dev.ghast.linker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists the villager&rarr;session binding to disk so a session can be resumed after the linker (or
 * the Minecraft plugin) restarts. Keyed by the plugin's villager id; stores just enough to reattach
 * via ACP {@code session/load}: the agent session id, the profile, and the working directory.
 *
 * <p>Best-effort: a corrupt or unreadable file is logged and treated as empty rather than fatal.
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** One persisted binding. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String sessionId, String profileId, String cwd) {
    }

    private final Path file;
    private final Map<String, Entry> byVillager = new ConcurrentHashMap<>();

    public SessionStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (file == null || Files.notExists(file)) {
            return;
        }
        try {
            Map<String, Entry> loaded = MAPPER.readValue(file.toFile(), new TypeReference<>() {
            });
            byVillager.putAll(loaded);
            log.info("Loaded {} persisted session(s) from {}", byVillager.size(), file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not read session store {} ({}); starting empty", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Snapshot to a plain map so ordering is stable and we don't serialize the concurrent view.
            MAPPER.writeValue(file.toFile(), new LinkedHashMap<>(byVillager));
        } catch (IOException e) {
            log.warn("Could not write session store {}: {}", file, e.getMessage());
        }
    }

    /** The persisted agent session id for a villager, or {@code null} if none is known. */
    public String sessionIdFor(String villagerId) {
        Entry entry = villagerId == null ? null : byVillager.get(villagerId);
        return entry == null ? null : entry.sessionId();
    }

    public Entry get(String villagerId) {
        return villagerId == null ? null : byVillager.get(villagerId);
    }

    public Map<String, Entry> all() {
        return Map.copyOf(byVillager);
    }

    public void put(String villagerId, String sessionId, String profileId, String cwd) {
        if (villagerId == null || sessionId == null) {
            return;
        }
        byVillager.put(villagerId, new Entry(sessionId, profileId, cwd));
        save();
    }

    public void remove(String villagerId) {
        if (villagerId != null && byVillager.remove(villagerId) != null) {
            save();
        }
    }
}
