package org.rjpd.msdc

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.rjpd.msdc.databinding.ActivityMapVisualizationBinding
import java.io.File

class MapVisualizationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapVisualizationBinding
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val basePath = File(cacheDir, "osmdroid")
        val tileCache = File(basePath, "tiles")
        Configuration.getInstance().osmdroidBasePath = basePath
        Configuration.getInstance().osmdroidTileCache = tileCache
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMapVisualizationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val csvPath = intent.getStringExtra(EXTRA_GPS_CSV_PATH)?.let { File(it) }
        val titleName = intent.getStringExtra(EXTRA_COLLECTION_NAME) ?: getString(R.string.title_activity_map)
        supportActionBar?.title = titleName

        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        if (csvPath != null && csvPath.exists()) {
            val gpsPoints = parseGpsCsv(csvPath)
            if (gpsPoints.isNotEmpty()) {
                renderGpsRoute(gpsPoints)
            } else {
                Toast.makeText(this, R.string.no_gps_points_found, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, R.string.no_gps_points_found, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderGpsRoute(gpsPoints: List<GpsPoint>) {
        val geoPoints = gpsPoints.map { GeoPoint(it.latitude, it.longitude) }

        val line = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = getColor(R.color.teal_700)
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(line)

        val startPoint = gpsPoints.first()
        val startMarker = Marker(mapView).apply {
            position = GeoPoint(startPoint.latitude, startPoint.longitude)
            title = "Start Point"
            snippet = "Time: ${startPoint.datetimeUtc}\nAccuracy: ${startPoint.accuracy}m"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            createColoredMarkerDrawable(getColor(R.color.teal_700))?.let { icon = it }
        }
        mapView.overlays.add(startMarker)

        if (gpsPoints.size > 1) {
            val endPoint = gpsPoints.last()
            val endMarker = Marker(mapView).apply {
                position = GeoPoint(endPoint.latitude, endPoint.longitude)
                title = "End Point"
                snippet = "Time: ${endPoint.datetimeUtc}\nAccuracy: ${endPoint.accuracy}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                createColoredMarkerDrawable(getColor(R.color.red_700))?.let { icon = it }
            }
            mapView.overlays.add(endMarker)
        }

        val initialBox = BoundingBox.fromGeoPoints(geoPoints)
        val minSpan = 0.008

        val latCenter = initialBox.centerLatitude
        val lonCenter = initialBox.centerLongitude

        val latSpan = Math.max(initialBox.latitudeSpan, minSpan)
        @Suppress("DEPRECATION")
        val lonSpan = Math.max(initialBox.longitudeSpan, minSpan)

        val paddedBox = BoundingBox(
            latCenter + latSpan / 2.0,
            lonCenter + lonSpan / 2.0,
            latCenter - latSpan / 2.0,
            lonCenter - lonSpan / 2.0
        )

        mapView.post {
            mapView.zoomToBoundingBox(paddedBox, true, 80)
        }
    }

    private fun createColoredMarkerDrawable(color: Int): android.graphics.drawable.Drawable? {
        val defaultDrawable = androidx.core.content.ContextCompat.getDrawable(
            this,
            org.osmdroid.library.R.drawable.marker_default
        )?.mutate() ?: return null

        val wrappedDrawable = androidx.core.graphics.drawable.DrawableCompat.wrap(defaultDrawable)
        androidx.core.graphics.drawable.DrawableCompat.setTint(wrappedDrawable, color)
        return wrappedDrawable
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_GPS_CSV_PATH = "extra_gps_csv_path"
        const val EXTRA_COLLECTION_NAME = "extra_collection_name"
    }
}
