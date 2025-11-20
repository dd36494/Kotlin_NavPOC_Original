package com.example.myapplication.ui

import android.location.Geocoder
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.myapplication.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun MapsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Define our Route (Los Angeles to San Diego)
    val startLocation = LatLng(34.0549, -118.2426) // LA
    val endLocation = LatLng(32.7157, -117.1611)   // SD
    val routePoints = listOf(startLocation, endLocation)

    // State for POIs found by AI
    val poiMarkers = remember { mutableStateListOf<Pair<LatLng, String>>() }
    var isLoading by remember { mutableStateOf(false) }

    // AI Model
    val generativeModel = remember {
        GenerativeModel(
            modelName = "gemini-pro",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    // Camera centered between the two points
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(33.38, -117.7), 8f)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState
        ) {
            // Draw the route line
            Polyline(
                points = routePoints,
                color = Color.Blue,
                width = 10f
            )

            // Start Marker
            Marker(
                state = MarkerState(position = startLocation),
                title = "Start: Los Angeles",
                snippet = "Departure"
            )

            // End Marker
            Marker(
                state = MarkerState(position = endLocation),
                title = "End: San Diego",
                snippet = "Destination"
            )

            // AI Suggested Markers
            poiMarkers.forEach { (latLng, title) ->
                Marker(
                    state = MarkerState(position = latLng),
                    title = title,
                    snippet = "AI Suggested Stop",
                    icon = com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(
                        com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_MAGENTA
                    )
                )
            }
        }

        // Overlay UI
        if (isLoading) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.White.copy(alpha = 0.8f))
                    .padding(16.dp)
            ) {
                Text("AI is finding stops...")
            }
        }

        FloatingActionButton(
            onClick = {
                if (!isLoading) {
                    isLoading = true
                    scope.launch {
                        // 1. Ask AI
                        val prompt = "I am driving from Los Angeles to San Diego. " +
                                "List 3 famous tourist stops directly along this route. " +
                                "Return ONLY the names of the places separated by a semicolon ';'. " +
                                "Example: Place A; Place B; Place C"
                        
                        try {
                            val response = generativeModel.generateContent(prompt)
                            val placeNamesString = response.text ?: ""
                            
                            // 2. Process Names
                            val placeNames = placeNamesString.split(";").map { it.trim() }

                            // 3. Geocode (Convert Name -> LatLng)
                            val geocoder = Geocoder(context, Locale.US)
                            
                            poiMarkers.clear()
                            
                            placeNames.forEach { name ->
                                if (name.isNotEmpty()) {
                                    // Geocoding is blocking, move to IO thread
                                    val results = withContext(Dispatchers.IO) {
                                        try {
                                            // Helper for different Android versions
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                 // API 33+ approach could be implemented here with listener, 
                                                 // but synchronous getFromLocationName is easier for this POC structure 
                                                 // if wrapped in IO context.
                                                 @Suppress("DEPRECATION")
                                                 geocoder.getFromLocationName(name, 1)
                                            } else {
                                                @Suppress("DEPRECATION")
                                                geocoder.getFromLocationName(name, 1)
                                            }
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }

                                    if (!results.isNullOrEmpty()) {
                                        val location = results[0]
                                        poiMarkers.add(
                                            LatLng(location.latitude, location.longitude) to name
                                        )
                                    }
                                }
                            }
                            
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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