package ecosystem;

public final class WeatherSystem {
    private static final double MM_PER_INCH = 25.4;

    private WeatherSystem() {
    }

    public record Weather(double temperatureC, double rainfallMm) {
        public Weather {
            if (!Double.isFinite(temperatureC) || !Double.isFinite(rainfallMm) || rainfallMm < 0) {
                throw new IllegalArgumentException("Invalid weather reading.");
            }
        }
    }

    public interface WeatherSource {
        Weather read(int tick);
    }

    public static final class NativeWeatherSource implements WeatherSource {
        @Override
        public Weather read(int tick) {
            double temperatureC = 20 + tick % 5;
            return new Weather(temperatureC, simulatedRainfallMm(tick));
        }
    }

    /** Simulates an older station that returns Fahrenheit and inches in an array. */
    public static class LegacyWeatherStation {
        public double[] sample(int step) {
            double temperatureF = 68 + (step % 5) * 1.8;
            double rainfallInches = simulatedRainfallMm(step) / MM_PER_INCH;
            return new double[] {temperatureF, rainfallInches};
        }
    }

    /** Adapter: converts a station reading to the units and type the engine expects. */
    public static final class LegacyWeatherAdapter implements WeatherSource {
        private final LegacyWeatherStation station;

        public LegacyWeatherAdapter(LegacyWeatherStation station) {
            this.station = station;
        }

        @Override
        public Weather read(int tick) {
            double[] reading = station.sample(tick);
            if (reading == null || reading.length != 2) {
                throw new IllegalArgumentException("Legacy station must return [Fahrenheit, inches].");
            }

            double temperatureC = (reading[0] - 32) * 5 / 9;
            double rainfallMm = reading[1] * MM_PER_INCH;
            return new Weather(temperatureC, rainfallMm);
        }
    }

    // Both local weather sources use the same 60-tick rain cycle.
    private static double simulatedRainfallMm(int tick) {
        int phase = (tick / 20) % 3;
        return switch (phase) {
            case 0 -> 1;
            case 1 -> 0;
            default -> 2;
        };
    }
}
