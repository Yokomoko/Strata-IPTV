package com.strata.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.data.settings.AppSettings
import com.strata.tv.data.settings.BuiltInProviders
import com.strata.tv.ui.theme.StrataColors

/**
 * Full-screen first-run wizard.  Three steps:
 *   1. Pick a provider
 *   2. Enter credentials (tested before advancing)
 *   3. Region + language preferences
 *
 * On finish, [onFinished] is fired and Shell re-renders the main app.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onFinished: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid)
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 800.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Strata TV",
                color = StrataColors.AccentPrimaryBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = headlineFor(state.step),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            StepIndicator(state.step)
            Spacer(Modifier.height(20.dp))

            when (state.step) {
                SetupViewModel.Step.PickProvider -> PickProviderStep(viewModel)
                SetupViewModel.Step.Credentials -> CredentialsStep(state, viewModel)
                SetupViewModel.Step.Filters -> FiltersStep(state, viewModel)
            }
        }
    }
}

private fun headlineFor(step: SetupViewModel.Step): String = when (step) {
    SetupViewModel.Step.PickProvider -> "Welcome, let's set up your provider"
    SetupViewModel.Step.Credentials -> "Sign in to your provider"
    SetupViewModel.Step.Filters -> "Choose your region and languages"
}

@Composable
private fun StepIndicator(current: SetupViewModel.Step) {
    val steps = listOf(
        SetupViewModel.Step.PickProvider,
        SetupViewModel.Step.Credentials,
        SetupViewModel.Step.Filters,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { idx, step ->
            val active = step == current
            val done = steps.indexOf(current) > idx
            Box(
                modifier = Modifier
                    .width(if (active) 32.dp else 12.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            active -> StrataColors.AccentPrimaryBright
                            done -> StrataColors.AccentPrimary
                            else -> StrataColors.SurfaceOverlay
                        },
                    ),
            )
            if (idx < steps.size - 1) Spacer(Modifier.width(6.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PickProviderStep(viewModel: SetupViewModel) {
    Text(
        text = "Choose your IPTV provider",
        color = StrataColors.TextSecondary,
        fontSize = 14.sp,
    )
    Spacer(Modifier.height(16.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BuiltInProviders.ALL.forEach { entry ->
            Surface(
                onClick = { viewModel.selectProvider(entry.id) },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = StrataColors.SurfaceRaised,
                    focusedContainerColor = StrataColors.SurfaceFloat,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = entry.displayName,
                        color = StrataColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.description,
                        color = StrataColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CredentialsStep(state: SetupState, viewModel: SetupViewModel) {
    val isCustomXtream = state.providerId == "custom_xtream"
    val isCustomM3u = state.providerId == "custom_m3u"
    val isPresetXtream = !isCustomXtream && !isCustomM3u  // mybunny / skyglass / etc.

    // Hidden by default for preset providers — the URL is pre-filled
    // and only needs editing if the default is wrong.  Pulling it out
    // of the primary form stops the on-screen keyboard opening on the
    // URL field every time the user lands on this step.
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    BackRow(label = BuiltInProviders.byId(state.providerId)?.displayName ?: state.providerId,
        onBack = { viewModel.back() })
    Spacer(Modifier.height(16.dp))

    when {
        isCustomM3u -> {
            FieldRow(
                label = "M3U URL",
                value = state.customM3uUrl,
                onValueChange = { viewModel.updateField(SetupViewModel.Field.CustomM3uUrl, it) },
                keyboardType = KeyboardType.Uri,
            )
        }
        isCustomXtream -> {
            // No preset — user MUST enter a host, so show it up front.
            FieldRow(
                label = "Host (e.g. http://provider.com:8080)",
                value = state.providerHost,
                onValueChange = { viewModel.updateField(SetupViewModel.Field.Host, it) },
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(12.dp))
            FieldRow(
                label = "Username",
                value = state.username,
                onValueChange = { viewModel.updateField(SetupViewModel.Field.Username, it) },
            )
            Spacer(Modifier.height(12.dp))
            FieldRow(
                label = "Password",
                value = state.password,
                onValueChange = { viewModel.updateField(SetupViewModel.Field.Password, it) },
                isPassword = true,
            )
        }
        isPresetXtream -> {
            // Username + password first — host is pre-filled, hidden
            // behind Advanced.
            FieldRow(
                label = "Username",
                value = state.username,
                onValueChange = { viewModel.updateField(SetupViewModel.Field.Username, it) },
            )
            Spacer(Modifier.height(12.dp))
            FieldRow(
                label = "Password",
                value = state.password,
                onValueChange = { viewModel.updateField(SetupViewModel.Field.Password, it) },
                isPassword = true,
            )
            Spacer(Modifier.height(20.dp))
            PlaylistSourceToggle(
                useFiltered = state.useFilteredPlaylist,
                onToggle = { viewModel.toggleUseFilteredPlaylist() },
            )
            Spacer(Modifier.height(16.dp))
            AdvancedRow(
                expanded = advancedOpen,
                hostPreview = state.providerHost,
                onToggle = { advancedOpen = !advancedOpen },
            )
            if (advancedOpen) {
                Spacer(Modifier.height(12.dp))
                FieldRow(
                    label = "Host (only change this if your provider tells you to)",
                    value = state.providerHost,
                    onValueChange = { viewModel.updateField(SetupViewModel.Field.Host, it) },
                    keyboardType = KeyboardType.Uri,
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    if (state.errorMessage != null) {
        Text(state.errorMessage, color = StrataColors.StatusLive, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
    }

    PrimaryButton(
        label = if (state.testing) "Testing connection..." else "Test and continue",
        enabled = !state.testing,
        onClick = { viewModel.submitCredentials() },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistSourceToggle(
    useFiltered: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.SurfaceFloat,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // Plain text checkbox indicator — keeps the wizard's look
            // consistent with the country / language chips above.
            Text(
                text = if (useFiltered) "[x]" else "[ ]",
                color = if (useFiltered) StrataColors.AccentPrimaryBright
                else StrataColors.TextTertiary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Use my filtered list",
                    color = StrataColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (useFiltered) {
                        "Pulls only the channels and films you've ticked " +
                            "on your provider's website. Make sure you've " +
                            "actually picked something there or your library " +
                            "will be empty."
                    } else {
                        "Off — pulls your provider's full catalogue. " +
                            "Tick this if you've curated a personal " +
                            "playlist on the provider's website."
                    },
                    color = StrataColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdvancedRow(
    expanded: Boolean,
    hostPreview: String,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.SurfaceFloat,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (expanded) "Hide advanced" else "Advanced",
                color = StrataColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (expanded) "" else hostPreview,
                color = StrataColors.TextTertiary,
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersStep(state: SetupState, viewModel: SetupViewModel) {
    BackRow(label = "Region and languages", onBack = { viewModel.back() })
    Spacer(Modifier.height(16.dp))

    Text(
        text = "Pick the regions and languages you actually watch. " +
            "Tick UK for British channels, English for British and US shows. " +
            "You can change these later in Settings.",
        color = StrataColors.TextSecondary,
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(20.dp))

    SectionHeader("Region")
    val allCountryCodes = AppSettings.KNOWN_COUNTRIES.map { it.first }.toSet()
    SelectAllRow(
        allSelected = state.countries == allCountryCodes,
        onSelectAll = { viewModel.selectAllCountries(allCountryCodes) },
        onDeselectAll = { viewModel.deselectAllCountries() },
    )
    Spacer(Modifier.height(8.dp))
    CheckboxGrid(
        items = AppSettings.KNOWN_COUNTRIES,
        selected = state.countries,
        onToggle = { viewModel.toggleCountry(it) },
    )

    Spacer(Modifier.height(24.dp))

    SectionHeader("Languages")
    val allLanguageCodes = AppSettings.KNOWN_LANGUAGES.map { it.first }.toSet()
    SelectAllRow(
        allSelected = state.languages == allLanguageCodes,
        onSelectAll = { viewModel.selectAllLanguages(allLanguageCodes) },
        onDeselectAll = { viewModel.deselectAllLanguages() },
    )
    Spacer(Modifier.height(8.dp))
    CheckboxGrid(
        items = AppSettings.KNOWN_LANGUAGES,
        selected = state.languages,
        onToggle = { viewModel.toggleLanguage(it) },
    )

    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        label = "Finish setup",
        enabled = true,
        onClick = { viewModel.finish() },
    )
}

// ---------------------------------------------------------------------
// Reusable bits
// ---------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackRow(label: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onBack,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = StrataColors.SurfaceRaised,
                focusedContainerColor = StrataColors.SurfaceOverlay,
            ),
        ) {
            Text(
                text = "Back",
                color = StrataColors.TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(label, color = StrataColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) StrataColors.AccentPrimary else StrataColors.SurfaceFloat,
            focusedContainerColor = StrataColors.AccentPrimaryBright,
        ),
        enabled = enabled,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SelectAllRow(
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = { if (!allSelected) onSelectAll() },
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = StrataColors.SurfaceRaised,
                focusedContainerColor = StrataColors.AccentPrimary.copy(alpha = 0.6f),
            ),
        ) {
            Text(
                text = "Select all",
                color = StrataColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Surface(
            onClick = onDeselectAll,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = StrataColors.SurfaceRaised,
                focusedContainerColor = StrataColors.AccentPrimary.copy(alpha = 0.6f),
            ),
        ) {
            Text(
                text = "Deselect all",
                color = StrataColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CheckboxGrid(
    items: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items.forEach { (code, displayName) ->
            val isOn = code in selected
            Surface(
                onClick = { onToggle(code) },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isOn) StrataColors.AccentPrimary.copy(alpha = 0.85f)
                    else StrataColors.SurfaceRaised,
                    focusedContainerColor = if (isOn) StrataColors.AccentPrimaryBright
                    else StrataColors.SurfaceFloat,
                ),
            ) {
                Text(
                    // Same text either way so the chip doesn't resize
                    // when toggled.  Selection signalled via colour
                    // + weight only.
                    text = displayName,
                    color = if (isOn) Color.White else StrataColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = StrataColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    Column {
        Text(
            text = label,
            color = StrataColors.TextSecondary,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(StrataColors.SurfaceRaised)
                .border(1.dp, StrataColors.SurfaceOverlay, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(StrataColors.AccentPrimaryBright),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
