package ecosystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Public-contract regressions for Facade, Observer and Strategy. */
final class PatternChecks {
    private static int passed;

    private PatternChecks() { }

    static int run() {
        passed = 0;
        test("Facade requires a world and accepts subscriptions before reset", PatternChecks::facadeLifecycle);
        test("Rejected facade settings preserve state, notifications and seeded continuation", PatternChecks::facadeRejectedChanges);
        test("Facade queries neither advance time nor publish updates", PatternChecks::facadeQueries);
        test("Facade prevents nested commands while observers read the committed snapshot", PatternChecks::facadeNotifications);
        test("A new strategy object changes real feeding through the facade", PatternChecks::facadeStrategyInjection);
        test("Nested Observer publications keep FIFO order for every subscriber", PatternChecks::observerQueue);
        test("Subscription changes take effect on the next snapshot", PatternChecks::observerSubscriptionChanges);
        test("Observer registration and removal use listener identity", PatternChecks::observerIdentity);
        test("Observer failures are isolated and immutable history is bounded", PatternChecks::observerErrors);
        test("Observer rejects null input without interrupting later delivery", PatternChecks::observerNulls);
        test("Strategies reject malformed inputs and preserve supplied targets", PatternChecks::strategyInputs);
        test("All strategies handle empty food and stable ties independently of input order", PatternChecks::strategyOrdering);
        test("Safest-food scoring cannot overflow for large target values", PatternChecks::strategyArithmetic);
        test("The context rejects invalid strategy results before applying them", PatternChecks::strategyOutputs);
        return passed;
    }

    private static void facadeLifecycle() {
        EcosystemFacade facade = new EcosystemFacade();
        fails(IllegalStateException.class, facade::snapshot);
        fails(IllegalStateException.class, facade::step);
        fails(IllegalStateException.class, () -> facade.report(false, new Reporting.CsvRenderer()));
        fails(IllegalStateException.class, () -> facade.addHerbivores(1));
        fails(IllegalStateException.class, () -> facade.setStrategy("Nearest food"));
        fails(IllegalStateException.class, () -> facade.setStrategy(new Behaviors.NearestFoodStrategy()));
        fails(IllegalStateException.class, () -> facade.setWeatherSource(new WeatherSystem.NativeWeatherSource()));
        int[] calls = {0};
        facade.subscribe(snapshot -> calls[0]++);
        facade.reset(new Creation.WorldBuilder().build());
        check(calls[0] == 1 && facade.snapshot().tick() == 0, "First committed snapshot must be delivered");
        check(facade.observerErrors().isEmpty(), "Lifecycle should have no listener errors");
    }

    private static void facadeRejectedChanges() {
        EcosystemFacade facade = normalWorld(), control = normalWorld();
        Model.Snapshot before = facade.snapshot();
        int[] calls = {0};
        facade.subscribe(snapshot -> calls[0]++);
        fails(IllegalArgumentException.class, () -> facade.reset(
            new Creation.WorldConfig(0, 20, 7, "Meadow", 30, 5, 500, "Nearest food")));
        fails(IllegalArgumentException.class, () -> facade.addHerbivores(-1));
        fails(IllegalArgumentException.class, () -> facade.setStrategy("Unknown"));
        fails(NullPointerException.class, () -> facade.setStrategy((Behaviors.ForagingStrategy) null));
        fails(NullPointerException.class, () -> facade.setWeatherSource(null));
        fails(IllegalArgumentException.class, () -> facade.setStrategy(new Policy(" ", 4)));
        fails(IllegalArgumentException.class, () -> facade.setStrategy(new Policy(null, 4)));
        fails(IllegalStateException.class, () -> facade.setStrategy(new Policy("Broken name", 4) {
            public String name() { throw new IllegalStateException("Cannot read policy name"); }
        }));
        check(before == facade.snapshot() && calls[0] == 0, "Rejected settings must not publish or replace state");
        for (int tick = 0; tick < 40; tick++) {
            check(facade.step().equals(control.step()), "Rejected settings consumed state or random choices");
        }
    }

