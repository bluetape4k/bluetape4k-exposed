package io.bluetape4k.exposed.mysql8.gis

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test

class SpatialRelationTest: AbstractMySqlGisTest() {

    companion object: KLogging()

    /**
     * 테이블 선언은 dialect 체크가 있으므로 트랜잭션 내에서만 초기화되도록
     * 클래스(인스턴스)로 선언한다.
     */
    object RelationTable: LongIdTable("rel_test") {
        val name = varchar("name", 255)
        val zoneA = geoPolygon("zone_a")
        val zoneB = geoPolygon("zone_b")
    }

    object PointTable: LongIdTable("rel_point_test") {
        val name = varchar("name", 255)
        val pointA = geoPoint("point_a")
        val pointB = geoPoint("point_b")
    }

    @Test
    fun `ST_Contains - 외부 폴리곤이 내부 폴리곤을 완전히 포함`() {
        withGeoTables(RelationTable) {
            RelationTable.insert {
                it[name] = "포함관계"
                it[zoneA] = wgs84Rectangle(125.0, 35.0, 130.0, 42.0)   // 큰 직사각형
                it[zoneB] = wgs84Rectangle(126.0, 36.0, 128.0, 40.0)   // 작은 직사각형 (내부)
            }

            val rows = RelationTable.selectAll()
                .where { RelationTable.zoneA.stContains(RelationTable.zoneB) }
                .toList()

            rows shouldHaveSize 1
            rows.first()[RelationTable.name] shouldBeEqualTo "포함관계"
        }
    }

    @Test
    fun `ST_Within - 내부 폴리곤이 외부 폴리곤 안에 있음`() {
        withGeoTables(RelationTable) {
            RelationTable.insert {
                it[name] = "내부포함"
                it[zoneA] = wgs84Rectangle(125.0, 35.0, 130.0, 42.0)   // 큰 직사각형
                it[zoneB] = wgs84Rectangle(126.0, 36.0, 128.0, 40.0)   // 작은 직사각형 (내부)
            }

            val rows = RelationTable.selectAll()
                .where { RelationTable.zoneB.stWithin(RelationTable.zoneA) }
                .toList()

            rows shouldHaveSize 1
            rows.first()[RelationTable.name] shouldBeEqualTo "내부포함"
        }
    }

    @Test
    fun `ST_Intersects - 겹치는 두 폴리곤`() {
        withGeoTables(RelationTable) {
            RelationTable.insert {
                it[name] = "겹침"
                it[zoneA] = wgs84Rectangle(125.0, 35.0, 128.0, 39.0)
                it[zoneB] = wgs84Rectangle(127.0, 37.0, 130.0, 42.0)   // 부분 겹침
            }

            val rows = RelationTable.selectAll()
                .where { RelationTable.zoneA.stIntersects(RelationTable.zoneB) }
                .toList()

            rows shouldHaveSize 1
            rows.first()[RelationTable.name] shouldBeEqualTo "겹침"
        }
    }

    @Test
    fun `ST_Disjoint - 완전히 분리된 두 폴리곤`() {
        withGeoTables(RelationTable) {
            RelationTable.insert {
                it[name] = "분리"
                it[zoneA] = wgs84Rectangle(125.0, 35.0, 128.0, 38.0)
                it[zoneB] = wgs84Rectangle(129.0, 39.0, 132.0, 42.0)   // 완전 분리
            }

            val rows = RelationTable.selectAll()
                .where { RelationTable.zoneA.stDisjoint(RelationTable.zoneB) }
                .toList()

            rows shouldHaveSize 1
            rows.first()[RelationTable.name] shouldBeEqualTo "분리"
        }
    }

    @Test
    fun `ST_Overlaps - 부분 겹침은 true`() {
        withGeoTables(RelationTable) {
            RelationTable.insert {
                it[name] = "부분겹침"
                it[zoneA] = wgs84Rectangle(125.0, 35.0, 128.0, 39.0)
                it[zoneB] = wgs84Rectangle(127.0, 37.0, 130.0, 42.0)   // 부분 겹침
            }

            val rows = RelationTable.selectAll()
                .where { RelationTable.zoneA.stOverlaps(RelationTable.zoneB) }
                .toList()

            rows shouldHaveSize 1
            rows.first()[RelationTable.name] shouldBeEqualTo "부분겹침"
        }
    }

    @Test
    fun `ST_Overlaps - 완전 포함 시 false`() {
        withGeoTables(RelationTable) {
            RelationTable.insert {
                it[name] = "완전포함"
                it[zoneA] = wgs84Rectangle(125.0, 35.0, 130.0, 42.0)   // 큰 직사각형
                it[zoneB] = wgs84Rectangle(126.0, 36.0, 128.0, 40.0)   // 작은 직사각형 (내부)
            }

            val rows = RelationTable.selectAll()
                .where { RelationTable.zoneA.stOverlaps(RelationTable.zoneB) }
                .toList()

            rows.shouldBeEmpty()
        }
    }

    @Test
    fun `ST_Equals - 동일한 폴리곤`() {
        withGeoTables(RelationTable) {
            RelationTable.insert {
                it[name] = "동일폴리곤"
                it[zoneA] = wgs84Rectangle(126.0, 37.0, 127.0, 38.0)
                it[zoneB] = wgs84Rectangle(126.0, 37.0, 127.0, 38.0)   // 동일한 좌표
            }

            val rows = RelationTable.selectAll()
                .where { RelationTable.zoneA.stEquals(RelationTable.zoneB) }
                .toList()

            rows shouldHaveSize 1
            rows.first()[RelationTable.name] shouldBeEqualTo "동일폴리곤"
        }
    }

    @Test
    fun `ST_DWithin - 두 Point가 지정 거리 이내`() {
        withGeoTables(PointTable) {
            PointTable.insert {
                it[name] = "서울-수원"
                it[pointA] = wgs84Point(126.978, 37.566)   // 서울
                it[pointB] = wgs84Point(127.009, 37.264)   // 수원
            }

            // 50km 이내 → true (서울-수원 직선거리 약 35km)
            val withinRows = PointTable.selectAll()
                .where { PointTable.pointA.stDWithin(PointTable.pointB, 50000.0) }
                .toList()

            withinRows shouldHaveSize 1

            // 1km 이내 → false
            val notWithinRows = PointTable.selectAll()
                .where { PointTable.pointA.stDWithin(PointTable.pointB, 1000.0) }
                .toList()

            notWithinRows.shouldBeEmpty()
        }
    }
}
