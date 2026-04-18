package com.teslcan.app

/**
 * 기존 코드 호환용 래퍼. 실제 구현은 [RouteService] (Mapbox/OSRM 공통).
 */
class MapboxRouter {

    data class RouteResult(
        val roadDistance: Double,
        val straightDistance: Double,
        val routePoints: List<LatLon>,
        val success: Boolean
    )

    data class LatLon(val lat: Double, val lon: Double)

    fun getRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        straightDist: Double
    ): RouteResult {
        val r = RouteService.getRoute(fromLat, fromLon, toLat, toLon, straightDist)
        return RouteResult(
            r.roadDistance,
            r.straightDistance,
            r.routePoints.map { LatLon(it.lat, it.lon) },
            r.success
        )
    }

    fun remainingDistance(currentLat: Double, currentLon: Double, routePoints: List<LatLon>): Double {
        val pts = routePoints.map { RouteService.LatLon(it.lat, it.lon) }
        return RouteService.remainingDistance(currentLat, currentLon, pts)
    }

    fun isOnRoute(lat: Double, lon: Double, routePoints: List<LatLon>): Boolean {
        val pts = routePoints.map { RouteService.LatLon(it.lat, it.lon) }
        return RouteService.isOnRoute(lat, lon, pts)
    }

    fun isRouteAlignedWithBearing(
        routePoints: List<LatLon>,
        currentBearing: Double,
        checkDistanceM: Double = 200.0
    ): Boolean {
        val pts = routePoints.map { RouteService.LatLon(it.lat, it.lon) }
        return RouteService.isRouteAlignedWithBearing(pts, currentBearing, checkDistanceM)
    }
}
