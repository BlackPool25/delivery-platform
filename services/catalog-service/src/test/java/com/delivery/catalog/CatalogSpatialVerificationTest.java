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
    private static UUID autoH3RestaurantId;

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

    @Test
    @Order(5)
    @DisplayName("Verification Gate Step 5: Automatic Uber H3 Resolution 8 Spatial Index Computation")
    void testAutomaticH3Res8Computation() {
        CreateRestaurantRequest reqWithoutH3 = new CreateRestaurantRequest(
                "Auto H3 Bistro",
                "French bistro auto-indexed with Uber H3",
                "+14155554321",
                37.7749,
                -122.4194,
                SF_DELIVERY_ZONE_WKT,
                null, // null H3 index -> should auto-compute
                "san-francisco",
                20
        );

        RestaurantResponse created = catalogService.createRestaurant(reqWithoutH3);
        assertThat(created.h3Res8()).isNotNull();
        assertThat(created.h3Res8()).isGreaterThan(0L);
        autoH3RestaurantId = created.id();

        // Verify H3 index resolution is 8 using H3 library directly
        com.uber.h3core.H3Core h3;
        try {
            h3 = com.uber.h3core.H3Core.newInstance();
            int res = h3.getResolution(created.h3Res8());
            assertThat(res).isEqualTo(8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Order(6)
    @DisplayName("Verification Gate Step 6: Two-Stage Coarse-to-Fine Spatial Candidate Resolution")
    void testTwoStageCandidateResolution() {
        // Point in Mission District: 37.7749, -122.4194 (inside SF_DELIVERY_ZONE_WKT)
        double queryLat = 37.7749;
        double queryLng = -122.4194;

        com.delivery.catalog.dto.TwoStageSearchResponse response =
                catalogService.searchTwoStageCandidates(queryLat, queryLng, 1);

        assertThat(response).isNotNull();
        assertThat(response.centerH3Cell()).isGreaterThan(0L);
        assertThat(response.centerH3Hex()).isNotBlank();
        assertThat(response.kRing()).isEqualTo(1);
        assertThat(response.coarseCandidatesFound()).isGreaterThanOrEqualTo(1);
        assertThat(response.fineMatchesCovered()).isGreaterThanOrEqualTo(1);
        assertThat(response.restaurants())
                .extracting(RestaurantResponse::id)
                .contains(autoH3RestaurantId);
    }

    @Test
    @Order(7)
    @DisplayName("Verification Gate Step 7: Menu Management & 86-ing Dish Availability")
    void testMenuManagementAndEightySixingDishes() {
        // 1. Add dish
        com.delivery.catalog.dto.CreateDishRequest dishReq = new com.delivery.catalog.dto.CreateDishRequest(
                "Carne Asada Super Burrito",
                "Grilled steak, guacamole, beans, cheese, and salsa",
                new java.math.BigDecimal("14.50"),
                "MEXICAN"
        );
        com.delivery.catalog.dto.DishResponse dish = catalogService.addDish(restaurantId, dishReq);
        assertThat(dish.id()).isNotNull();
        assertThat(dish.name()).isEqualTo("Carne Asada Super Burrito");
        assertThat(dish.isAvailable()).isTrue();

        // 2. 86 the dish (kitchen ran out of steak)
        com.delivery.catalog.dto.DishResponse eightySixed = catalogService.eightySixDish(dish.id());
        assertThat(eightySixed.isAvailable()).isFalse();

        // 3. Verify availableOnly filter excludes the 86'd dish
        List<com.delivery.catalog.dto.DishResponse> availableDishes =
                catalogService.getDishesForRestaurant(restaurantId, true);
        assertThat(availableDishes)
                .extracting(com.delivery.catalog.dto.DishResponse::id)
                .doesNotContain(dish.id());

        // 4. Verify all dishes includes the 86'd dish
        List<com.delivery.catalog.dto.DishResponse> allDishes =
                catalogService.getDishesForRestaurant(restaurantId, false);
        assertThat(allDishes)
                .extracting(com.delivery.catalog.dto.DishResponse::id)
                .contains(dish.id());

        // 5. Un-86 (restore availability)
        com.delivery.catalog.dto.DishResponse restored =
                catalogService.updateDishAvailability(dish.id(), true);
        assertThat(restored.isAvailable()).isTrue();
    }
}
