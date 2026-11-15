package ecosystem;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic native-render checks: the supplied clock replaces sleeps and screenshot timing guesses. */
public final class HuntAnimationSmoke {
    private static final Path OUTPUT = Path.of("tmp", "hunt-qa");
    private static final List<BufferedImage> PANELS = new ArrayList<>();

    private record Fixture(AtomicLong clock, WorldPanel view, EcosystemFacade facade,
                           Model.Predator wolf, Model.Herbivore deer,
                           Model.Snapshot before, Model.Snapshot after, AtomicInteger completed) { }

    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args);
    }
    private static Object call(Object owner, String name) throws Exception { return call(owner, name, new Class<?>[0]); }
    private static List<?> shots(WorldPanel view) throws Exception { return (List<?>) field(view, "activeShots"); }
    private static List<?> marks(WorldPanel view) throws Exception { return (List<?>) field(view, "outcomeMarks"); }
    private static List<?> ghosts(WorldPanel view) throws Exception { return (List<?>) call(view, "captureGhosts"); }
    private static Object frame(WorldPanel view, Model.AnimalView animal) throws Exception {
        return call(view, "visualFrame", new Class<?>[]{Model.AnimalView.class, double.class}, animal, 1.0);
    }
    private static Point2D position(Object frame) throws Exception { return (Point2D) call(frame, "position"); }
    private static double number(Object frame, String name) throws Exception { return ((Number) call(frame, name)).doubleValue(); }
    private static long duration(Fixture f) throws Exception {
        check(!shots(f.view).isEmpty(), "The fixture did not start a visual shot");
        return ((Number) field(shots(f.view).get(0), "duration")).longValue();
    }
    private static long seed(boolean capture) {
        Random random = new Random(0x7a11babeL);
        for (int i = 0; i < 200; i++) {
            long candidate = random.nextLong();
            if ((new Random(candidate).nextDouble() < SimulationEngine.CAPTURE_CHANCE) == capture) return candidate;
        }
        throw new AssertionError("No contact seed found");
    }
    private static Fixture fixture(boolean capture, int width, int height, int stepDelay) {
        return fixture(capture, width, height, stepDelay, false);
    }
    private static Fixture fixture(boolean capture, int width, int height, int stepDelay, boolean pursuit) {
        EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
        facade.reset(new Creation.WorldBuilder().seed(seed(capture)).population(0, 0).build());
        Model.World world = facade.worldForTests();
        Model.Predator wolf = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, pursuit ? 7 : 8, 10);
        wolf.satietyTicks = 0; wolf.cooldown = 1000; wolf.energy = wolf.maxEnergy;
        Model.Herbivore deer = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, pursuit ? 10 : 9, 10);
        deer.cooldown = 1000;
        facade.setStrategy("Nearest food");
        Model.Snapshot before = facade.snapshot();
        AtomicLong clock = new AtomicLong(); AtomicInteger completed = new AtomicInteger();
        WorldPanel view = new WorldPanel(clock::get);
        view.setSize(width, height); view.setStepDuration(stepDelay); view.setZoomFactor(1.8);
        view.setEncounterFinishedListener(completed::incrementAndGet);
        view.onSnapshot(before);
        Model.Snapshot after = facade.step();
        check(after.hunts().size() == 1, "Fixture needs exactly one visible encounter");
        Model.HuntOutcome expected = pursuit ? Model.HuntOutcome.PURSUIT
            : capture ? Model.HuntOutcome.CAPTURE : Model.HuntOutcome.ESCAPE;
        check(after.hunts().get(0).outcome() == expected, "Fixture outcome changed: " + after.hunts());
        view.onSnapshot(after);
        return new Fixture(clock, view, facade, wolf, deer, before, after, completed);
    }

    private static BufferedImage render(WorldPanel view) {
        BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try { view.printAll(g); } finally { g.dispose(); }
        return image;
    }
    private static void capture(Fixture f, String filename, String title, double fraction) throws Exception {
        f.clock.set((long) (duration(f) * fraction));
        BufferedImage image = render(f.view);
        ImageIO.write(image, "png", OUTPUT.resolve(filename).toFile());
        int cropWidth = Math.min(460, image.getWidth()), cropHeight = 310;
        int cropX = (image.getWidth() - cropWidth) / 2;
        int centerY = 100 + Math.max(140, image.getHeight() - 250) / 2;
        int cropY = Math.max(0, centerY - 170);
        BufferedImage panel = new BufferedImage(460, 340, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = panel.createGraphics();
        g.setColor(new Color(0x172b2e)); g.fillRect(0, 0, 460, 340);
        g.drawImage(image, 0, 30, cropWidth, 30 + cropHeight, cropX, cropY, cropX + cropWidth, cropY + cropHeight, null);
        g.setColor(new Color(0xeee7d5)); g.setFont(new Font("Segoe UI", Font.BOLD, 14)); g.drawString(title, 12, 21); g.dispose();
        PANELS.add(panel);
    }
    private static void captureAfter(Fixture f, String filename, String title) throws Exception {
        BufferedImage image = render(f.view); ImageIO.write(image, "png", OUTPUT.resolve(filename).toFile());
        int centerY = 100 + Math.max(140, image.getHeight() - 250) / 2;
        BufferedImage panel = new BufferedImage(460, 340, BufferedImage.TYPE_INT_RGB); Graphics2D g = panel.createGraphics();
        g.setColor(new Color(0x172b2e)); g.fillRect(0, 0, 460, 340);
        int x = (image.getWidth() - 460) / 2, y = Math.max(0, centerY - 170);
        g.drawImage(image, 0, 30, 460, 340, x, y, x + 460, y + 310, null);
        g.setColor(new Color(0xeee7d5)); g.setFont(new Font("Segoe UI", Font.BOLD, 14)); g.drawString(title, 12, 21); g.dispose();
        PANELS.add(panel);
    }
    private static void captureSheet() throws Exception {
        BufferedImage sheet = new BufferedImage(460 * 4, 340 * ((PANELS.size() + 3) / 4), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        for (int i = 0; i < PANELS.size(); i++) g.drawImage(PANELS.get(i), i % 4 * 460, i / 4 * 340, null);
        g.dispose(); ImageIO.write(sheet, "png", OUTPUT.resolve("hunt-contact-sheet.png").toFile());
    }

    private static void testCapture() throws Exception {
        Fixture f = fixture(true, 1280, 820, 700); long duration = duration(f);
        check(f.view.isEncounterAnimating(), "Clock zero must start a real critical encounter");
        check(f.after.animals().stream().noneMatch(a -> a.id() == f.deer.id), "The logical capture must already be resolved");
        check(ghosts(f.view).size() == 1, "Removed prey must remain visible while the wolf approaches");
        Object early = ghosts(f.view).get(0);
        check(number(early, "alpha") == 1, "The caught deer cannot disappear at the start of approach");
        Model.AnimalView wolf = f.after.animals().stream().filter(a -> a.id() == f.wolf.id).findFirst().orElseThrow();
        Point2D start = position(frame(f.view, wolf));
        capture(f, "capture-start.png", "Capture - approach begins", 0);
        capture(f, "capture-approach.png", "Capture - wolf closes in", .35);
        check(position(frame(f.view, wolf)).distance(start) > .2, "The wolf must visibly traverse the approach");
        capture(f, "capture-lunge.png", "Capture - lunge", .62);
        capture(f, "capture-result.png", "Capture - outcome", .83);
        Object falling = ghosts(f.view).get(0);
        check(number(falling, "alpha") > 0 && number(falling, "alpha") < 1 && Math.abs(number(falling, "rotation")) > .05,
            "The prey should fall/fade after contact, rather than snap away");
        check(marks(f.view).size() == 1, "A successful catch must have a readable outcome marker");
        f.clock.set(duration + 1); render(f.view);
        check(!f.view.isEncounterAnimating() && ghosts(f.view).isEmpty() && f.completed.get() == 1,
            "Completion must remove the captured ghost and notify its owner exactly once");
        f.clock.addAndGet(500_000_000L); render(f.view);
        check(marks(f.view).size() == 1, "The outcome must remain readable after the movement completes");
        f.clock.addAndGet(2_000_000_000L); f.view.isEncounterAnimating();
        check(marks(f.view).isEmpty() && f.completed.get() == 1, "An outcome must expire without duplicate completion callbacks");
    }

    private static void testEscape() throws Exception {
        Fixture f = fixture(false, 1044, 661, 175); long duration = duration(f);
        check(duration >= 600_000_000L, "A 4x game speed must not make the decisive escape unreadable");
        Model.AnimalView deer = f.after.animals().stream().filter(a -> a.id() == f.deer.id).findFirst().orElseThrow();
        Model.HuntEvent event = f.after.hunts().get(0);
        capture(f, "escape-start.png", "Escape - deer waits for contact", .20);
        Point2D contact = position(frame(f.view, deer));
        check(contact.distance(event.preyBefore().x(), event.preyBefore().y()) < .001,
            "The escape must not teleport to its final cell before the lunge");
        capture(f, "escape-burst.png", "Escape - rapid dash", .60);
        Point2D midway = position(frame(f.view, deer));
        check(midway.distance(contact) > .05 && midway.distance(event.preyToX(), event.preyToY()) > .05,
            "The deer must pass through intermediate positions during its burst");
        capture(f, "escape-result.png", "Escape - deer gets away", .95);
        f.clock.set(duration + 1); render(f.view);
        check(!f.view.isEncounterAnimating() && f.completed.get() == 1 && ghosts(f.view).isEmpty(),
            "An escape finishes with a surviving logical deer and no duplicate ghosts");
        check(position(frame(f.view, deer)).distance(deer.x(), deer.y()) < .001 && marks(f.view).size() == 1,
            "The visible survivor must settle on its real destination with a readable escape result");
        captureAfter(f, "escape-settled.png", "Escape - surviving deer after dash");
    }

    private static void testRefreshResetAndReducedMotion() throws Exception {
        Fixture f = fixture(true, 1044, 661, 700); long duration = duration(f);
        f.clock.set(duration / 3); Object firstShot = shots(f.view).get(0);
        f.facade.setStrategy("Safest food"); f.view.onSnapshot(f.facade.snapshot());
        check(shots(f.view).size() == 1 && shots(f.view).get(0) == firstShot && f.completed.get() == 0,
            "Same-tick metadata must not replay or restart the encounter");
        f.view.setRunning(false); f.clock.set(duration + 1); f.view.isEncounterAnimating();
        check(f.completed.get() == 1, "Pausing simulation must still let the committed encounter finish");
        f.view.onSnapshot(f.facade.snapshot());
        check(shots(f.view).isEmpty() && f.completed.get() == 1, "Finished outcomes must not replay after a same-tick refresh");

        Fixture reduced = fixture(true, 1044, 661, 700);
        reduced.view.setMotionEnabled(false);
        check(!reduced.view.isEncounterAnimating() && ghosts(reduced.view).isEmpty() && marks(reduced.view).size() == 1,
            "Reduced motion must show the final result immediately without keeping removed animals visible");
        check(reduced.completed.get() == 1, "Reduced motion must release the owner's pacing gate");
        reduced.clock.addAndGet(2_000_000_000L); reduced.view.isEncounterAnimating();
        check(marks(reduced.view).isEmpty(), "Reduced-motion outcome text must eventually clear");

        Fixture reset = fixture(false, 1044, 661, 700);
        reset.facade.reset(new Creation.WorldBuilder().build()); reset.view.onSnapshot(reset.facade.snapshot());
        check(!reset.view.isEncounterAnimating() && shots(reset.view).isEmpty() && ghosts(reset.view).isEmpty()
                && marks(reset.view).isEmpty(), "Reset must discard all old encounters, ghosts and labels");
        reset.clock.addAndGet(5_000_000_000L); render(reset.view);
        check(marks(reset.view).isEmpty() && reset.completed.get() <= 1, "Reset must not resurrect an old result");
    }

    private static void testPursuitPickingAndReadOnlyPainting() throws Exception {
        Fixture pursuit = fixture(true, 1044, 661, 700, true); long duration = duration(pursuit);
        check(!pursuit.view.isEncounterAnimating(), "Ordinary pursuit must not hold the critical-outcome pacing gate");
        Model.AnimalView wolf = pursuit.after.animals().stream().filter(a -> a.id() == pursuit.wolf.id).findFirst().orElseThrow();
        Point2D first = position(frame(pursuit.view, wolf)); pursuit.clock.set(duration / 2); render(pursuit.view);
        check(position(frame(pursuit.view, wolf)).distance(first) > .2, "An approaching wolf must visibly run during pursuit");
        pursuit.view.setZoomFactor(2.2);
        Point2D screen = (Point2D) call(pursuit.view, "screenPosition", new Class<?>[]{Model.AnimalView.class, double.class}, wolf, 1.0);
        AtomicLong selected = new AtomicLong(-1); pursuit.view.setSelectionListener(a -> selected.set(a == null ? -1 : a.id()));
        long now = System.currentTimeMillis();
        pursuit.view.dispatchEvent(new MouseEvent(pursuit.view, MouseEvent.MOUSE_PRESSED, now, MouseEvent.BUTTON1_DOWN_MASK,
            (int) screen.getX(), (int) screen.getY() - 8, 1, false, MouseEvent.BUTTON1));
        pursuit.view.dispatchEvent(new MouseEvent(pursuit.view, MouseEvent.MOUSE_RELEASED, now + 1, 0,
            (int) screen.getX(), (int) screen.getY() - 8, 1, false, MouseEvent.BUTTON1));
        check(selected.get() == wolf.id(), "Camera-transformed picking must use the displayed in-flight animal position");

        Fixture painted = fixture(false, 1280, 820, 700), control = fixture(false, 1280, 820, 700);
        Model.Snapshot beforePainting = painted.facade.snapshot(); long finish = duration(painted) + 1_500_000_000L;
        for (int i = 0; i < 24; i++) { painted.clock.set(finish * i / 23); render(painted.view); }
        check(painted.facade.snapshot().equals(beforePainting), "Painting animation frames must not change logical state");
        check(painted.facade.step().equals(control.facade.step()), "Rendering must not consume gameplay randomness or affect the next turn");
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        SwingUtilities.invokeAndWait(() -> {
            try {
                testCapture(); testEscape(); testRefreshResetAndReducedMotion(); testPursuitPickingAndReadOnlyPainting();
                captureSheet();
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        System.out.println("SUCCESS: Hunt animation smoke passed: staged pursuit/lunge/capture/escape, readable results, pause/reset/same-tick/reduced-motion behavior, picking and model/RNG isolation.");
    }
}
