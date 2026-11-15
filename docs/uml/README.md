# UML diagrams

Digital Ecosystem uses nine design patterns for world creation, simulation, updates and reporting. The class diagrams show their Java classes and relationships. The sequence diagram follows one simulation step.

## Notation

- Class boxes separate the name, attributes and operations. `+` means public, `-` private, and `#` protected.
- `<<interface>>` identifies an interface; italic names identify abstract classes and operations. `<<record>>` identifies a Java record.
- A solid line with a hollow triangle points from a subclass to its superclass. A dashed line with a hollow triangle points from an implementation to its interface.
- A solid line with an open arrow shows a navigable association to a stored reference. A dashed open arrow shows a dependency; `<<create>>` marks object creation.
- Multiplicities apply to the indicated association end. They describe held references, rather than ownership of object lifetime.
- The sequence diagram uses lifelines, activation bars, solid synchronous calls and dashed replies.

`overview` connects the application components. `step-sequence` follows a successful step from the interface through the model to subscribers. Invalid weather stops a step before world mutation.

## Facade, Observer and Strategy contracts

`EcosystemFacade` owns its event bus. Clients use `subscribe()`, `unsubscribe()` and `observerErrors()`; they cannot publish through that internal instance. The `changing` guard covers each world command, snapshot construction and notification. Callbacks may read snapshots, request reports and change subscriptions. Nested world-changing commands are rejected.

`EventBus` matches subscribers by object identity and ignores a repeated subscription of the same instance. Each delivery uses a copied subscriber list. A standalone bus queues nested publications and drains them in FIFO order after the current snapshot reaches its subscribers. Callback exceptions do not stop delivery. Error history contains at most the newest 64 entries and is returned as an immutable list.

`setStrategy(ForagingStrategy)` accepts any implementation with a nonblank name that stays stable while installed. `setStrategy(String)` selects a built-in policy. The facade caches the name separately. The engine supplies immutable choices, requires a supplied target or `Optional.empty()`, and validates meal amounts in `checkedMeal()`. Built-in target scores use `long` arithmetic.

## Source mapping

| Source | Diagram participants |
|---|---|
| `Creation.java` | `WorldBuilder`, `WorldConfig`, `OrganismSpawner` and its three subclasses |
| `Biomes.java` | `BiomeFactory`, Meadow/Desert factories and their profile families |
| `EcosystemFacade.java`, `SimulationEngine.java` | Facade, strategy context and simulation coordination |
| `Behaviors.java` | `ForagingStrategy` and its three implementations |
| `Events.java`, `WorldPanel.java`, `App.java` | `EventBus`, `EcosystemObserver`, `WorldPanel`, `App.StatsPanel` and the log listener |
| `WeatherSystem.java` | `WeatherSource`, `LegacyWeatherAdapter`, `LegacyWeatherStation` and `NativeWeatherSource` |
| `Reporting.java`, `Model.java` | Report abstraction/renderers, visitors and visited organisms |

