@file:Suppress("ComplexMethod", "LongParameterList", "LongMethod")

package eu.wewox.pagecurl.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.ExperimentalPageCurlApi

/**
 * Inward tolerance (as a fraction of total PageCurl width) applied to the START rectangles
 * of the forward and backward drag zones in StartEndDragInteraction.
 *
 * Without this buffer, forward.start = Rect(0.5, 0, 1, 1) with zero tolerance, meaning any
 * touch landing at fraction x = 0.499... (for example a finger placed just left of the
 * mathematical center-line on a high-DPI screen) fails Rect.contains() and falls through
 * to the tap-animation path. The user then sees a "fast pre-rendered curl animation"
 * instead of the expected interactive drag-to-curl behavior.
 *
 * Value: 0.1f (10 % of total width applied inwards from each side). This is a general
 * robustness improvement applicable to all Android hardware and screen densities, not a
 * device-specific hack.
 */
internal const val DRAG_START_ZONE_INWARD_BUFFER = 0.1f

/**
 * Creates a PageCurlConfig with the default properties and memorizes it.
 *
 * @param backPageColor Color of the back-page. In majority of use-cases it should be set to the content background
 * color.
 * @param backPageContentAlpha The alpha which defines how content is "seen through" the back-page. From 0 (nothing
 * is visible) to 1 (everything is visible).
 * @param shadowColor The color of the shadow. In majority of use-cases it should be set to the inverted color to the
 * content background color. Should be a solid color, see [shadowAlpha] to adjust opacity.
 * @param shadowAlpha The alpha of the [color].
 * @param shadowRadius Defines how big the shadow is.
 * @param shadowOffset Defines how shadow is shifted from the page. A little shift may add more realism.
 * @param dragForwardEnabled True if forward drag interaction is enabled or not.
 * @param dragBackwardEnabled True if backward drag interaction is enabled or not.
 * @param tapForwardEnabled True if forward tap interaction is enabled or not.
 * @param tapBackwardEnabled True if backward tap interaction is enabled or not.
 * @param tapCustomEnabled True if custom tap interaction is enabled or not, see [onCustomTap].
 * @param dragInteraction The drag interaction setting.
 * @param tapInteraction The tap interaction setting.
 * @param onCustomTap The lambda to invoke to check if tap is handled by custom tap or not. Receives the density
 * scope, the PageCurl size and tap position. Returns true if tap is handled and false otherwise.
 */
