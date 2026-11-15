package ecosystem;

public final class Creation {
    private Creation() {
    }

    public record WorldConfig(
        int width,
        int height,
        long seed,
        String biome,
        int initialHerbivores,
        int initialPredators,
        int maxAnimals,
        String strategy
    ) {}

    /** Builder for a validated world configuration. */
    public static final class WorldBuilder {
        private int width = 20;
        private int height = 20;
        private long seed = 42;
        private String biome = "Meadow";
        private int initialHerbivores = 30;
        private int initialPredators = 5;
        private int maxAnimals = 500;
        private String strategy = "Nearest food";

        public WorldBuilder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public WorldBuilder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public WorldBuilder biome(String biome) {
            this.biome = biome;
            return this;
        }

        public WorldBuilder population(int herbivores, int predators) {
            initialHerbivores = herbivores;
            initialPredators = predators;
            return this;
        }

        public WorldBuilder maxAnimals(int maxAnimals) {
            this.maxAnimals = maxAnimals;
            return this;
        }

        public WorldBuilder strategy(String strategy) {
            this.strategy = strategy;
            return this;
        }

        public WorldConfig build() {
            if (width < 5 || width > 60 || height < 5 || height > 60) {
                throw new IllegalArgumentException("World size must be 5..60.");
            }

            long initialPopulation = (long) initialHerbivores + initialPredators;
            if (initialHerbivores < 0 || initialPredators < 0 || maxAnimals < 1
                    || maxAnimals > 2000 || initialPopulation > maxAnimals) {
                throw new IllegalArgumentException("Population must be nonnegative and not exceed cap (1..2000).");
            }

            Biomes.named(biome);
            Behaviors.named(strategy);
            return new WorldConfig(
                width, height, seed, biome, initialHerbivores, initialPredators, maxAnimals, strategy
            );
        }
    }

    /** Factory Method: each spawner chooses the type of organism to create. */
    public abstract static class OrganismSpawner<T extends Model.Organism> {
        public final T spawn(Model.World world, int x, int y) {
            T organism = createPending(world, x, y);
            if (organism instanceof Model.Plant plant) {
                world.plants.add(plant);
            } else if (organism instanceof Model.Animal animal) {
                world.animals.put(animal.id, animal);
            }
            return organism;
        }

        // The engine can queue newborns without adding them to the world during the current tick.
        public final T createPending(Model.World world, int x, int y) {
            if (!world.contains(x, y)) {
                throw new IllegalArgumentException("Coordinates outside world.");
            }

            long id = world.nextId++;
            return createOrganism(id, x, y);
        }

        protected abstract T createOrganism(long id, int x, int y);
    }

    public static final class PlantSpawner extends OrganismSpawner<Model.Plant> {
        private final Biomes.PlantProfile profile;

        public PlantSpawner(Biomes.PlantProfile profile) {
            this.profile = profile;
        }

        @Override
        protected Model.Plant createOrganism(long id, int x, int y) {
            return new Model.Plant(id, x, y, profile);
        }
    }

    public static final class HerbivoreSpawner extends OrganismSpawner<Model.Herbivore> {
        private final Biomes.HerbivoreProfile profile;

        public HerbivoreSpawner(Biomes.HerbivoreProfile profile) {
            this.profile = profile;
        }

        @Override
        protected Model.Herbivore createOrganism(long id, int x, int y) {
            return new Model.Herbivore(id, x, y, profile);
        }
    }

    public static final class PredatorSpawner extends OrganismSpawner<Model.Predator> {
        private final Biomes.PredatorProfile profile;

        public PredatorSpawner(Biomes.PredatorProfile profile) {
            this.profile = profile;
        }

        @Override
        protected Model.Predator createOrganism(long id, int x, int y) {
            return new Model.Predator(id, x, y, profile);
        }
    }
}
