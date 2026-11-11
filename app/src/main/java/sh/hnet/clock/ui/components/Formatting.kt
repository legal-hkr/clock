package sh.hnet.clock.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.core.text.htmlEncode

/** A string whose arguments (names the user gave) are escaped and set in bold by its own `<b>` tags. */
@Composable
fun boldNamesResource(@StringRes id: Int, vararg names: String): AnnotatedString =
    AnnotatedString.fromHtml(stringResource(id, *names.map { it.htmlEncode() }.toTypedArray()))
