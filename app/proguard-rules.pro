# Keep source/line numbers for readable crash stack traces in release builds.
-keepattributes SourceFile,LineNumberTable

# MapLibre native JNI classes and callbacks invoked across native boundaries.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.geojson.**
