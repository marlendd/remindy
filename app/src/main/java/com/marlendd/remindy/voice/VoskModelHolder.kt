package com.marlendd.remindy.voice

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vosk.Model
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Общая на процесс модель Vosk: распаковывается и грузится в память один раз,
 * переиспользуется и записью, и поиском (иначе каждый экран держал бы свою копию
 * модели ~50 МБ). Модель живёт до конца процесса, экраны её не закрывают.
 */
object VoskModelHolder {

    @Volatile private var model: Model? = null
    private val mutex = Mutex()

    suspend fun get(context: Context): Model {
        model?.let { return it }
        return mutex.withLock {
            model ?: unpack(context.applicationContext).also { model = it }
        }
    }

    private suspend fun unpack(context: Context): Model =
        suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context, "model-ru-small", "model",
                { loaded -> cont.resume(loaded) },
                { error -> cont.resumeWithException(error) },
            )
        }
}
