package ecosystem;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.Random;
import java.util.Objects;

/** Domain model. Mutation is owned by EcosystemFacade on one thread. */
public final class Model {
    public static final int TICKS_PER_DAY = 12;
    public static final int FOOD_RESERVE_TICKS = 2 * TICKS_PER_DAY;
    private Model() {
    }

    public abstract static class Organism {
        public final long id;
        public int x;
        public int y;

        protected Organism(long id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        public abstract void accept(Reporting.OrganismVisitor visitor);
    }

    public static final class Plant extends Organism {
        public int biomass;
        public final int capacity;
        public final int growth;

        public Plant(long id, int x, int y, Biomes.PlantProfile profile) {
            super(id, x, y);
            capacity = profile.capacity();
            growth = profile.growth();
        }

        @Override
        public void accept(Reporting.OrganismVisitor visitor) {
            visitor.visitPlant(this);
        }
    }

    /** What the animal actually did during its latest turn, independent of presentation. */
    public enum Activity { IDLE, GRAZING, FORAGING, SEARCHING, HUNTING, FEEDING, RESTING, ROAMING, FLEEING }

    public abstract static class Animal extends Organism {
        public int energy;
        public int age;
        public int cooldown;
        public Activity activity = Activity.IDLE;
        public int explorationDirection;
        public int lastMeal;
        public int reproductionSpent;
        public int satietyTicks = FOOD_RESERVE_TICKS;
        public int energySpent;
        public long chaseTargetId = -1;
        public int chaseTicks;
        public int chaseX;
        public int chaseY;
        public int lastFledTick = -1;
        public final int maxEnergy;
        public final int metabolism;
        public final int reproductionThreshold;
        public final int minAge;
        public final int reproductionCost;
        public final int birthEnergy;
        public final int reproductionCooldown;

        protected Animal(
                long id, int x, int y,
                int initialEnergy, int maxEnergy, int metabolism, int reproductionThreshold,
                int minAge, int reproductionCost, int birthEnergy, int reproductionCooldown) {
            super(id, x, y);
            energy = initialEnergy;
            this.maxEnergy = maxEnergy;
            this.metabolism = metabolism;
            this.reproductionThreshold = reproductionThreshold;
            this.minAge = minAge;
            this.reproductionCost = reproductionCost;
            this.birthEnergy = birthEnergy;
            this.reproductionCooldown = reproductionCooldown;
        }
    }

    public static final class Herbivore extends Animal {
        private static final int MIN_REPRODUCTION_AGE = 3 * TICKS_PER_DAY;
        private static final int REPRODUCTION_COST = 12;
        private static final int BIRTH_ENERGY = 12;
        private static final int REPRODUCTION_COOLDOWN = 6 * TICKS_PER_DAY;

        public Herbivore(long id, int x, int y, Biomes.HerbivoreProfile profile) {
            super(id, x, y, profile.initialEnergy(), profile.maxEnergy(), profile.metabolism(),
                    profile.threshold(), MIN_REPRODUCTION_AGE, REPRODUCTION_COST,
                    BIRTH_ENERGY, REPRODUCTION_COOLDOWN);
        }

        @Override
        public void accept(Reporting.OrganismVisitor visitor) {
            visitor.visitHerbivore(this);
        }
    }

    public static final class Predator extends Animal {
        private static final int MIN_REPRODUCTION_AGE = 12 * TICKS_PER_DAY;
        private static final int REPRODUCTION_COST = 18;
        private static final int BIRTH_ENERGY = 18;
        private static final int REPRODUCTION_COOLDOWN = 20 * TICKS_PER_DAY;

        public Predator(long id, int x, int y, Biomes.PredatorProfile profile) {
            super(id, x, y, profile.initialEnergy(), profile.maxEnergy(), profile.metabolism(),
                    profile.threshold(), MIN_REPRODUCTION_AGE, REPRODUCTION_COST,
                    BIRTH_ENERGY, REPRODUCTION_COOLDOWN);
        }

