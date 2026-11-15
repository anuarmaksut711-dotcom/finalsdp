package ecosystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Strategy: interchangeable rules for choosing food and grazing. */
public final class Behaviors {
    public static final int VISION = 3;
    public static final int MEAL = 4;
    public static final int RESERVE = 6;
    private static final int PREDATOR_RISK_WEIGHT = 5;

    private Behaviors() {
    }

    public record Target(int x, int y, int distance, int food, int risk) {
        public Target {
            if (x < 0 || y < 0 || distance < 0 || food < 0 || risk < 0) {
                throw new IllegalArgumentException("Target values must be nonnegative.");
            }
        }
    }

    /** Interchangeable food policies; the context owns movement and model changes. */
    public interface ForagingStrategy {
        /** A nonblank display name that stays constant while the policy is installed. */
        String name();

        /** Return a supplied target or empty, without changing the list or model. */
        Optional<Target> choose(List<Target> targets);

        /** Pure meal policy: return 0..min(MEAL, biomass) for nonnegative biomass. */
        default int meal(int biomass) {
            requireBiomass(biomass);
            return Math.min(MEAL, biomass);
        }
    }

    public static final class NearestFoodStrategy implements ForagingStrategy {
        @Override
        public String name() {
            return "Nearest food";
        }

        @Override
        public Optional<Target> choose(List<Target> targets) {
            return bestTarget(targets, 1, 0);
        }
    }

    public static final class SafestFoodStrategy implements ForagingStrategy {
        @Override
        public String name() {
            return "Safest food";
        }

        @Override
        public Optional<Target> choose(List<Target> targets) {
            return bestTarget(targets, 1, PREDATOR_RISK_WEIGHT);
        }
    }

    public static final class SustainableGrazingStrategy implements ForagingStrategy {
        @Override
        public String name() {
            return "Sustainable grazing";
        }

        @Override
        public Optional<Target> choose(List<Target> targets) {
            return bestTarget(targets, MEAL + RESERVE, 0);
        }

        @Override
        public int meal(int biomass) {
            requireBiomass(biomass);
            if (biomass < MEAL + RESERVE) {
                return 0;
            }
            return MEAL;
        }
    }

    private static Optional<Target> bestTarget(List<Target> targets, int minimumFood, int riskWeight) {
        Objects.requireNonNull(targets, "Targets are required.");
        Target best = null;
        long bestScore = Long.MAX_VALUE;

        for (Target candidate : targets) {
            Objects.requireNonNull(candidate, "Targets must not contain null.");
            if (candidate.food() < minimumFood) {
                continue;
            }

            long score = candidate.distance() + (long) riskWeight * candidate.risk();
            if (best == null || score < bestScore || (score == bestScore && comesFirst(candidate, best))) {
                best = candidate;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean comesFirst(Target candidate, Target current) {
        // Break ties by distance, then row, then column.
        if (candidate.distance() != current.distance()) {
            return candidate.distance() < current.distance();
        }
        if (candidate.y() != current.y()) {
            return candidate.y() < current.y();
        }
        return candidate.x() < current.x();
    }

    public static ForagingStrategy named(String name) {
        Objects.requireNonNull(name, "Strategy name is required.");
        return switch (name) {
            case "Nearest food" -> new NearestFoodStrategy();
            case "Safest food" -> new SafestFoodStrategy();
            case "Sustainable grazing" -> new SustainableGrazingStrategy();
            default -> throw new IllegalArgumentException("Unknown strategy: " + name);
        };
    }

    private static void requireBiomass(int biomass) {
        if (biomass < 0) {
            throw new IllegalArgumentException("Biomass must be nonnegative.");
        }
    }

    public static List<Target> candidates(Model.World world, Model.Herbivore herbivore) {
        int firstRow = Math.max(0, herbivore.y - VISION);
        int lastRow = Math.min(world.config.height() - 1, herbivore.y + VISION);
        int firstColumn = Math.max(0, herbivore.x - VISION);
        int lastColumn = Math.min(world.config.width() - 1, herbivore.x + VISION);
        List<Target> targets = new ArrayList<>();

        for (int y = firstRow; y <= lastRow; y++) {
            for (int x = firstColumn; x <= lastColumn; x++) {
                int distance = Math.abs(x - herbivore.x) + Math.abs(y - herbivore.y);
                if (distance <= VISION) {
                    int food = world.plant(x, y).biomass;
                    targets.add(new Target(x, y, distance, food, countNearbyPredators(world, x, y)));
                }
            }
        }
        return targets;
    }

    private static int countNearbyPredators(Model.World world, int x, int y) {
        int count = 0;
        for (Model.Animal animal : world.animals.values()) {
            int distance = Math.abs(x - animal.x) + Math.abs(y - animal.y);
            if (animal instanceof Model.Predator && distance <= 1) {
                count++;
            }
        }
        return count;
    }

    /** Search without knowing where unseen food is: sweep adjacent cells row by row. */
    public static void explore(Model.World world, Model.Animal animal) {
        int width = world.config.width();
        int length = width * world.config.height();
        int index = animal.y * width + (animal.y % 2 == 0 ? animal.x : width - 1 - animal.x);
        if (animal.explorationDirection == 0) {
            animal.explorationDirection = (animal.id & 1) == 0 ? 1 : -1;
        }
        int next = index + animal.explorationDirection;
        if (next < 0 || next >= length) {
            animal.explorationDirection = -animal.explorationDirection;
            next = index + animal.explorationDirection;
        }
        int row = next / width;
        int column = row % 2 == 0 ? next % width : width - 1 - next % width;
        moveOne(animal, column, row);
    }

    public static void moveOne(Model.Animal animal, int targetX, int targetY) {
        if (animal.x != targetX) {
            animal.x += Integer.compare(targetX, animal.x);
        } else if (animal.y != targetY) {
            animal.y += Integer.compare(targetY, animal.y);
        }
    }
}
