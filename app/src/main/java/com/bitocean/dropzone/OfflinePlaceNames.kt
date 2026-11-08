package com.bitocean.dropzone

import android.content.res.AssetManager
import android.icu.text.Transliterator
import android.location.Location

internal class OfflinePlaceNames(assets: AssetManager) {
    private data class City(val latitude: Double, val longitude: Double, val province: String, val name: String)

    private val cities = assets.open("places/china-cities.tsv").bufferedReader().useLines { lines ->
        lines.map { line ->
            val (lat, lon, province, name) = line.split('\t')
            City(lat.toDouble(), lon.toDouble(), province, name)
        }.toList()
    }
    private val simplify = Transliterator.getInstance("Traditional-Simplified")

    fun nearbyCity(latitude: Double, longitude: Double): String? {
        val distance = FloatArray(1)
        var best = 50_000f
        var nearest: City? = null
        for (city in cities) {
            Location.distanceBetween(latitude, longitude, city.latitude, city.longitude, distance)
            if (distance[0] < best) {
                nearest = city
                best = distance[0]
            }
        }
        val city = nearest ?: return null
        return listOf("中国", city.province, simplify.transliterate(city.name)).distinct().joinToString(" · ")
    }
}
