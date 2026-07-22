package com.marlendd.remindy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marlendd.remindy.R
import com.marlendd.remindy.data.Item
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Скруглённый угол карточки записи – общий для списка и результатов поиска. */
val RecordCardShape = RoundedCornerShape(18.dp)

/**
 * Содержимое кнопки «иконка + текст» для контент-Row кнопки Material (он уже центрирует по
 * вертикали). Иконка красится в contentColor кнопки (через LocalContentColor), текст – рядом.
 * contentDescription = null: смысл несёт видимый текст.
 */
@Composable
fun IconLabel(iconRes: Int, text: String, fontSize: TextUnit, iconSize: Dp = 26.dp) {
    Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(iconSize))
    Spacer(Modifier.width(12.dp))
    Text(text, fontSize = fontSize)
}

/**
 * Крупная карточка записи – общая для списка (Фаза 4) и результатов поиска. Тёплая
 * поверхность с мягкой тенью и тонкой рамкой, иерархия: имя 24sp bold, место 20sp,
 * дата 14sp вторичным цветом. Тап по карточке открывает правку.
 */
@Composable
fun RecordRow(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RecordCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                // Запись-факт (место не указано): строки места НЕТ вовсе – карточка
                // компактнее, и по силуэту видно, что это «просто запомнить», а не вещь
                if (item.location.isNotBlank()) {
                    Text(item.location, fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp))
                }
                val dateText = remember(item.updatedAt) { formatRuDate(item.updatedAt) }
                Text(
                    stringResource(R.string.row_updated, dateText),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // Миниатюра фото места, если снято (открывается тапом по карточке → правка)
            if (item.photoFile != null) {
                val thumb = rememberPhotoBitmap(item.photoFile, 256)
                if (thumb != null) {
                    Spacer(Modifier.width(12.dp))
                    Image(
                        thumb,
                        contentDescription = null, // информация дублируется текстом карточки
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
    }
}

private fun formatRuDate(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ru")).format(Date(epochMs))
