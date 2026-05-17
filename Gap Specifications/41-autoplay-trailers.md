# 41 - Autoplay Trailers and Preview Videos on Browse (Competitive Grievance)

## Title

Autoplay trailers blasting audio while I'm just trying to browse

## Source

- **Reddit**: r/netflix, r/fireTV, r/cordcutters -- consistently the #1 complaint about Netflix's TV app for years. Threads with thousands of upvotes ("How do I stop Netflix from autoplaying previews?").
- **Amazon Appstore reviews**: 1-star Netflix reviews frequently cite this as the reason.
- **Tech press**: The Verge, Ars Technica, TechCrunch have all covered the backlash. Netflix eventually added a profile-level toggle in 2020, but it is buried and resets on some devices.
- **Disney+, Prime Video**: Both adopted similar autoplay preview behaviour, drawing the same complaints.

## The Problem

When browsing the content catalogue on Netflix, Disney+, and Prime Video, hovering on a title for more than 1-2 seconds triggers a loud video preview or trailer. Users report:

1. **Jarring audio** -- blasts trailer audio while you are reading a synopsis or deciding what to watch.
2. **Bandwidth waste** -- streams HD video in the background on metered connections.
3. **Slows down browsing** -- UI stutters on Fire Stick as it loads and decodes preview video while simultaneously rendering the browse grid.
4. **Impossible to browse quietly** -- e.g. partner sleeping, baby napping. Users describe muting the TV just to browse.
5. **The "disable" toggle is hard to find** -- Netflix buried it under Profile > Manage Profiles > Autoplay Previews. Many users don't know it exists. It also doesn't persist reliably across all devices.

This is arguably the most universally despised feature in modern streaming UI design.

## How StrataTV Could Address It

StrataTV should **never autoplay trailers or video previews on browse screens**. Content cards show static artwork and metadata. If we ever add preview functionality in the future, it must be:

1. **Opt-in only** -- a clearly labelled setting ("Preview on hover: Off / On"), defaulting to Off.
2. **Muted by default** if enabled -- audio only plays if the user explicitly presses a "Play Preview" button.
3. **Delayed trigger** -- minimum 5 seconds of focus before any preview loads, with an immediate cancel on focus change.
4. **No bandwidth consumption on browse** -- previews are not pre-fetched or buffered until explicitly triggered.

This is a zero-cost differentiator: we simply don't build the annoying feature.

## Feasibility Score

**1** (trivial) -- This is a feature we do NOT build. The default state of our app already addresses this. The only work is ensuring our browse UI is compelling with static artwork alone, which is already the plan.

## Validity Score

**5** (universally hated) -- This affects every single Netflix/Disney+/Prime user. It is the most commonly cited complaint in streaming app reviews across all platforms. Reddit threads about this consistently reach the front page.

## Impact Score

**5** (Feasibility 1 x Validity 5 = 5)

## Technical Notes

- No implementation required -- this is the absence of a feature.
- Content detail screens can show a "Play Trailer" button if trailer URLs are available in IMDB/TMDB metadata (Phase 3 enrichment), but this would be an explicit user action, never automatic.
- Browse screens use `AsyncImage` for poster art with placeholder shimmer -- no video decoder involvement.
- On Fire Stick, avoiding background video decode during browse is a significant performance win. The device has limited hardware decoder slots (typically 1-2), and using one for previews would compete with actual playback.

## Priority Recommendation

**P0 -- Ship as-is.** This is already our default behaviour. Consider adding a brief "No autoplay previews, ever" note to app store marketing copy as a differentiator. If preview functionality is ever requested, gate it behind an explicit opt-in setting.
