package ecosystem;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Game presentation. Simulation rules remain behind the original ecosystem facade. */
public final class App {
    private static final int WINDOW_WIDTH = 1280, WINDOW_HEIGHT = 820;
    private static final Color INK = new Color(234, 233, 223), MUTED = new Color(181, 187, 180);
    private static final Color BACK = new Color(23, 30, 32), SURFACE = new Color(37, 45, 46);
    private static final Color LINE = new Color(83, 96, 92), ACCENT = new Color(200, 177, 124);
    private static final Color HERBIVORE_COLOR = new Color(220, 176, 103), PREDATOR_COLOR = new Color(150, 190, 199);
    private static final Font BODY = new Font("Segoe UI", Font.PLAIN, 13);
    private final EcosystemFacade facade = new EcosystemFacade();
    private final JPanel root = new JPanel(new BorderLayout());
    private final JComboBox<String> biome = options(new String[]{"Meadow", "Desert"}, new String[]{"Meadow", "Desert"});
    private final JComboBox<String> strategy = options(new String[]{"Nearest food", "Safest food", "Sustainable grazing"},
        new String[]{"Nearest food", "Safest food", "Sustainable grazing"});
    private final JComboBox<String> weatherSource = options(new String[]{"Legacy adapter", "Native"}, new String[]{"Weather station", "Native weather"});
    private final JComboBox<String> reportType = options(new String[]{"Summary", "Detailed"}, new String[]{"Summary", "Detailed"});
    private final JComboBox<String> format = options(new String[]{"Text", "CSV"}, new String[]{"Text (.txt)", "CSV (.csv)"});
    private final JSpinner seed = new JSpinner(new SpinnerNumberModel(42, -1000000, 1000000, 1));
    private final JSpinner herbs = new JSpinner(new SpinnerNumberModel(30, 0, 450, 1));
    private final JSpinner predators = new JSpinner(new SpinnerNumberModel(5, 0, 50, 1));
    private final JButton start = new GameButton("Play", true);
    private final JTextArea log = new JTextArea(10, 38);
    private final JCheckBox liveLog = new JCheckBox("Record events", true);
    private final StatsPanel stats = new StatsPanel();
    private final WorldPanel world = new WorldPanel();
    private final Timer timer = new Timer(700, event -> {
        if (!world.isEncounterAnimating()) runSafely(facade::step);
    });
    private Model.Snapshot pendingVisualSnapshot;
    private final Events.EcosystemObserver logObserver = this::showMessages;
    private final JLabel worldTitle = text("Digital Ecosystem / Meadow", 18, true);
    private final JLabel worldState = text("Step 0 · Paused", 12, false);
    private final JLabel weather = text("", 13, false);
    private final JLabel status = text("", 12, false);
    private final JLabel strategyHelp = text("Deer move toward the nearest grass.", 12, false);
    private final GameViewport viewport = new GameViewport();
    private final InspectorPanel inspector = new InspectorPanel();
    private final HudSummary resources = new HudSummary();
    private final JPanel ecosystemDock = createSidebar();
    private final JPanel settingsWorld = new JPanel(new GridBagLayout());
    private final JPanel settingsObserve = new JPanel(new BorderLayout(12, 14));
    private final JPanel settingsReport = new JPanel(new BorderLayout(12, 14));

    public App() { this("Meadow", "Nearest food"); }

