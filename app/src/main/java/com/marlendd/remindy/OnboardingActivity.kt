package com.marlendd.remindy

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marlendd.remindy.ui.UiScale
import com.marlendd.remindy.ui.theme.RemindyTheme

/**
 * Краткий онбординг: что это, как записать голосом, как найти. Открывается автоматически
 * при первом запуске (из [MainActivity]) и вручную из настроек («Как пользоваться»).
 * Флаг «показан» ставит сам MainActivity по возврату – здесь только экран и кнопка.
 * Ничего чувствительного не показывает, поэтому без гейта и protectFromRecents.
 */
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UiScale.ensureLoaded(this)
        setContent { RemindyTheme { OnboardingScreen(onDone = ::finish) } }
    }
}

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.onboarding_intro),
            fontSize = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(28.dp))
        Step(R.drawable.ic_mic, stringResource(R.string.onboarding_step_speak))
        Spacer(Modifier.size(20.dp))
        Step(R.drawable.ic_search, stringResource(R.string.onboarding_step_find))
        Spacer(Modifier.size(20.dp))
        Step(R.drawable.ic_list, stringResource(R.string.onboarding_step_list))

        Spacer(Modifier.size(28.dp))
        Text(
            stringResource(R.string.onboarding_offline),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(32.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        ) {
            Text(stringResource(R.string.onboarding_done), fontSize = 24.sp)
        }
    }
}

@Composable
private fun Step(iconRes: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 19.sp, modifier = Modifier.weight(1f))
    }
}
