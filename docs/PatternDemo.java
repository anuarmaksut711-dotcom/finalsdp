import ecosystem.Behaviors;
import ecosystem.Creation;
import ecosystem.EcosystemFacade;
import ecosystem.Events;
import ecosystem.Model;
import ecosystem.Reporting;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Executable client using only the facade's public application API. */
public final class PatternDemo {
    private static final class RichestPatchStrategy implements Behaviors.ForagingStrategy {
        private int selections;

        @Override
        public String name() { return "Richest patch"; }

        @Override
        public Optional<Behaviors.Target> choose(List<Behaviors.Target> targets) {
            selections++;
            return targets.stream().filter(target -> target.food() > 0)
                .min(Comparator.comparingInt(Behaviors.Target::food).reversed()
                    .thenComparingInt(Behaviors.Target::distance)
                    .thenComparingInt(Behaviors.Target::y)
                    .thenComparingInt(Behaviors.Target::x));
        }
    }

    public static void main(String[] args) {
        EcosystemFacade facade = new EcosystemFacade();
        int[] notifications = {0};
        Events.EcosystemObserver observer = snapshot -> {
            if (snapshot != facade.snapshot()) throw new AssertionError("Uncommitted notification");
            notifications[0]++;
        };
        facade.subscribe(observer);
        facade.reset(new Creation.WorldBuilder().population(1, 0).build());

        RichestPatchStrategy policy = new RichestPatchStrategy();
        facade.setStrategy(policy);
        for (int tick = 0; tick < 25; tick++) facade.step();
        Model.Snapshot snapshot = facade.snapshot();
        if (!snapshot.strategy().equals("Richest patch") || policy.selections == 0) {
            throw new AssertionError("The custom strategy was not executed");
        }
        if (snapshot.animals().get(0).lastMeal() == 0) throw new AssertionError("The selected food was not consumed");
        if (notifications[0] != 27) throw new AssertionError("Expected reset, strategy change and 25 turn updates");

        facade.unsubscribe(observer);
        facade.step();
        if (notifications[0] != 27 || !facade.observerErrors().isEmpty()) {
            throw new AssertionError("Observer lifecycle failed");
        }
        String report = facade.report(false, new Reporting.CsvRenderer());
        if (!report.contains("Richest patch")) throw new AssertionError("Facade export lost the selected policy");

        System.out.println("PASS Facade: create, advance, inspect and export through one application API");
        System.out.println("PASS Strategy: install a new policy object; real food selection and consumption use it");
        System.out.println("PASS Observer: 27 committed updates; unsubscribe stops subsequent delivery");
        System.out.println("SUCCESS: Facade, Observer and Strategy demonstration passed.");
    }
}
