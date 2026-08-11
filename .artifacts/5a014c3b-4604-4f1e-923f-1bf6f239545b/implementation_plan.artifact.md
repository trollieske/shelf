# Implementation Plan - Phase 8.5: Fixing Paging Logic & Gesture Conflicts

I have identified that the current "Gesture Overlay" is blocking swipes, and the "Page 1 of 1" issue is likely due to CSS constraints preventing the WebView from flowing content into columns correctly for certain books.

## Proposed Changes

### Rendering Engine (Paging Fix)
#### [MODIFY] [HtmlPageRenderer.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/reader/src/main/java/com/shelf/reader/reader/engine/HtmlPageRenderer.kt)
- **CSS Width Overhaul**: Set `html` and `body` width to `100vw` and `#content-wrapper` width to `max-content`. This ensures `scrollWidth` reflects the true length of the book.
- **Enhanced Measurement**:
    - Increase JavaScript delay to **250ms** for the initial measurement to allow fonts to settle.
    - Add a "Retry" check: if the content is long but `scrollWidth` is small, wait and measure again.
- **Detailed Logging**: Print `scrollWidth`, `innerWidth`, and the resulting `pageCount` to Logcat for debugging.

### Reader UI (Gesture Restoration)
#### [MODIFY] [ReaderScreen.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/reader/src/main/java/com/shelf/reader/reader/ui/ReaderScreen.kt)
- **Remove Blocking Layer**: Delete the top-level `Box` with `detectTapGestures`.
- **Integrated Taps**: Use standard `Modifier.clickable` (with no indication) on the page content *inside* the `HorizontalPager`.
    - **Pager Priority**: Standard `clickable` is designed to let drag gestures "propagate" to parent scrollable containers like `HorizontalPager`.
- **Navigation Safety**: Refine the "Next Chapter" logic to prevent accidental skips if the page count hasn't loaded yet.

## Verification Plan

### Manual Verification
- Deploy to OnePlus.
- **Swipe Check**: Verify that swiping works immediately on all books.
- **Page Count Check**: Verify that "Lord of the Rings" shows more than 1 page.
- **Tap Check**: Verify that tapping the edges turns pages and doesn't just skip chapters.
