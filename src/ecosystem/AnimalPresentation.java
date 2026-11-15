package ecosystem;

import java.util.Locale;

/** Game wording for facts published by the simulation, never inferred from animation. */
final class AnimalPresentation {
    private AnimalPresentation() { }

    static String action(Model.AnimalView animal) {
        return switch (animal.activity()) {
            case IDLE -> "Waiting for first step";
            case GRAZING -> "Grazing";
            case FORAGING -> "Moving toward grass";
            case SEARCHING -> animal.kind().equals("herbivore")
                ? "Searching for grass" : "Searching for prey";
            case HUNTING -> "Chasing a deer";
            case FEEDING -> "Caught prey and fed";
            case RESTING -> "Fed and resting";
            case ROAMING -> "Fed and roaming";
            case FLEEING -> "Escaped from a wolf";
        };
    }

    static String energyNote(Model.AnimalView animal) {
        if (animal.satietyTicks() > 0) return "Food reserve is used before energy.";
        if (animal.activity() == Model.Activity.IDLE) return "Acts on the next step.";
        if (animal.energy() <= animal.metabolism() * 3) return "Low energy. Needs food.";
        if (animal.activity() == Model.Activity.GRAZING) return "Food is here. No movement needed.";
        if (animal.reproductionSpent() > 0) return "Some energy was spent on offspring.";
        return "Without food, energy falls each step.";
    }

    static String days(int ticks) {
        return String.format(Locale.ROOT, "%.1f days", ticks / (double) Model.TICKS_PER_DAY);
    }

    static String satiety(Model.AnimalView animal) {
        return animal.satietyTicks() > 0 ? "Fed for " + days(animal.satietyTicks()) : "Hungry. Seeking food";
    }

    static int maxEnergy(String biome, Model.AnimalView animal) {
        Biomes.BiomeFactory family = Biomes.named(biome);
        return animal.kind().equals("herbivore") ? family.createHerbivoreProfile().maxEnergy()
            : family.createPredatorProfile().maxEnergy();
    }
}
