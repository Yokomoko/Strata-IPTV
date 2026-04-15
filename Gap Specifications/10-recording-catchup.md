# 10 - Recording / Catch-Up TV Integration

## Current State

The EPG system stores programme data in the `ProgrammeEntity` table with `start_time`, `end_time`, `title`, `description`, `channel_id`, and `icon` columns. The `ProgrammeDao` has:
- `inRange(from, to)` -- query programmes in a time window.
- `purgeBefore(before)` -- delete old programme data.
- `hasAfter(cutoff)` -- freshness check.

There is no concept of:
- Recording a programme (local or server-side).
- Catch-up / timeshift playback (watching a programme that already aired).
- Server-side recording APIs (many IPTV providers offer "catch-up" via modified stream URLs with time parameters).
- A "Recordings" section in the navigation.

The `Destination` enum has: Home, Live, Movies, Shows, Search, Settings. There is no "Recordings" or "Catch-Up" destination.

## Gap

Many IPTV providers support catch-up TV through URL manipulation. A typical pattern is appending a time range to the stream URL:

```
Live:     http://provider.com/live/stream/12345
Catch-up: http://provider.com/live/stream/12345?utc=1713020400&lutc=1713024000
```

or using the `timeshift` parameter defined in the XMLTV specification.

Some M3U playlists include `catchup`, `catchup-source`, and `catchup-days` attributes on channels that support catch-up:

```
#EXTINF:-1 tvg-id="bbc1" catchup="default" catchup-source="http://..." catchup-days="7",BBC One
```

This metadata is currently parsed by `M3uParser` but not stored or used.

## User Story

As a user, I want to browse the EPG, see a programme that aired earlier today (or in the past few days), and play it back using catch-up, so I don't miss shows that aired when I wasn't watching.

## Acceptance Criteria

### Phase 1: Catch-Up Playback from EPG
- [ ] In the EPG grid view, past programmes (before "now") are displayed with a distinct visual style (e.g., slightly dimmed, with a "catch-up" icon if the channel supports it).
- [ ] Selecting a past programme on a catch-up-enabled channel starts playback of that programme using the catch-up URL.
- [ ] The catch-up URL is constructed from the channel's `catchup-source` template and the programme's start/end times.
- [ ] A "catch-up available" indicator appears on channels that support it.
- [ ] If catch-up is not available for a channel, selecting a past programme shows "Catch-up not available for this channel."

### Phase 2: Catch-Up Browse Section
- [ ] A "Catch-Up" or "Replay" section is accessible (either as a sub-section of Live or a separate nav destination).
- [ ] It shows recently aired programmes grouped by channel, with poster/thumbnail when available from EPG icons.
- [ ] Programmes are browsable by channel, by day, or by genre (if genre data is available in the EPG).

### Phase 3: Server-Side Recording (Future)
- [ ] For providers that support recording APIs, add a "Record" button on future programme cells.
- [ ] Recorded programmes appear in a "My Recordings" section.
- [ ] This is provider-specific and may require API integration beyond URL manipulation.

## Technical Approach

### M3U Catch-Up Metadata

The `M3uParser` needs to extract and store catch-up attributes. Extend `M3uEntry` and `ContentItemEntity`:

```kotlin
// M3uEntry additions:
val catchupType: String = "",       // "default", "shift", "flussonic", "xc"
val catchupSource: String = "",     // URL template
val catchupDays: Int = 0,           // How many days back is supported

// ChannelEntity additions (new columns):
@ColumnInfo(name = "catchup_type") val catchupType: String = "",
@ColumnInfo(name = "catchup_source") val catchupSource: String = "",
@ColumnInfo(name = "catchup_days") val catchupDays: Int = 0,
```

This requires a Room database migration (version 1 -> 2).

### Catch-Up URL Builder

Create a `CatchupUrlBuilder` utility:

```kotlin
object CatchupUrlBuilder {
    fun build(
        channel: ChannelEntity,
        programme: ProgrammeEntity,
    ): String? {
        if (channel.catchupType.isEmpty()) return null
        
        val template = channel.catchupSource.ifEmpty {
            // Default: append UTC params to stream URL
            return "${channel.streamUrl}?utc=${programme.startTime.epochSecond}&lutc=${programme.endTime.epochSecond}"
        }
        
        return template
            .replace("{utc}", programme.startTime.epochSecond.toString())
            .replace("{lutc}", programme.endTime.epochSecond.toString())
            .replace("{duration}", ChronoUnit.SECONDS.between(programme.startTime, programme.endTime).toString())
            // ... other template variables
    }
}
```

### EPG Grid Interaction

In `GuideGridScreen.kt`, the `ProgrammeCell` `onClick` currently does nothing (`/* future: show programme details */`). Wire it to:
1. Check if the programme is in the past and the channel supports catch-up.
2. If yes, launch the player with the catch-up URL.
3. If no, show programme details (title, description, time).

### Files to Modify

| File | Change |
|------|--------|
| `data/m3u/M3uEntry.kt` | Add catch-up fields. |
| `data/m3u/M3uParser.kt` | Parse `catchup`, `catchup-source`, `catchup-days` attributes. |
| `data/db/Entities.kt` | Add catch-up columns to `ChannelEntity`. |
| `data/db/Daos.kt` | No DAO changes needed (columns auto-available). |
| `data/db/AppDatabase.kt` | Bump version, add migration. |
| `domain/CatchupUrlBuilder.kt` | New file: URL template resolution. |
| `ui/live/GuideGridScreen.kt` | Handle programme cell click for catch-up playback, visual indicators. |
| `ui/live/LiveViewModel.kt` | Wire catch-up URL building into the guide data. |
| `data/repo/SyncService.kt` | Store catch-up metadata during M3U sync. |

### Scope Management

This is a large feature. Recommended phasing:
1. **Phase 1** (Medium effort): Parse and store catch-up metadata, build URLs, enable playback from the EPG grid. No new screens.
2. **Phase 2** (Large effort): Dedicated catch-up browse UI.
3. **Phase 3** (Very large, provider-specific): Server-side recording.

## Priority

**Low** -- Catch-up depends on IPTV provider support (not all providers offer it). The core live TV experience (channel switching, favourites, OSD) should be solid before investing in catch-up. However, for providers that do support it, this is a significant value-add.

## Effort Estimate

**Large (1+ day)** for Phase 1 alone. The M3U parser changes, database migration, URL builder, and EPG grid interaction are all separate work items. Phase 2 and 3 are each additional 1+ day efforts.
