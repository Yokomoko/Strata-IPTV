# 67 - No Effective Content Rating or Maturity Filters

## Title

Cannot effectively filter out adult content when children are using the device

## Source

- **Reddit**: r/fireTV, r/cordcutters, r/Parenting -- "My kid scrolled past something graphic on Netflix before I could stop it." "Disney+ defaults to showing all content including the Deadpool films."
- **Amazon Appstore reviews**: Parental control complaints across all streaming apps.
- **r/IPTV**: IPTV playlists include adult channels mixed in with regular content. No filtering mechanism in most IPTV apps.
- **Consumer advocacy**: Parents report that existing parental controls are either too complex to set up or too easy for children to bypass.

## The Problem

Content filtering and parental controls on streaming and IPTV apps are inadequate:

1. **IPTV-specific: no content filtering at all** -- most IPTV apps display every channel in the playlist, including adult content, with no filtering.
2. **Profile-based controls are complex** -- Netflix/Disney+ require setting up a separate kids profile. Many parents skip this because it is too many steps.
3. **PIN protection is bypassed** -- children learn PINs quickly. No fallback protection.
4. **No time limits** -- streaming apps have no built-in screen time controls for children's profiles.
5. **Category-level blocking absent** -- cannot block an entire category (e.g., "Adult", "Horror") without individually blocking each title.

## How StrataTV Could Address It

StrataTV already has spec `31-parental-controls.md` planned. This gap spec validates the competitive motivation and adds IPTV-specific requirements:

1. **Category-based channel hiding** -- hide entire M3U categories (e.g., "XXX", "Adult", "18+") behind a PIN. These channels do not appear in the channel list, EPG, or search unless the PIN is entered.
2. **Quick PIN lock** -- a single button press activates "Kid Mode" which hides all adult content instantly. PIN required to deactivate.
3. **PIN on specific channels** -- long-press a channel to require PIN entry before tuning. Useful for channels that are borderline (e.g., premium movie channels with occasional adult content).
4. **Category auto-detection** -- parse M3U group-title tags for common adult category names ("Adult", "XXX", "18+", "+18") and prompt the user to hide them on first playlist import.
5. **Simple setup** -- a single-screen setup wizard: "Do you want to hide adult content? Set a 4-digit PIN." No complex profile system needed.

## Feasibility Score

**2** (low effort) -- Category filtering is a Room query filter. PIN storage is a hashed value in DataStore. The UI is a simple PIN entry dialog. Auto-detection is string matching on M3U category names.

## Validity Score

**4** (very common) -- Every parent with a shared TV worries about content filtering. For IPTV specifically, adult content in playlists is extremely common and the lack of any filtering is a significant barrier to families using IPTV apps.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Cross-reference**: Extends spec `31-parental-controls.md` with IPTV-specific requirements.
- **Category hiding**:
  ```kotlin
  @Entity(tableName = "hidden_categories")
  data class HiddenCategoryEntity(
      @PrimaryKey val category: String,   // M3U group-title value
      val requirePin: Boolean = true
  )
  ```
  Channel queries filter against this table:
  ```sql
  SELECT * FROM channels
  WHERE category NOT IN (SELECT category FROM hidden_categories WHERE requirePin = 1)
  ```
  When PIN is entered, temporarily include hidden categories for the session.
- **PIN storage**: Hash with PBKDF2 or bcrypt. Store in DataStore:
  ```kotlin
  val parentalPinHash: String?
  val parentalControlsEnabled: Boolean
  ```
- **Auto-detection on import**: After M3U parse, scan group-title values against a keyword list:
  ```kotlin
  val adultKeywords = setOf("adult", "xxx", "18+", "+18", "porn", "erotic")
  val detectedAdultCategories = categories.filter { cat ->
      adultKeywords.any { keyword -> cat.lowercase().contains(keyword) }
  }
  if (detectedAdultCategories.isNotEmpty()) {
      // Prompt: "We detected adult content categories. Would you like to hide them?"
  }
  ```
- **Kid Mode**: A quick toggle that sets `isKidMode = true` in a runtime `StateFlow`. All content queries check this flag and exclude hidden categories without requiring a DB transaction.
- **Fire Stick constraint**: PIN entry on D-pad is straightforward -- a 4-digit numeric input with large buttons. Consider the Fire TV remote's limited buttons: PIN entry should work with just the D-pad and Select.

## Priority Recommendation

**P1 -- Implement with spec 31.** Parental controls are a gate for family adoption of IPTV apps. The auto-detection of adult categories on first import is a uniquely valuable feature that no IPTV app currently offers. This should be part of the first-run experience to prevent families from encountering adult content before they have a chance to set up filtering.
