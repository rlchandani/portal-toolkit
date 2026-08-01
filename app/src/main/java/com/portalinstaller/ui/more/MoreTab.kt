package com.portalinstaller.ui.more

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.SectionCardShape
import com.portalinstaller.util.AppLanguage
import com.portalinstaller.data.store.HistoryEntry
import com.portalinstaller.data.store.ThemeMode

@Composable
internal fun MoreTab(
    context: Context,
    history: List<HistoryEntry>,
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSection) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text(stringResource(R.string.tab_history)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = { Text(stringResource(R.string.tab_settings)) }
            )
        }

        if (selectedSection == 0) {
            HistoryTab(history)
        } else {
            SettingsTab(
                context = context,
                selectedLanguage = selectedLanguage,
                onSelectLanguage = onSelectLanguage,
                themeMode = themeMode,
                onSelectThemeMode = onSelectThemeMode,
                useDynamicColor = useDynamicColor,
                onToggleDynamicColor = onToggleDynamicColor
            )
        }
    }
}

@Composable
internal fun HistoryTab(history: List<HistoryEntry>) {
    if (history.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val dateFormat = remember {
        java.text.SimpleDateFormat("dd MMM, HH:mm:ss", java.util.Locale.getDefault())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(history) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (entry.success) "+" else "-",
                        color = if (entry.success) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = dateFormat.format(java.util.Date(entry.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTab(
    context: Context,
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit
) {
    val versionName = remember(context) {
        runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: "1.2.0"
        }.getOrDefault("1.2.0")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_language_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_language_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val systemLanguage = AppLanguage.SYSTEM
                    FilterChip(
                        selected = selectedLanguage == systemLanguage,
                        onClick = { onSelectLanguage(systemLanguage) },
                        label = {
                            Text(
                                text = stringResource(systemLanguage.labelRes),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppLanguage.entries
                            .filterNot { it == systemLanguage }
                            .forEach { language ->
                                FilterChip(
                                    selected = selectedLanguage == language,
                                    onClick = { onSelectLanguage(language) },
                                    label = {
                                        Text(
                                            text = stringResource(language.labelRes),
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                    }
                }
            }
        }

        item {
            AppearanceCard(
                themeMode = themeMode,
                onSelectThemeMode = onSelectThemeMode,
                useDynamicColor = useDynamicColor,
                onToggleDynamicColor = onToggleDynamicColor
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_about_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.settings_app_name_value, stringResource(R.string.app_name)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_app_version_value, versionName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_portal_support_value),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionCardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_appearance_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_theme_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            ThemeMode.entries.forEach { mode ->
                ThemeModeRow(
                    mode = mode,
                    selected = themeMode == mode,
                    onSelect = { onSelectThemeMode(mode) }
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleDynamicColor(!useDynamicColor) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_description),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useDynamicColor,
                        onCheckedChange = onToggleDynamicColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val labelRes = when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