        @Override
        public void accept(Reporting.OrganismVisitor visitor) {
            visitor.visitPredator(this);
        }
    }

    public static final class World {
        public final Creation.WorldConfig config;
        public final Biomes.BiomeFactory biome;
        public final Random random;
        public final List<Plant> plants = new ArrayList<>();
        // Presentation facts for the latest successful turn; snapshots defensively copy them.
        public final List<HuntEvent> hunts = new ArrayList<>();
        // The sorted IDs make the animals' turn order repeatable.
        public final NavigableMap<Long, Animal> animals = new TreeMap<>();
        public long nextId = 1;
        public int tick;
        public WeatherSystem.Weather weather = new WeatherSystem.Weather(20, 1);

        public World(Creation.WorldConfig config, Biomes.BiomeFactory biome) {
            this.config = config;
            this.biome = biome;
            random = new Random(config.seed());
        }

        public Plant plant(int x, int y) {
            int cellIndex = y * config.width() + x;
            return plants.get(cellIndex);
        }

        public boolean contains(int x, int y) {
            return x >= 0 && x < config.width() && y >= 0 && y < config.height();
        }

        public void visit(Reporting.OrganismVisitor visitor) {
            for (Plant plant : plants) {
                plant.accept(visitor);
            }
            for (Animal animal : animals.values()) {
                animal.accept(visitor);
            }
        }
    }

    public record PlantView(int x, int y, int biomass, int capacity) {
    }

    public record AnimalView(long id, int x, int y, String kind, int energy, int age,
                             Activity activity, int lastMeal, int metabolism, int reproductionSpent,
                             int satietyTicks, int energySpent) {
        public AnimalView(long id, int x, int y, String kind, int energy, int age,
                          Activity activity, int lastMeal, int metabolism, int reproductionSpent) {
            this(id, x, y, kind, energy, age, activity, lastMeal, metabolism, reproductionSpent, 0, metabolism);
        }
        public AnimalView(long id, int x, int y, String kind, int energy, int age) {
            this(id, x, y, kind, energy, age, Activity.IDLE, 0, 0, 0, 0, 0);
        }
    }

    /** A completed logical encounter, whose intermediate poses may be animated by a viewer. */
    public enum HuntOutcome { PURSUIT, CAPTURE, ESCAPE }

    public record HuntEvent(HuntOutcome outcome, long wolfId, long deerId,
                            int wolfFromX, int wolfFromY, int wolfToX, int wolfToY,
                            AnimalView preyBefore, int preyToX, int preyToY) {
        public HuntEvent {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(preyBefore);
        }
    }

    /** Copy all published animal facts without retaining a reference to a mutable organism. */
    public static AnimalView animalView(Animal animal) {
        String kind = animal instanceof Herbivore ? "herbivore" : "predator";
        return new AnimalView(animal.id, animal.x, animal.y, kind, animal.energy, animal.age,
            animal.activity, animal.lastMeal, animal.metabolism, animal.reproductionSpent,
            animal.satietyTicks, animal.energySpent);
    }

    public record Stats(int plantPatches, int herbivores, int predators, int plantBiomass, int animalBodyUnits) {
        public int animalCount() {
            return herbivores + predators;
        }
    }

    public record Snapshot(
            int tick, int width, int height, String biome, String strategy, WeatherSystem.Weather weather,
            Stats stats, List<PlantView> plants, List<AnimalView> animals, List<String> messages,
            List<HuntEvent> hunts) {

        public Snapshot(int tick, int width, int height, String biome, String strategy,
                        WeatherSystem.Weather weather, Stats stats, List<PlantView> plants,
                        List<AnimalView> animals, List<String> messages) {
            this(tick, width, height, biome, strategy, weather, stats, plants, animals, messages, List.of());
        }

        public Snapshot {
            plants = List.copyOf(plants);
            animals = List.copyOf(animals);
            messages = List.copyOf(messages);
            hunts = List.copyOf(hunts);
        }
    }
}
