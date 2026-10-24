package com.worldexplorer.app.core.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

/**
 * Hosts a MapLibre [MapView] in Compose and forwards the lifecycle to it. [onReady] is called once
 * with a controller after the map is created; [onLoadFailed] when the style or tiles cannot load.
 */
@Composable
fun MapLibreView(
    modifier: Modifier = Modifier,
    onReady: (ExplorationMapController) -> Unit,
    onLoadFailed: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready = rememberUpdatedState(onReady)
    val failed = rememberUpdatedState(onLoadFailed)
    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            addOnDidFailLoadingMapListener { message -> failed.value(message) }
            getMapAsync { map -> ready.value(ExplorationMapController(context, map, this)) }
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}
