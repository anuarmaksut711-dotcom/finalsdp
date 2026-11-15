package ecosystem;

import java.util.List;
import java.util.Random;

/** Runs the simulation checks without opening a window. */
public final class SelfTest {
    private static int passed;

    public static void main(String[] args) {
        passed = 0;
        test("Builder rejects invalid configuration", SelfTest::testBuilder);
        test("Abstract Factory keeps each family compatible and behavior differs", SelfTest::testBiomeFactories);
        test("Factory Method calls subclass creation and registers once", SelfTest::testFactoryMethod);
        test("Strategies select three different targets on same fixture", SelfTest::testStrategies);
        test("Strategy ties are deterministic y then x", SelfTest::testStrategyTie);
        test("Adapter converts Fahrenheit/inches and rejects malformed data", SelfTest::testAdapter);
        test("Native source and legacy adapter agree over the full weather cycle", SelfTest::testWeatherCycle);
        test("Observer subscribe/unsubscribe and failed listener isolation", SelfTest::testObserver);
        test("Published snapshots do not expose mutable collections", SelfTest::testSnapshot);
        test("Visitor double dispatch counts hand-built fixture", SelfTest::testVisitors);
        test("Bridge supports both reports with both renderers", SelfTest::testReportFormats);
        test("Two identical runs match every snapshot for 100 ticks", SelfTest::testRepeatableRun);
        test("Energy, capacity, position and population bounds for 600 ticks", SelfTest::testWorldLimits);
        test("Newborn acts next tick and reproduction conserves parent/child energy", SelfTest::testBirth);
        test("Consumed prey is removed before its later turn", SelfTest::testHunt);
        test("Feeding can rescue animal after metabolism; no-food animal dies", SelfTest::testFeeding);
        test("Population cap includes queued newborns", SelfTest::testBirthLimit);
        test("Weather validation fails before world mutation", SelfTest::testFailedWeather);
        test("Growth responds to rainfall and family profile", SelfTest::testPlantGrowth);
        test("A hungry predator explores when prey is outside its visible area", SelfTest::testPredatorSearch);
        test("Food reserves postpone, but do not disable, genuine starvation", SelfTest::testSearchStarvation);
        test("No-food herbivore explores; local feeding is genuine stationary grazing", SelfTest::testHerbivoreActions);
        test("A regrowing scrap does not trap a herbivore beside nourishing food", SelfTest::testNourishingFood);
        test("Scarce local food does not stop exploration toward unseen nourishment", SelfTest::testScarceFoodSearch);
        test("Exploration covers every cell without jumping or crossing boundaries", SelfTest::testExplorationCoverage);
        test("Exploration preserves the sustainable strategy's food reserve", SelfTest::testSustainableSearch);
        test("A full grass meal feeds a deer for two complete game days", SelfTest::testDeerSatiety);
        test("A fed wolf leaves prey alive for its complete food reserve", SelfTest::testWolfSatiety);
        test("Satiety at one expires without an extra free hungry turn", SelfTest::testSatietyBoundary);
        test("First-contact catch probability is reproducibly between 70 and 80 percent", SelfTest::testCaptureProbability);
        test("Escaping prey moves within bounds and cannot be attacked twice that tick", SelfTest::testEscapeGrace);
        test("A failed hunt continues a pursuit and changes route after losing the trail", SelfTest::testChaseChoices);
        test("A weather failure preserves the seeded random stream", SelfTest::testWeatherRandomAtomicity);
        test("Predator reproduction needs sufficient prey and transfers real energy", SelfTest::testPredatorBirthPressure);
        test("Typed hunt outcomes preserve the actual contact, victim and escape endpoints", SelfTest::testTypedHuntOutcomes);
        test("Pursuit events describe only visible prey and the hunter's real movement", SelfTest::testTypedPursuit);
        test("Hunt snapshots are immutable and same-tick refreshes preserve completed facts", SelfTest::testHuntSnapshot);
        passed += PatternChecks.run();
        System.out.println("SUCCESS: " + passed + " checks passed.");
    }

    private static void testBuilder() {
        rejects(() -> new Creation.WorldBuilder().size(0, 20).build());
        rejects(() -> new Creation.WorldBuilder().population(-1, 1).build());
        rejects(() -> new Creation.WorldBuilder().maxAnimals(2).build());
        rejects(() -> new Creation.WorldBuilder().biome("Ocean").build());

        Creation.WorldBuilder builder = new Creation.WorldBuilder();
        Creation.WorldConfig config = builder.build();
        builder.seed(9);

        check(config.seed() == 42, "Built configuration must be immutable");
    }

    private static void testBiomeFactories() {
        Biomes.BiomeFactory meadow = new Biomes.MeadowBiomeFactory();
        Biomes.BiomeFactory desert = new Biomes.DesertBiomeFactory();

        for (Biomes.BiomeFactory factory : List.of(meadow, desert)) {
            String biomeName = factory.name();
            check(biomeName.equals(factory.createPlantProfile().family()), "Plant family");
            check(biomeName.equals(factory.createHerbivoreProfile().family()), "Herbivore family");
            check(biomeName.equals(factory.createPredatorProfile().family()), "Predator family");
        }

        int meadowGrowth = meadow.createPlantProfile().growth();
        int desertGrowth = desert.createPlantProfile().growth();
        check(meadowGrowth != desertGrowth, "Growth must differ");

        check(meadow.createHerbivoreProfile().initialEnergy() != desert.createHerbivoreProfile().initialEnergy(),
            "The biome families must retain different initial herbivore reserves");
    }

