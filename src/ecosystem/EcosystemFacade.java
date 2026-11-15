package ecosystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Facade for the single-threaded application. Clients use commands and immutable
 * snapshots; model mutation and event publication remain inside this boundary.
 */
public final class EcosystemFacade {
    private final SimulationEngine engine = new SimulationEngine();
    private final Events.EventBus events = new Events.EventBus();

    private Model.World world;
    private Behaviors.ForagingStrategy strategy;
    private String strategyName;
    private WeatherSystem.WeatherSource weather;
    private Model.Snapshot latest;
    private boolean changing;

    public EcosystemFacade() {
        this(new WeatherSystem.LegacyWeatherAdapter(new WeatherSystem.LegacyWeatherStation()));
    }

    public EcosystemFacade(WeatherSystem.WeatherSource weather) {
        this.weather = Objects.requireNonNull(weather);
    }

    public void subscribe(Events.EcosystemObserver observer) {
        events.subscribe(observer);
    }

    public void unsubscribe(Events.EcosystemObserver observer) {
        events.unsubscribe(observer);
    }

    public List<String> observerErrors() {
        return events.deliveryErrors();
    }

    public void setWeatherSource(WeatherSystem.WeatherSource source) {
        requireWorld();
        applyChange(() -> {
            weather = Objects.requireNonNull(source, "Weather source is required.");
            refresh(List.of("Weather source changed: " + source.getClass().getSimpleName()));
        });
    }

    Model.World worldForTests() {
        return world;
    }

    public void reset(Creation.WorldConfig config) {
        applyChange(() -> createWorld(config));
    }

    private void createWorld(Creation.WorldConfig config) {
        Objects.requireNonNull(config);
        // A caller can construct WorldConfig directly, so validate it here too.
        Creation.WorldConfig validated = new Creation.WorldBuilder()
                .size(config.width(), config.height())
                .seed(config.seed())
                .biome(config.biome())
                .population(config.initialHerbivores(), config.initialPredators())
                .maxAnimals(config.maxAnimals())
                .strategy(config.strategy())
                .build();

        Biomes.BiomeFactory biome = Biomes.named(validated.biome());
        Model.World fresh = new Model.World(validated, biome);
        Random random = new Random(validated.seed());

        Creation.PlantSpawner plants = new Creation.PlantSpawner(biome.createPlantProfile());
        for (int y = 0; y < validated.height(); y++) {
            for (int x = 0; x < validated.width(); x++) {
                Model.Plant plant = plants.spawn(fresh, x, y);
                plant.biomass = random.nextInt(plant.capacity + 1);
            }
        }

        Creation.HerbivoreSpawner herbivores = new Creation.HerbivoreSpawner(biome.createHerbivoreProfile());
        for (int i = 0; i < validated.initialHerbivores(); i++) {
            int x = random.nextInt(validated.width());
            int y = random.nextInt(validated.height());
            herbivores.spawn(fresh, x, y);
        }

        Creation.PredatorSpawner predators = new Creation.PredatorSpawner(biome.createPredatorProfile());
        for (int i = 0; i < validated.initialPredators(); i++) {
            int x = random.nextInt(validated.width());
            int y = random.nextInt(validated.height());
            predators.spawn(fresh, x, y);
        }

        world = fresh;
        strategy = Behaviors.named(validated.strategy());
        strategyName = strategy.name();
        refresh(List.of("World reset: " + validated.biome() + ", seed=" + validated.seed()));
    }

    public Model.Snapshot snapshot() {
        requireWorld();
        return latest;
    }

    public void setStrategy(String name) {
        requireWorld();
        setStrategy(Behaviors.named(name));
    }

    /** Installs any implementation of the strategy contract in the current world. */
    public void setStrategy(Behaviors.ForagingStrategy replacement) {
        requireWorld();
        applyChange(() -> {
            Objects.requireNonNull(replacement, "Foraging strategy is required.");
            String name = replacement.name();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Strategy name must not be blank.");
            }
            strategy = replacement;
            strategyName = name;
            refresh(List.of("Strategy changed: " + name));
        });
    }

    public void addHerbivores(int count) {
        requireWorld();
        applyChange(() -> spawnHerbivores(count));
    }

    private void spawnHerbivores(int count) {
        if (count < 1 || count > 50) {
            throw new IllegalArgumentException("Add 1..50 herbivores.");
        }

        int spacesLeft = world.config.maxAnimals() - world.animals.size();
        int countToAdd = Math.min(count, spacesLeft);
        int width = world.config.width();
        int height = world.config.height();
        Creation.HerbivoreSpawner spawner = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile());
        for (int i = 0; i < countToAdd; i++) {
            long id = world.nextId;
            int x = (int) (id % width);
            int y = (int) ((id / width) % height);
            spawner.spawn(world, x, y);
        }
        refresh(List.of("Added herbivores: " + countToAdd));
    }

    public String report(boolean detailed, Reporting.ReportRenderer renderer) {
        requireWorld();
        if (detailed) {
            return new Reporting.DetailedReport(renderer).generate(latest);
        }
        return new Reporting.SummaryReport(renderer).generate(latest);
    }

    public Model.Snapshot step() {
        requireWorld();
        applyChange(() -> refresh(engine.advance(world, strategy, weather)));
        return latest;
    }

    private void refresh(List<String> messages) {
        List<Model.PlantView> plants = new ArrayList<>();
        for (Model.Plant plant : world.plants) {
            plants.add(new Model.PlantView(plant.x, plant.y, plant.biomass, plant.capacity));
        }

        List<Model.AnimalView> animals = new ArrayList<>();
        for (Model.Animal animal : world.animals.values()) {
            animals.add(Model.animalView(animal));
        }

        Model.Stats stats = Reporting.collect(world);
        latest = new Model.Snapshot(world.tick, world.config.width(), world.config.height(), world.config.biome(),
                strategyName, world.weather, stats, plants, animals, messages, world.hunts);
        events.publish(latest);
    }

    private void applyChange(Runnable action) {
        if (changing) {
            throw new IllegalStateException("World changes cannot be nested. Observers may read snapshots during notification.");
        }
        changing = true;
        try {
            action.run();
        } finally {
            changing = false;
        }
    }

    private void requireWorld() {
        if (world == null) {
            throw new IllegalStateException("Build/reset a world first.");
        }
    }
}
