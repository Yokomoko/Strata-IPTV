# 31 - Multiple User Profiles

## Current State

Strata operates as a single-user application. All state -- watchlist, continue watching, watch history, favourites, content filter settings -- is stored in a single Room database with no user/profile scoping. The `WatchlistEntity`, `ContinueWatchingEntity`, `WatchHistoryEntity`, and `FavouriteEntity` tables have no `profile_id` or user identifier column. The Home screen shows the same rails, the same Continue Watching, and the same Watchlist regardless of who is using the device. There is no profile picker on app launch, no avatar selection, and no profile management in settings.

## Gap

Netflix's profile picker is arguably its most iconic UX element. Disney+ supports up to 7 profiles with individual Kids profiles. Prime Video supports up to 6 profiles. Profiles are fundamental to a household streaming experience because:
- Different family members have different watch histories and preferences.
- Recommendations become useless when mixed across users with different tastes.
- Parental controls (spec #29) are profile-specific on all major platforms.
- Continue Watching shows irrelevant content when shared across users.

Strata's single-user model means a household with 3 viewers gets a chaotic Home screen mixing everyone's activity.

## User Story

As a member of a household sharing a Fire Stick, I want to select my personal profile when opening Strata so that my watchlist, continue watching, and recommendations are separate from other household members.

## Acceptance Criteria

1. On first launch after enabling profiles, a profile creation flow prompts the user to create at least one profile.
2. On subsequent launches, a profile picker screen appears before the Home screen.
3. Up to 5 profiles are supported, each with a name and selectable avatar/color.
4. Each profile has its own isolated: Watchlist, Continue Watching, Watch History, Favourites.
5. Switching profiles from the profile picker reloads the Home screen with the selected profile's data.
6. A "Manage Profiles" option allows adding, editing (name/avatar), and deleting profiles.
7. One profile can optionally be designated as a "Kids" profile with a locked content rating filter (ties into spec #29).
8. The last-used profile is remembered and pre-selected on the profile picker (but the picker still shows).
9. Profile data is stored locally (no cloud sync for MVP).

## Technical Approach

1. **Schema changes**: This is the most invasive change in this gap specification set.
   - Add a `profiles` table: `id`, `name`, `avatar_index`, `is_kids`, `max_rating`, `created_at`.
   - Add a `profile_id` column (with foreign key) to: `watchlist`, `continue_watching`, `watch_history`, `favourites`.
   - Room migration: add columns with default value 1 (default profile) to preserve existing data.

2. **Active profile state**: Create a `ProfileManager` singleton (Hilt `@Singleton`) that exposes:
   - `activeProfile: StateFlow<ProfileEntity>`.
   - `fun switchProfile(id: Int)` -- updates the active profile and triggers UI refresh.
   - Store the active profile ID in DataStore.

3. **DAO scoping**: Modify all DAO queries for watchlist, CW, watch history, and favourites to filter by `profile_id`:
   ```sql
   SELECT * FROM watchlist WHERE profile_id = :profileId ORDER BY added_at DESC
   ```
   This affects `WatchlistDao`, `ContinueWatchingDao`, `WatchHistoryDao`, `FavouriteDao`.

4. **Profile picker screen**: Create `ProfilePickerScreen.kt` with a horizontal row of avatar circles (styled like Netflix's profile picker). Shown before `Shell` renders the sidebar/content area.

5. **Navigation flow**: In `MainActivity` or `StrataApp`, check if profiles are enabled and if no active profile is set. If so, show the profile picker. Otherwise, proceed to Home.

6. **Recommendation/Home scoping**: The `HomeViewModel` already reads from CW and watchlist DAOs; once those are profile-scoped, the Home screen automatically shows profile-specific data.

7. **Migration strategy**: Since this touches many tables, implement as a Room schema migration (version N+1) that adds the `profile_id` column with a default value. Existing data is assigned to profile 1 (the auto-created default profile).

## Priority

**P3 - Low**. This is the highest-effort item in this specification set and the most invasive schema change. It unlocks significant value for multi-person households but is not essential for single-user or primary-user scenarios. Should be implemented after the content discovery features (specs 21-25, 27) that improve the experience for any individual user.

## Effort Estimate

**Large (7-10 days)**. Schema migration, DAO modifications across 4 tables, ProfileManager singleton, profile picker UI, manage profiles UI, navigation flow changes, and thorough testing of data isolation between profiles.
