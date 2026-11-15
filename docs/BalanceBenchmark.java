import ecosystem.*;
import java.util.*;

/** Reproducible coexistence benchmark. Default stops after first extinction: no species can return. */
public final class BalanceBenchmark {
    public static void main(String[] args) {
        String seeds = args.length > 0 ? args[0] : "0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,42";
        int horizon = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        String biome = args.length > 2 ? args[2] : "Meadow";
        String strategy = args.length > 3 ? args[3] : "Nearest food";
        boolean full = args.length > 4 && Boolean.parseBoolean(args[4]);
        int at100 = 0, at500 = 0, at1000 = 0, total = 0;
        long started = System.nanoTime();
        System.out.println("seed,biome,strategy,stop_tick,first_extinction,deer_extinction,wolf_extinction,deer_100,wolf_100,deer_500,wolf_500,deer_1000,wolf_1000,deer_min,deer_max,wolf_min,wolf_max,successful_hunts,escapes,capture_rate");
        for (String seedText : seeds.split(",")) {
            long seed = Long.parseLong(seedText.strip());
            EcosystemFacade facade = new EcosystemFacade();
            facade.reset(new Creation.WorldBuilder().seed(seed).biome(biome).strategy(strategy).build());
            int[] deerAt = {-1,-1,-1}, wolfAt = {-1,-1,-1};
            int deerExt = -1, wolfExt = -1, firstExt = -1, hunts = 0, escapes = 0;
            int deerMin = Integer.MAX_VALUE, wolfMin = Integer.MAX_VALUE, deerMax = 0, wolfMax = 0;
            Model.Snapshot snapshot = facade.snapshot();
            int tick = 0;
            for (;;) {
                int d = snapshot.stats().herbivores(), w = snapshot.stats().predators();
                for (String message : snapshot.messages()) {
                    if (message.startsWith("Predator #") && message.contains(" ate #")) hunts++;
                    if (message.startsWith("Escaped:")) escapes++;
                }
                deerMin = Math.min(deerMin,d); deerMax = Math.max(deerMax,d);
                wolfMin = Math.min(wolfMin,w); wolfMax = Math.max(wolfMax,w);
                if (d == 0 && deerExt < 0) deerExt = tick;
                if (w == 0 && wolfExt < 0) wolfExt = tick;
                if ((d == 0 || w == 0) && firstExt < 0) firstExt = tick;
                if (tick == 100) { deerAt[0] = d; wolfAt[0] = w; }
                if (tick == 500) { deerAt[1] = d; wolfAt[1] = w; }
                if (tick == 1000) { deerAt[2] = d; wolfAt[2] = w; }
                if (tick >= horizon || (d == 0 && w == 0) || (!full && firstExt >= 0)) break;
                snapshot = facade.step(); tick++;
            }
            // Censored values remain -1; survival at a checkpoint is exact even if run stopped early.
            if (horizon >= 100 && (firstExt < 0 || firstExt > 100)) at100++;
            if (horizon >= 500 && (firstExt < 0 || firstExt > 500)) at500++;
            if (horizon >= 1000 && (firstExt < 0 || firstExt > 1000)) at1000++;
            total++;
            System.out.printf(Locale.ROOT,"%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.5f%n",seed,biome,strategy,tick,firstExt,deerExt,wolfExt,deerAt[0],wolfAt[0],deerAt[1],wolfAt[1],deerAt[2],wolfAt[2],deerMin,deerMax,wolfMin,wolfMax,hunts,escapes,(hunts + escapes) == 0 ? 0.0 : hunts / (double) (hunts + escapes));
            System.out.flush();
        }
        System.err.printf(Locale.ROOT,"SUMMARY seeds=%d both_alive_100=%d both_alive_500=%d both_alive_1000=%d elapsed_seconds=%.3f%n",total,horizon >= 100 ? at100 : -1,horizon >= 500 ? at500 : -1,horizon >= 1000 ? at1000 : -1,(System.nanoTime()-started)/1e9);
    }
}


