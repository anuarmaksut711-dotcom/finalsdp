package ecosystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Observer: listeners can be added and removed independently of the publisher. */
public final class Events {
    private Events() {
    }

    @FunctionalInterface
    public interface EcosystemObserver {
        void onSnapshot(Model.Snapshot snapshot);
    }

    /** Synchronous publisher for one application thread; nested publications stay in order. */
    public static final class EventBus {
        public static final int MAX_DELIVERY_ERRORS = 64;

        private final List<EcosystemObserver> observers = new ArrayList<>();
        private final List<String> deliveryErrors = new ArrayList<>();
        private final Deque<Model.Snapshot> pending = new ArrayDeque<>();
        private boolean publishing;

        public void subscribe(EcosystemObserver observer) {
            Objects.requireNonNull(observer, "observer");
            if (observers.stream().noneMatch(existing -> existing == observer)) {
                observers.add(observer);
            }
        }

        public void unsubscribe(EcosystemObserver observer) {
            Objects.requireNonNull(observer, "observer");
            observers.removeIf(existing -> existing == observer);
        }

        public void publish(Model.Snapshot snapshot) {
            pending.addLast(Objects.requireNonNull(snapshot, "snapshot"));
            if (publishing) {
                return;
            }

            publishing = true;
            try {
                while (!pending.isEmpty()) {
                    deliver(pending.removeFirst());
                }
            } finally {
                publishing = false;
                // Interrupted delivery must not leak queued updates into a later publication.
                pending.clear();
            }
        }

        private void deliver(Model.Snapshot snapshot) {
            // Changes made by callbacks apply to later snapshots, not the current delivery.
            for (EcosystemObserver observer : List.copyOf(observers)) {
                try {
                    observer.onSnapshot(snapshot);
                } catch (RuntimeException failure) {
                    if (deliveryErrors.size() == MAX_DELIVERY_ERRORS) {
                        deliveryErrors.remove(0);
                    }
                    deliveryErrors.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
            }
        }

        public List<String> deliveryErrors() {
            return List.copyOf(deliveryErrors);
        }
    }
}
