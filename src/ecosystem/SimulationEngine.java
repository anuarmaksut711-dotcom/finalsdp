package ecosystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** One deterministic, seeded turn. Food reserves measure time; energy is the hungry fallback. */
public final class SimulationEngine {
    public static final double CAPTURE_CHANCE = .75;
    public static final int CHASE_MEMORY_TICKS = 4;
    private static final int ENERGY_PER_PREY = 18;

    public List<String> advance(Model.World world, Behaviors.ForagingStrategy strategy,
                                WeatherSystem.WeatherSource weather) {
        Objects.requireNonNull(world, "World is required.");
        Objects.requireNonNull(strategy, "Foraging strategy is required.");
        Objects.requireNonNull(weather, "Weather source is required.");
        // No mutation or random draw is allowed before a successful weather reading.
        WeatherSystem.Weather reading = weather.read(world.tick);
        if (reading == null) throw new IllegalArgumentException("Weather source returned no reading.");
        world.weather = reading;
        world.hunts.clear();
        growPlants(world);
        List<String> messages = new ArrayList<>();
        List<Long> turnOrder = new ArrayList<>(world.animals.keySet());
        List<Model.Animal> newborns = new ArrayList<>();
        for (long id : turnOrder) {
            Model.Animal animal = world.animals.get(id);
            if (animal == null) continue;
            animal.age++;
            if (animal.cooldown > 0) animal.cooldown--;
            animal.lastMeal = 0;
            animal.reproductionSpent = 0;
            animal.energySpent = 0;
            boolean wasFed = animal.satietyTicks > 0;
            if (wasFed) animal.satietyTicks--;
            else { animal.energySpent = animal.metabolism; animal.energy -= animal.energySpent; }
            if (animal.lastFledTick == world.tick) animal.activity = Model.Activity.FLEEING;
            else if (wasFed) calmTurn(world, animal);
            else if (animal instanceof Model.Herbivore herbivore) feedHerbivore(world, strategy, herbivore);
            else if (animal instanceof Model.Predator predator) hunt(world, predator, messages);
            if (animal.energy <= 0) {
                world.animals.remove(id);
                messages.add("Died: " + animal.getClass().getSimpleName() + " #" + id);
                continue;
            }
            if (canReproduce(world, animal, newborns)) {
                Model.Animal child = createOffspring(world, animal);
                newborns.add(child);
                messages.add("Born: " + child.getClass().getSimpleName() + " #" + child.id);
            }
        }
        for (Model.Animal child : newborns) world.animals.put(child.id, child);
        world.tick++;
        messages.add(String.format(Locale.ROOT, "Tick %d: rain %.1f mm", world.tick, world.weather.rainfallMm()));
        return messages;
    }

    private void calmTurn(Model.World world, Model.Animal animal) {
        animal.chaseTicks = 0; animal.chaseTargetId = -1;
        if ((world.tick + animal.id) % 4 == 0) {
            Behaviors.explore(world, animal);
            animal.activity = Model.Activity.ROAMING;
        } else animal.activity = Model.Activity.RESTING;
    }

    private void growPlants(Model.World world) {
        double rainfall = world.weather.rainfallMm();
        for (Model.Plant plant : world.plants) {
            int growth = rainfall >= .999999 ? plant.growth : rainfall > 0 ? 1 : 0;
            plant.biomass = Math.min(plant.capacity, plant.biomass + growth);
        }
    }