@ExperimentalPageCurlApi
@Composable
public fun rememberPageCurlConfig(
    backPageColor: Color = Color.White,
    backPageContentAlpha: Float = 0.1f,
    shadowColor: Color = Color.Black,
    shadowAlpha: Float = 0.2f,
    shadowRadius: Dp = 15.dp,
    shadowOffset: DpOffset = DpOffset((-5).dp, 0.dp),
    dragForwardEnabled: Boolean = true,
    dragBackwardEnabled: Boolean = true,
    tapForwardEnabled: Boolean = true,
    tapBackwardEnabled: Boolean = true,
    tapCustomEnabled: Boolean = true,
    dragInteraction: PageCurlConfig.DragInteraction = PageCurlConfig.StartEndDragInteraction(),
    tapInteraction: PageCurlConfig.TapInteraction = PageCurlConfig.TargetTapInteraction(),
    onCustomTap: Density.(IntSize, Offset) -> Boolean = { _, _ -> false },
): PageCurlConfig =
    rememberSaveable(
        saver = listSaver(
            save = {
                fun Rect.forSave(): List<Any> =
                    listOf(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)

                fun PageCurlConfig.DragInteraction.getRectList(): List<Rect> =
                    when (this) {
                        is PageCurlConfig.GestureDragInteraction ->
                            listOf(forward.target, backward.target)

                        is PageCurlConfig.StartEndDragInteraction ->
                            listOf(forward.start, forward.end, backward.start, backward.end)
                    }

                fun PageCurlConfig.TapInteraction.getRectList(): List<Rect> =
                    when (this) {
                        is PageCurlConfig.TargetTapInteraction ->
                            listOf(forward.target, backward.target)
                    }

                fun PageCurlConfig.DragInteraction.forSave(): List<Any> =
                    listOf(this::class.java, pointerBehavior.name) + getRectList().flatMap(Rect::forSave)

                fun PageCurlConfig.TapInteraction.forSave(): List<Any> =
                    listOf(this::class.java) + getRectList().flatMap(Rect::forSave)

                listOf(
                    (it.backPageColor.value shr 32).toInt(),
                    it.backPageContentAlpha,
                    (it.shadowColor.value shr 32).toInt(),
                    it.shadowAlpha,
                    it.shadowRadius.value,
                    it.shadowOffset.x.value,
                    it.shadowOffset.y.value,
                    it.dragForwardEnabled,
                    it.dragBackwardEnabled,
                    it.tapForwardEnabled,
                    it.tapBackwardEnabled,
                    it.tapCustomEnabled,
                    *it.dragInteraction.forSave().toTypedArray(),
                    *it.tapInteraction.forSave().toTypedArray(),
                )
            },
            restore = {
                val iterator = it.iterator()
                fun Iterator<Any>.nextRect(): Rect =
                    Rect(next() as Float, next() as Float, next() as Float, next() as Float)

                PageCurlConfig(
                    Color(iterator.next() as Int),
                    iterator.next() as Float,
                    Color(iterator.next() as Int),
                    iterator.next() as Float,
                    Dp(iterator.next() as Float),
                    DpOffset(Dp(iterator.next() as Float), Dp(iterator.next() as Float)),
                    iterator.next() as Boolean,
                    iterator.next() as Boolean,
                    iterator.next() as Boolean,
                    iterator.next() as Boolean,
                    iterator.next() as Boolean,
                    when (iterator.next()) {
                        PageCurlConfig.GestureDragInteraction::class.java -> {
                            PageCurlConfig.GestureDragInteraction(
                                PageCurlConfig.DragInteraction.PointerBehavior.valueOf(iterator.next() as String),
                                PageCurlConfig.GestureDragInteraction.Config(iterator.nextRect()),
                                PageCurlConfig.GestureDragInteraction.Config(iterator.nextRect()),
                            )
                        }

                        PageCurlConfig.StartEndDragInteraction::class.java -> {
                            PageCurlConfig.StartEndDragInteraction(
                                PageCurlConfig.DragInteraction.PointerBehavior.valueOf(iterator.next() as String),
                                PageCurlConfig.StartEndDragInteraction.Config(iterator.nextRect(), iterator.nextRect()),
                                PageCurlConfig.StartEndDragInteraction.Config(iterator.nextRect(), iterator.nextRect()),
                            )
                        }

                        else -> error("Unable to restore PageCurlConfig")
                    },
                    when (iterator.next()) {
                        PageCurlConfig.TargetTapInteraction::class.java -> {
                            PageCurlConfig.TargetTapInteraction(
                                PageCurlConfig.TargetTapInteraction.Config(iterator.nextRect()),
                                PageCurlConfig.TargetTapInteraction.Config(iterator.nextRect()),
                            )
                        }

                        else -> error("Unable to restore PageCurlConfig")
                    },
                    onCustomTap
                )
            }
        )
    ) {
        PageCurlConfig(
            backPageColor = backPageColor,
            backPageContentAlpha = backPageContentAlpha,
            shadowColor = shadowColor,
            shadowAlpha = shadowAlpha,
            shadowRadius = shadowRadius,
            shadowOffset = shadowOffset,
            dragForwardEnabled = dragForwardEnabled,
            dragBackwardEnabled = dragBackwardEnabled,
            tapForwardEnabled = tapForwardEnabled,
            tapBackwardEnabled = tapBackwardEnabled,
            tapCustomEnabled = tapCustomEnabled,
            dragInteraction = dragInteraction,
            tapInteraction = tapInteraction,
            onCustomTap = onCustomTap
        )
    }.also { cfg ->
        cfg.backPageColor = backPageColor
        cfg.backPageContentAlpha = backPageContentAlpha
        cfg.shadowColor = shadowColor
        cfg.shadowAlpha = shadowAlpha
        cfg.shadowRadius = shadowRadius
        cfg.shadowOffset = shadowOffset
        cfg.dragForwardEnabled = dragForwardEnabled
        cfg.dragBackwardEnabled = dragBackwardEnabled
        cfg.tapForwardEnabled = tapForwardEnabled
        cfg.tapBackwardEnabled = tapBackwardEnabled
        cfg.tapCustomEnabled = tapCustomEnabled
        cfg.dragInteraction = dragInteraction
        cfg.tapInteraction = tapInteraction
        cfg.onCustomTap = onCustomTap
    }

/**
 * The configuration for PageCurl.
 *
 * @param backPageColor Color of the back-page. In majority of use-cases it should be set to the content background
 * color.
 * @param backPageContentAlpha The alpha which defines how content is "seen through" the back-page. From 0 (nothing
 * is visible) to 1 (everything is visible).
 * @param shadowColor The color of the shadow. In majority of use-cases it should be set to the inverted color to the
 * content background color. Should be a solid color, see [shadowAlpha] to adjust opacity.
 * @param shadowAlpha The alpha of the [shadowColor].
 * @param shadowRadius Defines how big the shadow is.
 * @param shadowOffset Defines how shadow is shifted from the page. A little shift may add more realism.
 * @param dragForwardEnabled True if forward drag interaction is enabled or not.
 * @param dragBackwardEnabled True if backward drag interaction is enabled or not.
 * @param tapForwardEnabled True if forward tap interaction is enabled or not.
 * @param tapBackwardEnabled True if backward tap interaction is enabled or not.
 * @param tapCustomEnabled True if custom tap interaction is enabled or not, see [onCustomTap].
 * @param dragInteraction The drag interaction setting.
 * @param tapInteraction The tap interaction setting.
 * @param onCustomTap The lambda to invoke to check if tap is handled by custom tap or not. Receives the density
 * scope, the PageCurl size and tap position. Returns true if tap is handled and false otherwise.
 */
