package com.worldexplorer.app.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worldexplorer.app.R
import com.worldexplorer.domain.usecase.UpdateSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val updateSettings: UpdateSettingsUseCase) : ViewModel() {
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            updateSettings { it.copy(onboardingCompleted = true) }
            onDone()
        }
    }
}

@Composable
fun OnboardingRoute(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    OnboardingScreen(onFinished = { viewModel.complete(onFinished) })
}

private data class Page(val icon: ImageVector, val title: Int, val text: Int)

private val pages = listOf(
    Page(Icons.Filled.Explore, R.string.onboarding_title_1, R.string.onboarding_text_1),
    Page(Icons.Filled.Cloud, R.string.onboarding_title_2, R.string.onboarding_text_2),
    Page(Icons.Filled.Shield, R.string.onboarding_title_3, R.string.onboarding_text_3),
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState { pages.size + 1 }
    val scope = rememberCoroutineScope()
    val last = pagerState.currentPage == pages.size
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pages.size) } }) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).testTag("onboarding_pager")) { index ->
            if (index < pages.size) InfoPage(pages[index]) else PermissionPage()
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size + 1) { i ->
                Box(
                    Modifier.padding(4.dp).size(if (i == pagerState.currentPage) 10.dp else 8.dp)
                        .background(if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        }
        Button(
            onClick = { if (last) onFinished() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("onboarding_next"),
        ) {
            Text(stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next))
        }
    }
}

@Composable
private fun InfoPage(page: Page) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(120.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(page.icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.height(32.dp))
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(page.text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionPage() {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var location by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var notifications by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> location = result.values.any { it } }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.onboarding_title_4), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_text_4), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        PermissionRow(Icons.Filled.LocationOn, R.string.onboarding_permission_location, R.string.onboarding_permission_location_desc, location, "grant_location") {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Spacer(Modifier.height(12.dp))
            PermissionRow(Icons.Filled.Notifications, R.string.onboarding_permission_notifications, R.string.onboarding_permission_notifications_desc, notifications, "grant_notifications") {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onboarding_later_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: Int, desc: Int, granted: Boolean, tag: String, onGrant: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.onboarding_granted), tint = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = onGrant, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.action_grant)) }
            }
        }
    }
}