    private static void facadeQueries() {
        EcosystemFacade facade = normalWorld();
        int[] calls = {0};
        facade.subscribe(snapshot -> calls[0]++);
        Model.Snapshot before = facade.snapshot();
        for (Reporting.ReportRenderer renderer : List.of(new Reporting.CsvRenderer(), new Reporting.PlainTextRenderer())) {
            check(!facade.report(false, renderer).isBlank(), "Summary query must work");
            check(!facade.report(true, renderer).isBlank(), "Detailed query must work");
        }
        check(facade.snapshot() == before && calls[0] == 0, "Queries must not change or publish state");
    }

    private static void facadeNotifications() {
        EcosystemFacade facade = normalWorld();
        List<Model.Snapshot> delivered = new ArrayList<>();
        int[] rejections = {0};
        facade.subscribe(snapshot -> {
            check(facade.snapshot() == snapshot, "Snapshot must be committed before notification");
            check(!facade.report(false, new Reporting.CsvRenderer()).isBlank(), "Observer may query reports");
            List<Runnable> nested = List.of(
                () -> facade.step(), () -> facade.reset(new Creation.WorldBuilder().build()),
                () -> facade.setStrategy("Safest food"), () -> facade.addHerbivores(1),
                () -> facade.setWeatherSource(new WeatherSystem.NativeWeatherSource()));
            for (Runnable action : nested) {
                fails(IllegalStateException.class, action);
                rejections[0]++;
            }
        });
        facade.subscribe(delivered::add);
        Model.Snapshot first = facade.step();
        check(first.tick() == 1 && delivered.get(0) == first && rejections[0] == 5, "One command means one committed turn");
        facade.step();
        check(delivered.get(1).tick() == 2 && facade.observerErrors().isEmpty(), "Guard must release after delivery");
    }

    private static void facadeStrategyInjection() {
        EcosystemFacade facade = hungryWorld();
        Model.Herbivore deer = (Model.Herbivore) facade.worldForTests().animals.firstEntry().getValue();
        Policy policy = new Policy("Half meal", 2);
        Model.Snapshot before = facade.snapshot();
        facade.setStrategy(policy);
        check(facade.snapshot().tick() == before.tick() && facade.snapshot().strategy().equals("Half meal"),
            "A strategy can be installed without rebuilding the world");
        Model.Snapshot after = facade.step();
        check(policy.choices == 1 && policy.meals > 1, "Engine must invoke the injected policies");
        check(deer.lastMeal == 2 && deer.satietyTicks == 12 && facade.worldForTests().plant(2, 2).biomass == 18,
            "Custom meal must change actual plant consumption and food reserve");
        check(after.animals().get(0).activity() == Model.Activity.GRAZING, "The custom target is used");
    }

    private static void observerQueue() {
        List<Model.Snapshot> snapshots = twoSnapshots();
        Events.EventBus bus = new Events.EventBus();
        List<String> order = new ArrayList<>();
        bus.subscribe(snapshot -> {
            order.add("A" + snapshot.tick());
            if (snapshot.tick() == 0) bus.publish(snapshots.get(1));
        });
        bus.subscribe(snapshot -> order.add("B" + snapshot.tick()));
        bus.publish(snapshots.get(0));
        check(order.equals(List.of("A0", "B0", "A1", "B1")), "Nested snapshots must not overtake current delivery");
    }

