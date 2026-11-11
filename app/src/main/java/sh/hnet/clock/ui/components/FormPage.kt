@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.clock.R
import kotlin.math.roundToInt

/**
 * A full page with one task, as ComfyChair's and Music's FormPage: a top bar (Back and title; none when
 * [title] is null), content that scrolls and one full-width filled button at the bottom. The
 * first-run wizard's pages are built on it.
 *
 * @param busy The button shows a loading indicator and Back is disabled
 * @param confirmIcon Shown before the button's label
 */
@Composable
fun FormPage(
    title: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onBack: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    @DrawableRes confirmIcon: Int? = null,
    contentSpacing: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().semantics { title?.let { paneTitle = it } }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (title != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    if (onBack != null) {
                        TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack, enabled = !busy)
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp).semantics { heading() })
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = content,
            )
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled && !busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            ) {
                if (busy) {
                    LoadingIndicator(color = LocalContentColor.current, modifier = Modifier.size(24.dp))
                } else {
                    if (confirmIcon != null) {
                        Icon(painterResource(confirmIcon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(confirmLabel)
                }
            }
        }
    }
}

/**
 * The app's mark at 144 dp, as ComfyChair's AppLogo: placed so the mark's left edge lines up with
 * text inset by [titleInset], with the empty band under the mark cut off.
 */
@Composable
fun AppLogo(titleInset: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier
            .size(LOGO_SIZE)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val markStart = (placeable.width * LOGO_MARK_START).roundToInt()
                val emptyBelow = (placeable.height * LOGO_EMPTY_BELOW).roundToInt()
                layout(placeable.width, placeable.height - emptyBelow) {
                    placeable.place(titleInset.roundToPx() - markStart, 0)
                }
            },
    )
}

private val LOGO_SIZE = 144.dp

/** Where the mark starts in ic_launcher_foreground, as a share of its width (x 3 of 24 units, scaled 8/3 from 22 of 108). */
private const val LOGO_MARK_START = (22f + 3f * 8f / 3f) / 108f

/** The empty band below the mark, as a share of the height (the mark ends at y 21 of 24 units). */
private const val LOGO_EMPTY_BELOW = (108f - 22f - 21f * 8f / 3f) / 108f
