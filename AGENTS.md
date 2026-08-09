# AI Configuration and Instructions

## General UI/UX Guidelines
- Always use emojis instead of SVG icons.
- Avoid using gradients.

## Code Constraints
- Do not change model strings found in code.

## Geocoding and Mapping Integration
- Prioritize the precise mapping of addresses on the map.
- Focus on optimizing the geocoding integration to ensure locations match the markers exactly.
- Provide direct answers and pragmatic solutions without long explanations.

## Synchronization & List-to-Map Binding Rules
- The waitlist and ride list must act as the single source of truth for UI cards and map overlays.
- Array index zero, the topmost item, must strictly bind to the first card and the first map marker.
- Dynamically render cards and overlays that match the active filter settings.
- Every active card must display synchronized fields from its corresponding ride object, including passenger names, scores, fare amounts, distances, and exact origin and destination addresses.
- Re-rendering the list or changing filters must immediately trigger map re-centering, marker repositioning for pickup and dropoff, and polyline route updates for all active visible cards.
- Removing or accepting a ride at index zero must instantly cascade the next item in line to index zero across both the card UI and the map markers.
