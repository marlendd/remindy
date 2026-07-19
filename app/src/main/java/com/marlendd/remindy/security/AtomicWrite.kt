package com.marlendd.remindy.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Атомарная запись файла: во временный рядом + fsync + rename. Обрыв процесса не
 * оставит битого целевого файла (максимум – осиротевший .tmp, который перезапишется
 * следующей записью). Общая для [AppPin] и [KeystorePassphraseManager].
 */
internal fun atomicWrite(target: File, data: ByteArray) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(data)
        out.flush()
        out.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        tmp.delete()
        throw IOException("Не удалось атомарно записать $target")
    }
}
