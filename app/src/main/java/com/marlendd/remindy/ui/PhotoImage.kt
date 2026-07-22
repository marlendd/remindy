package com.marlendd.remindy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.marlendd.remindy.photo.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Асинхронно декодирует фото места из PhotoStore под нужный размер (декод с диска –
 * не на main). Пока грузится или файла нет – null (вызывающий рисует заглушку/ничего).
 */
@Composable
fun rememberPhotoBitmap(fileName: String?, maxDim: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(fileName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(fileName) {
        bitmap = if (fileName == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                PhotoStore.decodeScaled(context, fileName, maxDim)?.asImageBitmap()
            }
        }
    }
    return bitmap
}