@ExperimentalPageCurlApi
public class PageCurlConfig(
    backPageColor: Color,
    backPageContentAlpha: Float,
    shadowColor: Color,
    shadowAlpha: Float,
    shadowRadius: Dp,
    shadowOffset: DpOffset,
    dragForwardEnabled: Boolean,
    dragBackwardEnabled: Boolean,
    tapForwardEnabled: Boolean,
    tapBackwardEnabled: Boolean,
    tapCustomEnabled: Boolean,
    dragInteraction: DragInteraction,
    tapInteraction: TapInteraction,
    onCustomTap: Density.(IntSize, Offset) -> Boolean,
) {
    /**
     * The color of the back-page. In majority of use-cases it should be set to the content background color.
     */
    public var backPageColor: Color by mutableStateOf(backPageColor)

    /**
     * The alpha which defines how content is "seen through" the back-page. From 0 (nothing is visible) to
     * 1 (everything is visible).
     */
    public var backPageContentAlpha: Float by mutableStateOf(backPageContentAlpha)

    /**
     * The color of the shadow. In majority of use-cases it should be set to the inverted color to the content
     * background color. Should be a solid color, see [shadowAlpha] to adjust opacity.
     */
    public var shadowColor: Color by mutableStateOf(shadowColor)

    /**
     * The alpha of the [shadowColor].
     */
    public var shadowAlpha: Float by mutableStateOf(shadowAlpha)

    /**
     * Defines how big the shadow is.
     */
    public var shadowRadius: Dp by mutableStateOf(shadowRadius)

    /**
     * Defines how shadow is shifted from the page. A little shift may add more realism.
     */
    public var shadowOffset: DpOffset by mutableStateOf(shadowOffset)

    /**
     * True if forward drag interaction is enabled or not.
     */
    public var dragForwardEnabled: Boolean by mutableStateOf(dragForwardEnabled)

    /**
     * True if backward drag interaction is enabled or not.
     */
    public var dragBackwardEnabled: Boolean by mutableStateOf(dragBackwardEnabled)

    /**
     * True if forward tap interaction is enabled or not.
     */
    public var tapForwardEnabled: Boolean by mutableStateOf(tapForwardEnabled)

    /**
     * True if backward tap interaction is enabled or not.
     */
    public var tapBackwardEnabled: Boolean by mutableStateOf(tapBackwardEnabled)

    /**
     * True if custom tap interaction is enabled or not, see [onCustomTap].
     */
    public var tapCustomEnabled: Boolean by mutableStateOf(tapCustomEnabled)

    /**
     * The drag interaction setting.
     */
    public var dragInteraction: DragInteraction by mutableStateOf(dragInteraction)

    /**
     * The tap interaction setting.
     */
    public var tapInteraction: TapInteraction by mutableStateOf(tapInteraction)

    /**
     * The lambda to invoke to check if tap is handled by custom tap or not.
     * Receives the density scope, the PageCurl size and tap position.
     * Returns true if tap is handled and false otherwise.
     */
    public var onCustomTap: Density.(IntSize, Offset) -> Boolean by mutableStateOf(onCustomTap)

    /**
     * The drag interaction setting.
     */
    public sealed interface DragInteraction {

        /**
         * The pointer behavior during drag interaction.
         */
        public val pointerBehavior: PointerBehavior

        /**
         * The enumeration of available pointer behaviors.
         */
        public enum class PointerBehavior {
            /**
             * The default behavior is an original one, where "page flip" is anchored to the user's finger.
             * The "page flip" in this sense is a line which divides the back page of the current page and the front
             * page of the next page. This means that when finger is dragged to the left edge, the next page is fully
             * visible.
             */
            Default,

            /**
             * In the page-edge behavior the right edge of the current page is anchored to the user's finger.
             * This means that when finger is dragged to the left edge, the next page is half visible.
             */
            PageEdge;
        }
    }

    /**
     * The drag interaction setting based on where user start and end drag gesture inside the PageCurl.
     *
     * @property pointerBehavior The pointer behavior during drag interaction.
     * @property forward The forward tap configuration. Its [Config.start] zone includes an
     * inward 10 % buffer (see [DRAG_START_ZONE_INWARD_BUFFER]) so touches landing fractionally
     * left of the exact horizontal center still trigger interactive forward drag rather than
     * falling through to the tap-animation path. Its [Config.end] zone uses the standard,
     * unbuffered left half to preserve drag-succeed thresholds.
     * @property backward The backward tap configuration, symmetric to [forward].
     */
    public data class StartEndDragInteraction(
        override val pointerBehavior: DragInteraction.PointerBehavior = DragInteraction.PointerBehavior.Default,
        val forward: Config = Config(start = rightHalfStartBuffered(), end = leftHalf()),
        val backward: Config = Config(start = leftHalfStartBuffered(), end = rightHalf())
    ) : DragInteraction {

        /**
         * The drag interaction configuration for forward or backward drag.
         *
         * @property start Defines a rectangle where interaction should start. The rectangle coordinates are relative
         * (from 0 to 1) and then scaled to the PageCurl bounds.
         * @property end Defines a rectangle where interaction should end. The rectangle coordinates are relative
         * (from 0 to 1) and then scaled to the PageCurl bounds.
         */
        public data class Config(val start: Rect, val end: Rect)
    }

    /**
     * The drag interaction setting based on the direction where drag has been started.
     *
     * @property pointerBehavior The pointer behavior during drag interaction.
     * @property forward The forward tap configuration.
     * @property backward The backward tap configuration.
     */
    public data class GestureDragInteraction(
        override val pointerBehavior: DragInteraction.PointerBehavior = DragInteraction.PointerBehavior.Default,
        val forward: Config = Config(target = full()),
        val backward: Config = Config(target = full()),
    ) : DragInteraction {

        /**
         * The drag interaction configuration for forward or backward drag.
         *
         * @property target Defines a rectangle where interaction captured. The rectangle coordinates are relative
         * (from 0 to 1) and then scaled to the PageCurl bounds.
         */
        public data class Config(val target: Rect)
    }

    /**
     * The tap interaction setting.
     */
    public sealed interface TapInteraction

    /**
     * The tap interaction setting based on where user taps inside the PageCurl.
     *
     * @property forward The forward tap configuration.
     * @property backward The backward tap configuration.
     */
    public data class TargetTapInteraction(
        val forward: Config = Config(target = rightHalf()),
        val backward: Config = Config(target = leftHalf())
    ) : TapInteraction {

        /**
         * The tap interaction configuration for forward or backward tap.
         *
         * @property target Defines a rectangle where interaction captured. The rectangle coordinates are relative
         * (from 0 to 1) and then scaled to the PageCurl bounds.
         */
        public data class Config(val target: Rect)
    }
}

