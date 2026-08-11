# Implementation Plan - Phase 8.4: Restoring Native Gestures & Paging Speed

I have identified that the "Single Gesture Overlay" I added in the last step is actually a "Glass Sheet" that is blocking all swipe motions from reaching the book. I have also identified a potential delay in the layout engine that is making some books feel "stuck" while they calculate their pages.

## Proposed Changes

### Reader UI (The "Transparent" Fix)
#### [MODIFY] [ReaderScreen.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/reader/src/main/java/com/shelf/reader/reader/ui/ReaderScreen.kt)
- **Remove Blocking Layer**: I will remove the `Box` with `pointerInput` that was covering the pager.
- **Embedded Navigation**: I will move the "Edge Tap" logic *inside* the `HorizontalPager` content area.
    - This ensures that when you **swipe**, the pager catches the motion.
    - When you **tap**, the content area catches the tap.
- **Improved Alignment**: I will adjust the `padding` and `column-width` calculation to be even more precise (using `floor` instead of `ceil` for the stride) to fix the "crushed" look.

### Rendering Engine (The "Speed" Fix)
#### [MODIFY] [HtmlPageRenderer.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/reader/src/main/java/com/shelf/reader/reader/engine/HtmlPageRenderer.kt)
- **Faster Layout**: I will reduce the JavaScript measurement delay back to **100ms**. Now that the width constraint is fixed, we don't need to wait as long.
- **Pre-emptive Preparation**: I will ensure the next chapter starts preparing even earlier to make the transition feel instantaneous.

## Verification Plan

### Manual Verification
- Deploy to OnePlus.
- **Swipe Test**: Verify that you can now drag the page slowly again.
- **Tap Test**: Verify that tapping the right 20% of the screen turns the page.
- **Book Compatibility**: Test with "The Hobbit" and verify that the 4 calculated pages are navigable.
