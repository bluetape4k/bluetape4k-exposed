package io.bluetape4k.exposed.mysql8.gis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.junit.jupiter.api.Test

class SpatialFunctionTest: AbstractMySqlGisTest() {

    companion object: KLogging()

    object GeoFuncPointTable: LongIdTable("func_points") {
        val name = varchar("name", 255)
        val location = geoPoint("location")
    }

    object GeoFuncPolygonTable: LongIdTable("func_polygons") {
        val name = varchar("name", 255)
        val polyA = geoPolygon("poly_a")
        val polyB = geoPolygon("poly_b")
    }

    @Test
    fun `ST_AsText - Point WKT 반환`() {
        withGeoTables(GeoFuncPointTable) {
            GeoFuncPointTable.insert {
                it[name] = "서울"
                it[location] = wgs84Point(126.9780, 37.5665)
            }

            val asTextExpr = GeoFuncPointTable.location.stAsText()
            val wkt = GeoFuncPointTable.select(asTextExpr).single()[asTextExpr]

            wkt.shouldNotBeNull()
            wkt shouldContain "126.978"
        }
    }

    @Test
    fun `ST_SRID - 컬럼 SRID 반환`() {
        withGeoTables(GeoFuncPointTable) {
            GeoFuncPointTable.insert {
                it[name] = "서울"
                it[location] = wgs84Point(126.9780, 37.5665)
            }

            val sridExpr = GeoFuncPointTable.location.stSrid()
            val srid = GeoFuncPointTable.select(sridExpr).single()[sridExpr]

            srid.shouldNotBeNull()
            srid shouldBeEqualTo 4326
        }
    }

    @Test
    fun `ST_GeometryType - Point 타입 반환`() {
        withGeoTables(GeoFuncPointTable) {
            GeoFuncPointTable.insert {
                it[name] = "서울"
                it[location] = wgs84Point(126.9780, 37.5665)
            }

            val typeExpr = GeoFuncPointTable.location.stGeometryType()
            val geomType = GeoFuncPointTable.select(typeExpr).single()[typeExpr]

            geomType.shouldNotBeNull()
            geomType shouldBeEqualTo "POINT"
        }
    }

    @Test
    fun `ST_Union - 두 폴리곤의 합집합`() {
        withGeoTables(GeoFuncPolygonTable) {
            GeoFuncPolygonTable.insert {
                it[name] = "union_test"
                it[polyA] = wgs84Rectangle(125.0, 35.0, 127.0, 38.0)
                it[polyB] = wgs84Rectangle(126.0, 37.0, 128.0, 40.0)
            }

            val unionExpr = GeoFuncPolygonTable.polyA.stUnion(GeoFuncPolygonTable.polyB)
            val wkt = GeoFuncPolygonTable.select(unionExpr).single()[unionExpr]

            wkt.shouldNotBeNull()
            wkt shouldContain "125"
        }
    }

    @Test
    fun `ST_Difference - 두 폴리곤의 차집합`() {
        withGeoTables(GeoFuncPolygonTable) {
            GeoFuncPolygonTable.insert {
                it[name] = "diff_test"
                it[polyA] = wgs84Rectangle(125.0, 35.0, 127.0, 38.0)
                it[polyB] = wgs84Rectangle(126.0, 37.0, 128.0, 40.0)
            }

            val diffExpr = GeoFuncPolygonTable.polyA.stDifference(GeoFuncPolygonTable.polyB)
            val wkt = GeoFuncPolygonTable.select(diffExpr).single()[diffExpr]

            wkt.shouldNotBeNull()
        }
    }

    @Test
    fun `ST_Intersection - 두 폴리곤의 교집합`() {
        withGeoTables(GeoFuncPolygonTable) {
            GeoFuncPolygonTable.insert {
                it[name] = "intersect_test"
                it[polyA] = wgs84Rectangle(125.0, 35.0, 127.0, 38.0)
                it[polyB] = wgs84Rectangle(126.0, 37.0, 128.0, 40.0)
            }

            val intersectExpr = GeoFuncPolygonTable.polyA.stIntersection(GeoFuncPolygonTable.polyB)
            val wkt = GeoFuncPolygonTable.select(intersectExpr).single()[intersectExpr]

            wkt.shouldNotBeNull()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `ST_Buffer - Point 주변 버퍼 (deprecated, 동작 확인)`() {
        withGeoTables(GeoFuncPointTable) {
            GeoFuncPointTable.insert {
                it[name] = "서울"
                it[location] = wgs84Point(126.9780, 37.5665)
            }

            val bufferExpr = GeoFuncPointTable.location.stBuffer(1000.0)
            val wkt = GeoFuncPointTable.select(bufferExpr).single()[bufferExpr]

            wkt.shouldNotBeNull()
        }
    }
}
