package dev.ghast.linker.ui;

import dev.ghast.linker.AgentSession;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.List;

/**
 * A tiny always-on status window so a desktop user can see the linker is running, how many plugins
 * are connected, and which agent sessions are live. Purely informational; safe to skip in headless
 * environments (the caller checks {@link java.awt.GraphicsEnvironment#isHeadless()}).
 */
public final class StatusWindow {

    private final JFrame frame = new JFrame("Minecraft Agent IDE — Linker");
    private final JLabel listeningLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final DefaultListModel<String> sessionModel = new DefaultListModel<>();

    public StatusWindow(String listeningAddress) {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Minecraft Agent IDE");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        listeningLabel.setText("Listening on " + listeningAddress);
        header.add(title);
        header.add(listeningLabel);
        header.add(statusLabel);
        root.add(header, BorderLayout.NORTH);

        JList<String> sessionList = new JList<>(sessionModel);
        JScrollPane scroll = new JScrollPane(sessionList);
        scroll.setBorder(BorderFactory.createTitledBorder("Active agent sessions"));
        root.add(scroll, BorderLayout.CENTER);

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(root);
        frame.setSize(420, 320);
        frame.setLocationByPlatform(true);
        setStatus(0, 0);
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    public void setStatus(int plugins, int sessions) {
        SwingUtilities.invokeLater(() ->
                statusLabel.setText(plugins + " plugin(s) connected · " + sessions + " session(s) active"));
    }

    public void setSessions(List<AgentSession> sessions) {
        SwingUtilities.invokeLater(() -> {
            sessionModel.clear();
            for (AgentSession s : sessions) {
                sessionModel.addElement(String.format("%s · villager %s · %s",
                        s.profile().displayNameOrId(), s.villagerId(), abbreviate(s.sessionId())));
            }
        });
    }

    private static String abbreviate(String id) {
        if (id == null) {
            return "(starting…)";
        }
        return id.length() > 12 ? id.substring(0, 12) + "…" : id;
    }
}
