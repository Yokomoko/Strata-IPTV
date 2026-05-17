# 54 - Poor Search Across All Content

## Title

Search is slow, inaccurate, and doesn't find content I know exists

## Source

- **Reddit**: r/fireTV, r/AndroidTV, r/cordcutters -- "Why does searching on Fire Stick take 10 seconds to show results?" "Netflix search doesn't find things by actor name." "Disney+ search is basically broken on Fire Stick."
- **Amazon Appstore reviews**: Search quality complaints across all apps.
- **r/PleX, r/IPTV**: Users report no search at all in many IPTV apps, or search only matching exact channel names.
- **Accessibility**: Voice search on Fire TV remote works for some apps but not others, creating an inconsistent experience.

## The Problem

Search on streaming TV apps is fundamentally frustrating:

1. **On-screen keyboard is painful** -- typing with a D-pad on a virtual keyboard is the worst UX on television. Each character requires multiple button presses.
2. **Slow results** -- search queries require server round-trips, showing spinners for 3-5 seconds before results appear.
3. **No fuzzy matching** -- searching "gray" won't find "grey". Misspelling an actor's name returns nothing.
4. **Limited search scope** -- many apps only search titles, not actors, directors, genres, or descriptions.
5. **No voice search integration** -- Fire TV remote has a microphone button, but many apps don't integrate with it.
6. **No search history** -- previous searches are not saved, requiring re-entry of the same query.
7. **IPTV-specific**: Most IPTV apps have no search at all, or only search channel names. No ability to search EPG programme titles or VOD descriptions.

## How StrataTV Could Address It

1. **Instant local search** -- all content metadata (channels, EPG, VOD) is stored locally in Room. Search queries return results in <100ms with no network latency.
2. **Broad search scope** -- search across channel names, EPG programme titles, VOD titles, actors, directors, genres, and descriptions.
3. **Fuzzy matching** -- use SQLite FTS5 (Full-Text Search) for typo-tolerant matching. "nteflix" finds "Netflix", "grey's anatomy" finds "Grey's Anatomy".
4. **Voice search support** -- integrate with Fire TV's voice search intent (`ACTION_SEARCH` / `ACTION_ASSIST`) to receive voice queries directly.
5. **Search history** -- persist recent searches in DataStore for quick re-access.
6. **Predictive/typeahead** -- show results as the user types each character, reducing the need to type full queries.
7. **Minimal keyboard interaction** -- prioritise voice search and search history to minimise on-screen keyboard usage.

## Feasibility Score

**3** (moderate effort) -- FTS5 setup, voice search integration, and the search UI are substantive work. The Room FTS virtual table setup requires careful schema design. Voice search integration requires handling Fire TV search intents.

## Validity Score

**4** (very common) -- Search is a universal need. Poor search is universally frustrating. Voice search integration is expected on Fire TV. For IPTV apps specifically, having any meaningful search at all is a differentiator.

## Impact Score

**12** (Feasibility 3 x Validity 4 = 12)

## Technical Notes

- **Room FTS5 virtual table**:
  ```kotlin
  @Fts4(contentEntity = ContentEntity::class)
  @Entity(tableName = "content_fts")
  data class ContentFts(
      val title: String,
      val description: String?,
      val actors: String?,       // comma-separated
      val director: String?,
      val genre: String?,
      val channelName: String?
  )
  ```
  Query: `SELECT * FROM content_fts WHERE content_fts MATCH :query`
  FTS5 supports prefix matching (`query*`), phrase matching (`"exact phrase"`), and Boolean operators.
- **Fuzzy matching**: FTS5 doesn't natively support fuzzy/typo matching. Options:
  - **Trigram tokenizer** (SQLite 3.34+, available on Fire OS 7+): Enables substring and approximate matching.
  - **Application-level Levenshtein**: For short queries (<20 chars), compute edit distance against a cached title list.
  - **Prefix matching**: `query*` handles many typo cases where the user typed the beginning correctly.
- **Voice search integration**: In `AndroidManifest.xml`:
  ```xml
  <intent-filter>
      <action android:name="android.intent.action.SEARCH" />
  </intent-filter>
  <meta-data android:name="android.app.searchable" android:resource="@xml/searchable" />
  ```
  Handle `Intent.ACTION_SEARCH` in the Activity, extract the query, and navigate to the search results screen.
- **Typeahead**: Debounce the search query with `Flow.debounce(300)` to avoid querying on every keystroke:
  ```kotlin
  searchQuery
      .debounce(300)
      .filter { it.length >= 2 }
      .flatMapLatest { query -> searchRepository.search(query) }
      .collect { results -> _uiState.update { it.copy(searchResults = results) } }
  ```
- **Search history**: Store in DataStore as a `List<String>` (last 20 queries). Show as chips above the keyboard for quick re-selection.
- **Fire Stick voice**: The Fire TV remote's microphone button triggers the system voice assistant. Apps can receive the transcribed text via the search intent. This works without any custom speech recognition.

## Priority Recommendation

**P2 -- Implement after core browse features.** Basic title search should be available from v1 (simple `LIKE '%query%'` query on Room). FTS5 full-text search, voice integration, and fuzzy matching can be layered on in v2. Even a basic local search puts StrataTV ahead of most IPTV apps.
