# 42 - "Are You Still Watching?" Interruptions (Competitive Grievance)

## Title

"Are you still watching?" prompts that interrupt playback

## Source

- **Reddit**: r/netflix, r/fireTV, r/cordcutters -- one of the top 5 Netflix complaints. "Are you still watching? Yes I am, for the 5th time tonight."
- **Memes/social media**: The phrase has become a cultural meme, indicating how universally experienced and resented it is.
- **Amazon Appstore reviews**: Cited across Netflix, Disney+, and Prime Video reviews.
- **Accessibility forums**: Users with mobility impairments find it especially frustrating as reaching the remote to dismiss the prompt is physically difficult.

## The Problem

After 2-3 episodes of continuous playback (Netflix) or a period of inactivity, streaming apps pause playback and display a full-screen "Are you still watching?" dialog. Users must physically grab the remote and press a button to continue.

User complaints include:

1. **Interrupts the viewing experience** -- breaks immersion during binge sessions.
2. **Paternalistic design** -- users feel the app is judging their viewing habits.
3. **Accessibility barrier** -- users with limited mobility, users who fell asleep (and want the show as background), users cooking/cleaning while watching.
4. **No way to disable it** -- Netflix provides no setting to turn this off. It is hardcoded server-side.
5. **Inconsistent timing** -- sometimes triggers after 2 episodes, sometimes after 4, making it unpredictable.

The feature exists because Netflix saves bandwidth when nobody is watching. For an IPTV app, the stream is already being broadcast regardless of whether the user is watching.

## How StrataTV Could Address It

1. **No "still watching" prompt by default** for live TV. Live streams are broadcast continuously; there is no bandwidth saving from pausing them.
2. **Optional sleep timer** instead -- a user-controlled timer ("Turn off in: 30m / 1h / 2h / End of episode / Never") that gracefully stops playback and returns to the home screen. This respects user autonomy.
3. **VOD inactivity handling** (optional, configurable): If implementing any inactivity check for VOD, make it a user-controlled setting defaulting to Off, with generous thresholds (e.g. 6+ hours), and a gentle "Playback paused" overlay rather than a condescending question.

## Feasibility Score

**1** (trivial) -- Again, this is primarily about NOT implementing an annoying feature. The sleep timer is a small addition (~1 day of work).

## Validity Score

**5** (universally hated) -- Affects every binge-watcher on every major platform. The meme status alone proves universal recognition.

## Impact Score

**5** (Feasibility 1 x Validity 5 = 5)

## Technical Notes

- **Sleep timer implementation**: A `CountDownTimer` or `viewModelScope.launch { delay(millis) }` coroutine in `PlayerViewModel` that calls `player.pause()` + navigates to home screen when elapsed. UI: a small timer icon in the controls overlay with remaining time.
- **State persistence**: Store the user's preferred sleep timer duration in DataStore so it can be quickly re-applied.
- **Fire Stick power**: Fire Stick already has its own system-level sleep timer (Settings > Device > Sleep), so our timer complements rather than replaces it.
- **Live TV**: No inactivity detection at all. The IPTV stream runs whether we display it or not.
- **VOD**: If the stream errors out due to server timeout after extended pause, handle reconnection gracefully rather than showing a "still watching?" prompt.

## Priority Recommendation

**P0 -- Ship as-is; P2 for sleep timer.** The absence of the prompt is our default. The sleep timer is a nice quality-of-life addition for a future release. Market the lack of interruptions as a feature.
