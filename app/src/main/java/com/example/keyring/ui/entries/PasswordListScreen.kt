package com.example.keyring.ui.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.keyring.R
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.util.RelativeTimeFormatter

@Composable
fun PasswordListScreen(
    repository: PasswordEntryRepository,
    onEntryClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by repository.observeEntries().collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current

    if (entries.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.list_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.list_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            itemsIndexed(
                items = entries,
                key = { _, entry -> entry.id }
            ) { index, entry ->
                PasswordEntryRow(
                    entry = entry,
                    relativeTime = RelativeTimeFormatter.formatPast(context, entry.updatedAt),
                    onClick = { onEntryClick(entry.id) }
                )
                if (index < entries.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}
