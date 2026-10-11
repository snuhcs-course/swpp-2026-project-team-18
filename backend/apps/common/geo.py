"""Shared coordinate distance, in meters."""

import math


def distance_meters(a_lat: float, a_lng: float, b_lat: float, b_lng: float) -> float:
    radius = 6_371_008.8
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    d_lat = p2 - p1
    d_lng = math.radians(b_lng - a_lng)
    h = math.sin(d_lat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(d_lng / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(h)))
