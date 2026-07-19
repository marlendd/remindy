package com.marlendd.remindy.security

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.OnboardingActivity
import com.marlendd.remindy.R
import com.marlendd.remindy.data.BackupCodec
import com.marlendd.remindy.data.BackupFormatException
import com.marlendd.remindy.data.BackupRecord
import com.marlendd.remindy.data.RecordRepository
import com.marlendd.remindy.data.RemindyDatabase
import com.marlendd.remindy.ui.UiScale
import com.marlendd.remindy.ui.theme.RemindyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Настройки замка чтения (Фаза 4, ТЗ F4: замок опционален). Сам под гейтом: чтобы менять
 * настройки безопасности, надо сперва войти (иначе замок можно было бы снять без кода).
 *
 * Замок ВКЛючён по умолчанию (решение пользователя). Выключение НЕ удаляет код (дремлет),
 * чтобы повторное включение не требовало заново его придумывать.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var appPin: AppPin

    private var ready by mutableStateOf(false)
    private var lockEnabled by mutableStateOf(true)
    private var biometricEnabled by mutableStateOf(true)
    private var biometricSupported by mutableStateOf(false)
    private var pinSet by mutableStateOf(false)

    private val gateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) ready = true else finish()
        }

    // Включение замка при отсутствии кода: сначала установить код; при отказе – замок не включаем
    private val enableLockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pinSet = true
                LockSettings.setLockEnabled(this, true)
                lockEnabled = true
            }
        }

    private val changePinLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pinSet = true
                Toast.makeText(this, R.string.settings_pin_changed, Toast.LENGTH_SHORT).show()
            }
        }

    // --- Резервная копия ------------------------------------------------------

    private var repository: RecordRepository? = null

    private var busy by mutableStateOf(false)
    private var showExportPassword by mutableStateOf(false)
    private var showImportPassword by mutableStateOf(false)
    private var showImportConfirm by mutableStateOf(false)
    private var importCount by mutableStateOf(0)

    private var exportPassword: String? = null
    private var pendingImportUri: Uri? = null
    private var pendingRestore: List<BackupRecord>? = null

    // SAF: пользователь сам выбирает, куда сохранить и откуда взять файл – без storage-разрешений
    private val createDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) runExport(uri) else exportPassword = null
        }

    private val openDocLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingImportUri = uri
                showImportPassword = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents()
        enableEdgeToEdge()
        UiScale.ensureLoaded(this)
        appPin = AppPin(this)

        lockEnabled = LockSettings.isLockEnabled(this)
        biometricEnabled = LockSettings.isBiometricEnabled(this)
        biometricSupported = biometricAvailable()

        setContent { RemindyTheme { if (ready) SettingsScreen() } }

        loadPinSetState()
        loadRepository()

        // Настройки безопасности – только после входа (если замок включён)
        if (LockSettings.isLockEnabled(this) && !ReadGate.unlocked) {
            gateLauncher.launch(Intent(this, UnlockActivity::class.java))
        } else {
            ready = true
        }
    }

    private fun loadPinSetState() {
        lifecycleScope.launch {
            pinSet = withContext(Dispatchers.IO) { appPin.isSet() }
        }
    }

    // База нужна для экспорта/восстановления. Открытие тяжёлое (Keystore + SQLCipher) –
    // на IO; до готовности кнопки бэкапа выключены. Если ключ Keystore потерян – null,
    // бэкап просто недоступен (не роняем экран настроек).
    private fun loadRepository() {
        lifecycleScope.launch {
            repository = try {
                RecordRepository(RemindyDatabase.getAsync(this@SettingsActivity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        Scaffold { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.title_settings),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        SettingSwitch(
                            title = stringResource(R.string.settings_lock),
                            summary = stringResource(R.string.settings_lock_summary),
                            checked = lockEnabled,
                            onChange = ::onLockToggled,
                        )
                        if (biometricSupported) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingSwitch(
                                title = stringResource(R.string.settings_biometric),
                                summary = null,
                                checked = biometricEnabled,
                                onChange = ::onBiometricToggled,
                            )
                        }
                    }
                }

                if (lockEnabled) {
                    Spacer(Modifier.size(20.dp))
                    OutlinedButton(
                        onClick = ::onChangePin,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.settings_change_pin), fontSize = 20.sp)
                    }
                }

                // Резервная копия: зашифрованный экспорт/импорт записей под отдельным
                // паролем. Защита от необратимой потери – ключ основной БД живёт в
                // Keystore и теряется при переустановке/сбросе устройства.
                Spacer(Modifier.size(28.dp))
                Text(
                    stringResource(R.string.settings_backup_section),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                val backupEnabled = repository != null && !busy
                OutlinedButton(
                    onClick = ::startExport,
                    enabled = backupEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.settings_backup_export), fontSize = 18.sp)
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = ::startImport,
                    enabled = backupEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.settings_backup_import), fontSize = 18.sp)
                }
                if (busy) {
                    Spacer(Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.backup_working),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                // Масштаб интерфейса (доступность: крупнее для слабого зрения).
                // Выбор применяется мгновенно ко всему UI, включая сам этот экран.
                Spacer(Modifier.size(28.dp))
                Text(
                    stringResource(R.string.settings_scale),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                ScaleOption(stringResource(R.string.scale_normal), UiScale.NORMAL)
                Spacer(Modifier.size(8.dp))
                ScaleOption(stringResource(R.string.scale_large), UiScale.LARGE)
                Spacer(Modifier.size(8.dp))
                ScaleOption(stringResource(R.string.scale_xlarge), UiScale.XLARGE)

                // Повторно открыть подсказку «как пользоваться» (тот же экран, что на первом запуске)
                Spacer(Modifier.size(28.dp))
                OutlinedButton(
                    onClick = { startActivity(Intent(this@SettingsActivity, OnboardingActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.settings_help), fontSize = 18.sp)
                }

                if (showExportPassword) {
                    PasswordDialog(
                        title = stringResource(R.string.settings_backup_export),
                        confirmLabel = stringResource(R.string.btn_backup_save),
                        withRepeat = true,
                        onDismiss = {
                            showExportPassword = false
                            exportPassword = null
                        },
                        onConfirm = ::onExportPasswordEntered,
                    )
                }
                if (showImportPassword) {
                    PasswordDialog(
                        title = stringResource(R.string.settings_backup_import),
                        confirmLabel = stringResource(R.string.btn_backup_restore),
                        withRepeat = false,
                        onDismiss = {
                            showImportPassword = false
                            pendingImportUri = null
                        },
                        onConfirm = ::onImportPasswordEntered,
                    )
                }
                if (showImportConfirm) {
                    AlertDialog(
                        onDismissRequest = ::cancelImport,
                        title = { Text(stringResource(R.string.backup_import_title)) },
                        text = { Text(stringResource(R.string.backup_import_msg, importCount)) },
                        confirmButton = {
                            TextButton(onClick = ::doRestore) {
                                Text(stringResource(R.string.btn_backup_restore))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = ::cancelImport) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun PasswordDialog(
        title: String,
        confirmLabel: String,
        withRepeat: Boolean,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit,
    ) {
        var pass by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        var show by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val shortMsg = stringResource(R.string.backup_password_short)
        val mismatchMsg = stringResource(R.string.backup_password_mismatch)
        val transformation = if (show) VisualTransformation.None else PasswordVisualTransformation()
        val kbd = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it; error = null },
                        singleLine = true,
                        label = {
                            Text(stringResource(if (withRepeat) R.string.backup_password_new else R.string.backup_password_enter))
                        },
                        visualTransformation = transformation,
                        keyboardOptions = kbd,
                        textStyle = TextStyle(fontSize = 20.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (withRepeat) {
                        Spacer(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = repeat,
                            onValueChange = { repeat = it; error = null },
                            singleLine = true,
                            label = { Text(stringResource(R.string.backup_password_repeat)) },
                            visualTransformation = transformation,
                            keyboardOptions = kbd,
                            textStyle = TextStyle(fontSize = 20.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = show, onCheckedChange = { show = it })
                        Text(stringResource(R.string.backup_password_show), fontSize = 16.sp)
                    }
                    if (withRepeat) {
                        Text(
                            stringResource(R.string.backup_password_warning),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (error != null) {
                        Text(
                            error!!,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pass.length < MIN_BACKUP_PASSWORD -> error = shortMsg
                        withRepeat && pass != repeat -> error = mismatchMsg
                        else -> onConfirm(pass)
                    }
                }) { Text(confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }

    @Composable
    private fun ScaleOption(label: String, value: Float) {
        val selected = UiScale.factor == value
        val mod = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        val onClick = { UiScale.set(this@SettingsActivity, value) }
        if (selected) {
            Button(onClick = onClick, modifier = mod) { Text(label, fontSize = 18.sp) }
        } else {
            OutlinedButton(onClick = onClick, modifier = mod) { Text(label, fontSize = 18.sp) }
        }
    }

    @Composable
    private fun SettingSwitch(
        title: String,
        summary: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, fontSize = 20.sp)
                if (summary != null) {
                    Text(
                        summary,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    // --- Действия -------------------------------------------------------------

    private fun onLockToggled(enabled: Boolean) {
        if (enabled) {
            if (pinSet) {
                LockSettings.setLockEnabled(this, true)
                lockEnabled = true
            } else {
                // кода ещё нет – сперва установить, замок включим по успеху (см. launcher)
                enableLockLauncher.launch(forceSetupIntent())
            }
        } else {
            LockSettings.setLockEnabled(this, false)
            lockEnabled = false
        }
    }

    private fun onBiometricToggled(enabled: Boolean) {
        LockSettings.setBiometricEnabled(this, enabled)
        biometricEnabled = enabled
    }

    private fun onChangePin() {
        changePinLauncher.launch(forceSetupIntent())
    }

    // --- Экспорт/восстановление -----------------------------------------------

    // Пустую базу не экспортируем: проверяем до диалога пароля и SAF, чтобы не создавать
    // пустой файл и не гонять пользователя зря.
    private fun startExport() {
        val repo = repository ?: return
        busy = true
        lifecycleScope.launch {
            val count = try {
                withContext(Dispatchers.IO) { repo.exportRecords().size }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                Toast.makeText(this@SettingsActivity, R.string.backup_export_err, Toast.LENGTH_LONG).show()
                return@launch
            }
            busy = false
            if (count == 0) {
                Toast.makeText(this@SettingsActivity, R.string.backup_export_empty, Toast.LENGTH_LONG).show()
            } else {
                showExportPassword = true
            }
        }
    }

    private fun onExportPasswordEntered(password: String) {
        exportPassword = password
        showExportPassword = false
        createDocLauncher.launch(defaultBackupName())
    }

    private fun runExport(uri: Uri) {
        val repo = repository
        val password = exportPassword
        if (repo == null || password == null) {
            // Редкий случай: процесс убили, пока был открыт системный пикер, и пароль
            // потерялся. Не молчим – сообщаем, чтобы пользователь повторил.
            exportPassword = null
            Toast.makeText(this, R.string.backup_export_err, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val records = repo.exportRecords()
                    val payload = BackupCodec.encode(records)
                    val chars = password.toCharArray()
                    val envelope = try {
                        BackupCrypto.encrypt(payload, chars)
                    } finally {
                        chars.fill(' ')
                    }
                    contentResolver.openOutputStream(uri)?.use { it.write(envelope) }
                        ?: throw IOException("Нет доступа к файлу")
                    records.size
                }
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.backup_export_ok, count),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.backup_export_err, Toast.LENGTH_LONG).show()
            } finally {
                exportPassword = null
                busy = false
            }
        }
    }

    private fun startImport() {
        openDocLauncher.launch(arrayOf("*/*"))
    }

    // Файл читаем и расшифровываем СРАЗУ (валидация пароля/формата), но данные пока не
    // трогаем: показываем подтверждение замены с числом записей. Реальная замена – в doRestore.
    private fun onImportPasswordEntered(password: String) {
        val uri = pendingImportUri ?: return
        showImportPassword = false
        busy = true
        lifecycleScope.launch {
            val result: Result<List<BackupRecord>> = withContext(Dispatchers.IO) {
                val chars = password.toCharArray()
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Нет доступа к файлу")
                    val payload = BackupCrypto.decrypt(bytes, chars)
                    Result.success(BackupCodec.decode(payload))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    chars.fill(' ')
                }
            }
            busy = false
            result.fold(
                onSuccess = { records ->
                    if (records.isEmpty()) {
                        // Валидная, но пустая копия: не предлагаем замену (иначе подтверждение
                        // «заменить 0 записей» тихо стёрло бы все текущие данные).
                        Toast.makeText(this@SettingsActivity, R.string.backup_import_empty, Toast.LENGTH_LONG).show()
                        pendingImportUri = null
                    } else {
                        pendingRestore = records
                        importCount = records.size
                        showImportConfirm = true
                    }
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is WrongPasswordException -> R.string.backup_import_wrong_pass
                        is BadBackupFileException, is BackupFormatException -> R.string.backup_import_bad_file
                        else -> R.string.backup_import_err
                    }
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    pendingImportUri = null
                },
            )
        }
    }

    private fun doRestore() {
        val repo = repository
        val records = pendingRestore
        showImportConfirm = false
        if (repo == null || records == null) {
            cancelImport()
            Toast.makeText(this, R.string.backup_import_err, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        lifecycleScope.launch {
            try {
                val inserted = withContext(Dispatchers.IO) { repo.replaceAllRecords(records) }
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.backup_import_ok, inserted),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.backup_import_err, Toast.LENGTH_LONG).show()
            } finally {
                pendingRestore = null
                pendingImportUri = null
                busy = false
            }
        }
    }

    private fun cancelImport() {
        showImportConfirm = false
        pendingRestore = null
        pendingImportUri = null
    }

    private fun defaultBackupName(): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "gde-lezhit-$date.rmdy"
    }

    private fun forceSetupIntent(): Intent =
        Intent(this, UnlockActivity::class.java)
            .putExtra(UnlockActivity.EXTRA_FORCE_SETUP, true)

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
}

// Минимальная длина пароля резервной копии. Файл может лежать в облаке/почте,
// поэтому не короче 6 символов (отдельно от 4-значного PIN-кода приложения).
private const val MIN_BACKUP_PASSWORD = 6