/**
 * Forward (right-side) START zone for StartEndDragInteraction, with an inward tolerance
 * buffer applied.
 *
 * Without the buffer this would be Rect(0.5, 0, 1.0, 1.0). Any finger placed fractionally
 * left of the exact x = 0.5 line (for example x = 0.498 on a device with rounded pixel
 * centers) would fail Rect.contains() and fall through to tap handling. The inward 10 %
 * buffer (DRAG_START_ZONE_INWARD_BUFFER) expands the left edge to x = 0.4 so touches near
 * the horizontal center still enter interactive drag mode.
 *
 * This is used ONLY for forward drag START zones. The forward END zone (where drag must
 * finish to succeed), the TargetTapInteraction tap zones, and the backward equivalents
 * keep unbuffered half-rectangles to preserve the 3-zone tap layout and drag-succeed
 * thresholds.
 */
private fun rightHalfStartBuffered(): Rect =
    Rect(0.5f - DRAG_START_ZONE_INWARD_BUFFER, 0.0f, 1.0f, 1.0f)

/**
 * Backward (left-side) START zone for StartEndDragInteraction, with a symmetric inward
 * buffer. Expands the right edge from x = 0.5 to x = 0.6. See [rightHalfStartBuffered] for
 * the full rationale.
 */
private fun leftHalfStartBuffered(): Rect =
    Rect(0.0f, 0.0f, 0.5f + DRAG_START_ZONE_INWARD_BUFFER, 1.0f)

/**
 * The full size of the PageCurl.
 */
private fun full(): Rect = Rect(0.0f, 0.0f, 1.0f, 1.0f)

/**
 * The left half of the PageCurl. Used for backward drag END zones, backward tap target
 * zones, and (through symmetric defaults) the original unbuffered left-half reference.
 * NOT used for drag START zones — see [leftHalfStartBuffered] instead.
 */
private fun leftHalf(): Rect = Rect(0.0f, 0.0f, 0.5f, 1.0f)

/**
 * The right half of the PageCurl. Used for forward drag END zones, forward tap target
 * zones, and (through symmetric defaults) the original unbuffered right-half reference.
 * NOT used for drag START zones — see [rightHalfStartBuffered] instead.
 */
private fun rightHalf(): Rect = Rect(0.5f, 0.0f, 1.0f, 1.0f)
