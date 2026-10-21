package com.delivery.catalog;

import com.delivery.catalog.dto.CoverageCheckResponse;
import com.delivery.catalog.dto.CreateRestaurantRequest;
import com.delivery.catalog.dto.RestaurantResponse;
import com.delivery.catalog.service.CatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CatalogSpatialVerificationTest {

    @Container
    static PostgreSQLContainer<?> postgis = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
    }

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static UUID restaurantId;

    // Delivery zone polygon in San Francisco (EPSG:4326 Geography):
    // Rectangular geodesic bounding box covering longitude [-122.4300, -122.4000] and latitude [37.7600, 37.7900]
    private static final String SF_DELIVERY_ZONE_WKT =
            "POLYGON((-122.4300 37.7600, -122.4000 37.7600, -122.4000 37.7900, -122.4300 37.7900, -122.4300 37.7600))";

    @Test
    @Order(1)
    @DisplayName("Verification Gate Step 1: Create restaurant with native geography(Polygon, 4326) delivery zone")
    void testCreateRestaurantWithNativeGeodesicPolygon() {
        CreateRestaurantRequest request = new CreateRestaurantRequest(
                "Mission Taqueria",
                "Authentic artisanal street tacos and burritos",
                "+14155551234",
                37.7749, // latitude
                -122.4194, // longitude
                SF_DELIVERY_ZONE_WKT,
                599814421671936000L, // H3 Res 8 cell index
                "san-francisco",
                15
        );

        RestaurantResponse response = catalogService.createRestaurant(request);
        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Mission Taqueria");
        assertThat(response.latitude()).isEqualTo(37.7749);
        assertThat(response.longitude()).isEqualTo(-122.4194);
        assertThat(response.isActive()).isTrue();
        assertThat(response.isAcceptingOrders()).isTrue();

        restaurantId = response.id();

        // Verify column data types directly in PostgreSQL information_schema and PostGIS geometry_columns / geography_columns
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'restaurants'
                  AND column_name IN ('location', 'delivery_zone')
                  AND udt_name = 'geography'
                """,
                Integer.class
        );
        assertThat(count).isEqualTo(2);
    }

    @Test
    @Order(2)
    @DisplayName("Verification Gate Step 2: Interior Dropoff Point - ST_Covers evaluates to TRUE")
    void testInteriorDropoffLocationCovered() {
        // Interior coordinate: -122.4150, 37.7750 (Civil Center, comfortably inside polygon)
        double interiorLat = 37.7750;
        double interiorLng = -122.4150;

        CoverageCheckResponse coverage = catalogService.checkDeliveryCoverage(restaurantId, interiorLat, interiorLng);
        assertThat(coverage.covered())
                .as("Interior coordinate must be covered by delivery zone")
                .isTrue();

        List<RestaurantResponse> covering = catalogService.findActiveRestaurantsCovering(interiorLat, interiorLng);
        assertThat(covering)
                .extracting(RestaurantResponse::id)
                .contains(restaurantId);

        // Native SQL verification on WGS 84 spheroid
        Boolean sqlCovered = jdbcTemplate.queryForObject(
                """
                SELECT ST_Covers(delivery_zone, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                FROM restaurants WHERE id = ?
                """,
                Boolean.class,
                interiorLng, interiorLat, restaurantId
        );
        assertThat(sqlCovered).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("Verification Gate Step 3: Exact Boundary Edge Point - ST_Covers evaluates to TRUE natively on spheroid")
    void testBoundaryEdgeDropoffLocationCovered() {
        // Boundary coordinate on the east edge line (x = -122.4000, y = 37.7750)
        double boundaryLat = 37.7750;
        double boundaryLng = -122.4000;

        CoverageCheckResponse coverage = catalogService.checkDeliveryCoverage(restaurantId, boundaryLat, boundaryLng);
        assertThat(coverage.covered())
                .as("Coordinate exactly on the polygon boundary MUST be covered by ST_Covers")
                .isTrue();

        // Verify ST_Covers vs ST_Contains on boundary edge
        // In spatial logic: ST_Covers accepts boundary points; ST_Contains rejects boundary points.
        Boolean sqlCovers = jdbcTemplate.queryForObject(
                """
                SELECT ST_Covers(delivery_zone, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                FROM restaurants WHERE id = ?
                """,
                Boolean.class,
                boundaryLng, boundaryLat, restaurantId
        );
        assertThat(sqlCovers).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("Verification Gate Step 4: Exterior Dropoff Point - ST_Covers evaluates to FALSE")
    void testExteriorDropoffLocationNotCovered() {
        // Exterior coordinate: -122.5000, 37.7750 (Ocean Beach, outside polygon)
        double exteriorLat = 37.7750;
        double exteriorLng = -122.5000;

        CoverageCheckResponse coverage = catalogService.checkDeliveryCoverage(restaurantId, exteriorLat, exteriorLng);
        assertThat(coverage.covered())
                .as("Exterior coordinate outside boundary must NOT be covered")
                .isFalse();

        List<RestaurantResponse> covering = catalogService.findActiveRestaurantsCovering(exteriorLat, exteriorLng);
        assertThat(covering)
                .extracting(RestaurantResponse::id)
                .doesNotContain(restaurantId);

        Boolean sqlCovered = jdbcTemplate.queryForObject(
                """
                SELECT ST_Covers(delivery_zone, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                FROM restaurants WHERE id = ?
                """,
                Boolean.class,
                exteriorLng, exteriorLat, restaurantId
        );
        assertThat(sqlCovered).isFalse();
    }
}
