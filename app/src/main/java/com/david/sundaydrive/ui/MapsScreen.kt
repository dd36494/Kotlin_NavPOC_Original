package com.david.sundaydrive.ui

import android.location.Geocoder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.sundaydrive.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockReason
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun MapsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // TTS Engine
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.ERROR) {
                tts?.language = Locale.US
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    // Initialize Clients
    val placesClient = remember {
        if (!Places.isInitialized()) {
            Places.initialize(context, BuildConfig.MAPS_API_KEY)
        }
        Places.createClient(context)
    }

    val geoApiContext = remember {
        GeoApiContext.Builder().apiKey(BuildConfig.MAPS_API_KEY).build()
    }

    // AI Model
    val generativeModel = remember {
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    // Route State
    var startName by remember { mutableStateOf("") }
    var startLatLng by remember { mutableStateOf<LatLng?>(null) }
    var endName by remember { mutableStateOf("") }
    var endLatLng by remember { mutableStateOf<LatLng?>(null) }
    var routePolyline by remember { mutableStateOf<List<LatLng>>(emptyList()) }

    var maxDetourMiles by remember { mutableStateOf(5f) }
    var isTourActive by remember { mutableStateOf(false) }

    // POIs and Waypoints
    val poiMarkers = remember { mutableStateListOf<Pair<LatLng, String>>() }
    val selectedWaypoints = remember { mutableStateListOf<Pair<LatLng, String>>() }
    var previewPoi by remember { mutableStateOf<Pair<LatLng, String>?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Proximity Trigger State
    var mockUserLocation by remember { mutableStateOf<LatLng?>(null) }
    val announcedWaypoints = remember { mutableStateListOf<String>() }

    // Camera
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(39.8283, -98.5795), 3f)
    }

    // Helper: Haversine distance in miles
    fun calculateDistanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3958.8 
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    // Helper: Distance from a point to a line segment (Simplified crosstrack distance)
    fun distanceToRouteMiles(point: LatLng, route: List<LatLng>): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        var minDistance = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val d = calculateDistanceMiles(point.latitude, point.longitude, route[i].latitude, route[i].longitude)
            if (d < minDistance) minDistance = d
        }
        return minDistance
    }

    // Proximity Effect: Trigger audio fact when within 3 miles
    LaunchedEffect(isTourActive, mockUserLocation) {
        if (isTourActive && mockUserLocation != null) {
            val allStops = selectedWaypoints + (endLatLng?.let { listOf(it to endName) } ?: emptyList())
            allStops.forEach { (latLng, name) ->
                if (!announcedWaypoints.contains(name)) {
                    val dist = calculateDistanceMiles(
                        mockUserLocation!!.latitude, mockUserLocation!!.longitude,
                        latLng.latitude, latLng.longitude
                    )
                    if (dist <= 3.0) {
                        announcedWaypoints.add(name)
                        val prompt = "Tell me a fun fact about $name as we are approaching it."
                        try {
                            val fact = generativeModel.generateContent(prompt).text ?: "Approaching $name."
                            tts?.speak(fact, TextToSpeech.QUEUE_FLUSH, null, null)
                        } catch (e: Exception) {
                            tts?.speak("Now arriving at $name", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                }
            }
        }
    }

    // Helper to zoom to fit all points
    fun zoomToFit(newMarkers: List<LatLng>) {
        val builder = LatLngBounds.Builder()
        startLatLng?.let { builder.include(it) }
        endLatLng?.let { builder.include(it) }
        newMarkers.forEach { builder.include(it) }
        selectedWaypoints.forEach { builder.include(it.first) }
        routePolyline.forEach { builder.include(it) }
        
        try {
            if (newMarkers.isNotEmpty() || (startLatLng != null && endLatLng != null)) {
                val target = if (newMarkers.isNotEmpty()) newMarkers[0] else startLatLng
                target?.let {
                     cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 8f)
                }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    // Helper to fetch optimized route
    fun fetchRoute() {
        if (startLatLng == null || endLatLng == null) return
        
        scope.launch(Dispatchers.IO) {
            try {
                val result = DirectionsApi.newRequest(geoApiContext)
                    .mode(TravelMode.DRIVING)
                    .origin(com.google.maps.model.LatLng(startLatLng!!.latitude, startLatLng!!.longitude))
                    .destination(com.google.maps.model.LatLng(endLatLng!!.latitude, endLatLng!!.longitude))
                    .let { request ->
                        if (selectedWaypoints.isNotEmpty()) {
                            val waypointLatLngs = selectedWaypoints.map { 
                                com.google.maps.model.LatLng(it.first.latitude, it.first.longitude) 
                            }
                            request.waypoints(*waypointLatLngs.toTypedArray())
                            request.optimizeWaypoints(true)
                        }
                        request.await()
                    }
                
                if (result.routes.isNotEmpty()) {
                    val decodedPath = result.routes[0].overviewPolyline.decodePath()
                    val newPolyline = decodedPath.map { LatLng(it.lat, it.lng) }
                    withContext(Dispatchers.Main) {
                        routePolyline = newPolyline
                        zoomToFit(poiMarkers.map { it.first })
                    }
                }
            } catch (e: Exception) {
                Log.e("MapsScreen", "Error fetching route", e)
                withContext(Dispatchers.Main) {
                     Toast.makeText(context, "Could not fetch route: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapClick = { previewPoi = null }
        ) {
            MapContent(
                isTourActive = isTourActive,
                routePolyline = routePolyline,
                startLatLng = startLatLng,
                startName = startName,
                endLatLng = endLatLng,
                endName = endName,
                poiMarkers = poiMarkers,
                selectedWaypoints = selectedWaypoints,
                onPoiClick = { previewPoi = it }
            )
        }

        // --- UI Overlays ---
        if (!isTourActive) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                // Top Control Card
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.85f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        LocationAutocompleteField(
                            value = startName,
                            onValueChange = { startName = it },
                            label = "From",
                            placesClient = placesClient,
                            onPlaceSelected = { name, latLng, _ ->
                                startName = name
                                startLatLng = latLng
                                cameraPositionState.position = CameraPosition.fromLatLngZoom(latLng, 8f)
                                poiMarkers.clear()
                                fetchRoute()
                            },
                            icon = Icons.Default.Place
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        LocationAutocompleteField(
                            value = endName,
                            onValueChange = { endName = it },
                            label = "To",
                            placesClient = placesClient,
                            onPlaceSelected = { name, latLng, _ ->
                                endName = name
                                endLatLng = latLng
                                poiMarkers.clear()
                                fetchRoute()
                            },
                            icon = Icons.Default.Navigation
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("Max Detour: ${maxDetourMiles.roundToInt()} Miles", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = maxDetourMiles,
                            onValueChange = { maxDetourMiles = it },
                            valueRange = 5f..30f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (startLatLng != null && endLatLng != null) {
                                    isTourActive = true
                                    mockUserLocation = startLatLng
                                    announcedWaypoints.clear()
                                    tts?.speak("Starting tour to $endName.", TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Start Tour")
                        }
                    }
                }

                // Bottom Interaction Card
                previewPoi?.let { poi ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp, start = 16.dp, end = 16.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = poi.second, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Button(
                                    onClick = {
                                        cameraPositionState.position = CameraPosition.fromLatLngZoom(poi.first, 14f)
                                        // Play audio on preview
                                        scope.launch {
                                            val prompt = "Tell me a fun fact about ${poi.second}."
                                            try {
                                                val fact = generativeModel.generateContent(prompt).text ?: "This is ${poi.second}."
                                                tts?.speak(fact, TextToSpeech.QUEUE_FLUSH, null, null)
                                            } catch (e: Exception) {
                                                tts?.speak("This is ${poi.second}", TextToSpeech.QUEUE_FLUSH, null, null)
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null)
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text("Preview")
                                }

                                val isAdded = selectedWaypoints.any { it.second == poi.second }
                                Button(
                                    onClick = {
                                        if (!isAdded) {
                                            selectedWaypoints.add(poi)
                                            fetchRoute()
                                        }
                                        previewPoi = null
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isAdded
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(if (isAdded) "In Path" else "Add to Path")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Tour Mode Overlay
            Button(
                onClick = { isTourActive = false },
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) { Text("Stop Tour") }

            Button(
                onClick = {
                    scope.launch {
                        selectedWaypoints.forEach { delay(2000); mockUserLocation = it.first }
                        delay(2000); mockUserLocation = endLatLng
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
            ) { Text("Simulate Proximity") }
        }

        if (isLoading) {
            Box(modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(24.dp)) {
                Text("AI is analyzing route...", color = Color.White)
            }
        }

        if (!isTourActive) {
            FloatingActionButton(
                onClick = {
                    if (startLatLng != null && endLatLng != null && routePolyline.isNotEmpty() && !isLoading) {
                        isLoading = true
                        scope.launch {
                            val prompt = "List 10 interesting tourist attractions located strictly along the drive between $startName and $endName. The detour from the main route must be less than ${maxDetourMiles.roundToInt()} miles. Provide only the names separated by semicolons. Example: Attraction A; Attraction B"
                            try {
                                val response = generativeModel.generateContent(prompt)
                                val names = (response.text ?: "").split(";").map { it.trim() }
                                val geocoder = Geocoder(context, Locale.US)
                                poiMarkers.clear()
                                
                                names.forEach { name ->
                                    if (name.isNotEmpty()) {
                                        val results = withContext(Dispatchers.IO) {
                                            try { @Suppress("DEPRECATION") geocoder.getFromLocationName("$name, US", 1) } catch (e: Exception) { null }
                                        }
                                        if (!results.isNullOrEmpty()) {
                                            val loc = LatLng(results[0].latitude, results[0].longitude)
                                            // More precise check: distance to the actual road polyline
                                            val distToRoad = distanceToRouteMiles(loc, routePolyline)
                                            if (distToRoad <= maxDetourMiles) {
                                                poiMarkers.add(loc to name)
                                            }
                                        }
                                    }
                                }
                                if (poiMarkers.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "No points found within $maxDetourMiles miles of the road. Try a larger detour.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                zoomToFit(poiMarkers.map { it.first })
                            } catch (e: Exception) { Log.e("MapsScreen", "AI Error", e) }
                            finally { isLoading = false }
                        }
                    } else if (routePolyline.isEmpty()) {
                        Toast.makeText(context, "Wait for the route to load first.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Icon(Icons.Default.Search, contentDescription = "Search") }
        }
    }
}

@GoogleMapComposable
@Composable
fun MapContent(
    isTourActive: Boolean,
    routePolyline: List<LatLng>,
    startLatLng: LatLng?,
    startName: String,
    endLatLng: LatLng?,
    endName: String,
    poiMarkers: List<Pair<LatLng, String>>,
    selectedWaypoints: List<Pair<LatLng, String>>,
    onPoiClick: (Pair<LatLng, String>) -> Unit
) {
    if (routePolyline.isNotEmpty()) {
        Polyline(points = routePolyline, color = if (isTourActive) Color.Green else Color.Blue, width = 15f)
    }
    startLatLng?.let { Marker(state = MarkerState(position = it), title = "Start: $startName") }
    endLatLng?.let { Marker(state = MarkerState(position = it), title = "End: $endName") }
    poiMarkers.forEach { poi ->
        if (!selectedWaypoints.contains(poi)) {
            Marker(
                state = MarkerState(position = poi.first),
                title = poi.second,
                icon = com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_MAGENTA),
                onClick = { onPoiClick(poi); true }
            )
        }
    }
    selectedWaypoints.forEach { poi ->
        Marker(
            state = MarkerState(position = poi.first),
            title = "Stop: ${poi.second}",
            icon = com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_ORANGE),
            onClick = { onPoiClick(poi); true }
        )
    }
}

@Composable
fun LocationAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placesClient: PlacesClient,
    onPlaceSelected: (String, LatLng, List<Place.Type>?) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    val sessionToken = remember { AutocompleteSessionToken.newInstance() }

    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true
                if (it.length > 2) {
                    val request = FindAutocompletePredictionsRequest.builder().setSessionToken(sessionToken).setQuery(it).build()
                    placesClient.findAutocompletePredictions(request).addOnSuccessListener { response -> predictions = response.autocompletePredictions }.addOnFailureListener { predictions = emptyList() }
                }
            },
            label = { Text(label) },
            trailingIcon = { Icon(icon, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (expanded && predictions.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().height(200.dp), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                LazyColumn {
                    items(predictions) { prediction ->
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            val placeId = prediction.placeId
                            val request = FetchPlaceRequest.builder(placeId, listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.TYPES)).setSessionToken(sessionToken).build()
                            placesClient.fetchPlace(request).addOnSuccessListener { response ->
                                val place = response.place
                                val latLng = place.latLng
                                if (latLng != null) {
                                    onValueChange(place.name ?: prediction.getPrimaryText(null).toString())
                                    onPlaceSelected(place.name ?: prediction.getPrimaryText(null).toString(), latLng, place.placeTypes as List<Place.Type>?)
                                    expanded = false; predictions = emptyList()
                                }
                            }
                        }.padding(16.dp)) { Text(text = prediction.getFullText(null).toString()) }
                    }
                }
            }
        }
    }
}
