package br.com.viaagora

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class MapMarker(val id: String, val latitude: Double, val longitude: Double, val color: String, val terminal: Boolean)
data class MapRoute(val points: List<Pair<Double, Double>>, val color: String)

@Suppress("DEPRECATION")
class MapPanel(context: Context, savedState: Bundle?, private val onMarker: (String) -> Unit,
    private val onPoint: (Double, Double) -> Unit, private val onLocation: () -> Unit) : FrameLayout(context) {
    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var items: List<MapMarker> = emptyList()
    private var routes: List<MapRoute> = emptyList()
    private var source: GeoJsonSource? = null
    private var routeSource: GeoJsonSource? = null
    private val ui = Ui(context)
    private val notice = ui.text("Carregando mapa…", 13f, Palette.ink)
    init {
        setBackgroundColor(Color.parseColor("#E8EEF2"))
        MapLibre.getInstance(context)
        mapView = MapView(context)
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapView.onCreate(savedState)
        notice.background = ui.rounded(Color.WHITE, 12)
        notice.setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        addView(notice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(context.dp(10),context.dp(10),context.dp(10),0) })
        val locate = ui.text("◎", 28f, Palette.ink).apply {
            gravity = Gravity.CENTER; background = ui.rounded(Color.WHITE, 24); elevation = context.dp(3).toFloat()
            contentDescription = "Minha posição"; isClickable = true; setOnClickListener { onLocation() }
        }
        addView(locate, LayoutParams(context.dp(48), context.dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0,context.dp(12),context.dp(12),0) })
        mapView.addOnDidFailLoadingMapListener { notice.visibility = VISIBLE; notice.text = "Mapa indisponível sem internet" }
        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.uiSettings.isCompassEnabled = false
            loaded.uiSettings.isLogoEnabled = false
            loaded.uiSettings.isAttributionEnabled = true
            loaded.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(START_LAT,START_LNG)).zoom(10.4).build()
            loaded.setStyle(Style.Builder().fromJson(STYLE)) { style ->
                // Linha de cada travessia (traçado da balsa) por baixo dos terminais.
                routeSource = GeoJsonSource("ferry-routes",FeatureCollection.fromFeatures(emptyList()))
                style.addSource(routeSource!!)
                style.addLayer(LineLayer("ferry-lines","ferry-routes").withProperties(
                    lineColor(Expression.toColor(Expression.get("color"))), lineCap(Property.LINE_CAP_ROUND),
                    lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(9, 2.5f), Expression.stop(15, 6f)))))
                source = GeoJsonSource("ferry-points",FeatureCollection.fromFeatures(emptyList()))
                style.addSource(source!!)
                // Bolinhas pequenas na visão geral e maiores de perto, para não se sobreporem fora do lugar.
                val radius = Expression.toNumber(Expression.get("radius"))
                style.addLayer(CircleLayer("ferry-markers","ferry-points").withProperties(
                    circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(9, Expression.product(Expression.literal(0.45f), radius)), Expression.stop(15, radius))),
                    circleColor(Expression.toColor(Expression.get("color"))),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f)))
                notice.visibility = GONE
                // Enquadra todos os terminais, qualquer que seja o tamanho da tela.
                val bounds = LatLngBounds.Builder().includes(crossingCatalog.flatMap { c -> listOf(c.pointA, c.pointB) }.map { LatLng(it.latitude, it.longitude) }).build()
                post { runCatching { loaded.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, context.dp(56))) } }
                renderMarkers()
            }
            loaded.addOnMapLongClickListener { point -> onPoint(point.latitude,point.longitude); true }
            loaded.addOnMapClickListener { point ->
                val screen = loaded.projection.toScreenLocation(point)
                val radius = context.dp(24).toFloat()
                val features = loaded.queryRenderedFeatures(RectF(screen.x-radius,screen.y-radius,screen.x+radius,screen.y+radius),"ferry-markers")
                val id = features.firstOrNull()?.getStringProperty("id")
                if(id!=null){onMarker(id);true}else false
            }
        }
    }
    fun update(data: List<MapMarker>, lines: List<MapRoute>) { items = data; routes = lines; renderMarkers() }
    fun center(): Pair<Double, Double> {
        val target = map?.cameraPosition?.target
        return Pair(target?.latitude ?: START_LAT, target?.longitude ?: START_LNG)
    }
    fun focus(latitude: Double, longitude: Double) { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude,longitude),15.0)) }
    private fun renderMarkers() {
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(routes.filter { it.points.size >= 2 }.map { route ->
            Feature.fromGeometry(LineString.fromLngLats(route.points.map { Point.fromLngLat(it.second, it.first) })).apply { addStringProperty("color", route.color) }
        }))
        source?.setGeoJson(FeatureCollection.fromFeatures(items.map { item ->
            Feature.fromGeometry(Point.fromLngLat(item.longitude,item.latitude)).apply {
                addStringProperty("id",item.id)
                addStringProperty("color",item.color)
                addNumberProperty("radius",if(item.terminal)14f else 9f)
            }
        }))
    }
    fun start() = mapView.onStart()
    fun resume() = mapView.onResume()
    fun pause() = mapView.onPause()
    fun stop() = mapView.onStop()
    fun destroy() = mapView.onDestroy()
    fun lowMemory() = mapView.onLowMemory()
    fun save(bundle: Bundle) = mapView.onSaveInstanceState(bundle)
    companion object {
        private const val START_LAT = -23.925
        private const val START_LNG = -46.215
        private const val STYLE = """{"version":8,"name":"ViaAgora","sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E8EEF2"}},{"id":"streets","type":"raster","source":"osm"}]}"""
    }
}
