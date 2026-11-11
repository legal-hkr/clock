@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package sh.hnet.clock.ui.clock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.clock.ClockApplication
import sh.hnet.clock.R
import sh.hnet.clock.time.City
import sh.hnet.clock.time.CitySearch
import sh.hnet.clock.time.zoneOrNull
import sh.hnet.clock.ui.components.EmptyState
import sh.hnet.clock.ui.components.TooltipIconButton
import sh.hnet.clock.ui.components.boldNamesResource
import sh.hnet.clock.ui.components.groupShapes
import sh.hnet.clock.ui.components.rememberNow
import sh.hnet.clock.ui.components.rememberTimeFormats
import sh.hnet.clock.ui.components.settingsRowColors
import sh.hnet.clock.ui.components.tabular

/**
 * Adding a city (SPEC §3.5.7): a search over the system's cities, all of them A to Z before
 * anything is typed, each with its country, zone abbreviation and current time. A tap adds the
 * city and goes back; one already on the Clock page says so and can't be added again.
 *
 * With [pickHome] the same page chooses the home time zone (§3.9.3): every city can be taken and
 * the current home is marked.
 */
@Composable
fun AddCityScreen(viewModel: ClockViewModel, pickHome: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ClockApplication
    val locale = LocalConfiguration.current.locales[0]
    val zones = remember(locale) { app.zoneNames(locale) }
    val formats = rememberTimeFormats()
    val now by rememberNow(everySecond = false)
    // Hundreds of lookups in the system's data: built once per language, off the main thread
    val search by produceState<CitySearch?>(null, zones) { value = withContext(Dispatchers.Default) { CitySearch(zones.all) } }
    val stored by viewModel.cities.collectAsStateWithLifecycle()
    val homeZoneId by viewModel.homeZone.collectAsStateWithLifecycle()
    val marked = remember(stored, homeZoneId, pickHome, zones) {
        if (pickHome) setOfNotNull(homeZoneId?.let(zones::canonical)) else stored.orEmpty().mapTo(HashSet()) { zones.canonical(it) }
    }

    val field = rememberTextFieldState()
    val query = field.text.toString()
    val results = remember(search, query) { search?.search(query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // The input alone, as Music's search: the field carries the bar's container itself
        SearchBarDefaults.InputField(
            textFieldState = field,
            searchBarState = rememberSearchBarState(),
            onSearch = { keyboard?.hide() },
            placeholder = { Text(stringResource(R.string.hint_search_city), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
            trailingIcon = if (query.isNotEmpty()) {
                { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_clear_search), { field.clearText() }) }
            } else null,
            colors = SearchBarDefaults.inputFieldColors(
                focusedContainerColor = SearchBarDefaults.colors().containerColor,
                unfocusedContainerColor = SearchBarDefaults.colors().containerColor,
                disabledContainerColor = SearchBarDefaults.colors().containerColor,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                .focusRequester(focus),
        )
        val found = results
        when {
            // The list is being read: nothing to show for a moment
            found == null -> Unit
            found.isEmpty() -> EmptyState(R.drawable.ic_search_off, boldNamesResource(R.string.msg_no_city, query.trim()), Modifier.imePadding()) {
                Text(
                    stringResource(R.string.msg_no_city_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(found, key = { _, city -> city.zoneId }, contentType = { _, _ -> "city" }) { position, city ->
                    val isMarked = city.zoneId in marked
                    CityResult(
                        city = city,
                        time = zoneOrNull(city.zoneId)?.let { formats.time(now, it) }.orEmpty(),
                        position = position,
                        count = found.size,
                        marked = isMarked,
                        enabled = pickHome || !isMarked,
                        markedLabel = if (pickHome) null else stringResource(R.string.label_added),
                        onClick = {
                            if (pickHome) viewModel.setHomeZone(city.zoneId) else viewModel.add(city.zoneId)
                            onBack()
                        },
                    )
                }
            }
        }
    }
}

/** A result: city, "Country (ZONE)", and its time now – or a check mark (with "Added") for a city already taken. */
@Composable
private fun CityResult(city: City, time: String, position: Int, count: Int, marked: Boolean, enabled: Boolean, markedLabel: String?, onClick: () -> Unit) {
    val place = if (city.country.isEmpty()) city.abbreviation else stringResource(R.string.label_country_zone, city.country, city.abbreviation)
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = groupShapes(position, count),
        colors = settingsRowColors(),
        supportingContent = { Text(place) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (marked) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                Text(if (marked && markedLabel != null) markedLabel else time, style = MaterialTheme.typography.bodyLarge.tabular)
            }
        },
    ) {
        Text(city.name)
    }
}