    private boolean canReproduce(Model.World world, Model.Animal animal, List<Model.Animal> newborns) {
        if (world.animals.size() + newborns.size() >= world.config.maxAnimals()
                || animal.age < animal.minAge || animal.cooldown != 0
                || animal.energy < animal.reproductionThreshold || animal.satietyTicks == 0) return false;
        int deer = 0, wolves = 0;
        for (Model.Animal present : world.animals.values()) {
            if (present instanceof Model.Herbivore) deer++; else wolves++;
        }
        for (Model.Animal child : newborns) {
            if (child instanceof Model.Herbivore) deer++; else wolves++;
        }
        int area = world.config.width() * world.config.height();
        if (animal instanceof Model.Herbivore) {
            int carrying = Math.max(2, area / (world.biome.name().equals("Desert") ? 4 : 3));
            if (deer >= carrying) return false;
            int food = 0;
            for (Model.Plant plant : world.plants) food += plant.biomass;
            return food >= area * 2;
        }
        return wolves < Math.max(1, area / 50) && deer >= wolves * 12;
    }

    private Model.Animal createOffspring(Model.World world, Model.Animal parent) {
        Model.Animal child = parent instanceof Model.Herbivore
            ? new Creation.HerbivoreSpawner(world.biome.createHerbivoreProfile()).createPending(world, parent.x, parent.y)
            : new Creation.PredatorSpawner(world.biome.createPredatorProfile()).createPending(world, parent.x, parent.y);
        child.energy = parent.birthEnergy;
        parent.energy -= parent.reproductionCost;
        parent.reproductionSpent = parent.reproductionCost;
        parent.cooldown = parent.reproductionCooldown;
        return child;
    }

    private void feedHerbivore(Model.World world, Behaviors.ForagingStrategy strategy, Model.Herbivore animal) {
        List<Behaviors.Target> candidates = Behaviors.candidates(world, animal);
        List<Behaviors.Target> sustaining = candidates.stream()
            .filter(target -> checkedMeal(strategy, target.food()) >= animal.metabolism).toList();
        List<Behaviors.Target> choices = List.copyOf(sustaining.isEmpty() ? candidates : sustaining);
        Optional<Behaviors.Target> chosen = strategy.choose(choices);
        if (chosen == null) {
            throw new IllegalStateException("Foraging strategy must return an Optional target.");
        }
        if (chosen.isEmpty()) {
            Behaviors.explore(world, animal); eatActualCell(world, strategy, animal);
            animal.activity = Model.Activity.SEARCHING; return;
        }
        Behaviors.Target target = chosen.get();
        if (!choices.contains(target)) {
            throw new IllegalStateException("Foraging strategy must choose a supplied target.");
        }
        boolean moving = animal.x != target.x() || animal.y != target.y();
        if (!moving && checkedMeal(strategy, target.food()) < animal.metabolism) {
            Behaviors.explore(world, animal); eatActualCell(world, strategy, animal);
            animal.activity = Model.Activity.SEARCHING; return;
        }
        Behaviors.moveOne(animal, target.x(), target.y());
        int meal = eatActualCell(world, strategy, animal);
        animal.activity = !moving && meal > 0 ? Model.Activity.GRAZING : Model.Activity.FORAGING;
    }

    private int eatActualCell(Model.World world, Behaviors.ForagingStrategy strategy, Model.Herbivore animal) {
        Model.Plant plant = world.plant(animal.x, animal.y);
        int meal = checkedMeal(strategy, plant.biomass);
        plant.biomass -= meal;
        animal.energy = Math.min(animal.maxEnergy, animal.energy + meal);
        animal.lastMeal = meal;
        if (meal > 0) animal.satietyTicks = Model.FOOD_RESERVE_TICKS * meal / Behaviors.MEAL;
        return meal;
    }

    private int checkedMeal(Behaviors.ForagingStrategy strategy, int biomass) {
        int meal = strategy.meal(biomass);
        if (meal < 0 || meal > Math.min(Behaviors.MEAL, biomass)) {
            throw new IllegalStateException("Foraging strategy returned an invalid meal.");
        }
        return meal;
    }

