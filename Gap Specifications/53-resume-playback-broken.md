# 53 - Cannot Resume Exactly Where You Left Off

## Title

Resume playback puts you in the wrong place or does not work at all

## Source

- **Reddit**: r/fireTV, r/DisneyPlus, r/PleX -- "Disney+ starts from the beginning every time." "Netflix resume is 30 seconds behind where I stopped." "Plex loses my position after the app is killed."
- **Amazon Appstore reviews**: Resume failures are a top-5 complaint for Disney+ and Apple TV+ on Fire Stick.
- **r/IPTV**: IPTV app users report no resume support at all for VOD content.
- **Tech support forums**: Troubleshooting resume issues is one of the most common streaming support topics.

## The Problem

Resume/continue watching functionality is unreliable across streaming apps:

1. **Position lost on app kill** -- Fire OS aggressively kills background apps to reclaim RAM. If the app doesn't persist the position before being killed, resume fails.
2. **Inaccurate position** -- resume jumps to a position 30-60 seconds before or after where the user actually stopped, due to keyframe-aligned saving.
3. **Resume not offered** -- some apps restart from the beginning without asking "Resume" or "Start Over".
4. **Continue Watching shows wrong items** -- finished content appears in Continue Watching, or partially watched content disappears.
5. **Cross-session loss** -- watching on Fire Stick, then the app updates or crashes, and the position is gone.
6. **VOD-only limitation** -- most IPTV apps have no resume support for VOD content, even though users may be watching a 2-hour film.

## How StrataTV Could Address It

1. **Aggressive position persistence** -- save playback position to Room every 10 seconds during playback, and immediately on any player state change (pause, buffer, stop, error).
2. **Lifecycle-aware saving** -- save position in `onPause()`, `onStop()`, and `onDestroy()` lifecycle callbacks, ensuring survival through Fire OS app kills.
3. **Resume dialog** -- when opening content with a saved position > 60 seconds, show "Resume at 45:22 / Start Over" dialog.
4. **Accurate position** -- save the exact position (not keyframe-aligned). ExoPlayer will seek to the nearest keyframe before this position, which is the correct behavior (a few seconds before is better than after).
5. **Continue Watching accuracy** -- mark content as "finished" when position reaches 95% of duration (not 100%, to account for credits). Remove finished content from Continue Watching after 24 hours.
6. **Live TV last channel** -- remember the last watched live channel and offer to resume it on app launch (separate from VOD resume).

## Feasibility Score

**2** (low effort) -- Room persistence, lifecycle callbacks, and a simple dialog. The data layer for continue watching likely already exists or is planned.

## Validity Score

**5** (universally experienced) -- Every streaming user has experienced a lost playback position. It is one of the most basic expected features and one of the most frustrating when broken.

## Impact Score

**10** (Feasibility 2 x Validity 5 = 10)

## Technical Notes

- **Position persistence**:
  ```kotlin
  // In PlayerViewModel, launched in viewModelScope
  private fun startPositionSaver() {
      viewModelScope.launch {
          while (isActive) {
              delay(10_000) // every 10 seconds
              saveCurrentPosition()
          }
      }
  }
  
  private suspend fun saveCurrentPosition() {
      val position = player.currentPosition
      val duration = player.duration
      if (position > 0 && duration > 0) {
          continueWatchingDao.upsert(
              ContinueWatchingEntity(
                  contentId = currentContentId,
                  positionMs = position,
                  durationMs = duration,
                  updatedAt = System.currentTimeMillis(),
                  finished = position.toFloat() / duration > 0.95f
              )
          )
      }
  }
  ```
- **Lifecycle hooks**: In `PlayerScreen` composable or the hosting Activity:
  ```kotlin
  DisposableEffect(Unit) {
      val observer = LifecycleEventObserver { _, event ->
          when (event) {
              Lifecycle.Event.ON_PAUSE -> viewModel.saveCurrentPosition()
              Lifecycle.Event.ON_STOP -> viewModel.saveCurrentPosition()
              else -> {}
          }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  ```
- **Resume dialog**: A `Dialog` composable shown when `savedPosition > 60_000L`:
  - "Resume at ${formatTime(savedPosition)}" button (focused by default)
  - "Start Over" button
  - D-pad navigable with Left/Right between the two options.
- **Continue Watching cleanup**: A `WorkManager` periodic task (or coroutine on app startup) that removes entries where `finished = true AND updatedAt < now - 24h`.
- **Last live channel**: Store `last_live_channel_id` in DataStore. On app launch, if the user navigates to Live, auto-tune to this channel.
- **Fire Stick constraint**: Room writes are async (suspend functions on `Dispatchers.IO`), so the 10-second save interval has no UI thread impact. The Room database file survives app process kills by the OS.

## Priority Recommendation

**P1 -- Core feature, implement early.** Reliable resume is table stakes for any video player. It should be built into the player infrastructure from the start, not bolted on later. The 10-second periodic save plus lifecycle hooks pattern is simple and robust. This single feature prevents a significant source of user frustration.
