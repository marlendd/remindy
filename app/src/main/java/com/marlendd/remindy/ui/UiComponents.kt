package com.marlendd.remindy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marlendd.remindy.R
import com.marlendd.remindy.data.Item
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Крупная карточка записи – общая для списка (Фаза 4, порт `item_row.xml`) и результатов
 * поиска. Размеры 1:1 с исходным XML: имя 24sp bold, место 20sp, дата 14sp вторичным цветом.
 */
@Composable
fun RecordRow(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(item.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(item.location, fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp))
        val dateText = remember(item.updatedAt) { formatRuDate(item.updatedAt) }
        Text(
            stringResource(R.string.row_updated, dateText),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun formatRuDate(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ru")).format(Date(epochMs))