    private static void testFactoryMethod() {
        Model.World world = emptyWorld().worldForTests();

        class CountingSpawner extends Creation.OrganismSpawner<Model.Herbivore> {
            int calls;

            @Override
            protected Model.Herbivore createOrganism(long id, int x, int y) {
                calls++;
                return new Model.Herbivore(id, x, y, world.biome.createHerbivoreProfile());
            }
        }

        CountingSpawner spawner = new CountingSpawner();
        Model.Herbivore herbivore = spawner.spawn(world, 2, 2);

        check(spawner.calls == 1, "Factory method must be called once");
        check(world.animals.size() == 1, "Spawn must register one animal");
        check(world.animals.get(herbivore.id) == herbivore, "Registered animal must be the one returned");

        rejects(() -> spawner.spawn(world, -1, 0));
        check(spawner.calls == 1, "Invalid spawn must not call factory");
    }

    private static void testStrategies() {
        List<Behaviors.Target> candidates = List.of(
            new Behaviors.Target(1, 0, 1, 8, 5),
            new Behaviors.Target(0, 3, 3, 6, 0),
            new Behaviors.Target(2, 0, 2, 14, 1)
        );
        Behaviors.ForagingStrategy nearest = new Behaviors.NearestFoodStrategy();
        Behaviors.ForagingStrategy safest = new Behaviors.SafestFoodStrategy();
        Behaviors.ForagingStrategy sustainable = new Behaviors.SustainableGrazingStrategy();

        check(nearest.choose(candidates).orElseThrow().x() == 1, "Nearest chooses A");
        check(safest.choose(candidates).orElseThrow().y() == 3, "Safest chooses B");
        check(sustainable.choose(candidates).orElseThrow().x() == 2, "Sustainable chooses C");

        List<Behaviors.Target> lowFood = List.of(new Behaviors.Target(0, 0, 0, 9, 0));
        check(sustainable.choose(lowFood).isEmpty(), "No reserve fallback");
        check(sustainable.meal(9) == 0, "Reserve at actual destination");
    }

    private static void testStrategyTie() {
        List<Behaviors.Target> candidates = List.of(
            new Behaviors.Target(3, 2, 1, 8, 0),
            new Behaviors.Target(1, 2, 1, 8, 0),
            new Behaviors.Target(2, 1, 1, 8, 0)
        );
        Behaviors.ForagingStrategy strategy = new Behaviors.NearestFoodStrategy();

        Behaviors.Target chosen = strategy.choose(candidates).orElseThrow();

        check(chosen.y() == 1, "Tie must use y");
    }

    private static void testAdapter() {
        WeatherSystem.LegacyWeatherStation station = new WeatherSystem.LegacyWeatherStation() {
            @Override
            public double[] sample(int tick) {
                return new double[]{68, 0.5};
            }
        };

        WeatherSystem.Weather reading = new WeatherSystem.LegacyWeatherAdapter(station).read(1);

        check(Math.abs(reading.temperatureC() - 20) < 1e-9, "Convert Fahrenheit to Celsius");
        check(Math.abs(reading.rainfallMm() - 12.7) < 1e-9, "Convert inches to millimetres");

        WeatherSystem.LegacyWeatherStation invalidStation = new WeatherSystem.LegacyWeatherStation() {
            @Override
            public double[] sample(int tick) {
                return new double[]{68};
            }
        };
        WeatherSystem.LegacyWeatherAdapter adapter = new WeatherSystem.LegacyWeatherAdapter(invalidStation);
        rejects(() -> adapter.read(0));
    }

    private static void testWeatherCycle() {
        WeatherSystem.WeatherSource nativeSource = new WeatherSystem.NativeWeatherSource();
        WeatherSystem.LegacyWeatherStation station = new WeatherSystem.LegacyWeatherStation();
        WeatherSystem.WeatherSource legacySource = new WeatherSystem.LegacyWeatherAdapter(station);

        for (int tick = 0; tick < 60; tick++) {
            WeatherSystem.Weather nativeReading = nativeSource.read(tick);
            WeatherSystem.Weather legacyReading = legacySource.read(tick);
            double temperatureDifference = Math.abs(nativeReading.temperatureC() - legacyReading.temperatureC());
            double rainfallDifference = Math.abs(nativeReading.rainfallMm() - legacyReading.rainfallMm());

            check(temperatureDifference < 1e-9 && rainfallDifference < 1e-9, "Weather mismatch at " + tick);
        }
    }

    private static void testObserver() {
        class CountingObserver implements Events.EcosystemObserver {
            int deliveries;

            @Override
            public void onSnapshot(Model.Snapshot snapshot) {
                deliveries++;
            }
        }

        EcosystemFacade facade = emptyWorld();
        CountingObserver observer = new CountingObserver();

        facade.subscribe(observer);
        facade.subscribe(observer);
        facade.step();
        check(observer.deliveries == 1, "No duplicate subscription");

        facade.unsubscribe(observer);
        facade.step();
        check(observer.deliveries == 1, "Unsubscribe");

        facade.subscribe(snapshot -> {
            throw new IllegalStateException("test listener");
        });
        facade.subscribe(observer);
        facade.step();
        check(observer.deliveries == 2, "One failed listener must not stop others");
        check(facade.observerErrors().size() == 1, "Failed delivery must be recorded");
    }

    private static void testSnapshot() {
        EcosystemFacade facade = emptyWorld();
        Model.Snapshot before = facade.snapshot();

        boolean immutable = false;
        try {
            before.plants().clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }

        check(immutable, "Snapshot list immutable");
        facade.step();
        check(before.tick() == 0, "Old snapshot unchanged");
    }

