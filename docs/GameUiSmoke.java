import ecosystem.App;
import ecosystem.EcosystemFacade;
import ecosystem.Reporting;
import ecosystem.Model;
import ecosystem.Creation;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Swing integration checks for the game interface; run outside headless mode. */
public final class GameUiSmoke {
    private static Object field(Object object, String name) throws Exception {
        Field result = object.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result.get(object);
    }

    private static <T extends Component> T find(Container root, Class<T> type, String text) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                String label = component instanceof AbstractButton button ? button.getText() : "";
                if (label.equals(text)) return type.cast(component);
            }
            if (component instanceof Container nested) {
                T result = find(nested, type, text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container nested) {
                T result = find(nested, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JButton button(Container root, String text) {
        JButton result = find(root, JButton.class, text);
        check(result != null, "Button missing: " + text);
        return result;
    }

    private static Component named(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container nested) {
                Component found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void separated(Container root, Component first, Component second, String description) {
        check(first != null && second != null, "Missing HUD components: " + description);
        Rectangle firstBox = SwingUtilities.convertRectangle(first.getParent(), first.getBounds(), root);
        Rectangle secondBox = SwingUtilities.convertRectangle(second.getParent(), second.getBounds(), root);
        check(!firstBox.intersects(secondBox), "HUD overlaps: " + description + " " + firstBox + " / " + secondBox);
    }

    private static void capture(Component root, Path path) throws Exception {
        BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { root.printAll(graphics); } finally { graphics.dispose(); }
        Files.createDirectories(path.toAbsolutePath().getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    private static void bounds(Container parent) {
        for (Component component : parent.getComponents()) {
            if (!component.isShowing()) continue;
            if (component instanceof JButton || component instanceof JComboBox<?>
                    || component instanceof JSpinner || component instanceof JCheckBox) {
                Rectangle box = component.getBounds();
                check(box.width > 0 && box.height > 0 && box.x >= 0 && box.y >= 0
                        && box.x + box.width <= parent.getWidth()
                        && box.y + box.height <= parent.getHeight(),
                        "Clipped control: " + component.getClass().getSimpleName()
                                + " " + (component instanceof AbstractButton b ? b.getText() : "")
                                + " " + box + " in " + parent.getSize());
            }
            if (component instanceof Container nested) bounds(nested);
        }
    }

    /** A Swing timer drives the real modal dialog while doClick enters its event loop. */
    private static void settings(JButton entry, String action, Runnable beforeAction) {
        boolean[] visited = {false};
        Throwable[] failure = {null};
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Timer driver = new Timer(50, event -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
                try {
                    if (System.nanoTime() > deadline) throw new AssertionError("Settings timed out");
                    JTabbedPane tabs = find(dialog, JTabbedPane.class);
                    if (tabs == null) continue;
                    Set<String> titles = new HashSet<>();
                    JButton apply = button(dialog, "Create world");
                    for (int index = 0; index < tabs.getTabCount(); index++) {
                        titles.add(tabs.getTitleAt(index));
                        tabs.setSelectedIndex(index);
                        dialog.validate();
                        bounds(dialog);
                        check(apply.isVisible() == (index == 0),
                                "Create-world action has incorrect visibility on tab " + tabs.getTitleAt(index));
                        if (tabs.getTitleAt(index).equals("Observation")) {
                            capture(dialog.getRootPane(), Path.of("tmp", "isometric-settings.png"));
                        }
                    }
                    check(titles.containsAll(Set.of("World", "Observation", "Report")),
                            "Settings lost a required feature group: " + titles);
                    tabs.setSelectedIndex(0);
                    visited[0] = true;
                    ((Timer) event.getSource()).stop();
                    if (beforeAction != null) beforeAction.run();
                    if (action == null) dialog.dispose();
                    else button(dialog, action).doClick(0);
                } catch (Throwable error) {
                    failure[0] = error;
                    ((Timer) event.getSource()).stop();
                    dialog.dispose();
                }
                break;
            }
        });
        driver.start();
        try { entry.doClick(0); } finally { driver.stop(); }
        if (failure[0] != null) throw new AssertionError("Settings dialog failed", failure[0]);
        check(visited[0], "Settings dialog was not reached from " + entry.getText());
    }

    private static void export(App app, Path output, boolean detailed, boolean csv,
                               boolean approve, Integer overwrite) throws Exception {
        boolean[] visited = new boolean[2];
        Throwable[] failure = {null};
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Timer driver = new Timer(50, event -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
                try {
                    if (System.nanoTime() > deadline) throw new AssertionError("Export dialog timed out");
                    JFileChooser chooser = find(dialog, JFileChooser.class);
                    if (chooser != null && !visited[0]) {
                        visited[0] = true;
                        chooser.setSelectedFile(output.toFile());
                        if (approve) chooser.approveSelection();
                        else chooser.cancelSelection();
                    } else {
                        JOptionPane prompt = find(dialog, JOptionPane.class);
                        if (prompt == null) continue;
                        check(overwrite != null && dialog.getTitle().equals("Replace file?"),
                                "Unexpected export prompt: " + dialog.getTitle());
                        visited[1] = true;
                        prompt.setValue(overwrite);
                    }
                } catch (Throwable error) {
                    failure[0] = error;
                    dialog.dispose();
                }
            }
        });
        Method export = App.class.getDeclaredMethod("exportReport", boolean.class, boolean.class);
        export.setAccessible(true);
        driver.start();
        try { export.invoke(app, detailed, csv); } finally { driver.stop(); }
        if (failure[0] != null) throw new AssertionError("Export failed", failure[0]);
        check(visited[0], "Export did not show a file chooser");
        check(visited[1] == (overwrite != null), "Overwrite prompt mismatch");
    }

    private static void checkExports(App app, EcosystemFacade facade, Path output) throws Exception {
        for (boolean detailed : new boolean[]{false, true}) {
            for (boolean csv : new boolean[]{false, true}) {
                Reporting.ReportRenderer renderer = csv ? new Reporting.CsvRenderer()
                        : new Reporting.PlainTextRenderer();
                Path path = output.resolve((detailed ? "detailed." : "summary.") + renderer.extension());
                export(app, path, detailed, csv, true, null);
                check(Files.readString(path, StandardCharsets.UTF_8).equals(facade.report(detailed, renderer)),
                        "Exported report differs from selected report: " + path.getFileName());
            }
        }
        Path cancelled = output.resolve("cancelled.csv");
        export(app, cancelled, false, true, false, null);
        check(Files.notExists(cancelled), "Cancel wrote a report");
        Path replaced = output.resolve("replaced.csv");
        Files.writeString(replaced, "original", StandardCharsets.UTF_8);
        export(app, replaced, true, true, true, JOptionPane.NO_OPTION);
        check(Files.readString(replaced).equals("original"), "Declining overwrite changed the file");
        export(app, replaced, true, true, true, JOptionPane.YES_OPTION);
        check(Files.readString(replaced, StandardCharsets.UTF_8).equals(
                facade.report(true, new Reporting.CsvRenderer())), "Confirmed replacement wrote the wrong report");
    }

    private static void checkModalExport(JButton entry, Path cancelled) {
        boolean[] visited = {false, false};
        Throwable[] failure = {null};
        Timer settingsDriver = new Timer(50, event -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog settings) || !settings.isShowing()) continue;
                JTabbedPane tabs = find(settings, JTabbedPane.class);
                if (tabs == null) continue;
                ((Timer) event.getSource()).stop();
                visited[0] = true;
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    if (tabs.getTitleAt(i).equals("Report")) tabs.setSelectedIndex(i);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                Timer chooserDriver = new Timer(50, chooserEvent -> {
                    for (Window chooserWindow : Window.getWindows()) {
                        if (!(chooserWindow instanceof JDialog dialog) || !dialog.isShowing()) continue;
                        JFileChooser chooser = find(dialog, JFileChooser.class);
                        if (chooser == null) continue;
                        try {
                            check(System.nanoTime() < deadline, "Modal report chooser timed out");
                            check(dialog.getOwner() == settings, "Report chooser is owned by the main frame instead of settings");
                            visited[1] = true;
                            chooser.setSelectedFile(cancelled.toFile());
                            chooser.cancelSelection();
                        } catch (Throwable error) {
                            failure[0] = error;
                            dialog.dispose();
                        }
                        ((Timer) chooserEvent.getSource()).stop();
                    }
                });
                chooserDriver.start();
                try {
                    JButton export = button(settings, "Save report...");
                    check(export.isShowing(), "Export is not reachable from the report tab");
                    export.doClick(0);
                } catch (Throwable error) { failure[0] = error; }
                finally { chooserDriver.stop(); settings.dispose(); }
                break;
            }
        });
        settingsDriver.start();
        try { entry.doClick(0); } finally { settingsDriver.stop(); }
        if (failure[0] != null) throw new AssertionError("Modal report export failed", failure[0]);
        check(visited[0] && visited[1], "Settings report route did not reach its file chooser");
        check(Files.notExists(cancelled), "Cancel in modal report export wrote a file");
    }

    private static Model.World domainWorld(EcosystemFacade facade) throws Exception {
        Method method = EcosystemFacade.class.getDeclaredMethod("worldForTests");
        method.setAccessible(true);
        return (Model.World) method.invoke(facade);
    }

    private static String activityLabel(Model.AnimalView animal) throws Exception {
        Method method = Class.forName("ecosystem.AnimalPresentation").getDeclaredMethod("action", Model.AnimalView.class);
        method.setAccessible(true);
        return (String) method.invoke(null, animal);
    }

    private static Object presentation(String methodName, Class<?>[] types, Object... args) throws Exception {
        Method method = Class.forName("ecosystem.AnimalPresentation").getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    /** Yield the EDT to normal animation, repaint and completion listeners. */
    private static void allowEvents(int millis) {
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Timer stop = new Timer(millis, event -> loop.exit());
        stop.setRepeats(false); stop.start();
        check(loop.enter(), "Could not run the Swing event loop for encounter completion"); stop.stop();
    }

    private static void checkEncounterUi(App app, JFrame frame) throws Exception {
        JPanel root = (JPanel) field(app, "root");
        JComponent view = (JComponent) field(app, "world");
        EcosystemFacade facade = (EcosystemFacade) field(app, "facade");
        Method motion = view.getClass().getDeclaredMethod("setMotionEnabled", boolean.class);
        Method busy = view.getClass().getDeclaredMethod("isEncounterAnimating");
        Method listener = view.getClass().getDeclaredMethod("setEncounterFinishedListener", Runnable.class);
        motion.setAccessible(true); busy.setAccessible(true); listener.setAccessible(true);
        motion.invoke(view, false);
        facade.reset(new Creation.WorldBuilder().population(0, 0).build());
        Model.World world = domainWorld(facade);
        Model.Predator wolf = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 8, 10);
        wolf.satietyTicks = 0; wolf.cooldown = 1000;
        Model.Herbivore prey = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 9, 10);
        prey.cooldown = 1000;
        facade.setStrategy("Nearest food"); world.random.setSeed(0);
        view.getActionMap().get("next").actionPerformed(new ActionEvent(view, 0, "next"));
        view.getActionMap().get("next").actionPerformed(new ActionEvent(view, 0, "next"));
        check(((Model.AnimalView) field(field(app, "inspector"), "animal")).id() == prey.id,
                "Encounter fixture did not select its deer");
        motion.invoke(view, true);
        Runnable original = (Runnable) field(view, "encounterFinishedListener");
        AtomicInteger completions = new AtomicInteger();
        listener.invoke(view, (Runnable) () -> { completions.incrementAndGet(); original.run(); });
        Timer clock = (Timer) field(app, "timer"); int delay = clock.getDelay(), initial = clock.getInitialDelay();
        JButton play = (JButton) field(app, "start");
        try {
            Model.Snapshot after = facade.step();
            check(after.hunts().size() == 1 && after.hunts().get(0).outcome() == Model.HuntOutcome.CAPTURE,
                    "The UI fixture must produce a real catch");
            check((Boolean) busy.invoke(view), "A catch must start visible playback");
            check(field(app, "pendingVisualSnapshot").equals(after), "HUD did not retain its pending completed turn");
            check(((Model.Snapshot) field(field(app, "resources"), "snapshot")).tick() == 0,
                    "The resource count spoiled a capture before its visible outcome");
            check(!(Boolean) field(field(app, "inspector"), "lost"), "Inspector spoiled capture during approach");
            clock.setDelay(100); clock.setInitialDelay(100); play.doClick(0);
            allowEvents(320);
            check(facade.snapshot().tick() == 1 && (Boolean) busy.invoke(view),
                    "Automatic playback advanced while a critical encounter was unresolved");
            play.doClick(0); frame.validate(); bounds(root);
            capture(root, Path.of("tmp", "hunt-qa", "ui-capture-inflight.png"));
            allowEvents(650);
            check(!(Boolean) busy.invoke(view) && completions.get() == 1,
                    "Paused playback must finish and notify the HUD exactly once");
            check(field(app, "pendingVisualSnapshot") == null && facade.snapshot().tick() == 1,
                    "Completion must flush the HUD without changing logical time");
            check(field(field(app, "resources"), "snapshot").equals(after), "Resolved resource count is stale");
            Object inspector = field(app, "inspector");
            check((Boolean) field(inspector, "lost") && field(inspector, "deathReason").equals("Deer caught by a wolf"),
                    "Victim inspector did not publish the caught outcome after playback");
            capture(root, Path.of("tmp", "hunt-qa", "ui-capture-outcome.png"));
            check(facade.observerErrors().isEmpty(), "Encounter callbacks failed an observer");
        } finally {
            clock.stop(); clock.setDelay(delay); clock.setInitialDelay(initial); listener.invoke(view, original);
            motion.invoke(view, false);
            Method clear = view.getClass().getDeclaredMethod("clearSelection"); clear.setAccessible(true); clear.invoke(view);
            facade.reset(new Creation.WorldBuilder().build()); motion.invoke(view, true);
        }
    }

    private static void checkHelp(JButton entry) {
        Throwable[] failure = {null}; boolean[] visited = {false};
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Timer driver = new Timer(50, event -> {
            if (System.nanoTime() > deadline) {
                failure[0] = new AssertionError("Timed out waiting for the help dialog");
                for (Window window : Window.getWindows()) if (window instanceof JDialog && window.isShowing()) window.dispose();
                ((Timer) event.getSource()).stop(); return;
            }
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog dialog) || !dialog.isShowing() || !dialog.getTitle().equals("How to play")) continue;
                try {
                    bounds(dialog); check(find(dialog, JScrollPane.class) != null, "Long help must remain scrollable");
                    capture(dialog.getContentPane(), Path.of("tmp", "hunt-qa", "ui-help.png"));
                    visited[0] = true; button(dialog, "Close").doClick(0);
                } catch (Throwable error) { failure[0] = error; dialog.dispose(); }
                ((Timer) event.getSource()).stop();
            }
        });
        driver.start(); try { entry.doClick(0); } finally { driver.stop(); }
        if (failure[0] != null) throw new AssertionError("Help failed", failure[0]);
        check(visited[0], "Help action did not open its controls window");
    }

    /** Small domain fixtures verify that the inspector consumes authoritative life/death events. */
    private static void checkActivityInspector(App app, JFrame frame) throws Exception {
        JComponent animatedView = (JComponent) field(app, "world");
        Method motion = animatedView.getClass().getDeclaredMethod("setMotionEnabled", boolean.class);
        motion.setAccessible(true); motion.invoke(animatedView, false);
        Model.AnimalView compatible = new Model.AnimalView(1, 2, 3, "herbivore", 20, 0);
        check(compatible.activity() == Model.Activity.IDLE && compatible.lastMeal() == 0
                && compatible.metabolism() == 0 && compatible.reproductionSpent() == 0
                && compatible.satietyTicks() == 0 && compatible.energySpent() == 0,
                "Six-argument snapshot constructor is no longer backwards-compatible");
        Model.AnimalView tenArguments = new Model.AnimalView(2, 2, 3, "predator", 30, 1,
                Model.Activity.SEARCHING, 0, 3, 0);
        check(tenArguments.activity() == Model.Activity.SEARCHING && tenArguments.satietyTicks() == 0,
                "Ten-argument snapshot constructor is no longer backwards-compatible");
        check(presentation("days", new Class<?>[]{int.class}, 12).equals("1.0 days"),
                "Twelve simulation ticks are not presented as one game day");
        JPanel root = (JPanel) field(app, "root");
        JComponent view = (JComponent) field(app, "world");
        JComponent inspector = (JComponent) field(app, "inspector");
        EcosystemFacade facade = (EcosystemFacade) field(app, "facade");

        facade.reset(new Creation.WorldBuilder().size(5, 5).population(0, 1).build());
        view.getActionMap().get("next").actionPerformed(new ActionEvent(view, 0, "next"));
        Model.Snapshot fed = facade.step();
        Model.AnimalView wolf = (Model.AnimalView) field(inspector, "animal");
        check(wolf != null && (wolf.activity() == Model.Activity.RESTING || wolf.activity() == Model.Activity.ROAMING)
                        && wolf.energySpent() == 0 && wolf.satietyTicks() > 0 && wolf.lastMeal() == 0,
                "Fed wolf is hunting or losing energy instead of using its food reserve");
        check(wolf.equals(fed.animals().get(0)), "Inspector modified the published fed-wolf state");
        Model.Animal actualWolf = domainWorld(facade).animals.get(wolf.id());
        check(((Integer) presentation("maxEnergy", new Class<?>[]{String.class, Model.AnimalView.class}, "Meadow", wolf))
                        == actualWolf.maxEnergy, "Inspector maximum energy does not match the animal's biome profile");
        frame.validate();
        bounds(root);
        separated(root, inspector, named(root, "gameControls"), "fed-wolf inspector and playback toolbar");
        capture(root, Path.of("tmp", "animal-fed-wolf.png"));

        actualWolf.satietyTicks = 0;
        Model.Snapshot searched = facade.step();
        wolf = (Model.AnimalView) field(inspector, "animal");
        check(wolf != null && wolf.kind().equals("predator") && wolf.activity() == Model.Activity.SEARCHING,
                "Isolated wolf activity was not passed to the inspector");
        check(wolf.equals(searched.animals().get(0)) && wolf.lastMeal() == 0 && wolf.energySpent() > 0,
                "Inspector changed published searching/feeding metadata");
        check(activityLabel(wolf).equals("Searching for prey"), "Searching wolf has misleading activity text");
        domainWorld(facade).animals.get(wolf.id()).energy = 1;
        facade.step();
        frame.validate();
        check((Boolean) field(inspector, "lost") && inspector.isShowing(), "Starved selection was dismissed without explanation");
        check(field(inspector, "deathReason").equals("Starved"), "Starvation was reported as predation");
        bounds(root);
        separated(root, inspector, named(root, "gameControls"), "starvation inspector and playback toolbar");
        capture(root, Path.of("tmp", "animal-starvation.png"));

        facade.reset(new Creation.WorldBuilder().size(5, 5).population(1, 1).build());
        Model.World world = domainWorld(facade);
        Model.Animal deer = world.animals.values().stream().filter(Model.Herbivore.class::isInstance).findFirst().orElseThrow();
        Model.Animal predator = world.animals.values().stream().filter(Model.Predator.class::isInstance).findFirst().orElseThrow();
        deer.x = 2; deer.y = 2; predator.x = 3; predator.y = 2;
        predator.satietyTicks = 0;
        world.random.setSeed(0); // First capture draw is < .75; fed deer #26 does not roam on tick zero.
        for (Model.Plant plant : world.plants) plant.biomass = plant.capacity;
        view.getActionMap().get("next").actionPerformed(new ActionEvent(view, 0, "next"));
        check(((Model.AnimalView) field(inspector, "animal")).id() == deer.id, "Predation fixture selected the wrong animal");
        facade.step();
        check((Boolean) field(inspector, "lost") && field(inspector, "deathReason").equals("Deer caught by a wolf"),
                "A hunted deer was incorrectly reported as starving");

        facade.reset(new Creation.WorldBuilder().size(5, 5).population(1, 1).build());
        world = domainWorld(facade);
        deer = world.animals.values().stream().filter(Model.Herbivore.class::isInstance).findFirst().orElseThrow();
        predator = world.animals.values().stream().filter(Model.Predator.class::isInstance).findFirst().orElseThrow();
        deer.x = 2; deer.y = 2; predator.x = 3; predator.y = 2; predator.satietyTicks = 0;
        long escapeSeed = 0;
        while (new Random(escapeSeed).nextDouble() < .9) escapeSeed++;
        world.random.setSeed(escapeSeed);
        view.getActionMap().get("next").actionPerformed(new ActionEvent(view, 0, "next"));
        Model.Snapshot escaped = facade.step();
        Model.AnimalView survivor = (Model.AnimalView) field(inspector, "animal");
        check(survivor != null && survivor.id() == deer.id && survivor.activity() == Model.Activity.FLEEING
                        && !(Boolean) field(inspector, "lost"), "Escaped deer is not alive and fleeing in the inspector");
        check(escaped.messages().stream().anyMatch(message -> message.startsWith("Escaped:"))
                        && activityLabel(survivor).equals("Escaped from a wolf"), "Escape event has no readable explanation");
        check(((JTextArea) field(app, "log")).getText().contains("escaped from wolf"), "Escape journal event was not translated");
        check(((JLabel) field(app, "status")).getText().contains("Escaped: 1"), "HUD did not count the escaped deer");
        frame.validate();
        bounds(root);
        separated(root, inspector, named(root, "gameControls"), "escaped-deer inspector and playback toolbar");
        capture(root, Path.of("tmp", "animal-escape.png"));

        facade.reset(new Creation.WorldBuilder().size(5, 5).population(1, 0).build());
        world = domainWorld(facade);
        deer = world.animals.firstEntry().getValue(); deer.satietyTicks = 0;
        for (Model.Plant plant : world.plants) plant.biomass = plant.capacity;
        view.getActionMap().get("next").actionPerformed(new ActionEvent(view, 0, "next"));
        facade.step();
        Model.AnimalView grazer = (Model.AnimalView) field(inspector, "animal");
        check(grazer.activity() == Model.Activity.GRAZING && grazer.lastMeal() > 0
                        && grazer.energySpent() > 0 && grazer.satietyTicks() > 0,
                "Grazing inspector lost its actual cost, meal or replenished food reserve");

        facade.reset(new Creation.WorldBuilder().size(5, 5).population(1, 0).build());
        world = domainWorld(facade);
        deer = world.animals.firstEntry().getValue();
        deer.age = deer.minAge;
        deer.energy = deer.maxEnergy;
        for (Model.Plant plant : world.plants) plant.biomass = plant.capacity;
        view.getActionMap().get("next").actionPerformed(new ActionEvent(view, 0, "next"));
        Model.Snapshot birth = facade.step();
        Model.AnimalView parent = (Model.AnimalView) field(inspector, "animal");
        long parentId = parent.id();
        check(parent.reproductionSpent() > 0 && parent.equals(birth.animals().stream()
                        .filter(animal -> animal.id() == parentId).findFirst().orElseThrow()),
                "Parenthood energy cost was not preserved in inspector metadata");
        while (facade.snapshot().tick() < Model.TICKS_PER_DAY) facade.step();
        check(((JLabel) field(app, "worldState")).getText().contains("Day 2"), "Main HUD day did not advance at twelve ticks");
        check(facade.observerErrors().isEmpty(), "Activity observers failed: " + facade.observerErrors());
        motion.invoke(animatedView, true);
    }

    public static void main(String[] args) throws Exception {
        // Exercise the shipped entry point without relying on its translated window title.
        Set<Window> initialWindows = new HashSet<>(Arrays.asList(Window.getWindows()));
        App.main(new String[0]);
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = Arrays.stream(Window.getWindows())
                    .filter(window -> !initialWindows.contains(window) && window.isShowing())
                    .filter(JFrame.class::isInstance).map(JFrame.class::cast)
                    .findFirst().orElseThrow(() -> new AssertionError("Main window did not open"));
            check(frame.getWidth() > 600 && frame.getHeight() > 400, "Main window has no useful size");
            frame.dispose();
        });

        JFrame[] frame = new JFrame[1];
        Timer[] timer = new Timer[1];
        JButton[] start = new JButton[1];
        EcosystemFacade[] facade = new EcosystemFacade[1];
        CountDownLatch advanced = new CountDownLatch(1);
        CountDownLatch advancedAfterPause = new CountDownLatch(1);
        int[] pausedTick = new int[1];
        Path exportOutput = Files.createTempDirectory("ecosystem-game-ui-");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    App app = new App();
                    JPanel root = (JPanel) field(app, "root");
                    facade[0] = (EcosystemFacade) field(app, "facade");
                    timer[0] = (Timer) field(app, "timer");
                    start[0] = (JButton) field(app, "start");
                    frame[0] = new JFrame("Game UI smoke");
                    frame[0].setContentPane(root);
                    frame[0].pack();
                    frame[0].setVisible(true);
                    for (Dimension size : new Dimension[]{new Dimension(1280, 820), new Dimension(1080, 720), new Dimension(1044, 661)}) {
                        root.setPreferredSize(size);
                        Insets frameInsets = frame[0].getInsets();
                        frame[0].setSize(size.width + frameInsets.left + frameInsets.right,
                                size.height + frameInsets.top + frameInsets.bottom);
                        frame[0].validate();
                        bounds(root);
                        check(root.getWidth() == size.width && root.getHeight() == size.height,
                                "Client size was not tested: " + size + "; actual=" + root.getSize()
                                        + "; frame=" + frame[0].getSize() + "; min=" + frame[0].getMinimumSize());
                        if (size.width == 1280) capture(root, Path.of("tmp", "isometric-main-smoke.png"));
                    }
                    check(facade[0].snapshot().tick() == 0, "Initial world is not paused at tick zero");
                    check(!timer[0].isRunning(), "Simulation starts without user action");
                    button(root, "Step").doClick(0);
                    check(facade[0].snapshot().tick() == 1, "One step did not advance exactly once");

                    JComponent world = (JComponent) field(app, "world");
                    Method motion = world.getClass().getDeclaredMethod("setMotionEnabled", boolean.class);
                    motion.setAccessible(true);
                    motion.invoke(world, false);

                    Method zoom = world.getClass().getDeclaredMethod("getZoomFactor");
                    zoom.setAccessible(true);
                    double originalZoom = ((Number) zoom.invoke(world)).doubleValue();
                    button(root, "+").doClick(0);
                    check(((Number) zoom.invoke(world)).doubleValue() > originalZoom, "Zoom-in button did not move the camera");
                    button(root, "−").doClick(0);
                    check(Math.abs(((Number) zoom.invoke(world)).doubleValue() - originalZoom) < 0.0001,
                            "Zoom-out button did not reverse zoom-in");
                    button(root, "+").doClick(0);
                    button(root, "View").doClick(0);
                    check(Math.abs(((Number) zoom.invoke(world)).doubleValue() - 1) < 0.0001,
                            "Reset-view button did not restore camera scale");

                    Method screenPosition = world.getClass().getDeclaredMethod("screenPosition", Model.AnimalView.class, double.class);
                    screenPosition.setAccessible(true);
                    Point2D target = null;
                    for (Model.AnimalView animal : facade[0].snapshot().animals()) {
                        if (!animal.kind().equals("herbivore") || (animal.activity() != Model.Activity.RESTING
                                && animal.activity() != Model.Activity.ROAMING)) continue;
                        Point2D point = (Point2D) screenPosition.invoke(world, animal, 1.0);
                        if (point.getX() > 110 && point.getX() < world.getWidth() - 80
                                && point.getY() > 160 && point.getY() < world.getHeight() - 190) {
                            target = point; break;
                        }
                    }
                    check(target != null, "No animal is visible in the unobscured play area at minimum size");
                    long clickTime = System.currentTimeMillis();
                    world.dispatchEvent(new MouseEvent(world, MouseEvent.MOUSE_PRESSED, clickTime,
                            MouseEvent.BUTTON1_DOWN_MASK, (int) target.getX(), (int) target.getY(), 1, false, MouseEvent.BUTTON1));
                    world.dispatchEvent(new MouseEvent(world, MouseEvent.MOUSE_RELEASED, clickTime + 1,
                            0, (int) target.getX(), (int) target.getY(), 1, false, MouseEvent.BUTTON1));
                    JComponent inspector = (JComponent) field(app, "inspector");
                    Model.AnimalView selected = (Model.AnimalView) field(inspector, "animal");
                    check(selected != null && selected.age() == 1, "Click selection did not expose current animal age");
                    check((selected.activity() == Model.Activity.RESTING || selected.activity() == Model.Activity.ROAMING)
                                    && selected.lastMeal() == 0 && selected.energySpent() == 0 && selected.satietyTicks() > 0,
                            "Selected fed deer did not expose its authoritative reserve and zero energy cost");
                    String tooltip = world.getToolTipText(new MouseEvent(world, MouseEvent.MOUSE_MOVED, clickTime,
                            0, (int) target.getX(), (int) target.getY(), 0, false));
                    check(tooltip != null && tooltip.contains(activityLabel(selected)), "Fed-deer tooltip does not match its activity");
                    check(((Long) field(world, "selectedId")) == selected.id(), "World and inspector selections differ");
                    frame[0].validate();
                    check(inspector.isShowing() && inspector.getVisibleRect().equals(
                                    new Rectangle(0, 0, inspector.getWidth(), inspector.getHeight())),
                            "Selected animal inspector is clipped at minimum size");
                    separated(root, inspector, named(root, "gameControls"), "animal inspector and playback toolbar");
                    capture(root, Path.of("tmp", "isometric-minimum-selected.png"));
                    motion.invoke(world, true);

                    JPanel dock = (JPanel) field(app, "ecosystemDock");
                    check(!dock.isShowing(), "Ecosystem dock should be hidden until requested");
                    button(root, "Ecosystem").doClick(0);
                    frame[0].validate();
                    check(dock.isShowing(), "Ecosystem button did not reveal the dock");
                    bounds(root);
                    separated(root, dock, named(root, "gameControls"), "ecosystem dock and playback toolbar");
                    JComboBox<?> strategy = (JComboBox<?>) field(app, "strategy");
                    check(strategy.isShowing(), "Strategy is not reachable inside the ecosystem dock");
                    strategy.setSelectedItem("Safest food");
                    check(facade[0].snapshot().strategy().equals("Safest food"), "Strategy lost its meaning");
                    capture(root, Path.of("tmp", "isometric-dock.png"));
                    button(dock, "Close").doClick(0);
                    check(!dock.isShowing(), "Dock close button did not dismiss the ecosystem panel");
                    int count = facade[0].snapshot().stats().herbivores();
                    button(root, "+5 deer").doClick(0);
                    check(facade[0].snapshot().stats().herbivores() == count + 5, "Add deer failed");

                    JTextArea log = (JTextArea) field(app, "log");
                    JCheckBox liveLog = (JCheckBox) field(app, "liveLog");
                    check(liveLog.isSelected(), "Live journal should start enabled");
                    liveLog.doClick(0);
                    int length = log.getText().length();
                    button(root, "Step").doClick(0);
                    check(log.getText().length() == length, "Journal unsubscribe did not work");
                    liveLog.doClick(0);
                    button(root, "Step").doClick(0);
                    check(log.getText().length() > length, "Journal resubscribe did not work");

                    settings(button(root, "New world"), "Create world", () -> {
                        try {
                            JSpinner seed = (JSpinner) field(app, "seed");
                            ((JSpinner.DefaultEditor) seed.getEditor()).getTextField().setText("129");
                            ((JComboBox<?>) field(app, "biome")).setSelectedItem("Desert");
                        } catch (Exception error) { throw new RuntimeException(error); }
                    });
                    check(facade[0].snapshot().tick() == 0, "Create world did not reset time");
                    check(facade[0].snapshot().stats().herbivores() == 30, "Create world lost population setting");
                    check(facade[0].snapshot().biome().equals("Desert"), "Selected biome did not reach the model");
                    check(facade[0].snapshot().messages().get(0).contains("seed=129"), "Typed seed was not committed");
                    settings(button(root, "Settings"), null, null);
                    checkModalExport(button(root, "Settings"), exportOutput.resolve("modal-cancelled.csv"));

                    JComboBox<?> weather = (JComboBox<?>) field(app, "weatherSource");
                    weather.setSelectedItem("Native");
                    check(facade[0].snapshot().messages().get(0).contains("NativeWeatherSource"), "Native weather failed");
                    weather.setSelectedItem("Legacy adapter");
                    check(facade[0].snapshot().messages().get(0).contains("LegacyWeatherAdapter"), "Legacy weather failed");
                    check(facade[0].observerErrors().isEmpty(),
                            "An observer failed: " + facade[0].observerErrors());

                    checkExports(app, facade[0], exportOutput);
                    checkHelp(button(root, "How to play"));
                    checkEncounterUi(app, frame[0]);
                    checkActivityInspector(app, frame[0]);
                    facade[0].reset(new Creation.WorldBuilder().biome("Desert").seed(129).build());

                    facade[0].subscribe(snapshot -> {
                        if (snapshot.tick() > 0) advanced.countDown();
                    });
                    start[0].doClick(0);
                    check(timer[0].isRunning() && start[0].getText().equals("Pause"), "Play control failed");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            check(advanced.await(5, TimeUnit.SECONDS), "Running timer did not advance the world");
            SwingUtilities.invokeAndWait(() -> {
                start[0].doClick(0);
                check(!timer[0].isRunning() && start[0].getText().equals("Play"), "Pause control failed");
                pausedTick[0] = facade[0].snapshot().tick();
                facade[0].subscribe(snapshot -> {
                    if (snapshot.tick() > pausedTick[0]) advancedAfterPause.countDown();
                });
                check(facade[0].observerErrors().isEmpty(), "Observer failed during animation");
            });
            check(!advancedAfterPause.await(700, TimeUnit.MILLISECONDS), "World advanced after pause");
            System.out.println("SUCCESS: Isometric game UI smoke passed: visible launch; control bounds at 1280x820, 1080x720 and minimum 1044x661; fed-deer click and tooltip; fed wolf uses reserves with zero energy cost; hungry wolf searches with actual cost; 12-tick days and profile maxima; successful escape survives and is translated/counted; starvation versus predation death reasons; grazing and parenthood costs; compatible 6/10-argument snapshots; camera zoom and reset; ecosystem dock and strategy; step; deer; journal subscriptions; modal settings tabs and contextual create action; typed seed; biome; weather; all four report formats, cancel and overwrite; nested modal export; play and pause. Captures include tmp/animal-fed-wolf.png and tmp/animal-escape.png");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (timer[0] != null) timer[0].stop();
                if (frame[0] != null) frame[0].dispose();
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog && window.isShowing()) window.dispose();
                }
            });
            try (var files = Files.walk(exportOutput)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
