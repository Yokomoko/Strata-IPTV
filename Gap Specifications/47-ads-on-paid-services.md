# 47 - Ads and Promotions on Paid Services

## Title

Seeing ads and self-promotions on a service I already pay for

## Source

- **Reddit**: r/fireTV (top complaints), r/cordcutters, r/AndroidTV -- "I pay for this service and still get ads." Amazon Fire TV home screen ads are one of the most discussed grievances.
- **Amazon Appstore reviews**: Fire TV device reviews overwhelmingly cite home screen ads. Netflix and Prime Video reviews cite in-app promotional banners.
- **Tech press**: Widespread coverage of Amazon adding more ads to Fire TV OS, Netflix introducing ad-supported tiers while still showing promos on premium tiers.
- **r/PleX**: Users migrating to Plex/Jellyfin specifically to escape ads, then frustrated when Plex itself started showing "Plex Movies & TV" ad-supported content mixed with their personal libraries.

## The Problem

Users paying for streaming services are increasingly subjected to advertising:

1. **Fire TV OS home screen** -- Amazon shows full-screen banner ads, sponsored content rows, and "featured" app promotions on the device home screen. Users paid for the hardware and still get ads.
2. **In-app promos** -- Netflix shows interstitial cards promoting its own originals between content rows. Prime Video promotes Amazon Freevee content and rentals alongside included content.
3. **Pre-roll promos** -- Disney+ and Prime Video show trailers for other content before the selected title plays.
4. **Plex ad-supported content** -- Plex mixes free ad-supported movies into personal library views, blurring the line between owned and promoted content.
5. **Unskippable promotions** -- some promos cannot be dismissed or skipped, wasting 15-30 seconds.

## How StrataTV Could Address It

1. **Zero ads, zero promotions, zero sponsored content** -- StrataTV shows only the user's own content from their M3U playlist. There is no business model incentive to inject ads.
2. **Clean home screen** -- every item on every rail is content the user has access to via their playlist. No "suggested apps", no "trending on other platforms", no cross-promotion.
3. **No ad-supported tier** -- there is no tiered model. All users get the same ad-free experience.
4. **Transparent content sourcing** -- every piece of content clearly shows which playlist/provider it comes from. Nothing is injected by StrataTV itself.

## Feasibility Score

**1** (trivial) -- This is a business model decision, not a technical feature. We simply do not build ad infrastructure.

## Validity Score

**5** (universally hated) -- Ad fatigue on paid services is the #1 driver of cord-cutting sentiment. Fire TV home screen ads are the single most complained-about aspect of the Fire TV platform.

## Impact Score

**5** (Feasibility 1 x Validity 5 = 5)

## Technical Notes

- No implementation required. This is the absence of ad infrastructure.
- Ensure no third-party SDKs are included that could inject ads (no AdMob, no Amazon Ads SDK, no Firebase Ads).
- The `build.gradle.kts` dependencies should be audited to confirm no ad-related transitive dependencies.
- If a future monetisation model is considered, it should be a one-time purchase or subscription without ad injection.
- Marketing angle: "Your content. Nothing else." or "No ads. No promos. No nonsense."

## Priority Recommendation

**P0 -- Architectural decision, already in effect.** Ensure this remains a core principle. Document it as a design constraint so future development never introduces promotional content injection. This is a powerful differentiator, especially for Fire TV users who are bombarded with ads on every other surface of their device.
