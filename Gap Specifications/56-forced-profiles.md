# 56 - Profiles Forced on Single-User Households

## Title

Forced to choose a profile every time I launch the app, even though I live alone

## Source

- **Reddit**: r/netflix, r/fireTV, r/DisneyPlus -- "I'm the only person who uses this Fire Stick. Why do I have to select my profile every single time?" "Please let me set a default profile."
- **Amazon Appstore reviews**: One of the most common convenience complaints. Netflix added "auto-profile" eventually but it is device-specific and not always reliable.
- **r/AndroidTV**: Users report that even after deleting extra profiles, some apps still show the profile selector.
- **UX critiques**: Profile selection adds 2-3 seconds to every app launch and is pure friction for solo users.

## The Problem

Multi-profile systems designed for family households create friction for single users:

1. **Mandatory profile selection on every launch** -- even with one profile, some apps show the profile picker, adding an unnecessary step.
2. **No "default profile" option** -- users cannot designate a profile to auto-select on launch.
3. **"Who's watching?" screen is unskippable** -- Netflix's profile selector requires a deliberate selection. There is no "remember me" or "always use this profile".
4. **Profile-specific settings fragmentation** -- subtitle preferences, audio language, autoplay settings are per-profile, but single users just want one set of global settings.
5. **Accidental profile switches** -- family members selecting the wrong profile corrupts recommendations and watch history.

## How StrataTV Could Address It

1. **No profiles by default** -- StrataTV launches directly into the home screen. There is no profile system in v1. All settings and history are global.
2. **Future profile support is opt-in** -- if profiles are added later (spec 31 mentions parental controls which may require them), they should be:
   - Disabled by default for solo users.
   - "Default profile" option to skip the selection screen.
   - PIN-protected profile switching rather than open selection.
3. **Single settings namespace** -- all preferences (subtitles, audio, playback, UI) are global. No per-profile fragmentation unless profiles are explicitly enabled.
4. **Zero-friction launch** -- the app goes from icon tap to home screen content with no intermediate screens (no profile picker, no "what's new" interstitial, no promotional splash).

## Feasibility Score

**1** (trivial) -- This is the default state of the app. Not building a profile system is easier than building one.

## Validity Score

**3** (common complaint) -- Affects single-user households significantly. Less relevant for family households who need profiles. IPTV users skew toward personal devices (a dedicated Fire Stick) where single-user is the norm.

## Impact Score

**3** (Feasibility 1 x Validity 3 = 3)

## Technical Notes

- No implementation required for v1. The absence of a profile system eliminates this friction entirely.
- **Future profiles architecture** (if needed for parental controls):
  ```kotlin
  @Entity(tableName = "profiles")
  data class ProfileEntity(
      @PrimaryKey(autoGenerate = true) val profileId: Long = 0,
      val name: String,
      val pin: String?,          // null for main profile
      val isDefault: Boolean,    // auto-select on launch
      val isRestricted: Boolean, // parental controls active
      val avatarResId: Int?
  )
  ```
  - On launch: if only one profile exists OR a default profile is set, skip the picker entirely.
  - Profile picker only shown when: multiple profiles exist AND no default is set.
- **DataStore scoping**: If profiles are added, DataStore keys should be prefixed with profile ID: `"profile_1_subtitle_lang"`. Current global keys become the default profile's keys with no migration needed.
- **Fire Stick context**: Fire Stick devices are often personal devices (bedroom TV, office, individual setup). The assumption of single-user is more accurate here than for a shared living room TV.

## Priority Recommendation

**P0 -- Already our default.** No action needed for v1. If profiles are added in the future (for parental controls or multi-playlist support), ensure a "default profile" option is included from day one to preserve the zero-friction launch experience.
