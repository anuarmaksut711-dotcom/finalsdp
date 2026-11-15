package ecosystem;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Isometric world view: the camera and animation never mutate the simulation. */
final class WorldPanel extends JPanel implements Events.EcosystemObserver {
    private static final double TILE_X = 25, TILE_Y = 12.5;
    private Model.Snapshot snapshot;
    private final Map<Long, Point2D.Double> origins = new HashMap<>();
    private final Map<Long, Model.AnimalView> animalsById = new HashMap<>();
    private final Map<Long, Boolean> facing = new HashMap<>();
    private Consumer<Model.AnimalView> selectionListener = animal -> { };
    private Runnable encounterFinishedListener = () -> { };
    private final LongSupplier nanos;
    private final boolean suppliedClock;
    private final List<Shot> activeShots = new ArrayList<>();
    private final List<OutcomeMark> outcomeMarks = new ArrayList<>();
    private boolean encounterWasActive, notifyingCompletion;
    private Long selectedId;
    private long transitionStarted = -1;
    private int stepDuration = 650;
    private boolean motionEnabled = true, running, gridVisible;
    private double zoom = 1, panX, panY;
    private Point dragStart;
    private double dragPanX, dragPanY;
    private boolean dragged;
    private BufferedImage terrain;
    private Rectangle2D.Double cacheBounds;
    private boolean terrainDirty = true;
    private final javax.swing.Timer animation = new javax.swing.Timer(33, event -> animate());

