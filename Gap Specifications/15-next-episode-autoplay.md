# 15 - Next Episode Auto-Play

## Current State

When a VOD episode reaches `Player.STATE_ENDED`, the `onPlaybackStateChanged` listener calls `saveFullContinueWatching()` and the player sits on a black frame. The user must press Back, navigate to the show detail screen, find the next episode, and press Play manually.

`PlayerArgs` carries `title` (formatted as "Series S1E3"), `contentType = "show"`, and `streamUrl`, but has no reference to the series title, season number, or episode number. The ViewModel has no access to `EpisodeDao` and cannot resolve the next episode in sequence.

`EpisodeEntity` stores `series_title`, `season_number`, `episode_number`, and `stream_url`, so the data needed to find the next episode already exists in the database.

## Gap

Every major streaming service auto-plays the next episode with a countdown overlay. This is the single most important binge-watching feature and its absence forces tedious manual navigation on a D-pad remote -- a significant friction point for series viewers.

## User Story

> As a series viewer, I want the next episode to start automatically after the current one ends so that I can enjoy uninterrupted binge-watching.

## Acceptance Criteria

1. When a series episode reaches the final 30 seconds (or `STATE_ENDED` if duration is unknown), a "Next Episode" overlay card appears in the bottom-right showing: next episode title, season/episode number, and a 15-second countdown ring.
2. When the countdown reaches zero, the player seamlessly transitions to the next episode (new stream URL, updated title, reset position).
3. The user can press D-pad Centre / Enter to play the next episode immediately (skip the countdown).
4. The user can press Back or a "Cancel" button to dismiss the overlay and remain on the current episode's end frame.
5. The current episode is marked as `watched = true` in `EpisodeEntity` when auto-play triggers.
6. If the current episode is the last in a season, the overlay offers the first episode of the next season. If it is the series finale, the overlay shows "Series complete" and offers to return to the show detail screen.
7. Continue Watching is updated with the new episode's content ID and position = 0.
8. A Settings toggle allows disabling auto-play entirely ("Auto-play next episode: On / Off").

## Technical Approach

1. **Extend PlayerArgs**: Add optional fields: `seriesTitle: String?`, `seasonNumber: Int?`, `episodeNumber: Int?`. Populate these when launching from `ShowDetailScreen`.
2. **Inject EpisodeDao**: Add `EpisodeDao` to `PlayerViewModel`'s constructor (already using Hilt, so add `@Inject` parameter).
3. **Next episode resolution**: Add a `suspend fun resolveNextEpisode(seriesTitle: String, season: Int, episode: Int): EpisodeEntity?` function that queries:
   ```sql
   SELECT * FROM episodes
   WHERE series_title = :seriesTitle
     AND (season_number > :season OR (season_number = :season AND episode_number > :episode))
   ORDER BY season_number, episode_number
   LIMIT 1
   ```
   Add this query to `EpisodeDao`.
4. **Countdown trigger**: In the `onPlaybackStateChanged` listener, when `STATE_READY` has fired and the position is within 30s of `player.duration` (checked via a periodic coroutine), resolve the next episode and populate `nextEpisode: NextEpisodeInfo?` in `PlayerUiState`.
5. **Countdown coroutine**: Launch a `viewModelScope` coroutine that counts down from 15 to 0 (updating `countdownSeconds: Int` in UI state), then calls `playNextEpisode()`.
6. **playNextEpisode()**: Marks current episode watched, updates continue-watching, resets ViewModel state, calls `initialize()` with new stream parameters.
7. **UI overlay**: A `NextEpisodeCard` composable rendered at `Alignment.BottomEnd` with: episode thumbnail (use series poster as fallback), episode title, season/episode label, a circular countdown indicator using `Canvas` + `drawArc`, and a "Play Now" / "Cancel" button row. Uses `AnimatedVisibility` with `slideInHorizontally` + `fadeIn`.
8. **Settings**: `auto_play_next_episode: Boolean` in DataStore, defaulting to `true`.

## Priority

**P1 -- High** (critical binge-watching feature, major UX improvement for the primary series viewing flow)

## Effort Estimate

**3-4 days**
- 0.5 day: extend `PlayerArgs` + `EpisodeDao` query + DAO injection
- 1 day: next-episode resolution logic + countdown coroutine in ViewModel
- 1 day: `NextEpisodeCard` composable + countdown ring animation + D-pad focus
- 0.5 day: mark-as-watched logic + continue-watching updates
- 0.5 day: Settings toggle + DataStore integration + edge cases (series finale, missing stream URLs)
