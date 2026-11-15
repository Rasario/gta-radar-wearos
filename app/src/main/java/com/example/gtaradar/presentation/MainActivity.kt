package com.example.gtaradar.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.example.gtaradar.R
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class GtaMarker(
    val id: String = java.util.UUID.randomUUID().toString(),
    val lat: Double,
    val lng: Double,
    var category: String, // "MISSION" или "PLACE"
    var iconType: String, // "HOME", "MISSION", "WORK", "BAR", "CLOTHES", "STRIP", "SHOP", "FRIEND", "CINEMA", "CASINO", "ATM", "CUSTOM", "PLAYER"
    var title: String = "Объект",
    var goal: String = "Описание точки",
    var reward: String = "Награда / Инфо",
    var colorHex: String = "#b35fe9",
    var customImageUri: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent {
            ViceCityRadarScreen()
        }
    }
}

@Composable
fun ViceCityRadarScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val spbDefault = LatLng(59.9343, 30.3351)
    val focusRequester = remember { FocusRequester() }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasLocationPermission = isGranted }

    val savedMarkers = remember { mutableStateListOf<GtaMarker>().apply { addAll(loadMarkers(context)) } }

    var pendingCustomLat by remember { mutableDoubleStateOf(0.0) }
    var pendingCustomLng by remember { mutableDoubleStateOf(0.0) }
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var playerMarkerInstance by remember { mutableStateOf<Marker?>(null) }
    var lastKnownPlayerLatLng by remember { mutableStateOf(spbDefault) }
    var pendingPoint by remember { mutableStateOf<LatLng?>(null) }
    var creationStep by remember { mutableIntStateOf(0) }
    var selectedCategoryForCreation by remember { mutableStateOf("") }

    var showSplashScreen by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        delay(2200)
        showSplashScreen = false
        focusRequester.requestFocus()

        mapInstance?.let { map ->
            triggerSkyDrop(context, map, spbDefault) { marker, latLng ->
                playerMarkerInstance = marker
                lastKnownPlayerLatLng = latLng
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val contentResolver = context.contentResolver
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val newMarker = GtaMarker(
                lat = pendingCustomLat,
                lng = pendingCustomLng,
                category = "PLACE",
                iconType = "CUSTOM",
                title = "Свое место",
                goal = "Пользовательская точка",
                reward = "Фото из галереи",
                customImageUri = it.toString()
            )
            savedMarkers.add(newMarker)
            saveMarkers(context, savedMarkers)
            mapInstance?.let { map -> refreshAllMarkersOnMap(context, map, savedMarkers, playerMarkerInstance) {} }
            pendingPoint = null
            creationStep = 0
            focusRequester.requestFocus()
        }
    }

    var selectedMarkerData by remember { mutableStateOf<GtaMarker?>(null) }
    var missionMode by remember { mutableStateOf(false) }
    var hasActiveRoute by remember { mutableStateOf(false) }
    var currentMissionIndex by remember { mutableIntStateOf(0) }
    var mapBearing by remember { mutableFloatStateOf(0f) }
    var navMode by remember { mutableIntStateOf(0) }
    var accumulatedScroll by remember { mutableStateOf(0f) }

    DisposableEffect(navMode) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (navMode == 2 && event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientationValues = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientationValues)
                    val azimuthDeg = Math.toDegrees(orientationValues[0].toDouble()).toFloat()

                    mapInstance?.let { map ->
                        map.setCameraPosition(
                            CameraPosition.Builder()
                                .target(lastKnownPlayerLatLng)
                                .bearing(azimuthDeg.toDouble())
                                .zoom(map.cameraPosition.zoom)
                                .build()
                        )
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (navMode == 2 && rotVectorSensor != null) {
            sensorManager.registerListener(listener, rotVectorSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    val viceCityStyleUrl = "https://api.maptiler.com/maps/01a1d5d0-2761-7473-99cd-cb2b8c19f1ba/style.json?key=NvYZOCq21thpiVpuBD10"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Black)
            .clip(CircleShape)
            .border(BorderStroke(4.dp, ComposeColor(0xFFb35fe9)), CircleShape)
            .onRotaryScrollEvent { event ->
                accumulatedScroll += event.verticalScrollPixels
                if (abs(accumulatedScroll) > 25f) {
                    mapInstance?.let { map ->
                        val currentZoom = map.cameraPosition.zoom
                        val zoomDelta = if (accumulatedScroll > 0) -0.25 else 0.25
                        val targetZoom = (currentZoom + zoomDelta).coerceIn(3.0, 19.0)
                        map.easeCamera(CameraUpdateFactory.zoomTo(targetZoom), 120)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    accumulatedScroll = 0f
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        mapInstance = map
                        map.setStyle(viceCityStyleUrl) { style ->
                            map.uiSettings.isCompassEnabled = false
                            map.uiSettings.isLogoEnabled = false
                            map.uiSettings.isAttributionEnabled = false
                            map.uiSettings.setTiltGesturesEnabled(true)

                            try {
                                val bitmap = BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_bus_stop)
                                if (bitmap != null) {
                                    style.addImage("auto-bus-stop-icon", bitmap)
                                    val busLayer = SymbolLayer("automatic-bus-stops", "openmaptiles").apply {
                                        setSourceLayer("transport")
                                        setFilter(Expression.eq(Expression.get("subclass"), Expression.literal("bus_stop")))
                                        setProperties(
                                            PropertyFactory.iconImage("auto-bus-stop-icon"),
                                            PropertyFactory.iconSize(0.45f),
                                            PropertyFactory.iconAllowOverlap(true)
                                        )
                                    }
                                    style.addLayer(busLayer)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            if (style.getSource("route-source") == null) {
                                style.addSource(GeoJsonSource("route-source"))
                                val lineLayer = LineLayer("route-layer", "route-source").apply {
                                    setProperties(
                                        PropertyFactory.lineWidth(6f),
                                        PropertyFactory.lineColor(Color.parseColor("#FFD700")),
                                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                                    )
                                }
                                style.addLayer(lineLayer)
                            }

                            refreshAllMarkersOnMap(ctx, map, savedMarkers, playerMarkerInstance) { marker ->
                                playerMarkerInstance = marker
                            }

                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(spbDefault, 4.0))
                        }

                        map.addOnCameraMoveListener {
                            mapBearing = map.cameraPosition.bearing.toFloat()
                        }

                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                navMode = 0
                            }
                        }

                        map.setOnMarkerClickListener { clickedMarker ->
                            if (clickedMarker != playerMarkerInstance) {
                                val found = savedMarkers.find {
                                    it.lat == clickedMarker.position.latitude && it.lng == clickedMarker.position.longitude
                                }
                                if (found != null) {
                                    selectedMarkerData = found
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                }
                            }
                            true
                        }

                        map.addOnMapLongClickListener { point ->
                            pendingPoint = point
                            creationStep = 0
                            true
                        }
                    }
                }
            }
        )

        // Индикатор Севера "N"
        val orbitAngle = Math.toRadians((-mapBearing).toDouble())
        val radiusPx = 88.dp
        val nOffsetX = radiusPx * sin(orbitAngle).toFloat()
        val nOffsetY = radiusPx * -cos(orbitAngle).toFloat()

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = nOffsetX, y = nOffsetY)
                .size(22.dp)
                .clip(CircleShape)
                .background(ComposeColor.Black)
                .border(1.5.dp, ComposeColor(0xFFb35fe9), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = ComposeColor.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        // Кнопка сброса активного маршрута
        if (hasActiveRoute && !missionMode && !showSplashScreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .height(20.dp)
                    .width(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ComposeColor(0xCCFF3333))
                    .border(1.dp, ComposeColor.White, RoundedCornerShape(8.dp))
                    .clickable {
                        clearRouteOnMap(mapInstance)
                        hasActiveRoute = false
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("✕ СБРОСИТЬ", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
            }
        }

        // Кнопка геолокации / вращения (справа)
        if (!missionMode && !showSplashScreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (navMode == 2) ComposeColor(0xFFFF66B2) else ComposeColor(0xCC18B5B5))
                    .border(1.5.dp, ComposeColor.White, CircleShape)
                    .clickable {
                        focusRequester.requestFocus()
                        mapInstance?.let { map ->
                            when (navMode) {
                                0 -> {
                                    navMode = 1
                                    if (hasLocationPermission) {
                                        moveToCurrentLocation(context, map, spbDefault, playerMarkerInstance) { marker, latLng ->
                                            playerMarkerInstance = marker
                                            lastKnownPlayerLatLng = latLng
                                        }
                                    }
                                }
                                1 -> { navMode = 2 }
                                else -> {
                                    navMode = 0
                                    map.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400)
                                }
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("⊕", color = ComposeColor.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Кнопка "МИССИИ" внизу
        val activeMarkers = savedMarkers.toList()
        if (!missionMode && !showSplashScreen) {
            Button(
                onClick = {
                    if (activeMarkers.isNotEmpty()) {
                        missionMode = true
                        currentMissionIndex = 0
                        navMode = 0
                        val firstM = activeMarkers[0]
                        mapInstance?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(firstM.lat, firstM.lng), 16.0), 400)
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .height(24.dp)
                    .width(90.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = ComposeColor(0xFFb35fe9))
            ) {
                Text("МИССИИ", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
            }
        }

        // Стрелочки навигации слева и справа
        if (missionMode && activeMarkers.isNotEmpty() && !showSplashScreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 2.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xFF18B5B5))
                    .border(1.dp, ComposeColor.White, CircleShape)
                    .clickable {
                        currentMissionIndex = if (currentMissionIndex > 0) currentMissionIndex - 1 else activeMarkers.size - 1
                        val nextM = activeMarkers[currentMissionIndex]
                        mapInstance?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(nextM.lat, nextM.lng), 16.0), 300)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("<", color = ComposeColor.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xFF18B5B5))
                    .border(1.dp, ComposeColor.White, CircleShape)
                    .clickable {
                        currentMissionIndex = if (currentMissionIndex < activeMarkers.size - 1) currentMissionIndex + 1 else 0
                        val nextM = activeMarkers[currentMissionIndex]
                        mapInstance?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(nextM.lat, nextM.lng), 16.0), 300)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(">", color = ComposeColor.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Нижнее окошко миссии/точки
        AnimatedVisibility(
            visible = missionMode && !showSplashScreen,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(100)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .fillMaxWidth(0.72f)
                .wrapContentHeight()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ComposeColor(0xDD1A0F26))
                    .border(1.dp, ComposeColor(0xFFb35fe9), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (activeMarkers.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Нет объектов", color = ComposeColor.White, fontSize = 9.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("✕", color = ComposeColor.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { missionMode = false })
                    }
                } else {
                    val index = currentMissionIndex.coerceIn(0, activeMarkers.size - 1)
                    val currentM = activeMarkers[index]

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(currentM.title, color = ComposeColor(0xFFFF66B2), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("✕", color = ComposeColor.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                                missionMode = false
                            })
                        }

                        Text(currentM.goal, color = ComposeColor.White, fontSize = 8.sp, maxLines = 1, textAlign = TextAlign.Center)

                        Button(
                            onClick = {
                                missionMode = false
                                hasActiveRoute = true
                                fetchAndDrawRoute(mapInstance, lastKnownPlayerLatLng, LatLng(currentM.lat, currentM.lng))
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            },
                            modifier = Modifier.height(20.dp).fillMaxWidth(0.8f),
                            colors = ButtonDefaults.buttonColors(backgroundColor = ComposeColor(0xFF18B5B5))
                        ) {
                            Text("МАРШРУТ", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
                        }
                    }
                }
            }
        }

        // Меню создания объекта по долгому нажатию (вертикальный список с кнопками-таблетками)
        pendingPoint?.let { latLng ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xEE1A0F26)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(12.dp)
                ) {
                    if (creationStep == 0) {
                        Text("ЧТО СОЗДАТЬ?", color = ComposeColor(0xFFFF66B2), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PillCategoryButton(title = "МИССИЯ", color = ComposeColor(0xFFb35fe9)) {
                                selectedCategoryForCreation = "MISSION"
                                creationStep = 1
                            }
                            PillCategoryButton(title = "МЕСТО", color = ComposeColor(0xFF18B5B5)) {
                                selectedCategoryForCreation = "PLACE"
                                creationStep = 1
                            }
                        }
                    } else {
                        Text(
                            if (selectedCategoryForCreation == "MISSION") "ВЫБЕРИТЕ МИССИЮ" else "ВЫБЕРИТЕ МЕСТО",
                            color = ComposeColor(0xFFFF66B2),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.88f)
                                .height(130.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (selectedCategoryForCreation == "MISSION") {
                                val missionItems = listOf(
                                    Triple("Убежище", "HOME", "ic_mission_home"),
                                    Triple("Заказ с корешами", "MISSION", "ic_mission_star"),
                                    Triple("Дело / Работа", "WORK", "ic_work_dollar")
                                )
                                missionItems.forEach { (title, type, iconName) ->
                                    PillSelectionButton(
                                        title = title,
                                        iconName = iconName,
                                        fallbackEmoji = when(type) { "HOME" -> "💾"; "MISSION" -> "⭐"; else -> "💵" },
                                        onClick = {
                                            val rewardText = when(type) {
                                                "WORK" -> "Награда: \$${(10..1000).random()}"
                                                "MISSION" -> "Награда: ${(1..100).random()} Respect"
                                                else -> "База / Сохранение"
                                            }
                                            val newMarker = GtaMarker(
                                                lat = latLng.latitude,
                                                lng = latLng.longitude,
                                                category = "MISSION",
                                                iconType = type,
                                                title = title,
                                                goal = "Выполнить задачу",
                                                reward = rewardText
                                            )
                                            savedMarkers.add(newMarker)
                                            saveMarkers(context, savedMarkers)
                                            mapInstance?.let { refreshAllMarkersOnMap(context, it, savedMarkers, playerMarkerInstance) {} }
                                            pendingPoint = null
                                            creationStep = 0
                                            focusRequester.requestFocus()
                                        }
                                    )
                                }
                            } else {
                                val placeItems = listOf(
                                    Triple("Бар", "BAR", "ic_place_bar"),
                                    Triple("Магазин одежды", "CLOTHES", "ic_place_clothes"),
                                    Triple("Стриптиз-клуб", "STRIP", "ic_place_strip"),
                                    Triple("Продукты", "SHOP", "ic_place_shop"),
                                    Triple("Дом друга", "FRIEND", "ic_place_friend"),
                                    Triple("Кинотеатр", "CINEMA", "ic_place_cinema"),
                                    Triple("Казино", "CASINO", "ic_place_casino"),
                                    Triple("Банкомат", "ATM", "ic_place_atm"),
                                    Triple("Свое фото", "CUSTOM", null)
                                )
                                placeItems.forEach { (title, type, iconName) ->
                                    PillSelectionButton(
                                        title = title,
                                        iconName = iconName,
                                        fallbackEmoji = when(type) {
                                            "BAR" -> "🍺"
                                            "CLOTHES" -> "👕"
                                            "STRIP" -> "🔥"
                                            "SHOP" -> "🛒"
                                            "FRIEND" -> "🏠"
                                            "CINEMA" -> "🎬"
                                            "CASINO" -> "🎰"
                                            "ATM" -> "🏧"
                                            else -> "🖼️"
                                        },
                                        onClick = {
                                            if (type == "CUSTOM") {
                                                pendingCustomLat = latLng.latitude
                                                pendingCustomLng = latLng.longitude
                                                imagePickerLauncher.launch("image/*")
                                            } else {
                                                val newMarker = GtaMarker(
                                                    lat = latLng.latitude,
                                                    lng = latLng.longitude,
                                                    category = "PLACE",
                                                    iconType = type,
                                                    title = title,
                                                    goal = "Посетить место",
                                                    reward = "Точка интереса"
                                                )
                                                savedMarkers.add(newMarker)
                                                saveMarkers(context, savedMarkers)
                                                mapInstance?.let { refreshAllMarkersOnMap(context, it, savedMarkers, playerMarkerInstance) {} }
                                                pendingPoint = null
                                                creationStep = 0
                                                focusRequester.requestFocus()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "ОТМЕНА",
                        color = ComposeColor.LightGray,
                        fontSize = 8.sp,
                        modifier = Modifier.clickable {
                            pendingPoint = null
                            creationStep = 0
                            focusRequester.requestFocus()
                        }
                    )
                }
            }
        }

        // Окно редактирования маркера при клике
        selectedMarkerData?.let { marker ->
            var editTitle by remember(marker) { mutableStateOf(marker.title) }
            var editGoal by remember(marker) { mutableStateOf(marker.goal) }
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xF20F051D)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 14.dp)
                ) {
                    Text("НАСТРОЙКА", color = ComposeColor(0xFFFF66B2), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(4.dp))

                    BasicTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        textStyle = TextStyle(color = ComposeColor.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .background(ComposeColor(0xFF2A1E38), RoundedCornerShape(4.dp))
                            .padding(4.dp)
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    BasicTextField(
                        value = editGoal,
                        onValueChange = { editGoal = it },
                        textStyle = TextStyle(color = ComposeColor.LightGray, fontSize = 10.sp, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .background(ComposeColor(0xFF2A1E38), RoundedCornerShape(4.dp))
                            .padding(4.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        marker.reward,
                        color = ComposeColor(0xFFFFD700),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                savedMarkers.remove(marker)
                                saveMarkers(context, savedMarkers)
                                mapInstance?.let { refreshAllMarkersOnMap(context, it, savedMarkers, playerMarkerInstance) {} }
                                selectedMarkerData = null
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                focusRequester.requestFocus()
                            },
                            modifier = Modifier.height(24.dp).width(75.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = ComposeColor(0xFFCC2222))
                        ) {
                            Text("УДАЛИТЬ", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
                        }

                        Button(
                            onClick = {
                                marker.title = editTitle
                                marker.goal = editGoal
                                saveMarkers(context, savedMarkers)
                                mapInstance?.let { refreshAllMarkersOnMap(context, it, savedMarkers, playerMarkerInstance) {} }
                                selectedMarkerData = null
                                focusRequester.requestFocus()
                            },
                            modifier = Modifier.height(24.dp).width(50.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = ComposeColor(0xFF18B5B5))
                        ) {
                            Text("OK", fontSize = 9.sp, color = ComposeColor.White)
                        }
                    }
                }
            }
        }

        // ЭКРАН ЗАПУСКА (SPLASH SCREEN)
        AnimatedVisibility(
            visible = showSplashScreen,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(600)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xFF0F051D)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(0xFFb35fe9))
                            .border(2.dp, ComposeColor(0xFFFF66B2), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val logoResId = context.resources.getIdentifier("ic_app_logo", "drawable", context.packageName)
                        val logoBitmap = if (logoResId != 0) BitmapFactory.decodeResource(context.resources, logoResId) else null

                        if (logoBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = logoBitmap.asImageBitmap(),
                                contentDescription = "App Logo",
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Text("★", color = ComposeColor.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "GTA RADAR",
                        color = ComposeColor(0xFFFF66B2),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "VICE CITY SATELLITE",
                        color = ComposeColor(0xFF18B5B5),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// Вспомогательный компонент для кнопок-таблеток выбора категории (Миссия / Место)
@Composable
fun PillCategoryButton(title: String, color: ComposeColor, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .border(1.dp, ComposeColor.White, RoundedCornerShape(14.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = ComposeColor.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

// Вспомогательный компонент для кнопок-таблеток элементов списка (иконка слева, текст справа)
@Composable
fun PillSelectionButton(
    title: String,
    iconName: String?,
    fallbackEmoji: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    Box(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(ComposeColor(0xFF2A1E38))
            .border(1.dp, ComposeColor(0xFFb35fe9), RoundedCornerShape(15.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Левая иконка из drawable или фоллбэк с эмодзи
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xFF18B5B5)),
                contentAlignment = Alignment.Center
            ) {
                var loadedBmp: Bitmap? = null
                if (iconName != null) {
                    try {
                        val resId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
                        if (resId != 0) {
                            loadedBmp = BitmapFactory.decodeResource(context.resources, resId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (loadedBmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = loadedBmp.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Text(fallbackEmoji, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Название справа
            Text(
                text = title,
                color = ComposeColor.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@SuppressLint("MissingPermission")
private fun triggerSkyDrop(
    context: Context,
    map: MapLibreMap,
    fallback: LatLng,
    onPlayerReady: (Marker?, LatLng) -> Unit
) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
        val target = if (location != null) LatLng(location.latitude, location.longitude) else fallback

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(15.0)
                    .tilt(25.0)
                    .build()
            ),
            1800
        )

        val playerMarkerData = GtaMarker(lat = target.latitude, lng = target.longitude, category = "PLAYER", iconType = "PLAYER")
        val added = addGtaMarkerToMap(context, map, playerMarkerData)
        onPlayerReady(added, target)
    }
}

private fun fetchAndDrawRoute(map: MapLibreMap?, start: LatLng, destination: LatLng) {
    if (map == null) return
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val urlStr = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson"
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "GTARadarWearApp")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val routes = json.getJSONArray("routes")
                if (routes.length() > 0) {
                    val geometry = routes.getJSONObject(0).getJSONObject("geometry")
                    val coordinates = geometry.getJSONArray("coordinates")

                    val points = mutableListOf<Point>()
                    for (i in 0 until coordinates.length()) {
                        val coord = coordinates.getJSONArray(i)
                        val lng = coord.getDouble(0)
                        val lat = coord.getDouble(1)
                        points.add(Point.fromLngLat(lng, lat))
                    }

                    withContext(Dispatchers.Main) {
                        map.style?.let { style ->
                            var source = style.getSourceAs<GeoJsonSource>("route-source")
                            if (source == null) {
                                source = GeoJsonSource("route-source")
                                style.addSource(source)
                            }
                            if (style.getLayer("route-layer") == null) {
                                val lineLayer = LineLayer("route-layer", "route-source").apply {
                                    setProperties(
                                        PropertyFactory.lineWidth(6f),
                                        PropertyFactory.lineColor(Color.parseColor("#FFD700")),
                                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                                    )
                                }
                                style.addLayer(lineLayer)
                            }
                            source.setGeoJson(LineString.fromLngLats(points))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private fun clearRouteOnMap(map: MapLibreMap?) {
    map?.style?.let { style ->
        val source = style.getSourceAs<GeoJsonSource>("route-source")
        source?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }
}

@SuppressLint("MissingPermission")
private fun moveToCurrentLocation(
    context: Context,
    map: MapLibreMap,
    fallback: LatLng,
    playerMarkerRef: Marker?,
    onPlayerMarkerCreated: (Marker?, LatLng) -> Unit
) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
        val target = if (location != null) LatLng(location.latitude, location.longitude) else fallback
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0), 400)

        if (playerMarkerRef != null) {
            playerMarkerRef.position = target
            onPlayerMarkerCreated(playerMarkerRef, target)
        } else {
            val playerMarkerData = GtaMarker(lat = target.latitude, lng = target.longitude, category = "PLAYER", iconType = "PLAYER")
            val added = addGtaMarkerToMap(context, map, playerMarkerData)
            onPlayerMarkerCreated(added, target)
        }
    }
}

private fun refreshAllMarkersOnMap(
    context: Context,
    map: MapLibreMap,
    markers: List<GtaMarker>,
    playerMarkerRef: Marker?,
    onPlayerCreated: (Marker?) -> Unit
) {
    val existingAnnotations = map.annotations.filterIsInstance<Marker>()

    existingAnnotations.forEach { annotation ->
        val isPlayer = (annotation == playerMarkerRef)
        if (!isPlayer) {
            val stillExists = markers.any { m ->
                abs(annotation.position.latitude - m.lat) < 0.00001 && abs(annotation.position.longitude - m.lng) < 0.00001
            }
            if (!stillExists) {
                map.removeAnnotation(annotation)
            }
        }
    }

    markers.forEach { m ->
        val found = existingAnnotations.find {
            abs(it.position.latitude - m.lat) < 0.00001 && abs(it.position.longitude - m.lng) < 0.00001
        }
        if (found == null) {
            val added = addGtaMarkerToMap(context, map, m)
            if (m.iconType == "PLAYER") onPlayerCreated(added)
        }
    }
}

private fun addGtaMarkerToMap(context: Context, map: MapLibreMap, marker: GtaMarker): Marker? {
    val size = 76
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    if (marker.iconType == "PLAYER") {
        val arrowPath = Path().apply {
            moveTo(cx, cy - 26f)
            lineTo(cx + 20f, cy + 22f)
            lineTo(cx, cy + 12f)
            lineTo(cx - 20f, cy + 22f)
            close()
        }
        fillPaint.color = Color.WHITE
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
    } else {
        var customBmp: Bitmap? = null

        if (marker.iconType == "CUSTOM" && marker.customImageUri != null) {
            try {
                val uri = Uri.parse(marker.customImageUri)
                val inputStream = context.contentResolver.openInputStream(uri)
                customBmp = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            val iconResName = when (marker.iconType) {
                "HOME" -> "ic_mission_home"
                "MISSION" -> "ic_mission_star"
                "WORK" -> "ic_work_dollar"
                "BAR" -> "ic_place_bar"
                "CLOTHES" -> "ic_place_clothes"
                "STRIP" -> "ic_place_strip"
                "SHOP" -> "ic_place_shop"
                "FRIEND" -> "ic_place_friend"
                "CINEMA" -> "ic_place_cinema"
                "CASINO" -> "ic_place_casino"
                "ATM" -> "ic_place_atm"
                else -> null
            }

            if (iconResName != null) {
                try {
                    val resId = context.resources.getIdentifier(iconResName, "drawable", context.packageName)
                    if (resId != 0) {
                        customBmp = BitmapFactory.decodeResource(context.resources, resId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (customBmp != null) {
            val scaledBmp = Bitmap.createScaledBitmap(customBmp, size, size, true)
            canvas.drawBitmap(scaledBmp, 0f, 0f, null)
        } else {
            val badgeRect = RectF(10f, 10f, size - 10f, size - 10f)
            fillPaint.color = Color.parseColor(marker.colorHex)
            canvas.drawRoundRect(badgeRect, 12f, 12f, fillPaint)
            canvas.drawRoundRect(badgeRect, 12f, 12f, strokePaint)

            val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.BLACK
            }

            when (marker.iconType) {
                "HOME" -> {
                    val floopyBody = RectF(cx - 14f, cy - 14f, cx + 14f, cy + 14f)
                    canvas.drawRect(floopyBody, symbolPaint)
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
                    canvas.drawRect(cx - 9f, cy - 14f, cx + 9f, cy - 2f, labelPaint)
                    val shutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.LTGRAY }
                    canvas.drawRect(cx - 6f, cy + 2f, cx + 6f, cy + 14f, shutterPaint)
                }
                "MISSION" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
                    canvas.drawText("★", cx, cy + 12f, textPaint)
                }
                "WORK" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
                    canvas.drawText("$", cx, cy + 11f, textPaint)
                }
                "BAR" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("🍺", cx, cy + 10f, textPaint)
                }
                "CLOTHES" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("👕", cx, cy + 10f, textPaint)
                }
                "STRIP" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("🔥", cx, cy + 10f, textPaint)
                }
                "SHOP" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("🛒", cx, cy + 10f, textPaint)
                }
                "FRIEND" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("🏠", cx, cy + 10f, textPaint)
                }
                "CINEMA" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("🎬", cx, cy + 10f, textPaint)
                }
                "CASINO" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("🎰", cx, cy + 10f, textPaint)
                }
                "ATM" -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
                    canvas.drawText("🏧", cx, cy + 10f, textPaint)
                }
            }
        }
    }

    val icon = IconFactory.getInstance(context).fromBitmap(bitmap)
    return map.addMarker(MarkerOptions().position(LatLng(marker.lat, marker.lng)).icon(icon))
}

private fun saveMarkers(context: Context, markers: List<GtaMarker>) {
    val prefs = context.getSharedPreferences("gta_radar_prefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    markers.filter { it.iconType != "PLAYER" }.forEach {
        val obj = JSONObject().apply {
            put("id", it.id)
            put("lat", it.lat)
            put("lng", it.lng)
            put("category", it.category)
            put("type", it.iconType)
            put("title", it.title)
            put("goal", it.goal)
            put("reward", it.reward)
            put("color", it.colorHex)
            put("customUri", it.customImageUri ?: "")
        }
        array.put(obj)
    }
    prefs.edit().putString("markers", array.toString()).apply()
}

private fun loadMarkers(context: Context): List<GtaMarker> {
    val prefs = context.getSharedPreferences("gta_radar_prefs", Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("markers", null) ?: return emptyList()
    val array = JSONArray(jsonStr)
    val list = mutableListOf<GtaMarker>()
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
            GtaMarker(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                lat = obj.getDouble("lat"),
                lng = obj.getDouble("lng"),
                category = obj.optString("category", "MISSION"),
                iconType = obj.getString("type"),
                title = obj.optString("title", "Объект"),
                goal = obj.optString("goal", "Описание"),
                reward = obj.optString("reward", "Инфо"),
                colorHex = obj.optString("color", "#b35fe9"),
                customImageUri = obj.optString("customUri", "").takeIf { it.isNotEmpty() }
            )
        )
    }
    return list
}