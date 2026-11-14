@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.clock.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.clock.R

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

/** The app's mark at 144 dp on Welcome, its left edge in line with text inset by [titleInset]. */
@Composable
fun AppLogo(titleInset: Dp, modifier: Modifier = Modifier) {
    AppMark(LOGO_SIZE, modifier.padding(start = titleInset, bottom = 16.dp))
}

/**
 * The app's mark as a launcher shows it (icon handoff of 12 Nov 2026, §5): the icon's foreground
 * layer at one and a half times [size], of which the round mask shows the middle, on the icon's own
 * white background layer. That white is the icon's, not a color of the UI. Without [background]
 * the mark stands on the page itself, at the same size.
 */
@Composable
fun AppMark(size: Dp, modifier: Modifier = Modifier, background: Boolean = true) {
    Box(modifier.size(size).clip(CircleShape).then(if (background) Modifier.background(Color.White) else Modifier), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
    }
}

private val LOGO_SIZE = 144.dp
