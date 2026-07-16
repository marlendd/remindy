package com.marlendd.remindy.security

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.marlendd.remindy.R
import com.marlendd.remindy.ui.theme.RemindyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectFromRecents()
        enableEdgeToEdge()
        appPin = AppPin(this)

        lockEnabled = LockSettings.isLockEnabled(this)
        biometricEnabled = LockSettings.isBiometricEnabled(this)
        biometricSupported = biometricAvailable()

        setContent { RemindyTheme { if (ready) SettingsScreen() } }

        loadPinSetState()

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

    @Composable
    private fun SettingsScreen() {
        Scaffold { inner ->
            Column(
                Modifier.fillMaxSize().padding(inner).padding(16.dp),
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
            }
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

    private fun forceSetupIntent(): Intent =
        Intent(this, UnlockActivity::class.java)
            .putExtra(UnlockActivity.EXTRA_FORCE_SETUP, true)

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
}
