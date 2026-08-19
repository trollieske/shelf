package eu.wewox.pagecurl.page

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.utils.multiply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG_TAP = "PageCurl.Tap"

// Kamigura fork: tap callbacks receive the tap position so the state can start the curl
// from the tapped corner.
@ExperimentalPageCurlApi
internal fun Modifier.tapGesture(
    config: PageCurlConfig,
    scope: CoroutineScope,
    onTapForward: suspend (Offset) -> Unit,
    onTapBackward: suspend (Offset) -> Unit,
): Modifier = pointerInput(config) {
    val tapInteraction = config.tapInteraction as? PageCurlConfig.TargetTapInteraction ?: return@pointerInput

    awaitEachGesture {
        val down = awaitFirstDown()
        val sizeW = size.width
        val sizeH = size.height
        val startFracX = if (sizeW > 0) down.position.x / sizeW else 0f
        val startFracY = if (sizeH > 0) down.position.y / sizeH else 0f
        val downConsumedBefore = down.isConsumed
        // FIX C VERIFY: Log the state of the down event BEFORE we call consume() in this
        // gesture detector. When combined with corresponding drag-detector logging, this lets
        // us verify whether the TapGesture's awaitFirstDown + consume() is firing first and
        // therefore robbing the drag detector of a chance to process the event (Fix C
        // hypothesis). Also logs touchSlop (Fix D verification via same data channel).
        Log.d(TAG_TAP, "Down event in TAP detector: " +
            "down.id=${down.id.value} " +
            "pos=(${down.position.x},${down.position.y}) " +
            "frac=(${String.format("%.3f", startFracX)},${String.format("%.3f", startFracY)}) " +
            "size=${sizeW}x$sizeH " +
            "touchSlopPx=${viewConfiguration.touchSlop} " +
            "touchSlopFrac=${if (sizeW > 0) viewConfiguration.touchSlop / sizeW else 0f} " +
            "downAlreadyConsumed=$downConsumedBefore " +
            "fx=C_verify_tapBeforeConsume")
        down.consume()
        Log.d(TAG_TAP, "TAP detector just CONSUMED down.id=${down.id.value} consumedAfter=${down.isConsumed} (fx=C_verify_tapAfterConsume)")
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture

        val travelDist = (down.position - up.position).getDistance()
        val overSlop = travelDist > viewConfiguration.touchSlop
        if (overSlop) {
            Log.d(TAG_TAP, "TAP aborted: distance=${String.format("%.2f", travelDist)}px > touchSlop=${viewConfiguration.touchSlop}px")
            return@awaitEachGesture
        }

        if (config.tapCustomEnabled && config.onCustomTap(this, size, up.position)) {
            Log.d(TAG_TAP, "TAP handled by customTap (menu zone), fracX=${up.position.x / sizeW}")
            return@awaitEachGesture
        }

        val fwdTarget = tapInteraction.forward.target.multiply(size)
        val bwdTarget = tapInteraction.backward.target.multiply(size)
        val fwdOK = config.tapForwardEnabled && fwdTarget.contains(up.position)
        val bwdOK = config.tapBackwardEnabled && bwdTarget.contains(up.position)
        Log.d(TAG_TAP, "TAP match: fwdMatch=$fwdOK (target=$fwdTarget for up.x=${up.position.x}) bwdMatch=$bwdOK")

        if (fwdOK) {
            scope.launch {
                Log.d(TAG_TAP, "Fire onTapForward at (${up.position.x},${up.position.y})")
                onTapForward(up.position)
            }
            return@awaitEachGesture
        }

        if (bwdOK) {
            scope.launch {
                Log.d(TAG_TAP, "Fire onTapBackward at (${up.position.x},${up.position.y})")
                onTapBackward(up.position)
            }
            return@awaitEachGesture
        }
    }
}