    private static void observerSubscriptionChanges() {
        List<Model.Snapshot> snapshots = twoSnapshots();
        Events.EventBus bus = new Events.EventBus();
        List<String> order = new ArrayList<>();
        Events.EcosystemObserver old = snapshot -> order.add("B" + snapshot.tick());
        Events.EcosystemObserver added = snapshot -> order.add("C" + snapshot.tick());
        bus.subscribe(snapshot -> {
            order.add("A" + snapshot.tick());
            if (snapshot.tick() == 0) {
                bus.unsubscribe(old);
                bus.subscribe(added);
                bus.publish(snapshots.get(1));
            }
        });
        bus.subscribe(old);
        bus.publish(snapshots.get(0));
        check(order.equals(List.of("A0", "B0", "A1", "C1")), "Current listener copy must complete before changes apply");
    }

    private static void observerIdentity() {
        class EqualObserver implements Events.EcosystemObserver {
            int calls;
            public void onSnapshot(Model.Snapshot snapshot) { calls++; }
            public boolean equals(Object other) { return other instanceof EqualObserver; }
            public int hashCode() { return 1; }
        }
        Events.EventBus bus = new Events.EventBus();
        EqualObserver first = new EqualObserver(), second = new EqualObserver();
        bus.subscribe(first); bus.subscribe(first); bus.subscribe(second);
        Model.Snapshot snapshot = normalWorld().snapshot();
        bus.publish(snapshot);
        check(first.calls == 1 && second.calls == 1, "Only the same instance is a duplicate");
        bus.unsubscribe(second); bus.publish(snapshot);
        check(first.calls == 2 && second.calls == 1, "Removal must target the selected instance");
    }

    private static void observerErrors() {
        Events.EventBus bus = new Events.EventBus();
        int[] healthy = {0}, failure = {0};
        bus.subscribe(snapshot -> { throw new IllegalStateException("failure " + failure[0]++); });
        bus.subscribe(snapshot -> healthy[0]++);
        Model.Snapshot snapshot = normalWorld().snapshot();
        for (int n = 0; n < 70; n++) bus.publish(snapshot);
        List<String> errors = bus.deliveryErrors();
        check(healthy[0] == 70 && errors.size() == Events.EventBus.MAX_DELIVERY_ERRORS, "Failure history must be bounded");
        check(errors.get(0).endsWith("failure 6") && errors.get(63).endsWith("failure 69"), "Keep the latest failures");
        fails(UnsupportedOperationException.class, () -> errors.clear());
        bus.publish(snapshot);
        check(errors.get(0).endsWith("failure 6"), "A previously returned history must stay unchanged");
    }

    private static void observerNulls() {
        Events.EventBus bus = new Events.EventBus();
        fails(NullPointerException.class, () -> bus.subscribe(null));
        fails(NullPointerException.class, () -> bus.unsubscribe(null));
        fails(NullPointerException.class, () -> bus.publish(null));
        int[] calls = {0};
        bus.subscribe(snapshot -> calls[0]++);
        bus.publish(normalWorld().snapshot());
        check(calls[0] == 1, "Rejected null publication must not leave the bus stuck");
    }

    private static void strategyInputs() {
        fails(IllegalArgumentException.class, () -> new Behaviors.Target(0, 0, -1, 5, 0));
        for (Behaviors.ForagingStrategy policy : policies()) {
            fails(NullPointerException.class, () -> policy.choose(null));
            List<Behaviors.Target> bad = new ArrayList<>(); bad.add(null);
            fails(NullPointerException.class, () -> policy.choose(bad));
            fails(IllegalArgumentException.class, () -> policy.meal(-1));
            List<Behaviors.Target> input = new ArrayList<>(List.of(new Behaviors.Target(1, 1, 1, 20, 0)));
            List<Behaviors.Target> before = List.copyOf(input);
            check(policy.choose(input).isPresent() && input.equals(before), "Policies must not modify their input");
        }
    }

