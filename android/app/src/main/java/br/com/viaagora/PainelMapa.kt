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

// Mapa (MapLibre + OpenStreetMap) com os terminais coloridos pela espera, as rotas das balsas e os relatos.
data class MarcadorMapa(val id: String, val latitude: Double, val longitude: Double, val color: String, val terminal: Boolean)
data class RotaMapa(val pontos: List<Pair<Double, Double>>, val color: String)

@Suppress("DEPRECATION")
class PainelMapa(context: Context, estadoSalvo: Bundle?, private val aoTocarMarcador: (String) -> Unit,
    private val aoEscolherPonto: (Double, Double) -> Unit, private val aoLocalizar: () -> Unit) : FrameLayout(context) {
    private val vistaMapa: MapView
    private var mapa: MapLibreMap? = null
    private var itens: List<MarcadorMapa> = emptyList()
    private var rotas: List<RotaMapa> = emptyList()
    private var fonte: GeoJsonSource? = null
    private var fonteRotas: GeoJsonSource? = null
    private val ui = Ui(context)
    private val aviso = ui.text("Carregando mapa…", 13f, Paleta.tinta)
    init {
        setBackgroundColor(Color.parseColor("#E8EEF2"))
        MapLibre.getInstance(context)
        vistaMapa = MapView(context)
        addView(vistaMapa, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        vistaMapa.onCreate(estadoSalvo)
        aviso.background = ui.arredondado(Color.WHITE, 12)
        aviso.setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        addView(aviso, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(context.dp(10),context.dp(10),context.dp(10),0) })
        val localizar = ui.text("◎", 28f, Paleta.tinta).apply {
            gravity = Gravity.CENTER; background = ui.arredondado(Color.WHITE, 24); elevation = context.dp(3).toFloat()
            contentDescription = "Minha posição"; isClickable = true; setOnClickListener { aoLocalizar() }
        }
        addView(localizar, LayoutParams(context.dp(48), context.dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0,context.dp(12),context.dp(12),0) })
        vistaMapa.addOnDidFailLoadingMapListener { aviso.visibility = VISIBLE; aviso.text = "Mapa indisponível sem internet" }
        vistaMapa.getMapAsync { carregado ->
            mapa = carregado
            carregado.uiSettings.isCompassEnabled = false
            carregado.uiSettings.isLogoEnabled = false
            carregado.uiSettings.isAttributionEnabled = true
            carregado.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(LAT_INICIAL,LNG_INICIAL)).zoom(10.4).build()
            carregado.setStyle(Style.Builder().fromJson(ESTILO)) { style ->
                // Linha de cada travessia (traçado da balsa) por baixo dos terminais.
                fonteRotas = GeoJsonSource("ferry-routes",FeatureCollection.fromFeatures(emptyList()))
                style.addSource(fonteRotas!!)
                style.addLayer(LineLayer("ferry-lines","ferry-routes").withProperties(
                    lineColor(Expression.toColor(Expression.get("color"))), lineCap(Property.LINE_CAP_ROUND),
                    lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(9, 2.5f), Expression.stop(15, 6f)))))
                fonte = GeoJsonSource("ferry-points",FeatureCollection.fromFeatures(emptyList()))
                style.addSource(fonte!!)
                // Bolinhas pequenas na visão geral e maiores de perto, para não se sobreporem fora do lugar.
                val raio = Expression.toNumber(Expression.get("radius"))
                style.addLayer(CircleLayer("ferry-markers","ferry-points").withProperties(
                    circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(9, Expression.product(Expression.literal(0.45f), raio)), Expression.stop(15, raio))),
                    circleColor(Expression.toColor(Expression.get("color"))),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f)))
                aviso.visibility = GONE
                // Enquadra todos os terminais, qualquer que seja o tamanho da tela.
                val limites = LatLngBounds.Builder().includes(catalogoTravessias.flatMap { c -> listOf(c.pontoA, c.pontoB) }.map { LatLng(it.latitude, it.longitude) }).build()
                post { runCatching { carregado.moveCamera(CameraUpdateFactory.newLatLngBounds(limites, context.dp(56))) } }
                desenharMarcadores()
            }
            carregado.addOnMapLongClickListener { ponto -> aoEscolherPonto(ponto.latitude,ponto.longitude); true }
            carregado.addOnMapClickListener { ponto ->
                val tela = carregado.projection.toScreenLocation(ponto)
                val raio = context.dp(24).toFloat()
                val feicoes = carregado.queryRenderedFeatures(RectF(tela.x-raio,tela.y-raio,tela.x+raio,tela.y+raio),"ferry-markers")
                val id = feicoes.firstOrNull()?.getStringProperty("id")
                if(id!=null){aoTocarMarcador(id);true}else false
            }
        }
    }
    fun atualizar(dados: List<MarcadorMapa>, linhas: List<RotaMapa>) { itens = dados; rotas = linhas; desenharMarcadores() }
    fun centro(): Pair<Double, Double> {
        val target = mapa?.cameraPosition?.target
        return Pair(target?.latitude ?: LAT_INICIAL, target?.longitude ?: LNG_INICIAL)
    }
    fun focar(latitude: Double, longitude: Double) { mapa?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude,longitude),15.0)) }
    private fun desenharMarcadores() {
        fonteRotas?.setGeoJson(FeatureCollection.fromFeatures(rotas.filter { it.pontos.size >= 2 }.map { rota ->
            Feature.fromGeometry(LineString.fromLngLats(rota.pontos.map { Point.fromLngLat(it.second, it.first) })).apply { addStringProperty("color", rota.color) }
        }))
        fonte?.setGeoJson(FeatureCollection.fromFeatures(itens.map { item ->
            Feature.fromGeometry(Point.fromLngLat(item.longitude,item.latitude)).apply {
                addStringProperty("id",item.id)
                addStringProperty("color",item.color)
                addNumberProperty("radius",if(item.terminal)14f else 9f)
            }
        }))
    }
    fun start() = vistaMapa.onStart()
    fun resume() = vistaMapa.onResume()
    fun pause() = vistaMapa.onPause()
    fun stop() = vistaMapa.onStop()
    fun destroy() = vistaMapa.onDestroy()
    fun lowMemory() = vistaMapa.onLowMemory()
    fun save(bundle: Bundle) = vistaMapa.onSaveInstanceState(bundle)
    companion object {
        private const val LAT_INICIAL = -23.925
        private const val LNG_INICIAL = -46.215
        private const val ESTILO = """{"version":8,"name":"ViaAgora","sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E8EEF2"}},{"id":"streets","type":"raster","source":"osm"}]}"""
    }
}
