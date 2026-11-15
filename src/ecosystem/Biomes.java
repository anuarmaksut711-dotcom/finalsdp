package ecosystem;

public final class Biomes {
    private Biomes() {
    }

    public interface PlantProfile {
        String family();
        int capacity();
        int growth();
    }

    public interface HerbivoreProfile {
        String family();
        int initialEnergy();
        int maxEnergy();
        int metabolism();
        int threshold();
    }

    public interface PredatorProfile {
        String family();
        int initialEnergy();
        int maxEnergy();
        int metabolism();
        int threshold();
    }

    /** Abstract Factory: creates a matching set of profiles for one biome. */
    public interface BiomeFactory {
        String name();
        PlantProfile createPlantProfile();
        HerbivoreProfile createHerbivoreProfile();
        PredatorProfile createPredatorProfile();
    }

    public static BiomeFactory named(String name) {
        return switch (name) {
            case "Meadow" -> new MeadowBiomeFactory();
            case "Desert" -> new DesertBiomeFactory();
            default -> throw new IllegalArgumentException("Unknown biome: " + name);
        };
    }

    public static final class MeadowBiomeFactory implements BiomeFactory {
        @Override
        public String name() {
            return "Meadow";
        }

        @Override
        public PlantProfile createPlantProfile() {
            return new MeadowPlantProfile();
        }

        @Override
        public HerbivoreProfile createHerbivoreProfile() {
            return new MeadowHerbivoreProfile();
        }

        @Override
        public PredatorProfile createPredatorProfile() {
            return new MeadowPredatorProfile();
        }
    }

    public record MeadowPlantProfile() implements PlantProfile {
        @Override
        public String family() {
            return "Meadow";
        }

        @Override
        public int capacity() {
            return 20;
        }

        @Override
        public int growth() {
            return 2;
        }
    }

    public record MeadowHerbivoreProfile() implements HerbivoreProfile {
        @Override
        public String family() {
            return "Meadow";
        }

        @Override
        public int initialEnergy() {
            return 26;
        }

        @Override
        public int maxEnergy() {
            return 40;
        }

        @Override
        public int metabolism() {
            return 1;
        }

        @Override
        public int threshold() {
            return 24;
        }
    }

    public record MeadowPredatorProfile() implements PredatorProfile {
        @Override
        public String family() {
            return "Meadow";
        }

        @Override
        public int initialEnergy() {
            return 30;
        }

        @Override
        public int maxEnergy() {
            return 60;
        }

        @Override
        public int metabolism() {
            return 3;
        }

        @Override
        public int threshold() {
            return 48;
        }
    }

    public static final class DesertBiomeFactory implements BiomeFactory {
        @Override
        public String name() {
            return "Desert";
        }

        @Override
        public PlantProfile createPlantProfile() {
            return new DesertPlantProfile();
        }

        @Override
        public HerbivoreProfile createHerbivoreProfile() {
            return new DesertHerbivoreProfile();
        }

        @Override
        public PredatorProfile createPredatorProfile() {
            return new DesertPredatorProfile();
        }
    }

    public record DesertPlantProfile() implements PlantProfile {
        @Override
        public String family() {
            return "Desert";
        }

        @Override
        public int capacity() {
            return 12;
        }

        @Override
        public int growth() {
            return 1;
        }
    }

    public record DesertHerbivoreProfile() implements HerbivoreProfile {
        @Override
        public String family() {
            return "Desert";
        }

        @Override
        public int initialEnergy() {
            return 22;
        }

        @Override
        public int maxEnergy() {
            return 36;
        }

        @Override
        public int metabolism() {
            return 1;
        }

        @Override
        public int threshold() {
            return 24;
        }
    }

    public record DesertPredatorProfile() implements PredatorProfile {
        @Override
        public String family() {
            return "Desert";
        }

        @Override
        public int initialEnergy() {
            return 27;
        }

        @Override
        public int maxEnergy() {
            return 54;
        }

        @Override
        public int metabolism() {
            return 2;
        }

        @Override
        public int threshold() {
            return 44;
        }
    }
}