    private static void strategyOrdering() {
        for (Behaviors.ForagingStrategy policy : policies()) {
            check(policy.choose(List.of()).isEmpty(), "Empty input returns no target");
            check(policy.choose(List.of(new Behaviors.Target(0, 0, 0, 0, 0))).isEmpty(), "No-food input returns no target");
            List<Behaviors.Target> targets = new ArrayList<>(List.of(
                new Behaviors.Target(3, 1, 1, 20, 0), new Behaviors.Target(1, 1, 1, 20, 0),
                new Behaviors.Target(0, 2, 1, 20, 0)));
            for (int n = 0; n < 3; n++) {
                check(policy.choose(targets).orElseThrow().equals(new Behaviors.Target(1, 1, 1, 20, 0)),
                    "Equal scores use distance, row and column, rather than list order");
                Collections.rotate(targets, 1);
            }
        }
    }

    private static void strategyArithmetic() {
        Behaviors.Target safe = new Behaviors.Target(2, 0, 2, 20, 0);
        for (Behaviors.Target dangerous : List.of(
                new Behaviors.Target(1, 0, 1, 20, 500_000_000),
                new Behaviors.Target(1, 0, Integer.MAX_VALUE, 20, Integer.MAX_VALUE))) {
            check(new Behaviors.SafestFoodStrategy().choose(List.of(dangerous, safe)).orElseThrow().equals(safe),
                "Large risk must never wrap into a cheap negative score");
        }
    }

    private static void strategyOutputs() {
        List<Behaviors.ForagingStrategy> invalid = List.of(
            new Policy("Null choice", 4) { public Optional<Behaviors.Target> choose(List<Behaviors.Target> input) { return null; } },
            new Policy("Foreign target", 4) { public Optional<Behaviors.Target> choose(List<Behaviors.Target> input) {
                return Optional.of(new Behaviors.Target(50, 50, 0, 20, 0)); } },
            new Policy("Too much food", 5), new Policy("Negative food", -1));
        for (Behaviors.ForagingStrategy policy : invalid) {
            EcosystemFacade facade = hungryWorld();
            facade.setStrategy(policy);
            fails(IllegalStateException.class, facade::step);
            check(facade.worldForTests().plant(2, 2).biomass == 20, "Invalid output must not consume plants");
            // An extension failure is not a transaction; reset starts a new valid run.
            facade.reset(new Creation.WorldBuilder().build());
            check(facade.step().tick() == 1, "A failed extension must release the facade command guard");
        }
    }

    private static class Policy implements Behaviors.ForagingStrategy {
        private final String name;
        private final int amount;
        int choices, meals;
        Policy(String name, int amount) { this.name = name; this.amount = amount; }
        public String name() { return name; }
        public Optional<Behaviors.Target> choose(List<Behaviors.Target> targets) {
            choices++;
            fails(UnsupportedOperationException.class, targets::clear);
            return targets.stream().filter(target -> target.distance() == 0).findFirst();
        }
        public int meal(int biomass) { meals++; return Math.min(amount, biomass); }
    }

    private static EcosystemFacade normalWorld() {
        EcosystemFacade facade = new EcosystemFacade();
        facade.reset(new Creation.WorldBuilder().build());
        return facade;
    }

    private static EcosystemFacade hungryWorld() {
        EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
        facade.reset(new Creation.WorldBuilder().size(5, 5).population(1, 0).build());
        Model.World world = facade.worldForTests();
        for (Model.Plant plant : world.plants) plant.biomass = 20;
        Model.Animal deer = world.animals.firstEntry().getValue();
        deer.x = 2; deer.y = 2; deer.satietyTicks = 0;
        return facade;
    }

    private static List<Model.Snapshot> twoSnapshots() {
        EcosystemFacade facade = normalWorld();
        return List.of(facade.snapshot(), facade.step());
    }

    private static List<Behaviors.ForagingStrategy> policies() {
        return List.of(new Behaviors.NearestFoodStrategy(), new Behaviors.SafestFoodStrategy(),
            new Behaviors.SustainableGrazingStrategy());
    }

    private static void fails(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            check(type.isInstance(failure), "Expected " + type.getSimpleName() + ", got " + failure);
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void test(String name, Runnable action) {
        action.run(); passed++; System.out.println("PASS " + name);
    }
}