    private static void testVisitors() {
        Model.World world = emptyWorld().worldForTests();
        world.plant(0, 0).biomass = 7;
        new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 0, 0);
        new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 1, 0);

        Model.Stats stats = Reporting.collect(world);

        check(stats.plantPatches() == 25, "Visitor counts all plant patches");
        check(stats.herbivores() == 1 && stats.predators() == 1, "Visitor counts each animal type");
        check(stats.plantBiomass() == 7 && stats.animalBodyUnits() == 4, "Biomass visitor uses separate units");
    }

    private static void testReportFormats() {
        Model.Snapshot snapshot = emptyWorld().snapshot();
        List<Reporting.ReportRenderer> renderers = List.of(
            new Reporting.PlainTextRenderer(), new Reporting.CsvRenderer()
        );

        for (Reporting.ReportRenderer renderer : renderers) {
            String summary = new Reporting.SummaryReport(renderer).generate(snapshot);
            String detail = new Reporting.DetailedReport(renderer).generate(snapshot);
            check(!summary.isBlank(), "Summary");
            check(!detail.isBlank(), "Detailed");
        }

        Reporting.ReportDocument document = new Reporting.ReportDocument(
            "x", List.of("a"), List.of(List.of("a,\"b\"\nc"))
        );
        String csv = new Reporting.CsvRenderer().render(document);
        check(csv.contains("\"a,\"\"b\"\"\nc\""), "CSV escaping");
    }

    private static void testRepeatableRun() {
        EcosystemFacade first = new EcosystemFacade();
        EcosystemFacade second = new EcosystemFacade();
        Creation.WorldConfig config = new Creation.WorldBuilder().build();
        first.reset(config);
        second.reset(config);

        check(first.snapshot().equals(second.snapshot()), "Initial equality");
        for (int tick = 1; tick <= 100; tick++) {
            Model.Snapshot firstSnapshot = first.step();
            Model.Snapshot secondSnapshot = second.step();
            check(firstSnapshot.equals(secondSnapshot), "Mismatch tick " + tick);
        }
    }

    private static void testWorldLimits() {
        List<String> biomes = List.of("Meadow", "Desert");
        List<String> strategies = List.of("Nearest food", "Safest food", "Sustainable grazing");
        int populationCap = 100;

        for (String biome : biomes) {
            for (String strategy : strategies) {
                EcosystemFacade facade = new EcosystemFacade();
                Creation.WorldConfig config = new Creation.WorldBuilder()
                    .biome(biome)
                    .strategy(strategy)
                    .maxAnimals(populationCap)
                    .build();
                facade.reset(config);
                Model.World world = facade.worldForTests();

                for (int tick = 1; tick <= 100; tick++) {
                    Model.Snapshot snapshot = facade.step();
                    check(snapshot.stats().animalCount() <= populationCap, "Population cap");

                    for (Model.Plant plant : world.plants) {
                        check(plant.biomass >= 0 && plant.biomass <= plant.capacity, "Plant bound");
                    }
                    for (Model.Animal animal : world.animals.values()) {
                        check(animal.energy > 0 && animal.energy <= animal.maxEnergy, "Animal energy bound");
                        check(world.contains(animal.x, animal.y), "Animal position bound");
                        check(animal.satietyTicks >= 0 && animal.satietyTicks <= Model.FOOD_RESERVE_TICKS, "Food reserve bound");
                        check(animal.energySpent >= 0 && animal.lastMeal >= 0 && animal.reproductionSpent >= 0, "Turn accounting is nonnegative");
                    }
                }
            }
        }
    }

    private static void testBirth() {
        EcosystemFacade facade = emptyWorld();
        Model.World world = facade.worldForTests();
        fillPlants(world);
        Model.Herbivore parent = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 1, 1);
        parent.energy = parent.maxEnergy;
        parent.age = parent.minAge - 1;
        parent.satietyTicks = Model.TICKS_PER_DAY;
        int before = parent.energy;
        facade.step();
        check(world.animals.size() == 2, "An eligible parent with sufficient pasture should reproduce");
        Model.Animal child = world.animals.lastEntry().getValue();
        check(child.age == 0 && child.energy == parent.birthEnergy, "Newborn must not act on its birth turn");
        check(parent.energy + child.energy == before - parent.energySpent, "Reproduction must transfer energy, not create it");
        check(parent.cooldown == parent.reproductionCooldown, "Species-specific cooldown is set");
        check(parent.lastMeal == 0 && parent.reproductionSpent == parent.reproductionCost, "Fed parent records the separate birth cost");
        check(child.lastMeal == 0 && child.reproductionSpent == 0 && child.activity == Model.Activity.IDLE
                && child.energySpent == 0 && child.satietyTicks > 0,
            "A newborn receives a reserve but must not claim a turn or meal");
    }
    private static void testHunt() {
        Contact fixture = findContact(true);
        Model.World world = fixture.facade.worldForTests();
        check(!world.animals.containsKey(fixture.prey.id) && fixture.prey.age == 0,
            "A consumed prey must not take its later turn");
        check(fixture.wolf.activity == Model.Activity.FEEDING && fixture.wolf.lastMeal > 0,
            "Only a real catch may claim feeding");
        check(fixture.wolf.energy == Math.min(fixture.wolf.maxEnergy,
                fixture.beforeEnergy - fixture.wolf.energySpent + fixture.wolf.lastMeal),
            "A successful hunt restores the recorded meal, capped by capacity");
        check(fixture.wolf.satietyTicks == Model.FOOD_RESERVE_TICKS,
            "A successful catch provides the full reserve");
    }
    private static void testFeeding() {
        EcosystemFacade fed = emptyWorld();
        Model.World world = fed.worldForTests();
        Model.Herbivore herbivore = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 1, 1);
        herbivore.satietyTicks = 0;
        herbivore.energy = herbivore.metabolism;
        world.plant(1, 1).biomass = Behaviors.MEAL;
        fed.step();
        check(world.animals.containsKey(herbivore.id) && herbivore.energy > 0 && herbivore.lastMeal == Behaviors.MEAL,
            "An actual meal may rescue an animal after metabolic expenditure");
        EcosystemFacade starving = emptyWorld();
        Model.World dry = starving.worldForTests();
        Model.Herbivore hungry = new Creation.HerbivoreSpawner(dry.biome.createHerbivoreProfile()).spawn(dry, 1, 1);
        hungry.satietyTicks = 0;
        hungry.energy = hungry.metabolism;
        starving.step();
        check(dry.animals.isEmpty(), "Exhausted reserves with no food still cause death");
    }
    private static void testBirthLimit() {
        EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
        facade.reset(new Creation.WorldBuilder().size(5, 5).population(0, 0).maxAnimals(3).build());
        Model.World world = facade.worldForTests();
        fillPlants(world);
        for (int x = 0; x < 2; x++) {
            Model.Herbivore parent = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, x, 0);
            parent.energy = parent.maxEnergy;
            parent.age = parent.minAge;
            parent.satietyTicks = Model.TICKS_PER_DAY;
        }
        facade.step();
        check(world.animals.size() == 3, "Queued newborns must count toward the population cap");
    }
    private static void testFailedWeather() {
        EcosystemFacade facade = new EcosystemFacade(tick -> {
            throw new IllegalArgumentException("weather unavailable");
        });
        facade.reset(new Creation.WorldBuilder().build());
        Model.Snapshot before = facade.snapshot();

        rejects(facade::step);

        check(facade.snapshot().equals(before), "Failed weather read changed model");
    }

    private static void testPlantGrowth() {
        EcosystemFacade wetMeadow = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 1));
        wetMeadow.reset(new Creation.WorldBuilder().size(5, 5).population(0, 0).build());
        clearPlants(wetMeadow.worldForTests());
        wetMeadow.step();
        check(wetMeadow.worldForTests().plant(0, 0).biomass == 2, "Meadow wet growth");

        EcosystemFacade wetDesert = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 1));
        wetDesert.reset(new Creation.WorldBuilder().size(5, 5).population(0, 0).biome("Desert").build());
        clearPlants(wetDesert.worldForTests());
        wetDesert.step();
        check(wetDesert.worldForTests().plant(0, 0).biomass == 1, "Desert wet growth");

        EcosystemFacade dry = emptyWorld();
        dry.step();
        check(dry.worldForTests().plant(0, 0).biomass == 0, "No growth without rain");
    }

    private static void testPredatorSearch() {
        EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
        facade.reset(new Creation.WorldBuilder().size(20, 20).population(0, 0).build());
        Model.World world = facade.worldForTests(); clearPlants(world);
        Model.Predator wolf = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 0, 0);
        new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 18, 18);
        wolf.satietyTicks = 0;
        int energy = wolf.energy;
        facade.step();
        check(Math.abs(wolf.x) + Math.abs(wolf.y) == 1 && wolf.activity == Model.Activity.SEARCHING,
            "A hungry wolf without visible prey must explore rather than freeze");
        check(wolf.lastMeal == 0 && wolf.energy == energy - wolf.energySpent && wolf.energySpent == wolf.metabolism,
            "Searching invents no meal and records the actual metabolic cost");
    }
    private static void testSearchStarvation() {
        EcosystemFacade facade = emptyWorld(); Model.World world = facade.worldForTests();
        Model.Predator wolf = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 2, 2);
        int initialEnergy = wolf.energy, initialReserve = wolf.satietyTicks, turns = 0;
        while (world.animals.containsKey(wolf.id) && turns < 1000) {
            int reserve = wolf.satietyTicks;
            facade.step(); turns++;
            check(wolf.lastMeal == 0 && world.contains(wolf.x, wolf.y), "No food can be invented during a foodless run");
            check(wolf.energySpent == (reserve > 0 ? 0 : wolf.metabolism), "Reserves and metabolism are accounted separately");
        }
        int expected = initialReserve + (initialEnergy + wolf.metabolism - 1) / wolf.metabolism;
        check(turns == expected && turns > Model.TICKS_PER_DAY && world.animals.isEmpty(),
            "Food reserves postpone starvation by their actual duration; they do not make wolves immortal");
    }
    private static void testHerbivoreActions() {
        EcosystemFacade searching = emptyWorld(); Model.World dry = searching.worldForTests();
        Model.Herbivore hungry = new Creation.HerbivoreSpawner(dry.biome.createHerbivoreProfile()).spawn(dry, 2, 2);
        hungry.satietyTicks = 0; int before = hungry.energy;
        searching.step();
        check(Math.abs(hungry.x - 2) + Math.abs(hungry.y - 2) == 1 && hungry.activity == Model.Activity.SEARCHING,
            "A hungry herbivore with no food must explore");
        check(hungry.lastMeal == 0 && hungry.energy == before - hungry.energySpent && hungry.energySpent == hungry.metabolism,
            "A searching herbivore pays its real metabolic cost");
        EcosystemFacade grazing = emptyWorld(); Model.World meadow = grazing.worldForTests();
        Model.Herbivore fed = new Creation.HerbivoreSpawner(meadow.biome.createHerbivoreProfile()).spawn(meadow, 2, 2);
        fed.satietyTicks = 0; before = fed.energy; meadow.plant(2, 2).biomass = 12;
        grazing.step();
        check(fed.x == 2 && fed.y == 2 && fed.activity == Model.Activity.GRAZING,
            "Eating the current patch is real stationary grazing");
        check(fed.lastMeal == Behaviors.MEAL && meadow.plant(2, 2).biomass == 12 - Behaviors.MEAL
                && fed.energy == Math.min(fed.maxEnergy, before - fed.energySpent + fed.lastMeal),
            "Grazing consumes actual grass and restores bounded energy");
    }
    private static void testNourishingFood() {
        EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, .5));
        facade.reset(new Creation.WorldBuilder().size(5, 5).population(0, 0).build());
        Model.World world = facade.worldForTests();
        for (Model.Plant plant : world.plants) plant.biomass = 20;
        world.plant(2, 2).biomass = 0;
        Model.Herbivore herbivore = crumbDeer(world, 2, 2);
        herbivore.satietyTicks = 0;
        herbivore.energy = 5;
        facade.step();
        check(Math.abs(herbivore.x - 2) + Math.abs(herbivore.y - 2) == 1,
            "A single regrown unit must not pin the animal next to a nourishing patch");
        check(herbivore.energy == 7 && herbivore.lastMeal == 4 && herbivore.activity == Model.Activity.FORAGING,
            "The animal reaches nearby food rather than slowly starving on scraps");
        for (int i = 0; i < 6; i++) facade.step();
        check(world.animals.containsKey(herbivore.id) && herbivore.energy > 0, "Regrowth cannot recreate the original five-turn starvation trap");
    }

    private static void testExplorationCoverage() {
        for (int width : new int[]{5, 6}) {
            EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
            facade.reset(new Creation.WorldBuilder().size(width, 5).population(0, 0).build());
            Model.World world = facade.worldForTests();
            for (int direction : new int[]{-1, 1}) {
                Model.Predator animal = new Model.Predator(77, 2, 2, world.biome.createPredatorProfile());
                animal.explorationDirection = direction;
                boolean[] visited = new boolean[width * 5];
                visited[animal.y * width + animal.x] = true;
                for (int i = 0; i < width * 5 * 2; i++) {
                    int x = animal.x, y = animal.y;
                    Behaviors.explore(world, animal);
                    check(world.contains(animal.x, animal.y) && Math.abs(animal.x - x) + Math.abs(animal.y - y) == 1,
                        "A search step cannot jump, wrap through an edge or stay motionless");
                    visited[animal.y * width + animal.x] = true;
                }
                for (boolean seen : visited) check(seen, "Foodless search must eventually cover the whole board");
            }
        }
    }

    private static void testScarceFoodSearch() {
        EcosystemFacade facade = emptyWorld();
        Model.World world = facade.worldForTests();
        for (Model.Plant plant : world.plants) plant.biomass = 1;
        world.plant(4, 4).biomass = 20;
        Model.Herbivore herbivore = crumbDeer(world, 2, 2);
        herbivore.satietyTicks = 0;
        herbivore.energy = 5;
        facade.step();
        check(Math.abs(herbivore.x - 2) + Math.abs(herbivore.y - 2) == 1
                && herbivore.activity == Model.Activity.SEARCHING,
            "Insufficient scraps must not stop the animal from exploring");
        check(herbivore.lastMeal == 1 && herbivore.energy == 4 && world.plant(herbivore.x, herbivore.y).biomass == 0
                && herbivore.satietyTicks == Model.FOOD_RESERVE_TICKS / Behaviors.MEAL,
            "Searching still consumes permitted food at the destination: 5 - 2 + 1");
        check(world.plant(2, 2).biomass == 1, "No meal is taken remotely from the abandoned patch");
        boolean reachedFood = false;
        for (int i = 0; i < Model.FOOD_RESERVE_TICKS * 3 && world.animals.containsKey(herbivore.id); i++) {
            facade.step();
            if (herbivore.x == 4 && herbivore.y == 4 && herbivore.lastMeal == Behaviors.MEAL) { reachedFood = true; break; }
        }
        check(reachedFood && world.animals.containsKey(herbivore.id) && herbivore.energy > 0,
            "After using partial food reserves, exploration must discover nearby unseen nourishment");
    }

    private static void testSustainableSearch() {
        EcosystemFacade facade = emptyWorld();
        Model.World world = facade.worldForTests();
        facade.setStrategy("Sustainable grazing");
        for (Model.Plant plant : world.plants) plant.biomass = 9;
        Model.Herbivore herbivore = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 2, 2);
        herbivore.satietyTicks = 0;
        int before = herbivore.energy;
        facade.step();
        check(herbivore.activity == Model.Activity.SEARCHING && herbivore.lastMeal == 0 && herbivore.energy == before - herbivore.energySpent,
            "Exploration must not bypass the selected meal policy");
        for (Model.Plant plant : world.plants) check(plant.biomass == 9, "No sustainable meal is allowed below ten units");
    }

    private static void testDeerSatiety() {
        EcosystemFacade facade = emptyWorld(); Model.World world = facade.worldForTests();
        Model.Herbivore deer = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 2, 2);
        deer.satietyTicks = 0;
        world.plant(2, 2).biomass = Behaviors.MEAL;
        facade.step();
        check(deer.satietyTicks == Model.FOOD_RESERVE_TICKS && deer.lastMeal == Behaviors.MEAL,
            "A full permitted grass meal must provide two days of food reserve");
        check(Model.FOOD_RESERVE_TICKS == Model.TICKS_PER_DAY * 2, "The reserve really measures two game days");
        int energy = deer.energy;
        clearPlants(world);
        for (int i = 0; i < Model.FOOD_RESERVE_TICKS; i++) {
            facade.step();
            check(deer.satietyTicks == Model.FOOD_RESERVE_TICKS - i - 1 && deer.energy == energy,
                "A reserve protects every one of its complete turns");
            check(deer.lastMeal == 0 && deer.energySpent == 0
                    && (deer.activity == Model.Activity.RESTING || deer.activity == Model.Activity.ROAMING),
                "A fed deer must neither consume more grass nor pretend it is hungry");
        }
        facade.step();
        check(deer.satietyTicks == 0 && deer.energySpent == deer.metabolism && deer.energy == energy - deer.metabolism,
            "Metabolic expenditure resumes after the final protected turn");
    }

    private static void testWolfSatiety() {
        Contact fixture = findContact(true); Model.World world = fixture.facade.worldForTests();
        Model.Herbivore second = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, fixture.wolf.x, fixture.wolf.y);
        int energy = fixture.wolf.energy;
        for (int i = 0; i < Model.FOOD_RESERVE_TICKS; i++) {
            second.x = fixture.wolf.x; second.y = fixture.wolf.y;
            second.satietyTicks = Model.FOOD_RESERVE_TICKS;
            fixture.facade.step();
            check(world.animals.containsKey(second.id) && fixture.wolf.lastMeal == 0,
                "A fed wolf must not kill even when another deer is in its cell");
            check(fixture.wolf.energy == energy && fixture.wolf.energySpent == 0,
                "Satiety must be reflected by actual zero hungry expenditure");
            check(fixture.wolf.activity == Model.Activity.RESTING || fixture.wolf.activity == Model.Activity.ROAMING,
                "A fed wolf rests or moves calmly, rather than reporting a hunt");
        }
        check(fixture.wolf.satietyTicks == 0, "Two days must expire, rather than becoming permanent immunity to hunger");
        second.x = fixture.wolf.x; second.y = fixture.wolf.y;
        world.random.setSeed(fixture.facade.worldForTests().config.seed());
        fixture.facade.step();
        check(!world.animals.containsKey(second.id) && fixture.wolf.activity == Model.Activity.FEEDING,
            "A wolf may hunt again once, but not before, its food reserve is exhausted");
    }

    private static void testSatietyBoundary() {
        for (boolean predator : new boolean[]{false, true}) {
            EcosystemFacade facade = emptyWorld(); Model.World world = facade.worldForTests();
            Model.Animal animal = predator
                ? new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 2, 2)
                : new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 2, 2);
            animal.satietyTicks = 1; int energy = animal.energy;
            facade.step();
            check(animal.satietyTicks == 0 && animal.energySpent == 0 && animal.energy == energy,
                "The last stored-food turn must still be protected");
            facade.step();
            check(animal.satietyTicks == 0 && animal.energySpent == animal.metabolism && animal.energy == energy - animal.metabolism,
                "A zero reserve must not grant an extra free hungry turn");
        }
    }

    private static void testCaptureProbability() {
        Random seeds = new Random(0x51a7e75L);
        int caught = 0, escaped = 0, trials = 2000;
        for (int i = 0; i < trials; i++) {
            Contact fixture = contactFixture(seeds.nextLong(), 1);
            fixture.facade.step();
            boolean survived = fixture.facade.worldForTests().animals.containsKey(fixture.prey.id);
            long attempts = fixture.facade.snapshot().messages().stream()
                .filter(message -> message.startsWith("Escaped:") || message.contains(" ate #")).count();
            check(attempts == 1, "A first contact must have exactly one catch or escape outcome");
            if (survived) {
                escaped++;
                check(fixture.prey.activity == Model.Activity.FLEEING && fixture.prey.lastFledTick == 0,
                    "An escape on the very first world tick must be possible");
            } else caught++;
        }
        double rate = caught / (double) trials;
        check(rate >= .70 && rate <= .80 && escaped > 0 && caught > 0,
            "First-contact catch rate must be near 75%, observed " + rate);
        System.out.printf(java.util.Locale.ROOT, "Capture sample: %d/%d = %.2f%%; escapes=%d%n", caught, trials, rate * 100, escaped);
    }

    private static void testEscapeGrace() {
        Contact failure = findContact(false);
        long seed = failure.facade.worldForTests().config.seed();
        Contact pair = contactFixture(seed, 2); Model.World world = pair.facade.worldForTests();
        int startX = pair.prey.x, startY = pair.prey.y;
        pair.facade.step();
        check(world.animals.containsKey(pair.prey.id) && pair.prey.activity == Model.Activity.FLEEING,
            "A second wolf must not instantly invalidate a successful escape");
        check(world.contains(pair.prey.x, pair.prey.y)
                && Math.abs(pair.prey.x - startX) + Math.abs(pair.prey.y - startY) == 1,
            "Panic adds one bounded adjacent step, without another normal turn movement");
        check(pair.facade.snapshot().messages().stream().filter(message -> message.startsWith("Escaped:")).count() == 1,
            "Other wolves must not re-roll against the protected deer in that tick");
        Contact success = findContact(true);
        Contact expired = contactFixture(success.facade.worldForTests().config.seed(), 1);
        Model.World expiredWorld = expired.facade.worldForTests();
        expiredWorld.tick = 7; expired.prey.lastFledTick = 6;
        expired.facade.step();
        check(!expiredWorld.animals.containsKey(expired.prey.id),
            "Escape protection is limited to its actual tick, rather than permanent invulnerability");
    }

    private static void testChaseChoices() {
        Contact fixture = findContact(false); Model.World world = fixture.facade.worldForTests();
        check(fixture.wolf.chaseTargetId == fixture.prey.id && fixture.wolf.chaseTicks > 0,
            "A failed contact should retain a usable pursuit memory");
        int fromX = fixture.wolf.x, fromY = fixture.wolf.y;
        fixture.prey.x = 4; fixture.prey.y = 4;
        // Move the prey off the last sightline; a remembered route is still a legitimate next action.
        boolean pursued = false, changedRoute = false;
        for (int i = 0; i < 2; i++) {
            fixture.prey.x = 4; fixture.prey.y = 4;
            fixture.prey.satietyTicks = Model.FOOD_RESERVE_TICKS;
            fixture.facade.step();
            pursued |= fixture.wolf.activity == Model.Activity.HUNTING;
            changedRoute |= fixture.wolf.activity == Model.Activity.SEARCHING;
            check(Math.abs(fixture.wolf.x - fromX) + Math.abs(fixture.wolf.y - fromY) <= 1,
                "A pursuer cannot gain an unlimited number of moves in one turn");
            fromX = fixture.wolf.x; fromY = fixture.wolf.y;
            if (!world.animals.containsKey(fixture.prey.id)) break;
        }
        check(pursued, "A failed hunt must be able to continue pursuing its target");
        // A deer can subsequently leave the world in a fixture; the wolf must then drop the stale target.
        world.animals.remove(fixture.prey.id);
        fixture.wolf.satietyTicks = 0;
        fixture.wolf.energy = fixture.wolf.maxEnergy; // Isolate trail expiry from the separate starvation regression.
        for (int i = 0; i < SimulationEngine.CHASE_MEMORY_TICKS + 2; i++) {
            fixture.facade.step();
            changedRoute |= fixture.wolf.activity == Model.Activity.SEARCHING;
        }
        check(changedRoute && fixture.wolf.chaseTargetId == -1 && fixture.wolf.chaseTicks == 0,
            "A lost trail must eventually expire and allow a different search route; activity=" + fixture.wolf.activity
                + ", target=" + fixture.wolf.chaseTargetId + ", memory=" + fixture.wolf.chaseTicks + ", energy=" + fixture.wolf.energy
                + ", alive=" + world.animals.containsKey(fixture.wolf.id));
    }

    private static void testWeatherRandomAtomicity() {
        int[] failures = {1};
        EcosystemFacade interrupted = new EcosystemFacade(tick -> {
            if (failures[0]-- > 0) throw new IllegalArgumentException("weather unavailable");
            return new WeatherSystem.Weather(20, 0);
        });
        EcosystemFacade control = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
        Creation.WorldConfig config = new Creation.WorldBuilder().seed(99173).build();
        interrupted.reset(config); control.reset(config);
        for (Model.Animal animal : interrupted.worldForTests().animals.values()) animal.satietyTicks = 0;
        for (Model.Animal animal : control.worldForTests().animals.values()) animal.satietyTicks = 0;
        Model.Snapshot before = interrupted.snapshot();
        rejects(interrupted::step);
        check(interrupted.snapshot().equals(before), "The failed reading must not publish or mutate a snapshot");
        check(interrupted.worldForTests().random.nextLong() == control.worldForTests().random.nextLong(),
            "The failed weather call must not consume the seeded random stream");
        for (int i = 0; i < 80; i++) check(interrupted.step().equals(control.step()),
            "Subsequent hunts after a weather error must match the uninterrupted control");
        EcosystemFacade nullWeather = new EcosystemFacade(tick -> null);
        nullWeather.reset(config); before = nullWeather.snapshot();
        rejects(nullWeather::step);
        check(nullWeather.snapshot().equals(before), "A null weather reading must also leave the world untouched");
    }

    private static void testPredatorBirthPressure() {
        for (int preyCount : new int[]{11, 12}) {
            EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
            facade.reset(new Creation.WorldBuilder().size(20, 20).population(0, 0).build());
            Model.World world = facade.worldForTests(); fillPlants(world);
            Model.Predator parent = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 0, 0);
            parent.age = parent.minAge - 1; parent.energy = parent.maxEnergy; parent.satietyTicks = Model.TICKS_PER_DAY;
            for (int i = 0; i < preyCount; i++) {
                Model.Herbivore deer = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 10 + i % 5, 10 + i / 5);
                deer.cooldown = 1000;
            }
            int before = parent.energy;
            facade.step();
            long wolves = world.animals.values().stream().filter(animal -> animal instanceof Model.Predator).count();
            check(wolves == (preyCount < 12 ? 1 : 2), "Predator births require enough prey to support them");
            if (preyCount >= 12) {
                Model.Animal child = world.animals.lastEntry().getValue();
                check(child instanceof Model.Predator && child.age == 0 && child.energy == parent.birthEnergy,
                    "A supported predator offspring is queued and takes no birth-turn action");
                check(parent.energy + child.energy == before - parent.energySpent && parent.reproductionSpent == parent.reproductionCost,
                    "Predator reproduction transfers rather than invents energy");
            }
        }
    }

    private static void testTypedHuntOutcomes() {
        for (boolean captured : new boolean[]{true, false}) {
            Contact contact = findContact(captured);
            Model.Snapshot snapshot = contact.facade.snapshot();
            check(snapshot.hunts().size() == 1, "One contact must publish exactly one typed encounter");
            Model.HuntEvent event = snapshot.hunts().get(0);
            check(event.outcome() == (captured ? Model.HuntOutcome.CAPTURE : Model.HuntOutcome.ESCAPE),
                "The typed outcome must agree with the actual survival of the deer");
            check(event.wolfId() == contact.wolf.id && event.deerId() == contact.prey.id,
                "Encounter identities must refer to their actual participants");
            check(event.wolfFromX() == 2 && event.wolfFromY() == 2
                    && event.wolfToX() == contact.wolf.x && event.wolfToY() == contact.wolf.y,
                "A renderer must receive the hunter's actual movement endpoints");
            check(event.preyBefore().id() == contact.prey.id && event.preyBefore().x() == 2
                    && event.preyBefore().y() == 2 && event.preyBefore().age() == 0,
                "A caught deer must retain an immutable pre-contact sprite view after removal");
            if (captured) {
                check(snapshot.animals().stream().noneMatch(animal -> animal.id() == event.deerId()),
                    "Capture removes the logical animal; its animation is presentation only");
                check(event.preyToX() == 2 && event.preyToY() == 2, "Capture settles at the contact point");
            } else {
                check(event.preyToX() == contact.prey.x && event.preyToY() == contact.prey.y
                        && Math.abs(event.preyToX() - event.preyBefore().x())
                            + Math.abs(event.preyToY() - event.preyBefore().y()) == 1,
                    "Escape animation must use the same single adjacent step as the simulation");
            }
            int recordedEnergy = event.preyBefore().energy();
            contact.prey.energy = 1;
            check(event.preyBefore().energy() == recordedEnergy, "Hunt playback must not retain mutable animal references");
        }
    }

    private static void testTypedPursuit() {
        EcosystemFacade facade = emptyWorld(); Model.World world = facade.worldForTests();
        Model.Predator wolf = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 0, 2);
        wolf.satietyTicks = 0;
        Model.Herbivore prey = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 3, 2);
        facade.step();
        check(facade.snapshot().hunts().size() == 1, "Visible prey should publish a pursuit before contact");
        Model.HuntEvent event = facade.snapshot().hunts().get(0);
        check(event.outcome() == Model.HuntOutcome.PURSUIT && event.wolfId() == wolf.id && event.deerId() == prey.id,
            "Pursuit must identify the selected visible target without inventing a catch");
        check(event.wolfFromX() == 0 && event.wolfFromY() == 2 && event.wolfToX() == 1 && event.wolfToY() == 2,
            "A pursuit event cannot grant extra movement");
        check(world.animals.containsKey(prey.id), "Pursuit is not a capture outcome");

        EcosystemFacade unseen = emptyWorld(); world = unseen.worldForTests();
        wolf = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 0, 0);
        wolf.satietyTicks = 0;
        new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 4, 4);
        unseen.step();
        check(unseen.snapshot().hunts().isEmpty(), "Exploration must not expose the identity of unseen prey to an animator");
    }

    private static void testHuntSnapshot() {
        Contact contact = findContact(false); Model.Snapshot completed = contact.facade.snapshot();
        List<Model.HuntEvent> recorded = completed.hunts();
        try { recorded.clear(); throw new AssertionError("Hunt lists must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        contact.facade.setStrategy("Safest food");
        check(contact.facade.snapshot().tick() == completed.tick() && contact.facade.snapshot().hunts().equals(recorded),
            "Changing a control during the same tick must preserve, not regenerate, completed encounter facts");
        contact.wolf.satietyTicks = Model.FOOD_RESERVE_TICKS;
        contact.facade.step();
        check(contact.facade.snapshot().hunts().isEmpty() && completed.hunts().equals(recorded) && recorded.size() == 1,
            "A later non-hunt turn must clear current events without mutating an earlier snapshot");
        Model.Snapshot legacy = new Model.Snapshot(completed.tick(), completed.width(), completed.height(), completed.biome(),
            completed.strategy(), completed.weather(), completed.stats(), completed.plants(), completed.animals(), completed.messages());
        check(legacy.hunts().isEmpty(), "The legacy snapshot constructor must remain compatible");
        contact.facade.reset(new Creation.WorldBuilder().build());
        check(contact.facade.snapshot().hunts().isEmpty(), "A new world must not replay encounters from the previous world");
    }

    private record Contact(EcosystemFacade facade, Model.Predator wolf, Model.Herbivore prey, int beforeEnergy) { }

    private static Contact contactFixture(long seed, int wolves) {
        EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
        facade.reset(new Creation.WorldBuilder().size(5, 5).seed(seed).population(0, 0).build());
        Model.World world = facade.worldForTests(); clearPlants(world);
        Model.Predator first = null;
        for (int i = 0; i < wolves; i++) {
            Model.Predator wolf = new Creation.PredatorSpawner(world.biome.createPredatorProfile()).spawn(world, 2, 2);
            wolf.satietyTicks = 0; wolf.cooldown = 1000;
            if (first == null) first = wolf;
        }
        Model.Herbivore prey = new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).spawn(world, 2, 2);
        prey.energy = prey.maxEnergy; prey.cooldown = 1000;
        return new Contact(facade, first, prey, first.energy);
    }

    private static Contact findContact(boolean success) {
        Random seeds = new Random(0x6e7a113L);
        for (int i = 0; i < 200; i++) {
            Contact fixture = contactFixture(seeds.nextLong(), 1);
            fixture.facade.step();
            if (success == !fixture.facade.worldForTests().animals.containsKey(fixture.prey.id)) return fixture;
        }
        throw new AssertionError("No deterministic contact fixture found for success=" + success);
    }

    private static Model.Herbivore crumbDeer(Model.World world, int x, int y) {
        Biomes.HerbivoreProfile base = world.biome.createHerbivoreProfile();
        Biomes.HerbivoreProfile profile = new Biomes.HerbivoreProfile() {
            public String family() { return base.family(); }
            public int initialEnergy() { return base.initialEnergy(); }
            public int maxEnergy() { return base.maxEnergy(); }
            public int metabolism() { return 2; }
            public int threshold() { return base.threshold(); }
        };
        return new Creation.HerbivoreSpawner(profile).spawn(world, x, y);
    }

    private static void fillPlants(Model.World world) {
        for (Model.Plant plant : world.plants) plant.biomass = plant.capacity;
    }
    private static EcosystemFacade emptyWorld() {
        EcosystemFacade facade = new EcosystemFacade(tick -> new WeatherSystem.Weather(20, 0));
        facade.reset(new Creation.WorldBuilder().size(5, 5).population(0, 0).build());
        clearPlants(facade.worldForTests());
        return facade;
    }

    private static void clearPlants(Model.World world) {
        for (Model.Plant plant : world.plants) {
            plant.biomass = 0;
        }
    }

    private static void rejects(Runnable work) {
        try {
            work.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void test(String name, Runnable work) {
        work.run();
        passed++;
        System.out.println("PASS " + name);
    }
}