    private void hunt(Model.World world, Model.Predator wolf, List<String> messages) {
        Model.Herbivore target = nearestHerbivore(world, wolf);
        if (target == null) {
            if (wolf.chaseTicks > 0 && (wolf.x != wolf.chaseX || wolf.y != wolf.chaseY)) {
                Behaviors.moveOne(wolf, wolf.chaseX, wolf.chaseY);
                wolf.chaseTicks--; wolf.activity = Model.Activity.HUNTING;
            } else {
                wolf.chaseTargetId = -1; wolf.chaseTicks = 0;
                Behaviors.explore(world, wolf); wolf.activity = Model.Activity.SEARCHING;
            }
            return;
        }
        int wolfFromX = wolf.x, wolfFromY = wolf.y;
        Model.AnimalView preyBefore = Model.animalView(target);
        wolf.chaseTargetId = target.id; wolf.chaseTicks = CHASE_MEMORY_TICKS;
        wolf.chaseX = target.x; wolf.chaseY = target.y;
        wolf.activity = Model.Activity.HUNTING;
        Behaviors.moveOne(wolf, target.x, target.y);
        if (wolf.x != target.x || wolf.y != target.y) {
            world.hunts.add(new Model.HuntEvent(Model.HuntOutcome.PURSUIT, wolf.id, target.id,
                wolfFromX, wolfFromY, wolf.x, wolf.y, preyBefore, target.x, target.y));
            return;
        }
        // Exactly one roll/attempt for this wolf. An escaped deer is protected for this whole tick.
        if (world.random.nextDouble() < CAPTURE_CHANCE) {
            world.animals.remove(target.id);
            wolf.energy = Math.min(wolf.maxEnergy, wolf.energy + ENERGY_PER_PREY);
            wolf.lastMeal = ENERGY_PER_PREY;
            wolf.satietyTicks = Model.FOOD_RESERVE_TICKS;
            wolf.activity = Model.Activity.FEEDING;
            wolf.chaseTargetId = -1; wolf.chaseTicks = 0;
            world.hunts.add(new Model.HuntEvent(Model.HuntOutcome.CAPTURE, wolf.id, target.id,
                wolfFromX, wolfFromY, wolf.x, wolf.y, preyBefore, target.x, target.y));
            messages.add("Predator #" + wolf.id + " ate #" + target.id);
        } else {
            flee(world, target, wolf);
            wolf.chaseX = target.x; wolf.chaseY = target.y;
            world.hunts.add(new Model.HuntEvent(Model.HuntOutcome.ESCAPE, wolf.id, target.id,
                wolfFromX, wolfFromY, wolf.x, wolf.y, preyBefore, target.x, target.y));
            messages.add("Escaped: Herbivore #" + target.id + " from Predator #" + wolf.id);
        }
    }

    private void flee(Model.World world, Model.Herbivore deer, Model.Predator wolf) {
        List<int[]> choices = new ArrayList<>(); int best = -1;
        for (int[] delta : new int[][]{{1,0},{0,1},{-1,0},{0,-1}}) {
            int x = deer.x + delta[0], y = deer.y + delta[1];
            if (!world.contains(x, y)) continue;
            int distance = Math.abs(x - wolf.x) + Math.abs(y - wolf.y);
            if (distance > best) { choices.clear(); best = distance; }
            if (distance == best) choices.add(new int[]{x, y});
        }
        int[] destination = choices.get(world.random.nextInt(choices.size()));
        deer.x = destination[0]; deer.y = destination[1];
        deer.lastFledTick = world.tick; deer.activity = Model.Activity.FLEEING;
    }

    private Model.Herbivore nearestHerbivore(Model.World world, Model.Predator wolf) {
        Model.Herbivore nearest = null; int best = Behaviors.VISION + 1;
        for (Model.Animal animal : world.animals.values()) {
            if (!(animal instanceof Model.Herbivore herbivore) || animal.lastFledTick == world.tick) continue;
            int distance = Math.abs(animal.x - wolf.x) + Math.abs(animal.y - wolf.y);
            if (distance <= Behaviors.VISION && wolf.chaseTicks > 0 && animal.id == wolf.chaseTargetId) return herbivore;
            if (distance < best) { nearest = herbivore; best = distance; }
        }
        return nearest;
    }
}
