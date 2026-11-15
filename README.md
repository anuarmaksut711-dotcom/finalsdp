# Digital Ecosystem

A Java 17 desktop simulation of plants, deer and wolves, built with Swing and Java2D.

## Run

JDK 17 or newer is required.

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

Linux or macOS:

```sh
sh run.sh
```

The launch scripts build the application before opening it. Alternatively, open `src` in an IDE and run `ecosystem.App.main`.

## Controls

- **Play / Pause** and **Step** control time.
- **Ecosystem** opens population charts and strategy selection.
- **New world** selects the biome, seed and starting populations.
- Selecting an animal shows its action, energy and age.
- **Settings** contains weather, observation and report options.
- Report export supports Summary or Detailed content in Text or CSV.

## Project contributions

| Member | Pattern responsibilities | Primary code |
|---|---|---|
| Maksut Anuar | Facade, Strategy, Observer | `EcosystemFacade.java`, `Behaviors.java`, `Events.java` |
| Mukhitden Nurdaulet | Builder, Factory Method, Abstract Factory | `Creation.java`, `Biomes.java` |
| Balymbetova Dilnaz | Adapter, Bridge, Visitor | `WeatherSystem.java`, `Reporting.java` |
