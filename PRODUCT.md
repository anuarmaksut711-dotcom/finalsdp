# Product Specification

## Purpose and platform

Digital Ecosystem is a desktop sandbox for observing interactions between plants, deer and wolves. It also provides executable examples of nine design patterns in a single application.

The application requires JDK 17 or newer and uses Swing and Java2D. It runs locally without network services or external runtime libraries. Windows, Linux and macOS launch scripts are included.

## User functions

- Create a Meadow or Desert world using a seed and initial animal populations.
- Run, pause, change playback speed or advance one tick.
- Add five deer while respecting the animal cap.
- Switch between three herbivore foraging strategies.
- Inspect animal activity, energy, age, food reserve and turn accounting.
- View population history and plant biomass.
- Pan, zoom and reset the isometric camera.
- Show the grid, disable movement animation or enable the event log.
- Switch between the legacy weather adapter and native weather source.
- Export summary or detailed reports as text or CSV.
- Run model checks, export examples or render screenshots from the command line.

## World configuration

The default configuration is 20 by 20 cells, seed 42, 30 deer, 5 wolves, Meadow, Nearest food and a maximum of 500 animals. Each cell contains one plant patch. Multiple animals can occupy the same cell. Boundaries are closed.

The builder accepts widths and heights from 5 through 60, any `long` seed, nonnegative initial populations and a cap from 1 through 2000. Initial population must not exceed the cap. The interface exposes seeds from -1000000 through 1000000, 0 through 450 initial deer and 0 through 50 initial wolves.

## Biome profiles

| Property | Meadow | Desert |
|---|---:|---:|
| Plant capacity | 20 | 12 |
| Plant growth with at least 1 mm rain | 2 | 1 |
| Deer initial / maximum energy | 26 / 40 | 22 / 36 |
| Hungry deer metabolism | 1 | 1 |
| Deer reproduction energy threshold | 24 | 24 |
| Wolf initial / maximum energy | 30 / 60 | 27 / 54 |
| Hungry wolf metabolism | 3 | 2 |
| Wolf reproduction energy threshold | 48 | 44 |

## Time, food and movement

One day is 12 ticks. All new animals start with 24 reserve ticks. A full grass meal consumes 4 plant units and restores 24 reserve ticks; a partial meal restores 6 ticks per unit. A captured deer gives a wolf 18 energy and 24 reserve ticks. Feeding replaces the reserve; it does not accumulate an unlimited reserve.

Fed animals spend one reserve tick and no metabolic energy. They rest or take a calm exploratory step. Hungry animals spend metabolic energy and seek food. Feeding may rescue an animal after that expenditure; an animal with nonpositive energy after its action is removed.

Ordinary movement is one adjacent cell per animal turn, with a vision radius of 3 in Manhattan distance. Herbivores use the selected strategy. A wolf has a 75% chance to capture deer at contact. A failed capture causes one adjacent escape reaction and protects the deer from further attacks for that tick. Pursuit memory lasts up to 4 search turns.

## Reproduction

| Property | Deer | Wolves |
|---|---:|---:|
| Minimum age | 36 ticks | 144 ticks |
| Reproduction cooldown | 72 ticks | 240 ticks |
| Energy transferred to offspring | 12 | 18 |

Birth also requires positive food reserve and a free animal slot. Deer need total plant biomass of at least twice the world area and a population below the biome's carrying limit: `max(2, area / 3)` for Meadow or `max(2, area / 4)` for Desert. Wolves require at least 12 deer per current wolf and a wolf count below `max(1, area / 50)`. Integer divisions round down. Counts include queued offspring. Newborns act on the next tick.

## Weather and reporting

Both local weather sources produce the same 60-tick cycle: 20 ticks with 1 mm rain, 20 dry ticks, then 20 ticks with 2 mm rain. Temperature cycles from 20 through 24 degrees Celsius. Rain affects plant growth; temperature is displayed only. The adapter converts Fahrenheit and inches to application units.

Summary reports contain tick, biome, strategy, weather, populations and biomass totals. Detailed reports list plant and animal state. Formats are text and CSV. Reports describe a snapshot and cannot resume a simulation.

## Scope and limits

The model uses abstract units rather than physical measurements. Animal body units count deer as 1 and wolves as 3. Population survival depends on configuration and actions. The interface presents deer and wolf artwork for herbivore and predator model classes. Water, border trees and rocks are decorative. The application has no score, victory condition, multiplayer, undo or save-game loading.