    WorldPanel() { this(System::nanoTime, false); }
    WorldPanel(LongSupplier nanos) { this(nanos, true); }
    private WorldPanel(LongSupplier nanos, boolean suppliedClock) {
        this.nanos = Objects.requireNonNull(nanos);
        this.suppliedClock = suppliedClock;
        setOpaque(true);
        setBackground(new Color(0x142c31));
        setPreferredSize(new Dimension(1280, 820));
        setMinimumSize(new Dimension(600, 420));
        setFocusable(true);
        setToolTipText("");
        getAccessibleContext().setAccessibleName("Isometric ecosystem: deer and wolves");
        getAccessibleContext().setAccessibleDescription("Use the mouse wheel to zoom and drag to move the camera. Click an animal to inspect it. Arrow keys select animals, Escape clears selection, and Home resets the camera.");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                dragStart = event.getPoint(); dragPanX = panX; dragPanY = panY; dragged = false;
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (dragStart == null) return;
                if (dragStart.distance(event.getPoint()) > 4) dragged = true;
                if (dragged) {
                    panX = dragPanX + event.getX() - dragStart.x;
                    panY = dragPanY + event.getY() - dragStart.y;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)); repaint();
                }
            }
            @Override public void mouseReleased(MouseEvent event) {
                if (!dragged && SwingUtilities.isLeftMouseButton(event)) {
                    if (event.getClickCount() > 1) resetCamera();
                    else {
                        Model.AnimalView hit = animalAt(event.getX(), event.getY());
                        selectedId = hit == null ? null : hit.id();
                        selectionListener.accept(hit); repaint();
                    }
                }
                dragStart = null; setCursor(Cursor.getDefaultCursor());
            }
            @Override public void mouseMoved(MouseEvent event) {
                setCursor(Cursor.getPredefinedCursor(animalAt(event.getX(), event.getY()) == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
            }
            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                zoomAt(zoom * Math.pow(1.15, -event.getPreciseWheelRotation()), event.getX(), event.getY());
            }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse);
        bind("ESCAPE", "clear", this::clearSelection);
        bind("HOME", "home", this::resetCamera);
        bind("PLUS", "zoom-in", this::zoomIn);
        bind("MINUS", "zoom-out", this::zoomOut);
        bind("RIGHT", "next", () -> selectNext(1));
        bind("DOWN", "next-down", () -> selectNext(1));
        bind("LEFT", "previous", () -> selectNext(-1));
        bind("UP", "previous-up", () -> selectNext(-1));
    }
    private void bind(String key, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }
    void setSelectionListener(Consumer<Model.AnimalView> listener) { selectionListener = Objects.requireNonNull(listener); }
    void setEncounterFinishedListener(Runnable listener) { encounterFinishedListener = Objects.requireNonNull(listener); }
    boolean isEncounterAnimating() { updatePlayback(); return encounterWasActive; }
    void setRunning(boolean value) { running = value; repaint(); }
    void setStepDuration(int millis) { stepDuration = Math.max(50, millis); }
    void setMotionEnabled(boolean value) {
        motionEnabled = value;
        if (!value) { transitionStarted = -1; origins.clear(); finishShots(true); }
        repaint();
    }
    void setGridVisible(boolean value) { gridVisible = value; repaint(); }
    void clearSelection() { selectedId = null; selectionListener.accept(null); repaint(); }
    void resetCamera() { zoom = 1; panX = 0; panY = 0; repaint(); }
    void zoomIn() { zoomAt(zoom * 1.2, getWidth() / 2.0, safeCenterY()); }
    void zoomOut() { zoomAt(zoom / 1.2, getWidth() / 2.0, safeCenterY()); }
    void setZoomFactor(double value) { zoomAt(value, getWidth() / 2.0, safeCenterY()); }
    double getZoomFactor() { return zoom; }
    @Override public void addNotify() { super.addNotify(); if (!GraphicsEnvironment.isHeadless()) animation.start(); }
    @Override public void removeNotify() { animation.stop(); super.removeNotify(); }
    private void animate() {
        boolean hadEffects = !activeShots.isEmpty() || !outcomeMarks.isEmpty();
        updatePlayback();
        if (motionEnabled && transitionStarted >= 0) {
            if (fraction() >= 1) transitionStarted = -1;
            repaint();
        } else if (motionEnabled && running || hadEffects) repaint();
    }
    @Override public void onSnapshot(Model.Snapshot next) {
        updatePlayback();
        boolean reset = snapshot == null || next.tick() < snapshot.tick() || !next.biome().equals(snapshot.biome())
            || next.width() != snapshot.width() || next.height() != snapshot.height()
            || next.messages().stream().anyMatch(message -> message.startsWith("World reset:"));
        if (!reset && next.tick() > snapshot.tick()) {
            Map<Long, Point2D.Double> starts = new HashMap<>();
            Map<Long, Model.AnimalView> oldAnimals = new HashMap<>();
            double fraction = fraction();
            for (Model.AnimalView old : snapshot.animals()) {
                starts.put(old.id(), visualFrame(old, fraction).position()); oldAnimals.put(old.id(), old);
            }
            finishShots(true);
            origins.clear();
            for (Model.AnimalView animal : next.animals()) {
                Point2D.Double previous = starts.get(animal.id());
                if (previous != null) {
                    origins.put(animal.id(), previous);
                    double oldIsoX = previous.x - previous.y, nextIsoX = animal.x() - animal.y();
                    if (Math.abs(oldIsoX - nextIsoX) > .01) facing.put(animal.id(), nextIsoX < oldIsoX);
                }
            }
            transitionStarted = playbackEnabled() ? nanos.getAsLong() : -1;
            if (!suppliedClock && GraphicsEnvironment.isHeadless()) outcomeMarks.clear();
            for (Model.HuntEvent event : next.hunts()) {
                Shot shot = new Shot(event, starts, oldAnimals, nanos.getAsLong());
                if (playbackEnabled()) activeShots.add(shot); else publishOutcome(shot, nanos.getAsLong());
            }
            encounterWasActive = activeShots.stream().anyMatch(Shot::critical);
        } else if (reset) {
            origins.clear(); facing.clear(); selectedId = null; transitionStarted = -1;
            activeShots.clear(); outcomeMarks.clear(); completeEncounter();
        }
        snapshot = next; animalsById.clear();
        for (Model.AnimalView animal : next.animals()) animalsById.put(animal.id(), animal);
        facing.keySet().retainAll(animalsById.keySet()); terrainDirty = true;
        if (selectedId != null && !animalsById.containsKey(selectedId)) selectedId = null;
        if (selectedId != null && !encounterWasActive) selectionListener.accept(animalsById.get(selectedId)); repaint();
    }
    private void selectNext(int direction) {
        if (snapshot == null || snapshot.animals().isEmpty()) return;
        List<Model.AnimalView> animals = snapshot.animals(); int index = -1;
        for (int i = 0; i < animals.size(); i++) if (Objects.equals(selectedId, animals.get(i).id())) index = i;
        selectedId = animals.get(index < 0 ? 0 : Math.floorMod(index + direction, animals.size())).id();
        selectionListener.accept(animalsById.get(selectedId)); repaint();
    }
    private double fraction() {
        if (!playbackEnabled() || transitionStarted < 0) return 1;
        double value = Math.min(1, (nanos.getAsLong() - transitionStarted) / (Math.min(520, stepDuration * .85) * 1_000_000.0));
        return 1 - Math.pow(1 - Math.max(0, value), 3);
    }

    private boolean playbackEnabled() { return motionEnabled && (suppliedClock || !GraphicsEnvironment.isHeadless()); }
    private static double unit(double value) { return Math.max(0, Math.min(1, value)); }
    private static double smooth(double value) { double t = unit(value); return t * t * (3 - 2 * t); }
    private static Point2D.Double point(double x, double y) { return new Point2D.Double(x, y); }
    private static Point2D.Double between(Point2D.Double a, Point2D.Double b, double t) {
        return point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
    }
    private void completeEncounter() {
        if (!encounterWasActive || notifyingCompletion) return;
        encounterWasActive = false; notifyingCompletion = true;
        try { encounterFinishedListener.run(); } finally { notifyingCompletion = false; }
    }
    private void finishShots(boolean showOutcomes) {
        if (showOutcomes) for (Shot shot : activeShots) publishOutcome(shot, nanos.getAsLong());
        activeShots.clear(); completeEncounter();
    }
    private void updatePlayback() {
        long now = nanos.getAsLong();
        for (Shot shot : activeShots) {
            if (shot.critical() && shot.progress(now) >= .64) {
                publishOutcome(shot, shot.started + (long) (shot.duration * .64));
            }
        }
        activeShots.removeIf(shot -> shot.progress(now) >= 1);
        outcomeMarks.removeIf(mark -> now >= mark.expires);
        if (activeShots.stream().noneMatch(Shot::critical)) completeEncounter();
    }
    private void publishOutcome(Shot shot, long born) {
        if (!shot.critical() || shot.published) return;
        shot.published = true;
        boolean caught = shot.event.outcome() == Model.HuntOutcome.CAPTURE;
        Point2D.Double where = caught ? shot.contact : shot.preyEnd;
        outcomeMarks.add(new OutcomeMark(shot.event, where, born, born + 1_350_000_000L));
        if (outcomeMarks.size() > 18) outcomeMarks.remove(0);
    }

    /** One event retains the removed prey until the visible lunge has actually reached it. */
    private final class Shot {
        final Model.HuntEvent event;
        final Model.AnimalView wolfBefore;
        final Point2D.Double wolfStart, wolfEnd, preyStart, contact, preyEnd;
        final long started, duration;
        boolean published;
        Shot(Model.HuntEvent event, Map<Long, Point2D.Double> starts,
             Map<Long, Model.AnimalView> oldAnimals, long now) {
            this.event = event; started = now;
            wolfBefore = oldAnimals.get(event.wolfId());
            wolfStart = starts.getOrDefault(event.wolfId(), point(event.wolfFromX(), event.wolfFromY()));
            wolfEnd = point(event.wolfToX(), event.wolfToY());
            contact = point(event.preyBefore().x(), event.preyBefore().y());
            preyStart = starts.getOrDefault(event.deerId(), contact);
            preyEnd = point(event.preyToX(), event.preyToY());
            double millis = critical() ? Math.max(650, Math.min(1100, stepDuration * 1.15))
                : Math.max(100, Math.min(520, stepDuration * .85));
            duration = (long) (millis * 1_000_000);
        }
        boolean critical() { return event.outcome() != Model.HuntOutcome.PURSUIT; }
        double progress(long now) { return unit((now - started) / (double) duration); }
    }
    private record OutcomeMark(Model.HuntEvent event, Point2D.Double position, long born, long expires) { }
    private record RenderAnimal(Model.AnimalView animal, Point2D.Double position, double alpha,
                                double rotation, double lift, double stride, double scaleY,
                                boolean flip, boolean dust) { }

    private Shot shotFor(long id) {
        Shot found = null;
        for (Shot shot : activeShots) {
            if (shot.event.wolfId() != id && shot.event.deerId() != id) continue;
            if (found == null || shot.critical()) found = shot;
        }
        return found;
    }
    private RenderAnimal visualFrame(Model.AnimalView animal, double ordinaryProgress) {
        Shot shot = shotFor(animal.id());
        Point2D.Double normal = position(animal, ordinaryProgress);
        boolean flip = facing.getOrDefault(animal.id(), (animal.id() & 1) == 0);
        Point2D.Double from = origins.get(animal.id());
        boolean walking = motionEnabled && from != null && ordinaryProgress < 1 && from.distanceSq(animal.x(), animal.y()) > .0001;
        if (shot == null) return new RenderAnimal(animal, normal, 1, 0,
            walking ? Math.abs(Math.sin(ordinaryProgress * Math.PI * 3)) * 1.8 : 0,
            walking ? ordinaryProgress * Math.PI * 4 : 0, 1, flip, false);
        double p = shot.progress(nanos.getAsLong());
        Point2D.Double location;
        double rotation = 0, lift = 0, stride = 0, alpha = 1, scaleY = 1;
        boolean dust = false;
        Point2D.Double directionFrom, directionTo;
        if (animal.id() == shot.event.wolfId()) {
            double approach = shot.critical() ? smooth(p / .52) : smooth(p);
            location = between(shot.wolfStart, shot.wolfEnd, approach);
            directionFrom = shot.wolfStart; directionTo = shot.wolfEnd;
            if (p < (shot.critical() ? .58 : 1)) { stride = p * Math.PI * 10; lift = Math.abs(Math.sin(stride)) * 2.5; dust = true; }
            if (shot.critical() && p > .43 && p < .79) {
                double lunge = Math.sin(unit((p - .43) / .36) * Math.PI);
                double dx = shot.contact.x - shot.wolfStart.x, dy = shot.contact.y - shot.wolfStart.y;
                double length = Math.max(.25, Math.abs(dx) + Math.abs(dy));
                location.x += dx / length * lunge * .19; location.y += dy / length * lunge * .19;
                lift += lunge * 8; rotation = lunge * -.13;
            }
            if (!animalsById.containsKey(animal.id())) alpha = 1 - smooth((p - .78) / .22);
        } else {
            directionFrom = shot.preyStart; directionTo = shot.preyEnd;
            if (shot.event.outcome() == Model.HuntOutcome.ESCAPE) {
                if (p < .40) location = between(shot.preyStart, shot.contact, smooth(p / .40));
                else {
                    double burst = smooth((p - .40) / .47);
                    location = between(shot.contact, shot.preyEnd, burst);
                    stride = (p - .40) * Math.PI * 18; lift = Math.abs(Math.sin(stride)) * 4; dust = p < .9;
                    directionFrom = shot.contact; directionTo = shot.preyEnd;
                }
            } else if (shot.event.outcome() == Model.HuntOutcome.CAPTURE) {
                location = between(shot.preyStart, shot.contact, smooth(p / .48));
                double fall = smooth((p - .64) / .36);
                rotation = fall * -.90; scaleY = 1 - fall * .45; alpha = 1 - fall;
                if (p > .49 && p < .67) { lift = Math.sin((p - .49) / .18 * Math.PI) * 4; stride = p * Math.PI * 12; }
            } else {
                if (p < .65) location = between(shot.preyStart, shot.contact, smooth(p / .65));
                else location = between(shot.contact, point(animal.x(), animal.y()), smooth((p - .65) / .35));
                if (shot.preyStart.distanceSq(animal.x(), animal.y()) > .001) { stride = p * Math.PI * 6; lift = Math.abs(Math.sin(stride)) * 1.8; }
            }
        }
        double isoDelta = (directionTo.x - directionTo.y) - (directionFrom.x - directionFrom.y);
        if (Math.abs(isoDelta) > .01) flip = isoDelta < 0;
        if (flip) rotation = -rotation;
        return new RenderAnimal(animal, location, alpha, rotation, lift, stride, scaleY, flip, dust);
    }
    private List<RenderAnimal> captureGhosts() {
        List<RenderAnimal> ghosts = new ArrayList<>();
        Set<Long> added = new HashSet<>();
        for (Shot shot : activeShots) {
            if (shot.event.outcome() == Model.HuntOutcome.CAPTURE && !animalsById.containsKey(shot.event.deerId()) && added.add(shot.event.deerId()))
                ghosts.add(visualFrame(shot.event.preyBefore(), fraction()));
            if (shot.wolfBefore != null && !animalsById.containsKey(shot.event.wolfId()) && added.add(shot.event.wolfId()))
                ghosts.add(visualFrame(shot.wolfBefore, fraction()));
        }
        return ghosts;
    }
    private List<RenderAnimal> renderAnimals(double progress) {
        List<RenderAnimal> frames = new ArrayList<>();
        for (Model.AnimalView animal : snapshot.animals()) frames.add(visualFrame(animal, progress));
        frames.addAll(captureGhosts());
        frames.sort(Comparator.comparingDouble(frame -> frame.position().x + frame.position().y));
        return frames;
    }
    private Point2D.Double position(Model.AnimalView animal, double progress) {
        Point2D.Double from = origins.get(animal.id());
        return from == null ? new Point2D.Double(animal.x(), animal.y())
            : new Point2D.Double(from.x + (animal.x() - from.x) * progress, from.y + (animal.y() - from.y) * progress);
    }
    private static Point2D.Double project(double x, double y) { return new Point2D.Double((x - y) * TILE_X, (x + y) * TILE_Y); }
    private Rectangle2D.Double worldBounds() {
        if (snapshot == null) return new Rectangle2D.Double(-570, -90, 1140, 670);
        return new Rectangle2D.Double(-snapshot.height() * TILE_X - 65, -85,
            (snapshot.width() + snapshot.height()) * TILE_X + 130,
            (snapshot.width() + snapshot.height()) * TILE_Y + 165);
    }
    private double safeCenterY() { return 65 + Math.max(140, getHeight() - 165) / 2.0; }
    private double baseScale() {
        Rectangle2D.Double bounds = worldBounds();
        return Math.max(.12, Math.min(Math.max(240, getWidth() - 78) / bounds.width,
            Math.max(170, getHeight() - 165) / bounds.height));
    }
    private AffineTransform camera() {
        Rectangle2D.Double bounds = worldBounds(); AffineTransform transform = new AffineTransform();
        transform.translate(getWidth() / 2.0 + panX, safeCenterY() + panY);
        transform.scale(baseScale() * zoom, baseScale() * zoom);
        transform.translate(-bounds.getCenterX(), -bounds.getCenterY()); return transform;
    }
    private void zoomAt(double requested, double x, double y) {
        if (!Double.isFinite(requested)) return;
        double next = Math.max(.6, Math.min(3.8, requested));
        try {
            Point2D anchor = camera().inverseTransform(new Point2D.Double(x, y), null); zoom = next;
            Point2D after = camera().transform(anchor, null);
            panX += x - after.getX(); panY += y - after.getY(); repaint();
        } catch (NoninvertibleTransformException ignored) { }
    }
    private Point2D.Double worldPosition(Model.AnimalView animal, double progress) {
        return projectedPosition(visualFrame(animal, progress).position(), animal.id());
    }
    private Point2D.Double projectedPosition(Point2D.Double logical, long id) {
        Point2D.Double projected = project(logical.x + .5, logical.y + .5);
        projected.x += ((hash(id) & 7) - 3.5) * .6;
        projected.y += ((hash(id + 9) & 7) - 3.5) * .35; return projected;
    }
    private Point2D.Double screenPosition(Model.AnimalView animal, double progress) {
        Point2D.Double target = new Point2D.Double(); camera().transform(worldPosition(animal, progress), target); return target;
    }
    private List<Model.AnimalView> depthOrder(double progress) {
        List<Model.AnimalView> sorted = new ArrayList<>(snapshot.animals());
        sorted.sort(Comparator.comparingDouble(animal -> { Point2D.Double logical = visualFrame(animal, progress).position(); return logical.x + logical.y; }));
        return sorted;
    }
    private Model.AnimalView animalAt(int x, int y) {
        if (snapshot == null) return null;
        double fraction = fraction(), scale = baseScale() * zoom; List<Model.AnimalView> sorted = depthOrder(fraction);
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Model.AnimalView animal = sorted.get(i); Point2D.Double point = screenPosition(animal, fraction);
            double halfWidth = Math.max(12, 30 * scale), height = Math.max(23, (animal.kind().equals("herbivore") ? 52 : 37) * scale);
            if (x >= point.x - halfWidth && x <= point.x + halfWidth && y >= point.y - height && y <= point.y + 8 * scale) return animal;
        }
        return null;
    }
    @Override public String getToolTipText(MouseEvent event) {
        Model.AnimalView animal = animalAt(event.getX(), event.getY());
        if (animal != null) return (animal.kind().equals("herbivore") ? "Deer" : "Wolf") + " #" + animal.id()
            + " · " + AnimalPresentation.action(animal) + " · Energy: " + animal.energy()
            + " · " + AnimalPresentation.satiety(animal) + " · Click to inspect";
        if (snapshot == null) return null;
        try {
            Point2D point = camera().inverseTransform(event.getPoint(), null);
            int x = (int) Math.floor((point.getX() / TILE_X + point.getY() / TILE_Y) / 2);
            int y = (int) Math.floor((point.getY() / TILE_Y - point.getX() / TILE_X) / 2);
            if (x < 0 || x >= snapshot.width() || y < 0 || y >= snapshot.height()) return "Wheel: zoom · Drag: camera · Double-click: whole island";
            Model.PlantView plant = snapshot.plants().get(y * snapshot.width() + x);
            return "Grass: " + plant.biomass() + " / " + plant.capacity() + " food units · Cell " + (x + 1) + ", " + (y + 1);
        } catch (NoninvertibleTransformException failure) { return null; }
    }
    @Override protected void paintComponent(Graphics base) {
        updatePlayback();
        super.paintComponent(base); Graphics2D g = (Graphics2D) base.create(); quality(g); drawWater(g);
        if (snapshot == null) { g.dispose(); return; }
        if (terrain == null || terrainDirty) createTerrain();
        AffineTransform original = g.getTransform(); g.transform(camera());
        g.drawImage(terrain, (int) cacheBounds.x, (int) cacheBounds.y, null);
        if (gridVisible) drawGrid(g);
        double progress = fraction();
        double life = motionEnabled && running && (suppliedClock || !GraphicsEnvironment.isHeadless()) ? nanos.getAsLong() / 1_000_000_000.0 : 0;
        for (RenderAnimal frame : renderAnimals(progress)) {
            if (frame.alpha() <= .001) continue;
            Model.AnimalView animal = frame.animal();
            Point2D.Double point = projectedPosition(frame.position(), animal.id());
            int x = (int) Math.round(point.x), y = (int) Math.round(point.y);
            boolean selected = Objects.equals(selectedId, animal.id());
            if (selected) {
                g.setColor(new Color(247, 218, 130, 60)); g.fill(new Ellipse2D.Double(x - 24, y - 9, 48, 19));
                g.setStroke(new BasicStroke(1.6f)); g.setColor(new Color(244, 213, 131)); g.draw(new Ellipse2D.Double(x - 24, y - 9, 48, 19));
            }
            if (frame.dust()) drawDust(g, x, y, frame.flip(), frame.stride());
            double idle = life == 0 || shotFor(animal.id()) != null ? 0 : Math.sin(life * 2.2 + animal.id()) * .65;
            Graphics2D sprite = (Graphics2D) g.create();
            sprite.setComposite(AlphaComposite.SrcOver.derive((float) unit(frame.alpha())));
            sprite.translate(x, y + idle - frame.lift()); sprite.rotate(frame.rotation()); sprite.scale(1, frame.scaleY());
            if (animal.kind().equals("herbivore")) GameSprites.drawDeer(sprite, 0, 0, 1, frame.flip(), frame.stride());
            else GameSprites.drawWolf(sprite, 0, 0, 1, frame.flip(), frame.stride());
            sprite.dispose();
            if (animal.activity() == Model.Activity.GRAZING && animal.lastMeal() > 0 && shotFor(animal.id()) == null) {
                double chew = life == 0 ? 0 : Math.sin(life * 5 + animal.id()) * 1.2;
                double mouthX = x + (frame.flip() ? -25 : 25), mouthY = y - 27 + chew;
                Path2D leaf = new Path2D.Double();
                leaf.moveTo(mouthX, mouthY); leaf.quadTo(mouthX + 2, mouthY - 8, mouthX + 9, mouthY - 6);
                leaf.quadTo(mouthX + 8, mouthY + 1, mouthX, mouthY); leaf.closePath();
                g.setColor(new Color(0xd7e7a1)); g.fill(leaf);
                g.setColor(new Color(0x668b3f)); g.setStroke(new BasicStroke(.8f));
                g.draw(new Line2D.Double(mouthX, mouthY, mouthX + 6, mouthY - 4));
            }
            if (animalsById.containsKey(animal.id()) && (selected || animal.satietyTicks() == 0 && animal.energy() < animal.metabolism() * 3)) drawEnergy(g, animal, x, y, selected);
        }
        drawContactDust(g);
        drawFireflies(g, life); g.setTransform(original);
        drawOutcomeMarks(g);
        if (hasFocus()) {
            g.setColor(new Color(175, 203, 177, 110)); g.setStroke(new BasicStroke(1.4f)); g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
        }
        g.dispose();
    }
    private void drawDust(Graphics2D g, int x, int y, boolean flip, double stride) {
        double pulse = .45 + .55 * Math.abs(Math.sin(stride));
        int direction = flip ? 1 : -1;
        for (int i = 0; i < 3; i++) {
            double size = 3 + i * 1.7;
            g.setColor(new Color(210, 193, 144, (int) (pulse * (65 - i * 15))));
            g.fill(new Ellipse2D.Double(x + direction * (13 + i * 7) - size, y + 2 - i * 1.6, size * 2, size));
        }
    }
    private void drawContactDust(Graphics2D g) {
        long now = nanos.getAsLong();
        for (Shot shot : activeShots) {
            if (!shot.critical()) continue;
            double p = shot.progress(now), burst = unit((p - .51) / .33);
            if (p < .51 || p > .88) continue;
            Point2D.Double center = project(shot.contact.x + .5, shot.contact.y + .5);
            for (int i = 0; i < 7; i++) {
                double angle = i * Math.PI * 2 / 7, radius = burst * 26;
                double size = (1 - burst) * 3.4 + 1;
                g.setColor(new Color(223, 207, 157, (int) ((1 - burst) * 115)));
                g.fill(new Ellipse2D.Double(center.x + Math.cos(angle) * radius - size,
                    center.y + Math.sin(angle) * radius * .4 - size / 2, size * 2, size));
            }
        }
    }
    private void drawOutcomeMarks(Graphics2D g) {
        long now = nanos.getAsLong();
        List<Rectangle> occupied = new ArrayList<>();
        int drawn = 0;
        g.setFont(new Font("Segoe UI", Font.BOLD, 12));
        for (int i = outcomeMarks.size() - 1; i >= 0 && drawn < 6; i--) {
            OutcomeMark mark = outcomeMarks.get(i);
            if (now < mark.born || now >= mark.expires) continue;
            Point2D.Double projected = project(mark.position.x + .5, mark.position.y + .5);
            Point2D screen = camera().transform(projected, null);
            if (screen.getX() < -50 || screen.getX() > getWidth() + 50 || screen.getY() < 25 || screen.getY() > getHeight() + 20) continue;
            boolean caught = mark.event.outcome() == Model.HuntOutcome.CAPTURE;
            String text = caught ? "CAUGHT" : "ESCAPED";
            int width = g.getFontMetrics().stringWidth(text) + 16;
            int x = (int) Math.max(8, Math.min(getWidth() - width - 8, screen.getX() - width / 2.0));
            int y = (int) Math.max(60, Math.min(getHeight() - 84, screen.getY() - 61 * baseScale() * zoom));
            Rectangle box = new Rectangle(x, y - 17, width, 22);
            for (int attempt = 0; attempt < 4; attempt++) {
                boolean overlaps = false;
                for (Rectangle prior : occupied) if (prior.intersects(box)) { overlaps = true; break; }
                if (!overlaps) break;
                box.y = Math.max(54, box.y - 25);
            }
            occupied.add(box);
            double remaining = (mark.expires - now) / 1_000_000_000.0;
            float alpha = motionEnabled ? (float) Math.min(1, remaining / .30) : 1;
            Graphics2D label = (Graphics2D) g.create();
            label.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, alpha)));
            label.setColor(new Color(20, 27, 27, 232)); label.fillRect(box.x, box.y, box.width, box.height);
            label.setColor(caught ? new Color(240, 190, 123) : new Color(218, 237, 187));
            label.fillRect(box.x, box.y + box.height - 2, box.width, 2);
            label.drawString(text, box.x + 8, box.y + 15);
            label.dispose(); drawn++;
        }
    }
    private void drawWater(Graphics2D g) {
        g.setPaint(new RadialGradientPaint(new Point2D.Double(getWidth() * .5, getHeight() * .52), Math.max(200, getWidth() * .65f),
            new float[]{0, .65f, 1}, new Color[]{new Color(0x28494a), new Color(0x19373c), new Color(0x10282e)}));
        g.fillRect(0, 0, getWidth(), getHeight()); g.setStroke(new BasicStroke(.8f));
        for (int i = 0; i < 90; i++) {
            int noise = hash(i * 729L + 45); int x = Math.floorMod(noise, Math.max(1, getWidth())); int y = Math.floorMod(noise >>> 9, Math.max(1, getHeight()));
            g.setColor(new Color(129, 173, 165, 12 + (i % 3) * 4)); g.drawLine(x, y, x + 9 + (noise & 31), y);
        }
    }
    private Shape landPath(double offsetY) {
        double w = snapshot.width() * TILE_X, h = snapshot.height() * TILE_X;
        double wy = snapshot.width() * TILE_Y, hy = snapshot.height() * TILE_Y;
        Path2D path = new Path2D.Double(); path.moveTo(-7, -13 + offsetY);
        for (int i = 1; i <= 4; i++) {
            double t = i / 4.0; path.lineTo(w * t + (i == 4 ? 15 : 8), wy * t - 10 + offsetY);
        }
        for (int i = 1; i <= 4; i++) {
            double t = i / 4.0; path.lineTo(w - h * t + 12, wy + hy * t + 9 + (i % 2) * 5 + offsetY);
        }
        for (int i = 1; i <= 4; i++) {
            double t = i / 4.0; path.lineTo(w - h - w * t - 12, wy + hy - wy * t + 7 + (i % 2) * 5 + offsetY);
        }
        for (int i = 1; i <= 3; i++) {
            double t = i / 4.0; path.lineTo(-h * (1 - t) - 12, hy * (1 - t) - 9 - (i % 2) * 4 + offsetY);
        }
        path.closePath(); return path;
    }
    private void createTerrain() {
        cacheBounds = worldBounds();
        terrain = new BufferedImage((int) Math.ceil(cacheBounds.width), (int) Math.ceil(cacheBounds.height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = terrain.createGraphics(); quality(g); g.translate(-cacheBounds.x, -cacheBounds.y);
        boolean desert = snapshot.biome().equals("Desert"); Shape top = landPath(0), bottom = landPath(39);
        g.setColor(new Color(16, 31, 29, 55)); g.translate(0, 17); g.fill(bottom); g.translate(0, -17);
        for (int i = 6; i >= 1; i--) {
            g.setColor(new Color(75, 125, 111, 10 + i * 2));
            g.setStroke(new BasicStroke(i * 7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(bottom);
        }
        g.setPaint(new GradientPaint(0, 250, new Color(desert ? 0x77664b : 0x3b5142), 0, 560, new Color(desert ? 0x4d5144 : 0x263c35))); g.fill(bottom);
        for (int edge = 0; edge < 2; edge++) {
            int count = edge == 0 ? snapshot.width() : snapshot.height();
            for (int i = 0; i < count; i += 2) {
                Point2D.Double a = edge == 0 ? project(i, snapshot.height()) : project(snapshot.width(), i);
                Point2D.Double b = edge == 0 ? project(Math.min(count, i + 2), snapshot.height()) : project(snapshot.width(), Math.min(count, i + 2));
                int noise = hash(i + edge * 113);
                g.setColor(new Color(edge == 0 ? 81 : 66, edge == 0 ? 91 : 80, edge == 0 ? 65 : 62, 110));
                g.fill(poly(a.x, a.y + 5, b.x, b.y + 4, b.x - 1, b.y + 31 + (noise & 7), a.x + 1, a.y + 33));
                g.setColor(new Color(22, 43, 35, 70)); g.draw(new Line2D.Double(b.x, b.y + 16, b.x + ((noise & 7) - 3), b.y + 37));
            }
        }
        g.setPaint(new GradientPaint(-300, 0, new Color(desert ? 0xc8af72 : 0x90a565), 250, 500, new Color(desert ? 0xa38e5c : 0x617f49))); g.fill(top);
        Shape oldClip = g.getClip(); g.clip(top);
        for (Model.PlantView plant : snapshot.plants()) {
            Point2D.Double point = project(plant.x() + .5, plant.y() + .5);
            double food = plant.biomass() / (double) Math.max(1, plant.capacity()); int noise = hash(plant.x() * 779L + plant.y() * 319L);
            double field = Math.sin(plant.x() * .45) * Math.cos(plant.y() * .34) + Math.sin((plant.x() + plant.y()) * .4) * .5;
            if ((noise & 7) < 2) {
                g.setColor(desert ? new Color(211, 187, 123, 35) : new Color(176, 186, 107, 25 + (int) ((field + 2) * 6)));
                g.fill(poly(point.x - 28, point.y - 10, point.x + 8, point.y - 18, point.x + 34, point.y - 2, point.x + 10, point.y + 13, point.x - 26, point.y + 8));
            }
            if (food < .18) {
                g.setColor(desert ? new Color(146, 123, 78, 55) : new Color(180, 157, 97, 42));
                g.fill(poly(point.x - 18, point.y - 2, point.x - 2, point.y - 8, point.x + 17, point.y + 1, point.x + 4, point.y + 9, point.x - 12, point.y + 7));
            } else if (food > .3 && (noise & 3) != 0) drawGroundPlant(g, point.x + (noise & 7) - 3, point.y + ((noise >>> 8) & 7) - 3, food, desert, noise);
            if ((noise & 127) == 43) rock(g, point.x, point.y, .5 + (noise & 7) * .07);
            if (food > .6 && (noise & 31) == 16) {
                g.setColor(new Color(desert ? 0xe3cca1 : 0xf0dfad)); g.fill(new Ellipse2D.Double(point.x - 2, point.y - 3, 2.5, 2.5)); g.fill(new Ellipse2D.Double(point.x + 4, point.y, 2.1, 2.1));
            }
        }
        g.setClip(oldClip);
        // The forest is beyond the logical board, preserving every traversable cell.
        for (int i = 0; i < 12; i++) {
            double distance = 1.1 + i * 1.52;
            if (distance < snapshot.height() - .5) { Point2D.Double p = project(-.85, distance); tree(g, p.x, p.y, .66 + ((hash(i) & 15) / 40.0), desert, i); }
            if (distance < snapshot.width() - .5) { Point2D.Double p = project(distance, -.9); tree(g, p.x, p.y, .65 + ((hash(i + 21) & 15) / 43.0), desert, i + 21); }
        }
        g.dispose(); terrainDirty = false;
    }
    private static void drawGroundPlant(Graphics2D g, double x, double y, double food, boolean desert, int seed) {
        g.setColor(desert ? new Color(0x777d4c) : new Color(0x536e3c));
        g.fill(poly(x - 7, y + 2, x - 9, y - 4, x - 4, y - 1, x - 1, y - 8 * food - 1, x + 2, y - 1, x + 7, y - 5, x + 5, y + 3));
        g.setColor(desert ? new Color(0xb3b16c) : new Color(0xa6b875)); g.fill(poly(x - 3, y + 1, x - 1, y - 7 * food, x + 1, y + 1));
        if (food > .8 && (seed & 7) == 1) {
            g.setColor(desert ? new Color(0xc7b885) : new Color(0x87975c)); g.fill(poly(x - 15, y + 1, x - 10, y - 6, x - 5, y - 3, x - 5, y + 3));
        }
    }
    private static void tree(Graphics2D g, double x, double y, double size, boolean desert, int seed) {
        Graphics2D p = (Graphics2D) g.create(); p.translate(x, y); p.scale(size, size);
        p.setColor(new Color(28, 47, 28, 45)); p.fill(new Ellipse2D.Double(-21, -4, 51, 17));
        p.setColor(new Color(desert ? 0x66543a : 0x4d5238)); p.fill(poly(-4, 1, -3, -31, 5, -34, 5, 1));
        if (desert) {
            p.setColor(new Color(0x68734c)); p.fill(poly(-30, -31, -21, -46, -1, -50, 22, -45, 31, -31, 11, -25, -17, -26));
            p.setColor(new Color(0x9a9c65)); p.fill(poly(-29, -33, -15, -43, 3, -47, 25, -36, 5, -31));
            p.setColor(new Color(0x7f8654)); p.fill(poly(5, -31, 25, -36, 31, -31, 10, -25));
        } else {
            p.setColor(new Color(0x304f39)); p.fill(poly(-25, -19, -17, -43, 0, -73, 17, -43, 28, -19, 9, -9, -10, -10));
            p.setColor(new Color(0x4b7048)); p.fill(poly(-25, -21, -12, -47, 0, -73, 1, -21, -10, -11));
            p.setColor(new Color(0x73905a)); p.fill(poly(-17, -43, 0, -73, -3, -43, -11, -30));
            p.setColor(new Color(0x42653f)); p.fill(poly(-20, -23, -10, -43, 0, -61, 9, -43, 20, -25, 4, -21));
            p.setColor(new Color(0x819a5e)); p.fill(poly(-15, -29, 0, -61, -3, -32, -10, -23));
        }
        p.dispose();
    }
    private static void rock(Graphics2D g, double x, double y, double size) {
        Graphics2D p = (Graphics2D) g.create(); p.translate(x, y); p.scale(size, size);
        p.setColor(new Color(44, 57, 42, 36)); p.fillOval(-12, 0, 29, 9);
        p.setColor(new Color(0x8e9680)); p.fill(poly(-12, 0, -8, -10, 5, -15, 15, -5, 13, 5, 0, 8));
        p.setColor(new Color(0xb9bea1)); p.fill(poly(-12, 0, -8, -10, 5, -15, 3, -3));
        p.setColor(new Color(0x687761)); p.fill(poly(3, -3, 5, -15, 15, -5, 13, 5, 0, 8)); p.dispose();
    }
    private void drawGrid(Graphics2D g) {
        g.setStroke(new BasicStroke(.6f)); g.setColor(new Color(229, 233, 181, 55));
        for (int x = 0; x <= snapshot.width(); x++) { Point2D a = project(x, 0), b = project(x, snapshot.height()); g.draw(new Line2D.Double(a, b)); }
        for (int y = 0; y <= snapshot.height(); y++) { Point2D a = project(0, y), b = project(snapshot.width(), y); g.draw(new Line2D.Double(a, b)); }
    }
    private void drawEnergy(Graphics2D g, Model.AnimalView animal, int x, int y, boolean selected) {
        int max = AnimalPresentation.maxEnergy(snapshot.biome(), animal);
        int top = y - (animal.kind().equals("herbivore") ? 54 : 39);
        g.setColor(new Color(18, 39, 34, 205)); g.fillRoundRect(x - 17, top, 34, 5, 4, 4);
        g.setColor(animal.satietyTicks() == 0 && animal.energy() < animal.metabolism() * 3 ? new Color(0xe7a56a) : new Color(0xc5d889)); g.fillRoundRect(x - 16, top + 1, Math.max(1, 32 * animal.energy() / max), 3, 3, 3);
        if (selected) {
            String label = (animal.kind().equals("herbivore") ? "Deer" : "Wolf") + " #" + animal.id(); g.setFont(new Font("Segoe UI", Font.BOLD, 11));
            int width = g.getFontMetrics().stringWidth(label);
            g.setColor(new Color(18, 39, 34, 218)); g.fillRoundRect(x - width / 2 - 7, top - 22, width + 14, 18, 7, 7);
            g.setColor(new Color(0xf3ead0)); g.drawString(label, x - width / 2, top - 9);
        }
    }
    private void drawFireflies(Graphics2D g, double life) {
        if (life == 0) return;
        for (int i = 0; i < 13; i++) {
            double x = ((hash(i * 113L) & 255) / 255.0) * snapshot.width(), y = ((hash(i * 279L + 7) & 255) / 255.0) * snapshot.height();
            Point2D.Double point = project(x, y); double flicker = .5 + .5 * Math.sin(life * 1.6 + i * 3.7);
            g.setColor(new Color(232, 221, 148, (int) (15 + flicker * 65)));
            g.fill(new Ellipse2D.Double(point.x + Math.sin(life * .35 + i) * 3, point.y - 18 + Math.cos(life * .4 + i) * 2, 2, 2));
        }
    }
    static void drawHerbivore(Graphics2D g, int x, int y, double scale, boolean flip) { GameSprites.drawDeer(g, x, y, scale, flip); }
    static void drawPredator(Graphics2D g, int x, int y, double scale, boolean flip) { GameSprites.drawWolf(g, x, y, scale, flip); }
    static void drawRabbit(Graphics2D g, int x, int y, double scale, boolean flip) { drawHerbivore(g, x, y, scale, flip); }
    static void drawFox(Graphics2D g, int x, int y, double scale, boolean flip) { drawPredator(g, x, y, scale, flip); }
    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
    private static Path2D poly(double... points) { Path2D p = new Path2D.Double(); p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]); p.closePath(); return p; }
    private static int hash(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return (int) (value ^ (value >>> 31));
    }
}

