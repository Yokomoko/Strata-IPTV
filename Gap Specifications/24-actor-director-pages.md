# 24 - Actor / Director Pages

## Current State

The movie detail screen (`MovieDetailScreen.kt`) displays cast as a plain text string: `movie.cast` is rendered in a `Text` composable under a "Cast" label with `maxLines = 2`. The cast data is a comma-separated string stored in `MovieEntity.cast` (populated from `TmdbCredits.cast`, top names by `order`). The show detail screen (`ShowDetailScreen.kt`) does not display cast at all -- `SeriesEntity.cast` exists in the schema but is not rendered. Cast members are not clickable or interactive in any way. There are no director fields on any entity.

## Gap

Netflix, Disney+, and Prime Video make cast and crew members clickable. Tapping an actor opens a filmography page showing all titles featuring that person. This is a major discovery vector -- users who enjoy an actor's performance in one title want to find their other work. Strata treats cast as dead text, missing this entire discovery pathway.

## User Story

As a Strata user viewing the detail page for "Dune: Part Two", I want to tap on "Timothee Chalamet" in the cast list and see all of his other movies and shows available in my library so that I can discover more content featuring actors I enjoy.

## Acceptance Criteria

1. Cast members on the movie detail screen are rendered as individually focusable, clickable chips (not plain text).
2. Cast members on the show detail screen are also rendered as clickable chips.
3. Clicking a cast member opens a Person detail screen showing:
   - The person's name prominently displayed.
   - A grid/rail of all movies and shows in the local library that list this person in their `cast` field.
   - If available, a photo from TMDB (requires storing `profile_path` during enrichment).
4. The person screen works entirely from local data (no network call required on navigation; TMDB person lookup is optional enrichment).
5. If the person has titles in both movies and shows, they are displayed in separate sections.
6. D-pad navigation works correctly: Left/Right across titles, Back returns to the detail screen.

## Technical Approach

1. **Schema**: Add `director` TEXT column to `MovieEntity` and `SeriesEntity`. During enrichment, extract from `TmdbCredits` (the crew array, filtering by `job == "Director"`). Optionally add a `persons` table for caching TMDB person data (id, name, profile_path), but this is not required for MVP.

2. **Cast parsing**: The existing comma-separated `cast` string is sufficient for matching. On the Person screen, query:
   ```sql
   SELECT * FROM movies WHERE hidden = 0 AND cast LIKE '%' || :name || '%'
   ```
   Apply Kotlin-side filtering to avoid false positives (e.g., "Chris" matching "Chris Evans" and "Chris Pratt").

3. **UI - Detail screen**: Replace the plain `Text` cast display with a `FlowRow` (or `TvLazyRow`) of `Surface` chips, each clickable. Use `ClickableSurfaceDefaults` with `SurfaceRaised`/`SurfaceFloat` focus colors.

4. **Navigation**: Add `DetailRoute.Person(name: String)` to `AppNavState`. Create `PersonScreen.kt` and `PersonViewModel.kt`.

5. **PersonViewModel**: Accepts a name string, queries `MovieDao` and `SeriesDao` with LIKE clauses, filters results in Kotlin to exact-match the name within the comma-separated cast list.

6. **Optional TMDB enrichment**: Add `TmdbApi.personSearch(name)` and `TmdbApi.personDetail(id)` to fetch a headshot photo and verified filmography. This is a nice-to-have enhancement on top of the local-data MVP.

## Priority

**P2 - Medium**. Actor pages are a meaningful discovery feature but require UI work on both the detail screens and a new screen. The cast data already exists; the gap is making it interactive.

## Effort Estimate

**Medium (3-4 days)**. New Person screen + ViewModel, cast chip refactor on both detail screens, navigation wiring. Optional TMDB person API integration adds 1-2 more days.
