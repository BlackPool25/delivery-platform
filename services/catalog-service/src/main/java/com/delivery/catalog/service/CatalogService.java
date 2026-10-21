package com.delivery.catalog.service;

import com.delivery.catalog.dto.CoverageCheckResponse;
import com.delivery.catalog.dto.CreateDishRequest;
import com.delivery.catalog.dto.CreateRestaurantRequest;
import com.delivery.catalog.dto.CreateVideoReelRequest;
import com.delivery.catalog.dto.DishResponse;
import com.delivery.catalog.dto.RestaurantResponse;
import com.delivery.catalog.dto.VideoReelResponse;
import com.delivery.catalog.entity.Dish;
import com.delivery.catalog.entity.Restaurant;
import com.delivery.catalog.entity.VideoReel;
import com.delivery.catalog.exception.DishNotFoundException;
import com.delivery.catalog.exception.InvalidSpatialDataException;
import com.delivery.catalog.exception.RestaurantNotFoundException;
import com.delivery.catalog.repository.DishRepository;
import com.delivery.catalog.repository.RestaurantRepository;
import com.delivery.catalog.repository.VideoReelRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final int SRID_WGS84 = 4326;

    private final RestaurantRepository restaurantRepository;
    private final DishRepository dishRepository;
    private final VideoReelRepository videoReelRepository;
    private final H3SpatialService h3SpatialService;
    private final GeometryFactory geometryFactory;

    public CatalogService(RestaurantRepository restaurantRepository,
                          DishRepository dishRepository,
                          VideoReelRepository videoReelRepository,
                          H3SpatialService h3SpatialService) {
        this.restaurantRepository = restaurantRepository;
        this.dishRepository = dishRepository;
        this.videoReelRepository = videoReelRepository;
        this.h3SpatialService = h3SpatialService;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), SRID_WGS84);
    }

    @Transactional
    public RestaurantResponse createRestaurant(CreateRestaurantRequest request) {
        log.info("Creating restaurant: {}", request.name());
        Point location = geometryFactory.createPoint(new Coordinate(request.longitude(), request.latitude()));
        location.setSRID(SRID_WGS84);

        Polygon deliveryZone = parsePolygonWkt(request.deliveryZoneWkt());

        long h3Cell = (request.h3Res8() != null && request.h3Res8() > 0)
                ? request.h3Res8()
                : h3SpatialService.latLngToRes8Cell(request.latitude(), request.longitude());

        Restaurant restaurant = new Restaurant(
                request.name(),
                request.description(),
                request.phone(),
                location,
                deliveryZone,
                h3Cell,
                request.cityId()
        );

        if (request.averagePrepTimeMinutes() != null) {
            restaurant.setAveragePrepTimeMinutes(request.averagePrepTimeMinutes());
        }

        Restaurant saved = restaurantRepository.save(restaurant);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurant(UUID id) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new RestaurantNotFoundException(id));
        return mapToResponse(restaurant);
    }

    @Transactional(readOnly = true)
    public CoverageCheckResponse checkDeliveryCoverage(UUID restaurantId, double latitude, double longitude) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new RestaurantNotFoundException(restaurantId));

        Boolean isCovered = restaurantRepository.isLocationCovered(restaurantId, latitude, longitude);
        boolean covered = Boolean.TRUE.equals(isCovered);

        Double distance = restaurantRepository.calculateDistanceMeters(restaurantId, latitude, longitude);

        return new CoverageCheckResponse(
                restaurant.getId(),
                restaurant.getName(),
                covered,
                latitude,
                longitude,
                distance != null ? distance : 0.0
        );
    }

    @Transactional(readOnly = true)
    public List<RestaurantResponse> findActiveRestaurantsCovering(double latitude, double longitude) {
        List<Restaurant> restaurants = restaurantRepository.findActiveRestaurantsCoveringLocation(latitude, longitude);
        return restaurants.stream().map(this::mapToResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<RestaurantResponse> findByH3Cell(Long h3Index) {
        return restaurantRepository.findByH3Res8AndIsActiveTrue(h3Index)
                .stream().map(this::mapToResponse).toList();
    }

    @Transactional(readOnly = true)
    public com.delivery.catalog.dto.TwoStageSearchResponse searchTwoStageCandidates(double latitude, double longitude, int kRing) {
        long centerCell = h3SpatialService.latLngToRes8Cell(latitude, longitude);
        String centerHex = h3SpatialService.cellToHex(centerCell);
        List<Long> searchCells = h3SpatialService.getGridDisk(centerCell, Math.max(0, kRing));

        List<Restaurant> coarseCandidates = restaurantRepository.findByH3Res8InAndIsActiveTrue(searchCells);
        List<RestaurantResponse> fineMatches = coarseCandidates.stream()
                .filter(r -> Boolean.TRUE.equals(restaurantRepository.isLocationCovered(r.getId(), latitude, longitude)))
                .map(this::mapToResponse)
                .toList();

        return new com.delivery.catalog.dto.TwoStageSearchResponse(
                latitude,
                longitude,
                centerCell,
                centerHex,
                kRing,
                coarseCandidates.size(),
                fineMatches.size(),
                fineMatches
        );
    }

    @Transactional
    public DishResponse addDish(UUID restaurantId, CreateDishRequest request) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new RestaurantNotFoundException(restaurantId));

        Dish dish = new Dish(
                restaurant,
                request.name(),
                request.description(),
                request.price(),
                request.cuisineType()
        );

        Dish saved = dishRepository.save(dish);
        return mapDishToResponse(saved);
    }

    @Transactional(readOnly = true)
    public DishResponse getDish(UUID dishId) {
        Dish dish = dishRepository.findById(dishId)
                .orElseThrow(() -> new DishNotFoundException(dishId));
        return mapDishToResponse(dish);
    }

    @Transactional(readOnly = true)
    public List<DishResponse> getDishesForRestaurant(UUID restaurantId, boolean availableOnly) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new RestaurantNotFoundException(restaurantId);
        }

        List<Dish> dishes = availableOnly
                ? dishRepository.findByRestaurantIdAndIsAvailableTrue(restaurantId)
                : dishRepository.findByRestaurantId(restaurantId);

        return dishes.stream().map(this::mapDishToResponse).toList();
    }

    @Transactional
    public DishResponse eightySixDish(UUID dishId) {
        log.info("86-ing dish from kitchen availability: {}", dishId);
        return updateDishAvailability(dishId, false);
    }

    @Transactional
    public DishResponse updateDishAvailability(UUID dishId, boolean isAvailable) {
        Dish dish = dishRepository.findById(dishId)
                .orElseThrow(() -> new DishNotFoundException(dishId));
        dish.setAvailable(isAvailable);
        dish.setUpdatedAt(Instant.now());
        Dish updated = dishRepository.save(dish);
        return mapDishToResponse(updated);
    }

    @Transactional
    public VideoReelResponse addVideoReel(UUID restaurantId, CreateVideoReelRequest request) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new RestaurantNotFoundException(restaurantId));

        Dish dish = null;
        if (request.dishId() != null) {
            dish = dishRepository.findById(request.dishId())
                    .orElseThrow(() -> new DishNotFoundException(request.dishId()));
        }

        VideoReel reel = new VideoReel(
                restaurant,
                dish,
                request.masterM3u8Url(),
                request.thumbnailUrl(),
                request.h3Res8(),
                request.durationSec() != null ? request.durationSec() : 0
        );

        VideoReel saved = videoReelRepository.save(reel);
        return mapReelToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<VideoReelResponse> getReelsByH3(Long h3Index) {
        return videoReelRepository.findByH3Res8(h3Index)
                .stream().map(this::mapReelToResponse).toList();
    }

    private Polygon parsePolygonWkt(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            throw new InvalidSpatialDataException("Delivery zone WKT cannot be empty");
        }
        try {
            WKTReader reader = new WKTReader(geometryFactory);
            Geometry geom = reader.read(wkt);
            if (!(geom instanceof Polygon polygon)) {
                throw new InvalidSpatialDataException("Expected WKT POLYGON but received: " + geom.getGeometryType());
            }
            polygon.setSRID(SRID_WGS84);
            return polygon;
        } catch (ParseException e) {
            throw new InvalidSpatialDataException("Failed to parse delivery zone WKT: " + e.getMessage(), e);
        }
    }

    private RestaurantResponse mapToResponse(Restaurant r) {
        WKTWriter writer = new WKTWriter();
        String zoneWkt = r.getDeliveryZone() != null ? writer.write(r.getDeliveryZone()) : null;
        Double lat = r.getLocation() != null ? r.getLocation().getY() : null;
        Double lng = r.getLocation() != null ? r.getLocation().getX() : null;

        return new RestaurantResponse(
                r.getId(),
                r.getName(),
                r.getDescription(),
                r.getPhone(),
                lat,
                lng,
                zoneWkt,
                r.getH3Res8(),
                r.getCityId(),
                r.isActive(),
                r.isAcceptingOrders(),
                r.getAveragePrepTimeMinutes(),
                r.getRating(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    private DishResponse mapDishToResponse(Dish d) {
        return new DishResponse(
                d.getId(),
                d.getRestaurant().getId(),
                d.getName(),
                d.getDescription(),
                d.getPrice(),
                d.getCuisineType(),
                d.isAvailable(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }

    private VideoReelResponse mapReelToResponse(VideoReel vr) {
        return new VideoReelResponse(
                vr.getId(),
                vr.getRestaurant().getId(),
                vr.getDish() != null ? vr.getDish().getId() : null,
                vr.getMasterM3u8Url(),
                vr.getThumbnailUrl(),
                vr.getH3Res8(),
                vr.getDurationSec(),
                vr.getCreatedAt()
        );
    }
}
