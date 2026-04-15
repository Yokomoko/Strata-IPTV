# 43 - "Skip Intro" Button Covering Content with No Way to Dismiss (Competitive Grievance)

## Title

Skip Intro button obscures content and cannot be hidden

## Source

- **Reddit**: r/netflix, r/DisneyPlus, r/fireTV -- frequent complaints about the "Skip Intro" and "Skip Recap" buttons blocking subtitles, important visuals, or on-screen text.
- **Accessibility forums**: The button overlays can obscure closed captions for deaf/hard-of-hearing viewers.
- **UX design critiques**: Multiple blog posts and UX teardowns cite this as a case of one feature undermining another.

## The Problem

Netflix, Disney+, and Prime Video display a "Skip Intro" button during opening credits/sequences. While the feature itself is popular, the implementation has issues:

1. **Covers subtitles** -- the button renders on top of caption text, making it unreadable for 15-30 seconds.
2. **Covers important content** -- some shows have plot-relevant cold opens that play during the "intro" detection window. The button distracts from these.
3. **Cannot be dismissed without skipping** -- there is no "keep watching" button. The only way to remove it is to wait for it to time out (10-15 seconds) or press it to skip.
4. **D-pad focus issues** -- on Fire Stick, the Skip Intro button often captures D-pad focus, making it hard to access other controls. Pressing "down" to reach volume or subtitles instead triggers the skip.
5. **No way to disable the button entirely** -- if you never want to skip intros (some users enjoy them), you still get the overlay every episode.

## How StrataTV Could Address It

StrataTV already has spec `14-skip-intro-recap.md` planned. The implementation should learn from these complaints:

1. **Position the button to avoid subtitle overlap** -- place it in the top-right corner rather than bottom-left/bottom-right where captions render. Alternatively, dynamically shift it if subtitles are active.
2. **Allow dismissal** -- pressing Back or a dedicated "Watch" button hides the overlay without skipping.
3. **D-pad isolation** -- the Skip Intro button should not be in the default D-pad focus chain. It should require an explicit navigation action (e.g. D-pad Right) to focus it, preventing accidental activation.
4. **Settings toggle** -- "Show Skip Intro button: Always / Never / Ask once per series".
5. **Subtitle-aware placement** -- if subtitles are active, the button moves above the subtitle region or becomes a small non-overlapping icon.

## Feasibility Score

**2** (low effort) -- The button positioning and D-pad focus management are straightforward Compose for TV work. The main challenge is accurate intro detection (covered in spec 14), not the UX of the button itself.

## Validity Score

**3** (common complaint) -- Not as universally hated as autoplay trailers, but frequently cited by subtitle users and D-pad navigation purists. Particularly relevant for IPTV viewers who often watch foreign content with subtitles.

## Impact Score

**6** (Feasibility 2 x Validity 3 = 6)

## Technical Notes

- **Button placement**: Use `Modifier.align(Alignment.TopEnd).padding(top = 48.dp)` to place above the subtitle region. If `subtitlesActive` state is true, add extra bottom-safe margin.
- **D-pad focus**: Use `focusRequester` + `focusProperties { canFocus = false }` by default, only enabling focus when the user navigates toward it with D-pad Right. Alternatively, use `FocusGroup` to isolate it from the main controls focus chain.
- **Dismissal**: `onKeyEvent` handler catches `KEYCODE_BACK` and sets `showSkipButton = false` without navigating away from the player.
- **Settings**: `skip_intro_button: String` in DataStore with values "always", "never", "once_per_series".
- **Cross-reference**: This spec refines the UX requirements of existing spec `14-skip-intro-recap.md`. The intro detection logic is defined there; this spec addresses only the button presentation complaints.

## Priority Recommendation

**P2 -- Implement alongside spec 14.** When building the skip intro feature from spec 14, incorporate these UX refinements from the start rather than retrofitting. The marginal effort is minimal.
