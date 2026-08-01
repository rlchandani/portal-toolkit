package com.portalinstaller.ui.more

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
import com.portalinstaller.viewmodel.HistoryEntry
import java.util.Date

@Composable
internal fun MoreTab(
    context: Context,
    history: List<HistoryEntry>,
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit
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
                onSelectLanguage = onSelectLanguage
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
    onSelectLanguage: (AppLanguage) -> Unit
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
            pkgInfo.versionName ?: "1.1.0"
        }.getOrDefault("1.1.0")
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

/**
 * Serializeaza istoricul de operatii ca JSON si il salveaza in
 * SharedPreferences, ca sa persiste intre repornirile aplicatiei.
 * Limitam la ultimele 100 de intrari, ca sa nu crestem nelimitat
 * dimensiunea datelor salvate.
 */
internal fun saveHistoryToPrefs(prefs: SharedPreferences, history: List<HistoryEntry>) {
    val jsonArray = org.json.JSONArray()
    history.take(100).forEach { entry ->
        val obj = org.json.JSONObject()
        obj.put("timestamp", entry.timestamp)
        obj.put("description", entry.description)
        obj.put("success", entry.success)
        jsonArray.put(obj)
    }
    prefs.edit().putString("operation_history", jsonArray.toString()).apply()
}

/**
 * Deserializeaza istoricul salvat din SharedPreferences. Daca nu exista
 * date salvate sau JSON-ul este corupt, intoarce o lista goala fara sa
 * arunce o exceptie vizibila utilizatorului.
 */
internal fun loadHistoryFromPrefs(prefs: SharedPreferences): List<HistoryEntry> {
    val raw = prefs.getString("operation_history", null) ?: return emptyList()
    return try {
        val jsonArray = org.json.JSONArray(raw)
        (0 until jsonArray.length()).map { i ->
            val obj = jsonArray.getJSONObject(i)
            HistoryEntry(
                timestamp = obj.getLong("timestamp"),
                description = obj.getString("description"),
                success = obj.getBoolean("success")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
