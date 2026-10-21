package com.delivery.catalog.dto;

import java.util.List;

public record TwoStageSearchResponse(
        double queryLatitude,
        double queryLongitude,
        long centerH3Cell,
        String centerH3Hex,
        int kRing,
        int coarseCandidatesFound,
        int fineMatchesCovered,
        List<RestaurantResponse> restaurants
) {}