    private App(String selectedBiome, String selectedStrategy) {
        biome.setSelectedItem(selectedBiome);
        strategy.setSelectedItem(selectedStrategy);
        root.setBackground(BACK);
        root.setPreferredSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));
        root.getAccessibleContext().setAccessibleName("Digital Ecosystem");
        root.add(viewport, BorderLayout.CENTER);
        viewport.add(world, Integer.valueOf(0));
        viewport.add(createHeader(), Integer.valueOf(100));
        viewport.add(createPlayfield(), Integer.valueOf(100));
        viewport.add(createCameraControls(), Integer.valueOf(100));
        viewport.add(ecosystemDock, Integer.valueOf(200));
        viewport.add(inspector, Integer.valueOf(200));
        viewport.add(status, Integer.valueOf(100));
        status.setOpaque(true); status.setBackground(BACK); status.setBorder(new EmptyBorder(0, 12, 0, 12));
        ecosystemDock.setVisible(false); inspector.setVisible(false);
        prepareSettings();
        configureActions();
        world.setSelectionListener(animal -> { stats.select(animal); inspector.select(animal); });
        world.setStepDuration(timer.getDelay());
        world.setEncounterFinishedListener(this::flushVisualSnapshot);
        facade.subscribe(world);
        facade.subscribe(stats);
        facade.subscribe(logObserver);
        facade.subscribe(this::scheduleWorldInfo);
        facade.reset(readConfiguration());
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "play");
        root.getActionMap().put("play", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (!(focus instanceof JTextField) && !(focus instanceof JComboBox) && !(focus instanceof AbstractButton)) start.doClick();
            }
        });
    }

    private JPanel createHeader() {
        JPanel header = new Surface(new BorderLayout(12, 0), SURFACE); header.setName("gameHeader");
        header.setBorder(new EmptyBorder(3, 12, 3, 8));
        JPanel brand = panel(new GridLayout(2, 1, 0, 0));
        worldTitle.setFont(BODY.deriveFont(Font.BOLD, 15f)); worldState.setFont(BODY.deriveFont(11f));
        brand.add(worldTitle); brand.add(worldState); header.add(brand, BorderLayout.WEST);
        JPanel middle = panel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        resources.setPreferredSize(new Dimension(324, 39)); middle.add(resources);
        weather.setFont(BODY.deriveFont(12f)); middle.add(weather);
        header.add(middle, BorderLayout.CENTER);
        JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        actions.add(button("How to play", this::showHelp)); actions.add(button("Settings", this::showSettings));
        header.add(actions, BorderLayout.EAST); return header;
    }

    private JPanel createPlayfield() {
        JPanel controls = new Surface(new BorderLayout(8, 0), SURFACE); controls.setName("gameControls");
        controls.setBorder(new EmptyBorder(5, 10, 5, 10));
        JPanel playback = panel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        start.setPreferredSize(new Dimension(96, 34));
        start.setToolTipText("Play / Pause. Press Space when the map has focus."); playback.add(start);
        playback.add(button("Step", () -> runSafely(() -> { pause(); facade.step(); })));
        JComboBox<String> speed = options(new String[]{"0.5×", "1×", "2×", "4×"}, new String[]{"0.5×", "1×", "2×", "4×"});
        speed.setSelectedIndex(1); speed.setPreferredSize(new Dimension(70, 33));
        speed.getAccessibleContext().setAccessibleName("Simulation speed"); speed.setToolTipText("Simulation speed. Capture and escape animations always finish.");
        speed.addActionListener(e -> {
            int delay = new int[]{1400, 700, 350, 175}[speed.getSelectedIndex()];
            timer.setDelay(delay); timer.setInitialDelay(delay); world.setStepDuration(delay);
        });
        playback.add(speed); playback.add(button("+5 deer", () -> runSafely(() -> { pause(); facade.addHerbivores(5); })));
        controls.add(playback, BorderLayout.WEST);
        JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.add(button("Ecosystem", this::toggleEcosystem)); actions.add(button("New world", this::showSettings));
        controls.add(actions, BorderLayout.EAST); return controls;
    }

    private JPanel createCameraControls() {
        JPanel controls = panel(new GridLayout(3, 1, 0, 3)); controls.setName("cameraControls");
        JButton plus = button("+", world::zoomIn); plus.setToolTipText("Zoom in");
        plus.getAccessibleContext().setAccessibleName("Zoom in");
        JButton minus = button("−", world::zoomOut); minus.setToolTipText("Zoom out");
        minus.getAccessibleContext().setAccessibleName("Zoom out");
        JButton reset = button("View", world::resetCamera); reset.setToolTipText("Show the whole island");
        for (JButton control : new JButton[]{plus, minus, reset}) {
            control.setBorder(new EmptyBorder(4, 3, 4, 3)); control.setFont(BODY.deriveFont(12f)); controls.add(control);
        }
        return controls;
    }
    private JPanel createSidebar() {
        JPanel side = new Surface(new BorderLayout(0, 10), SURFACE);
        side.setName("ecosystemDock"); side.setBorder(new EmptyBorder(15, 16, 15, 16));
        JPanel header = panel(new BorderLayout());
        header.add(text("Ecosystem", 18, true), BorderLayout.WEST);
        JButton close = button("Close", () -> { ecosystemDock.setVisible(false); viewport.revalidate(); });
        close.setFont(BODY.deriveFont(11f)); header.add(close, BorderLayout.EAST);
        side.add(header, BorderLayout.NORTH); side.add(stats, BorderLayout.CENTER);
        JPanel behavior = panel(new BorderLayout(0, 7));
        behavior.add(text("Deer foraging", 13, true), BorderLayout.NORTH);
        strategy.setPreferredSize(new Dimension(245, 36)); behavior.add(strategy, BorderLayout.CENTER);
        strategyHelp.setPreferredSize(new Dimension(245, 30)); behavior.add(strategyHelp, BorderLayout.SOUTH);
        side.add(behavior, BorderLayout.SOUTH); return side;
    }

    private void toggleEcosystem() {
        ecosystemDock.setVisible(!ecosystemDock.isVisible()); viewport.revalidate(); viewport.repaint();
    }
    private void configureActions() {
        start.addActionListener(event -> {
            if (timer.isRunning()) pause();
            else { timer.start(); start.setText("Pause"); world.setRunning(true); scheduleWorldInfo(facade.snapshot()); }
        });
        strategy.addActionListener(event -> runSafely(() -> {
            pause(); facade.setStrategy((String) strategy.getSelectedItem());
            String[] help = {"Deer move toward the nearest grass.", "Deer prefer food away from wolves.", "Deer leave grass to regrow."};
            strategyHelp.setText(help[strategy.getSelectedIndex()]);
        }));
        strategyHelp.setText(new String[]{"Deer move toward the nearest grass.", "Deer prefer food away from wolves.", "Deer leave grass to regrow."}[strategy.getSelectedIndex()]);
        weatherSource.addActionListener(event -> runSafely(() -> {
            pause();
            facade.setWeatherSource(weatherSource.getSelectedIndex() == 0
                ? new WeatherSystem.LegacyWeatherAdapter(new WeatherSystem.LegacyWeatherStation())
                : new WeatherSystem.NativeWeatherSource());
        }));
        liveLog.addActionListener(event -> {
            if (liveLog.isSelected()) facade.subscribe(logObserver);
            else facade.unsubscribe(logObserver);
        });
    }

    private void prepareSettings() {
        settingsWorld.setOpaque(false);
        settingsWorld.setBorder(new EmptyBorder(12, 5, 8, 5));
        addSetting(0, "Biome", biome, "Grass grows more slowly in the desert.");
        addSetting(1, "World seed", seed, "The same seed creates the same world.");
        addSetting(2, "Starting deer", herbs, "0 to 450 animals");
        addSetting(3, "Starting wolves", predators, "0 to 50 animals");
        for (JSpinner input : new JSpinner[]{seed, herbs, predators}) {
            input.setPreferredSize(new Dimension(150, 34));
            ((JSpinner.DefaultEditor) input.getEditor()).getTextField().setFont(BODY);
        }
        settingsObserve.setOpaque(false);
        JPanel toggles = panel(new GridLayout(0, 1, 0, 8));
        JCheckBox grid = new JCheckBox("Show grid");
        grid.setOpaque(false); grid.addActionListener(e -> world.setGridVisible(grid.isSelected()));
        JCheckBox motion = new JCheckBox("Smooth motion", true);
        motion.setOpaque(false); motion.addActionListener(e -> world.setMotionEnabled(motion.isSelected()));
        liveLog.setOpaque(false);
        toggles.add(grid); toggles.add(motion); toggles.add(liveLog);
        JPanel weatherRow = panel(new BorderLayout(8, 0));
        weatherRow.add(text("Weather source", 13, true), BorderLayout.WEST);
        weatherRow.add(weatherSource, BorderLayout.CENTER);
        toggles.add(weatherRow);
        settingsObserve.add(toggles, BorderLayout.NORTH);
        log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
        log.setFont(BODY); log.setBackground(SURFACE); log.setForeground(INK);
        log.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane logs = new JScrollPane(log);
        logs.setBorder(BorderFactory.createLineBorder(LINE));
        logs.setPreferredSize(new Dimension(450, 180));
        settingsObserve.add(logs, BorderLayout.CENTER);
        settingsObserve.add(text("Both weather sources use the same simulated cycle.", 12, false), BorderLayout.SOUTH);
        settingsReport.setOpaque(false);
        JPanel reportFields = panel(new GridLayout(0, 2, 14, 16));
        reportFields.add(text("Report content", 13, true)); reportFields.add(reportType);
        reportFields.add(text("File format", 13, true)); reportFields.add(format);
        settingsReport.add(reportFields, BorderLayout.NORTH);
        JTextArea explanation = prose("Export population, plant reserves and weather. A detailed report also lists every plant and animal.\n\nOpen CSV files in a spreadsheet. Reports cannot be loaded to resume a simulation.");
        settingsReport.add(explanation, BorderLayout.CENTER);
        settingsReport.add(button("Save report...", () -> runSafely(() -> exportReport(reportType.getSelectedIndex() == 1, format.getSelectedIndex() == 1))), BorderLayout.SOUTH);
    }

    private void addSetting(int row, String title, JComponent input, String help) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 19, 20);
        JPanel labels = panel(new GridLayout(2, 1, 0, 4));
        JLabel label = text(title, 14, true); label.setLabelFor(input);
        labels.add(label); labels.add(text(help, 12, false));
        settingsWorld.add(labels, c);
        c.gridx = 1; c.weightx = 0; c.insets = new Insets(0, 0, 19, 0);
        settingsWorld.add(input, c);
        input.getAccessibleContext().setAccessibleName(title);
    }

    private void showSettings() {
        pause();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(root), "World settings", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setBackground(BACK); body.setBorder(new EmptyBorder(18, 20, 18, 20));
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(SURFACE); tabs.setForeground(INK);
        tabs.setFont(BODY); tabs.addTab("World", settingsWorld); tabs.addTab("Observation", settingsObserve); tabs.addTab("Report", settingsReport);
        body.add(tabs, BorderLayout.CENTER);
        JPanel actions = panel(new BorderLayout(12, 0));
        JLabel settingsHint = text("Creating a world restarts the simulation.", 12, false);
        actions.add(settingsHint, BorderLayout.CENTER);
        JPanel buttons = panel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(button("Close", dialog::dispose));
        JButton apply = new GameButton("Create world", true);
        apply.addActionListener(e -> {
            try { resetWorld(); dialog.dispose(); }
            catch (RuntimeException failure) { JOptionPane.showMessageDialog(dialog, failure.getMessage(), "Check values", JOptionPane.ERROR_MESSAGE); }
        });
        buttons.add(apply);
        tabs.addChangeListener(e -> {
            int tab = tabs.getSelectedIndex();
            if (tab < 0) return;
            apply.setVisible(tab == 0);
            settingsHint.setText(new String[]{"Creating a world restarts the simulation.", "Observation settings apply immediately.", "Export a report of the current step."}[tab]);
        });
        actions.add(buttons, BorderLayout.EAST); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setMinimumSize(new Dimension(620, 450)); dialog.pack();
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setLocationRelativeTo(root); dialog.setVisible(true);
    }

    private void showHelp() {
        pause();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(root), "How to play", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(0, 20)); body.setBackground(BACK);
        body.setBorder(new EmptyBorder(24, 28, 24, 28));
        body.add(text("Controls", 20, true), BorderLayout.NORTH);
        JTextArea guide = prose("Control time and observe the island. One game day is 12 steps. A full meal keeps a deer or wolf fed for about 2 days. Food reserves are used before energy. Fed wolves do not hunt.\n\nWhen a hungry wolf reaches a deer, it has a 75% chance of capture. Otherwise the deer escapes. The wolf may keep chasing or change direction. Capture and escape animations finish even at high speed; CAUGHT or ESCAPED appears above the pair. Animals can still starve without food.\n\nMouse wheel: zoom.\nDrag: move the camera.\nView: show the whole island.\n\nPlay / Pause: control time.\nStep: advance one simulation step.\n+5 deer: add herbivores to the current world.\n\nA deer can graze without moving. Animals search the island when food is out of sight. Click an animal to inspect its action, food reserve and energy use. Ecosystem shows population history and foraging strategies.\n\nNew world opens the biome, starting populations and seed. Settings contains the event log and report export. This is a sandbox with no score or victory timer.");
        JScrollPane helpScroll = new JScrollPane(guide);
        helpScroll.setPreferredSize(new Dimension(550, 360));
        helpScroll.setBorder(BorderFactory.createEmptyBorder());
        helpScroll.setOpaque(false); helpScroll.getViewport().setOpaque(false);
        body.add(helpScroll, BorderLayout.CENTER);
        body.add(button("Close", dialog::dispose), BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.pack(); dialog.setResizable(false); dialog.setLocationRelativeTo(root); dialog.setVisible(true);
    }

    private void resetWorld() {
        Creation.WorldConfig config = readConfiguration();
        pause(); log.setText(""); stats.clear(); world.clearSelection(); world.resetCamera(); facade.reset(config);
    }

    private Creation.WorldConfig readConfiguration() {
        try { seed.commitEdit(); herbs.commitEdit(); predators.commitEdit(); }
        catch (ParseException invalid) { throw new IllegalArgumentException("Enter whole numbers for the world seed and animal counts.", invalid); }
        return new Creation.WorldBuilder().seed(((Number) seed.getValue()).longValue())
            .biome((String) biome.getSelectedItem()).population((Integer) herbs.getValue(), (Integer) predators.getValue())
            .strategy((String) strategy.getSelectedItem()).build();
    }

    private void pause() {
        timer.stop(); start.setText("Play"); world.setRunning(false);
        if (facade.snapshot() != null) scheduleWorldInfo(facade.snapshot());
    }

    private void runSafely(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) { pause(); JOptionPane.showMessageDialog(root, failure.getMessage(), "Check values", JOptionPane.ERROR_MESSAGE); }
    }

    private void scheduleWorldInfo(Model.Snapshot snapshot) {
        pendingVisualSnapshot = snapshot;
        if (!world.isEncounterAnimating()) flushVisualSnapshot();
    }

    private void flushVisualSnapshot() {
        if (pendingVisualSnapshot == null) return;
        Model.Snapshot resolved = pendingVisualSnapshot;
        pendingVisualSnapshot = null;
        updateWorldInfo(resolved);
    }

    private void updateWorldInfo(Model.Snapshot snapshot) {
        resources.update(snapshot); inspector.update(snapshot);
        worldTitle.setText(snapshot.biome().equals("Desert") ? "DIGITAL ECOSYSTEM / DESERT" : "DIGITAL ECOSYSTEM / MEADOW");
        int day = snapshot.tick() / Model.TICKS_PER_DAY + 1;
        worldState.setText("Day " + day + " · Step " + snapshot.tick() + " · " + (timer.isRunning() ? "Running" : "Paused"));
        weather.setText(String.format(Locale.ROOT, "%.0f °C · %.1f mm rain", snapshot.weather().temperatureC(), snapshot.weather().rainfallMm()));
        weather.setToolTipText("Temperature and rainfall for this step. Rain helps grass grow.");
        if (snapshot.tick() == 0) status.setText("Space: play / pause. Wheel: zoom. Drag: move camera.");
        else {
            long born = snapshot.messages().stream().filter(m -> m.startsWith("Born:")).count();
            long died = snapshot.messages().stream().filter(m -> m.startsWith("Died:")).count();
            long hunted = snapshot.messages().stream().filter(m -> m.contains(" ate #")).count();
            long escaped = snapshot.messages().stream().filter(m -> m.startsWith("Escaped:")).count();
            status.setText("Day " + day + " · Born: " + born + " · Starved: " + died + " · Caught: " + hunted + " · Escaped: " + escaped);
        }
        if (snapshot.stats().animalCount() == 0) status.setText("No animals remain. Add deer or create a new world.");
    }

    private void showMessages(Model.Snapshot snapshot) {
        for (String message : snapshot.messages()) log.append(localizeMessage(message) + "\n");
        if (log.getText().length() > 12000) log.setText(log.getText().substring(log.getText().length() - 9000));
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static String localizeMessage(String message) {
        return message.replace("Escaped: Herbivore #", "Deer #")
            .replace(" from Predator #", " escaped from wolf #")
            .replace("Herbivore", "Deer").replace("Predator", "Wolf")
            .replace(" ate #", " caught deer #")
            .replace("Added herbivores: ", "Added deer: ")
            .replace("Died: ", "Starved: ")
            .replace("Weather source changed: LegacyWeatherAdapter", "Weather source: Weather station")
            .replace("Weather source changed: NativeWeatherSource", "Weather source: Native weather");
    }

    private void exportReport(boolean detailed, boolean csv) {
        pause();
        Reporting.ReportRenderer renderer = reportRenderer(csv);
        Component dialogParent = settingsReport.isShowing() ? settingsReport : root;
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Save ecosystem report");
        chooser.setSelectedFile(new File("ecosystem-" + facade.snapshot().tick() + "." + renderer.extension()));
        if (chooser.showSaveDialog(dialogParent) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        if (Files.exists(file) && JOptionPane.showConfirmDialog(dialogParent, "This file already exists. Replace it?", "Replace file?", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try { Files.writeString(file, facade.report(detailed, renderer), StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("Could not save the report. Check the folder and write permission.", failure); }
        log.append("Report saved: " + file + "\n"); status.setText("Report saved: " + file.getFileName());
    }

    private static Reporting.ReportRenderer reportRenderer(boolean csv) {
        return csv ? new Reporting.CsvRenderer() : new Reporting.PlainTextRenderer();
    }

    private static JPanel panel(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    private static JLabel text(String value, int size, boolean bold) {
        JLabel label = new JLabel(value); label.setFont(BODY.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size));
        label.setForeground(bold ? INK : MUTED); return label;
    }
    private static JTextArea prose(String value) {
        JTextArea text = new JTextArea(value); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setOpaque(false); text.setFont(BODY.deriveFont(14f)); text.setForeground(INK); text.setBorder(new EmptyBorder(8, 0, 8, 0)); return text;
    }
    private static JButton button(String title, Runnable action) { JButton b = new GameButton(title, false); b.addActionListener(e -> action.run()); return b; }
    private static JComboBox<String> options(String[] values, String[] labels) {
        JComboBox<String> box = new JComboBox<>(values);
        box.setFont(BODY); box.setForeground(INK); box.setBackground(SURFACE);
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                for (int i = 0; i < values.length; i++) if (values[i].equals(value)) label.setText(labels[i]);
                label.setBorder(new EmptyBorder(5, 8, 5, 8)); label.setBackground(selected ? ACCENT : SURFACE); label.setForeground(selected ? BACK : INK); return label;
            }
        });
        return box;
    }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        try {
            String command = args.length == 0 ? "" : args[0];
            switch (command) {
                case "--help", "-h" -> {
                    requireArgumentCount(args, 1);
                    printUsage();
                }
                case "--self-test", "--test" -> {
                    requireArgumentCount(args, 1);
                    SelfTest.main(new String[0]);
                }
                case "--examples" -> {
                    requireArgumentCount(args, 2);
                    writeExampleReports(Path.of(args.length > 1 ? args[1] : "examples"));
                }
                case "--demo" -> {
                    requireArgumentCount(args, 2);
                    writeComparison(Path.of(args.length > 1 ? args[1] : "comparison.csv"));
                }
                case "--screenshot" -> {
                    requireArgumentCount(args, 7);
                    configureSwing();
                    writeScreenshot(args);
                }
                case "" -> {
                    if (args.length != 0) throw new IllegalArgumentException("The command cannot be empty.");
                    if (GraphicsEnvironment.isHeadless()) {
                        throw new IllegalArgumentException("A desktop session is required to open the application.");
                    }
                    configureSwing();
                    SwingUtilities.invokeAndWait(App::showWindow);
                }
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            }
        } catch (Exception failure) {
            Throwable reason = failure;
            while (reason instanceof java.lang.reflect.InvocationTargetException && reason.getCause() != null) {
                reason = reason.getCause();
            }
            System.err.println("Error: " + (reason.getMessage() == null ? "The command could not be completed." : reason.getMessage()));
            System.err.println("Run with --help for usage.");
            System.exit(2);
        }
    }

    private static void requireArgumentCount(String[] args, int maximum) {
        if (args.length > maximum) {
            throw new IllegalArgumentException("Too many arguments for " + args[0] + ".");
        }
    }

    private static void printUsage() {
        System.out.println("Digital Ecosystem - Java 17+ simulation");
        System.out.println("Usage: java -jar digital-ecosystem.jar [command]");
        System.out.println("  (no command)                         Open the desktop application");
        System.out.println("  --help                               Show this help");
        System.out.println("  --self-test                          Run model and pattern checks");
        System.out.println("  --examples [directory]               Write four reports (default: examples)");
        System.out.println("  --demo [file.csv]                    Compare three strategies over 100 steps");
        System.out.println("  --screenshot [file.png [steps [biome [strategy [width [height]]]]]]");
        System.out.println("Defaults: screenshot.png, 16, Meadow, Nearest food, 1280, 820");
        System.out.println("Biomes: Meadow, Desert");
        System.out.println("Strategies: Nearest food, Safest food, Sustainable grazing");
        System.out.println("Quote strategy names containing spaces. Screenshot minimum: 1044 x 661.");
    }

    private static void configureSwing() throws UnsupportedLookAndFeelException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        for (String key : new String[]{"Label.font", "Button.font", "ComboBox.font", "Spinner.font", "TextField.font", "CheckBox.font", "TabbedPane.font", "OptionPane.messageFont", "OptionPane.buttonFont", "ToolTip.font"}) UIManager.put(key, new FontUIResource(BODY));
        UIManager.put("Panel.background", BACK); UIManager.put("ComboBox.background", SURFACE);
        for (String key : new String[]{"Label.foreground", "CheckBox.foreground", "TabbedPane.foreground", "OptionPane.messageForeground", "Button.foreground", "TextField.foreground", "Spinner.foreground"}) UIManager.put(key, INK);
        for (String key : new String[]{"CheckBox.background", "Button.background", "TextField.background", "Spinner.background", "ComboBox.buttonBackground", "ToolTip.background"}) UIManager.put(key, SURFACE);
        UIManager.put("TextField.caretForeground", ACCENT); UIManager.put("ToolTip.foreground", INK);
        UIManager.put("TextArea.selectionBackground", ACCENT); UIManager.put("TextArea.selectionForeground", BACK);
        UIManager.put("ComboBox.selectionBackground", ACCENT); UIManager.put("ComboBox.selectionForeground", BACK);
        UIManager.put("TabbedPane.background", SURFACE); UIManager.put("TabbedPane.unselectedBackground", SURFACE);
        UIManager.put("TabbedPane.selected", SURFACE); UIManager.put("TabbedPane.contentAreaColor", BACK);
        UIManager.put("OptionPane.yesButtonText", "Yes"); UIManager.put("OptionPane.noButtonText", "No");
        UIManager.put("OptionPane.cancelButtonText", "Cancel"); UIManager.put("FileChooser.saveButtonText", "Save");
        UIManager.put("FileChooser.cancelButtonText", "Cancel");
        ToolTipManager.sharedInstance().setInitialDelay(300);
    }

    private static void showWindow() {
        App app = new App();
        JFrame frame = new JFrame("Digital Ecosystem");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); frame.setContentPane(app.root);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(1060, 700));
        frame.setSize(Math.min(WINDOW_WIDTH + 16, screen.width), Math.min(WINDOW_HEIGHT + 39, screen.height));
        frame.setLocationRelativeTo(null); frame.setVisible(true);
    }
    private static void writeExampleReports(Path output) throws IOException {
        Files.createDirectories(output);
        EcosystemFacade facade = new EcosystemFacade();
        facade.reset(new Creation.WorldBuilder().build());
        for (int step = 0; step < 16; step++) {
            facade.step();
        }

        List<Reporting.ReportRenderer> renderers = List.of(reportRenderer(false), reportRenderer(true));
        for (Reporting.ReportRenderer renderer : renderers) {
            Path summary = output.resolve("summary-tick16." + renderer.extension());
            Files.writeString(summary, facade.report(false, renderer), StandardCharsets.UTF_8);
            System.out.println("Saved " + summary.toAbsolutePath());
        }
        for (Reporting.ReportRenderer renderer : renderers) {
            Path detailed = output.resolve("detailed-tick16." + renderer.extension());
            Files.writeString(detailed, facade.report(true, renderer), StandardCharsets.UTF_8);
            System.out.println("Saved " + detailed.toAbsolutePath());
        }
    }

    private static void writeComparison(Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        StringBuilder csv = new StringBuilder("strategy,tick,herbivores,predators,plant_biomass,animal_body_units\n");
        String[] strategies = {"Nearest food", "Safest food", "Sustainable grazing"};
        for (String name : strategies) {
            EcosystemFacade facade = new EcosystemFacade();
            facade.reset(new Creation.WorldBuilder().strategy(name).build());
            for (int tick = 0; tick <= 100; tick++) {
                Model.Snapshot snapshot = facade.snapshot();
                Model.Stats stats = snapshot.stats();
                csv.append(name).append(',')
                    .append(snapshot.tick()).append(',')
                    .append(stats.herbivores()).append(',')
                    .append(stats.predators()).append(',')
                    .append(stats.plantBiomass()).append(',')
                    .append(stats.animalBodyUnits()).append('\n');
                if (tick < 100) {
                    facade.step();
                }
            }
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        System.out.println("Saved 303 observations: " + output.toAbsolutePath());
    }

    private static void writeScreenshot(String[] args) throws Exception {
        Path path = Path.of(args.length > 1 ? args[1] : "screenshot.png");
        int steps = args.length > 2 ? parseInteger(args[2], "Screenshot steps") : 16;
        String biome = args.length > 3 ? args[3] : "Meadow";
        String strategy = args.length > 4 ? args[4] : "Nearest food";
        if (steps < 0) {
            throw new IllegalArgumentException("Screenshot step count must be non-negative.");
        }
        Biomes.named(biome);
        Behaviors.named(strategy);

        int width = args.length > 5 ? parseInteger(args[5], "Screenshot width") : WINDOW_WIDTH;
        int height = args.length > 6 ? parseInteger(args[6], "Screenshot height") : WINDOW_HEIGHT;
        if (width < 1044 || width > 4096 || height < 661 || height > 4096) {
            throw new IllegalArgumentException("Screenshot size must be 1044..4096 pixels wide and 661..4096 pixels high.");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            App app = new App(biome, strategy);
            for (int i = 0; i < steps; i++) {
                app.facade.step();
            }
            app.root.setSize(width, height);
            layoutTree(app.root);
            Graphics2D graphics = image.createGraphics();
            try {
                app.root.printAll(graphics);
            } finally {
                graphics.dispose();
            }
        });
        Files.createDirectories(path.toAbsolutePath().getParent());
        ImageIO.write(image, "png", path.toFile());
        System.out.println("Saved UI image: " + path.toAbsolutePath());
    }

    private static int parseInteger(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }

    // Offscreen screenshots have no window to lay out the Swing components.
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }


    private static final class Surface extends JPanel {
        private final Color fill;
        Surface(LayoutManager layout, Color fill) { super(layout); this.fill = fill; setOpaque(false); }
        @Override protected void paintComponent(Graphics base) {
            Graphics2D g = (Graphics2D) base.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill); g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(LINE); g.drawRect(0, 0, getWidth() - 1, getHeight() - 1); g.dispose();
            super.paintComponent(base);
        }
    }

    private static final class GameButton extends JButton {
        private final boolean primary;
        GameButton(String text, boolean primary) {
            super(text); this.primary = primary; setFont(BODY.deriveFont(primary ? Font.BOLD : Font.PLAIN, 13f));
            setForeground(primary ? new Color(30, 48, 39) : INK); setBorder(new EmptyBorder(7, 10, 7, 10));
            setOpaque(false); setContentAreaFilled(false); setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics base) {
            Graphics2D g = (Graphics2D) base.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = primary ? ACCENT : new Color(52, 63, 64);
            if (getModel().isPressed()) fill = primary ? new Color(168, 143, 91) : new Color(31, 41, 43);
            else if (getModel().isRollover()) fill = primary ? new Color(219, 199, 152) : new Color(69, 82, 84);
            if (!isEnabled()) fill = LINE;
            g.setColor(fill); g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(primary ? fill.brighter() : LINE); g.setStroke(new BasicStroke(1)); g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            if (hasFocus()) { g.setStroke(new BasicStroke(2)); g.setColor(new Color(171, 121, 49)); g.drawRect(2, 2, getWidth() - 5, getHeight() - 5); }
            g.dispose(); super.paintComponent(base);
        }
    }

    /** Each overlay owns only its visible rectangle, leaving map input unobstructed. */
    private final class GameViewport extends JLayeredPane {
        @Override public void doLayout() {
            int w = getWidth(), h = getHeight();
            for (Component c : getComponents()) {
                if (c == world) c.setBounds(0, 0, w, h);
                else if (c == ecosystemDock) c.setBounds(w - 324, 58, 312, Math.max(330, h - 138));
                else if (c == inspector) c.setBounds(12, h - 309, 306, 234);
                else if (c == status) c.setBounds(0, h - 70, w, 24);
                else if ("gameHeader".equals(c.getName())) c.setBounds(0, 0, w, 46);
                else if ("gameControls".equals(c.getName())) c.setBounds(0, h - 46, w, 46);
                else if ("cameraControls".equals(c.getName())) c.setBounds(10, 63, 38, 104);
            }
        }
    }

    private final class HudSummary extends JPanel {
        private Model.Snapshot snapshot;
        HudSummary() {
            setOpaque(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Open population history and foraging settings");
            addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent event) { toggleEcosystem(); } });
        }
        void update(Model.Snapshot next) {
            snapshot = next;
            getAccessibleContext().setAccessibleName("Deer: " + next.stats().herbivores() + ", wolves: " + next.stats().predators() + ", grass reserve: " + next.stats().plantBiomass());
            repaint();
        }
        @Override protected void paintComponent(Graphics base) {
            if (snapshot == null) return;
            Graphics2D g = (Graphics2D) base.create(); quality(g);
            int cell = getWidth() / 3;
            WorldPanel.drawRabbit(g, 19, 32, .52, false);
            WorldPanel.drawFox(g, cell + 19, 31, .52, false);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(ACCENT);
            for (int i = 0; i < 4; i++) {
                int x = cell * 2 + 12 + i * 4;
                g.drawLine(x, 29, x - 2, 20 - i % 2 * 4); g.drawLine(x, 29, x + 4, 19 + i % 3 * 2);
            }
            int capacity = snapshot.plants().stream().mapToInt(Model.PlantView::capacity).sum();
            String[] values = {Integer.toString(snapshot.stats().herbivores()), Integer.toString(snapshot.stats().predators()), Math.round(snapshot.stats().plantBiomass() * 100.0 / Math.max(1, capacity)) + "%"};
            String[] names = {"Deer", "Wolves", "Grass"};
            for (int i = 0; i < 3; i++) {
                drawText(g, names[i] + " " + values[i], cell * i + 38, 25, 13, false, INK);
                if (i != 0) { g.setColor(LINE); g.setStroke(new BasicStroke(1)); g.drawLine(cell * i, 9, cell * i, 31); }
            }
            g.dispose();
        }
    }

    private final class InspectorPanel extends JPanel {
        private Model.AnimalView animal;
        private boolean lost;
        private String deathReason = "Animal died";
        private Model.Snapshot snapshot;
        InspectorPanel() {
            setLayout(null); setOpaque(false);
            JButton close = button("Close", world::clearSelection); close.setFont(BODY.deriveFont(11f));
            add(close);
        }
        @Override public void doLayout() { getComponent(0).setBounds(getWidth() - 88, 8, 80, 28); }
        void select(Model.AnimalView next) {
            boolean wasVisible = isVisible();
            animal = next; lost = false; setVisible(next != null);
            if (wasVisible != isVisible()) { viewport.revalidate(); viewport.repaint(); }
            repaint();
        }
        void update(Model.Snapshot next) {
            snapshot = next;
            if (animal != null) {
                long id = animal.id();
                Model.AnimalView previous = animal;
                animal = next.animals().stream().filter(a -> a.id() == id).findFirst().orElse(null);
                if (animal == null) {
                    lost = true;
                    boolean starved = next.messages().stream().anyMatch(m -> m.equals("Died: "
                        + (previous.kind().equals("herbivore") ? "Herbivore" : "Predator") + " #" + id));
                    boolean hunted = next.hunts().stream().anyMatch(h -> h.deerId() == id && h.outcome() == Model.HuntOutcome.CAPTURE)
                        || next.messages().stream().anyMatch(m -> m.endsWith(" ate #" + id));
                    deathReason = starved ? "Starved" : hunted ? "Deer caught by a wolf" : "Animal left the world";
                }
            }
            repaint();
        }
        @Override protected void paintComponent(Graphics base) {
            Graphics2D g = (Graphics2D) base.create(); quality(g);
            g.setColor(SURFACE); g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(LINE); g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.drawLine(0, 39, getWidth(), 39);
            if (animal != null) {
                boolean herbivore = animal.kind().equals("herbivore");
                int max = AnimalPresentation.maxEnergy(snapshot != null ? snapshot.biome() : "Meadow", animal);
                drawText(g, (herbivore ? "Deer" : "Wolf") + " #" + animal.id(), 17, 29, 15, true, INK);
                drawText(g, "Last step: " + AnimalPresentation.action(animal), 17, 55, 12, true, ACCENT);
                if (herbivore) WorldPanel.drawRabbit(g, 47, 128, 1.35, false);
                else WorldPanel.drawFox(g, 47, 128, 1.35, false);
                drawText(g, "Energy " + animal.energy() + " / " + max, 101, 85, 13, true, INK);
                g.setColor(LINE); g.fillRoundRect(101, 97, 185, 7, 7, 7);
                g.setColor(animal.satietyTicks() == 0 && animal.energy() <= max / 4 ? HERBIVORE_COLOR : ACCENT);
                g.fillRoundRect(101, 97, Math.min(185, 185 * animal.energy() / max), 7, 7, 7);
                drawText(g, AnimalPresentation.satiety(animal), 101, 122, 12, true, animal.satietyTicks() > 0 ? ACCENT : HERBIVORE_COLOR);
                drawText(g, "Age: " + AnimalPresentation.days(animal.age()), 101, 143, 12, false, MUTED);
                g.setColor(LINE); g.drawLine(17, 160, getWidth() - 17, 160);
                drawText(g, "This step: ate " + animal.lastMeal() + " · spent " + animal.energySpent(), 17, 181, 12, false, MUTED);
                if (animal.reproductionSpent() > 0) {
                    drawText(g, "Another " + animal.reproductionSpent() + " energy spent on offspring", 17, 202, 11, false, MUTED);
                }
                drawText(g, AnimalPresentation.energyNote(animal), 17, 224, 11, false,
                    animal.satietyTicks() == 0 && animal.energy() <= animal.metabolism() * 3 ? HERBIVORE_COLOR : MUTED);
            } else if (lost) {
                drawText(g, deathReason, 17, 55, 15, true, INK);
                drawText(g, "Select another animal", 17, 88, 13, false, MUTED);
                drawText(g, "or add deer to the island.", 17, 109, 13, false, MUTED);
            }
            g.dispose(); super.paintComponent(base);
        }
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    private static void drawText(Graphics2D g, String text, int x, int y, int size, boolean bold, Color color) {
        g.setFont(BODY.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size)); g.setColor(color); g.drawString(text, x, y);
    }

    private static final class StatsPanel extends JPanel implements Events.EcosystemObserver {
        private Model.Snapshot snapshot;
        private Model.AnimalView selection;
        private boolean selectionLost;
        private final List<Model.Stats> history = new ArrayList<>();
        StatsPanel() { setOpaque(false); setPreferredSize(new Dimension(278, 300)); }
        void clear() { history.clear(); selection = null; selectionLost = false; }
        void select(Model.AnimalView animal) { selection = animal; selectionLost = false; repaint(); }
        public void onSnapshot(Model.Snapshot next) {
            if (snapshot != null && snapshot.tick() == next.tick() && !history.isEmpty()) history.set(history.size() - 1, next.stats());
            else history.add(next.stats());
            if (history.size() > 100) history.remove(0);
            if (selection != null) {
                long id = selection.id(); selection = next.animals().stream().filter(a -> a.id() == id).findFirst().orElse(null);
                selectionLost = selection == null;
            }
            snapshot = next; repaint();
        }
        @Override protected void paintComponent(Graphics base) {
            if (snapshot == null) return;
            Graphics2D g = (Graphics2D) base.create(); quality(g);
            drawText(g, "Population over time", 0, 19, 13, true, INK);
            drawText(g, "Deer: " + snapshot.stats().herbivores(), 11, 43, 12, false, HERBIVORE_COLOR);
            drawText(g, "Wolves: " + snapshot.stats().predators(), 122, 43, 12, false, PREDATOR_COLOR);
            g.setColor(HERBIVORE_COLOR); g.fillOval(0, 36, 6, 6);
            g.setColor(PREDATOR_COLOR); g.fillOval(111, 36, 6, 6);
            int top = 65, bottom = Math.max(top + 35, getHeight() - 72), width = getWidth() - 3;
            int max = 10;
            for (Model.Stats point : history) max = Math.max(max, Math.max(point.herbivores(), point.predators()));
            g.setColor(LINE); g.setStroke(new BasicStroke(1));
            for (int i = 0; i <= 2; i++) { int y = top + (bottom - top) * i / 2; g.drawLine(0, y, width, y); }
            drawText(g, Integer.toString(max), 3, top + 13, 10, false, MUTED);
            if (history.size() < 2) drawText(g, "History appears after the first step", 3, top + 49, 11, false, MUTED);
            else for (boolean herbivore : new boolean[]{true, false}) {
                g.setColor(herbivore ? HERBIVORE_COLOR : PREDATOR_COLOR);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D path = new Path2D.Double();
                for (int i = 0; i < history.size(); i++) {
                    Model.Stats point = history.get(i);
                    int count = herbivore ? point.herbivores() : point.predators();
                    double x = width * i / (double) (history.size() - 1), y = bottom - (bottom - top) * count / (double) max;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                g.draw(path);
            }
            drawText(g, "Last " + history.size() + " observations", 1, bottom + 20, 11, false, MUTED);
            drawText(g, "Plant reserve: " + snapshot.stats().plantBiomass() + " units", 1, getHeight() - 22, 12, false, MUTED);
            drawText(g, "Animal body units: " + snapshot.stats().animalBodyUnits() + " units", 1, getHeight() - 3, 12, false, MUTED);
            g.dispose();
        }
    }
}




