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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.david.sundaydrive.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.maps.GeoApiContext
import com.google.maps.DirectionsApi
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

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

    // Initialize Places Client
    val placesClient = remember {
        if (!Places.isInitialized()) {
            Places.initialize(context, BuildConfig.MAPS_API_KEY)
        }
        Places.createClient(context)
    }

    // Initialize GeoApiContext for Directions API
    val geoApiContext = remember {
        GeoApiContext.Builder()
            .apiKey(BuildConfig.MAPS_API_KEY)
            .build()
    }

    // Route State
    var startName by remember { mutableStateOf("") }
    var startLatLng by remember { mutableStateOf<LatLng?>(null) }
    var endName by remember { mutableStateOf("") }
    var endLatLng by remember { mutableStateOf<LatLng?>(null) }
    var routePolyline by remember { mutableStateOf<List<LatLng>>(emptyList()) }

    var maxDetourMiles by remember { mutableStateOf(5f) }
    var isTourActive by remember { mutableStateOf(false) }

    // State for POIs found by AI
    val poiMarkers = remember { mutableStateListOf<Pair<LatLng, String>>() }
    var isLoading by remember { mutableStateOf(false) }

    // AI Model
    val generativeModel = remember {
        GenerativeModel(
            modelName = "gemini-1.0-pro",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    // Camera
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(39.8283, -98.5795), 3f) // Center of US
    }

    // Helper to zoom to fit all points
    fun zoomToFit(newMarkers: List<LatLng>) {
        val builder = LatLngBounds.Builder()
        startLatLng?.let { builder.include(it) }
        endLatLng?.let { builder.include(it) }
        newMarkers.forEach { builder.include(it) }
        routePolyline.forEach { builder.include(it) }
        
        try {
            if (newMarkers.isNotEmpty() || (startLatLng != null && endLatLng != null)) {
                // Prefer start location if markers are empty but route exists
                val target = if (newMarkers.isNotEmpty()) newMarkers[0] else startLatLng
                target?.let {
                     cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 8f)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    // Helper to fetch route
    fun fetchRoute() {
        if (startLatLng == null || endLatLng == null) return
        
        scope.launch(Dispatchers.IO) {
            try {
                val result = DirectionsApi.newRequest(geoApiContext)
                    .mode(TravelMode.DRIVING)
                    .origin(com.google.maps.model.LatLng(startLatLng!!.latitude, startLatLng!!.longitude))
                    .destination(com.google.maps.model.LatLng(endLatLng!!.latitude, endLatLng!!.longitude))
                    .await()
                
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
            cameraPositionState = cameraPositionState
        ) {
            // Draw Route Line from API
            if (routePolyline.isNotEmpty()) {
                Polyline(
                    points = routePolyline,
                    color = if (isTourActive) Color.Green else Color.Blue,
                    width = 15f
                )
            } else if (startLatLng != null && endLatLng != null) {
                // Fallback: straight line
                Polyline(
                    points = listOf(startLatLng!!, endLatLng!!),
                    color = if (isTourActive) Color.Green else Color.Blue,
                    width = 15f
                )
            }

            // Start Marker
            startLatLng?.let {
                Marker(
                    state = MarkerState(position = it),
                    title = "Start: $startName",
                    snippet = "Departure"
                )
            }

            // End Marker
            endLatLng?.let {
                Marker(
                    state = MarkerState(position = it),
                    title = "End: $endName",
                    snippet = "Destination"
                )
            }

            // AI Markers
            poiMarkers.forEach { (latLng, title) ->
                Marker(
                    state = MarkerState(position = latLng),
                    title = title,
                    snippet = "Click for Audio Tour",
                    icon = com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(
                        com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_MAGENTA
                    ),
                    onClick = {
                        scope.launch {
                            // Generate interesting fact
                            val prompt = "Tell me a fun fact about $title."
                            try {
                                val fact = generativeModel.generateContent(prompt).text ?: "This is $title."
                                tts?.speak(fact, TextToSpeech.QUEUE_FLUSH, null, null)
                                Toast.makeText(context, "Playing Audio...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Log.e("MapsScreen", "Error generating fact for $title", e)
                                tts?.speak("This is $title", TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        }
                        false // Return false to allow default behavior (info window)
                    }
                )
            }
        }

        // Control Panel Overlay (Only show if tour is NOT active)
        if (!isTourActive) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    LocationAutocompleteField(
                        label = "From",
                        initialValue = startName,
                        placesClient = placesClient,
                        onPlaceSelected = { name, latLng ->
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
                        label = "To",
                        initialValue = endName,
                        placesClient = placesClient,
                        onPlaceSelected = { name, latLng ->
                            endName = name
                            endLatLng = latLng
                            poiMarkers.clear()
                            fetchRoute()
                        },
                        icon = Icons.Default.Navigation
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Max Detour Distance: ${maxDetourMiles.roundToInt()} Miles", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = maxDetourMiles,
                        onValueChange = { maxDetourMiles = it },
                        valueRange = 5f..30f,
                        steps = 24, // (30 - 5) / 1 - 1 = 24 steps
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // START ROUTE BUTTON
                    Button(
                        onClick = {
                            if (startLatLng == null || endLatLng == null) {
                                Toast.makeText(context, "Please select both Start and End locations.", Toast.LENGTH_SHORT).show()
                            } else if (poiMarkers.isEmpty()) {
                                Toast.makeText(context, "Please tap Search (magnifying glass) first to find stops!", Toast.LENGTH_LONG).show()
                            } else {
                                isTourActive = true
                                tts?.speak("Starting tour to $endName. Tap any purple marker to hear about it.", TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400)) // Dark Green
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Start Tour Mode")
                    }
                }
            }
        } else {
            // Active Tour Controls
            Button(
                onClick = { 
                    isTourActive = false 
                    tts?.stop()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("Stop Tour")
            }
        }

        // Loading
        if (isLoading) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.medium)
                    .padding(16.dp)
            ) {
                Text("AI is analyzing route...", color = Color.White)
            }
        }

        // Search Action (Only show if NOT active tour)
        if (!isTourActive) {
            FloatingActionButton(
                onClick = {
                    if (startLatLng == null || endLatLng == null) {
                        Toast.makeText(context, "Please select both Start and End locations.", Toast.LENGTH_SHORT).show()
                    } else if (!isLoading) {
                        isLoading = true
                        scope.launch {
                            // SIMPLIFIED PROMPT to avoid safety filters
                            val prompt = "List 3 famous tourist attractions between $startName and $endName within ${maxDetourMiles.roundToInt()} miles detour. Format: Name; Name; Name"
                            
                            try {
                                val response = generativeModel.generateContent(prompt)
                                val placeNamesString = response.text ?: ""
                                
                                withContext(Dispatchers.Main) {
                                     Toast.makeText(context, "AI suggests: $placeNamesString", Toast.LENGTH_SHORT).show()
                                }

                                val placeNames = placeNamesString.split(";").map { it.trim() }

                                val geocoder = Geocoder(context, Locale.US)
                                poiMarkers.clear()
                                
                                placeNames.forEach { name ->
                                    if (name.isNotEmpty()) {
                                        val results = withContext(Dispatchers.IO) {
                                            try {
                                                @Suppress("DEPRECATION")
                                                geocoder.getFromLocationName(name, 1)
                                            } catch (e: Exception) {
                                                Log.e("MapsScreen", "Geocoding error for $name", e)
                                                null 
                                            }
                                        }

                                        if (!results.isNullOrEmpty()) {
                                            val location = results[0]
                                            poiMarkers.add(LatLng(location.latitude, location.longitude) to name)
                                        }
                                    }
                                }
                                
                                if (poiMarkers.isNotEmpty()) {
                                     zoomToFit(poiMarkers.map { it.first })
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "AI found names but Geocoder couldn't find coordinates. Try a major highway.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                
                            } catch (e: Exception) {
                                Log.e("MapsScreen", "AI Search Error", e)
                                // Detailed error logging
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "AI Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Find POIs")
            }
        }
    }
}

@Composable
fun LocationAutocompleteField(
    label: String,
    initialValue: String,
    placesClient: PlacesClient,
    onPlaceSelected: (String, LatLng) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    var query by remember(initialValue) { mutableStateOf(initialValue) }
    var predictions by remember { mutableStateOf<List<com.google.android.libraries.places.api.model.AutocompletePrediction>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { newValue ->
                query = newValue
                expanded = true
                
                if (newValue.length > 2) {
                    val token = AutocompleteSessionToken.newInstance()
                    val request = FindAutocompletePredictionsRequest.builder()
                        .setSessionToken(token)
                        .setQuery(newValue)
                        .build()

                    placesClient.findAutocompletePredictions(request)
                        .addOnSuccessListener { response ->
                            predictions = response.autocompletePredictions
                        }
                        .addOnFailureListener {
                            predictions = emptyList()
                        }
                }
            },
            label = { Text(label) },
            trailingIcon = { Icon(icon, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (expanded && predictions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn {
                    items(predictions) { prediction ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val placeId = prediction.placeId
                                    val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)
                                    val request = com.google.android.libraries.places.api.net.FetchPlaceRequest.newInstance(placeId, placeFields)

                                    placesClient.fetchPlace(request)
                                        .addOnSuccessListener { response ->
                                            val place = response.place
                                            val latLng = place.latLng
                                            if (latLng != null) {
                                                // We do NOT update 'query' here automatically to avoid confusion if the user wants to edit it
                                                // Instead, we callback. The parent might update initialValue, which triggers the remember(initialValue) above
                                                query = place.name ?: prediction.getPrimaryText(null).toString()
                                                onPlaceSelected(query, latLng)
                                                expanded = false
                                                predictions = emptyList()
                                            }
                                        }
                                }
                                .padding(16.dp)
                        ) {
                            Text(text = prediction.getPrimaryText(null).toString())
                        }
                    }
                }
            }
        }
    }
}