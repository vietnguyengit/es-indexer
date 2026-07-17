package au.org.aodn.esindexer.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/*
According to spec https://github.com/radiantearth/stac-spec/blob/master/collection-spec/collection-spec.md

The first bounding box always describes the overall spatial extent of the data.
All subsequent bounding boxes can be used to provide a more precise description of the extent and
identify clusters of data. Clients only interested in the overall spatial extent will only need to
access the first item in each array. It is recommended to only use multiple bounding boxes if a union
of them would then include a large uncovered area (e.g. the union of Germany and Chile).

The length of the inner array must be 2*n where n is the number of dimensions. The array contains all
axes of the  southwesterly most extent followed by all axes of the northeasterly most extent specified
in Longitude/Latitude or Longitude/Latitude/Elevation based on WGS 84. When using 3D geometries,
the elevation of the southwesterly most extent is the minimum depth/height in meters and the elevation
of the northeasterly most extent is the maximum.

The coordinate reference system of the values is WGS 84 longitude/latitude. Example that covers the
whole Earth:  [[-180.0, -90.0, 180.0, 90.0]]. Example that covers the whole earth with a depth of 100
meters to a height of 150 meters: [[-180.0, -90.0, -100.0, 180.0, 90.0, 150.0]].

This class is tailor for the above operation
 */
public class StacUtils {

    protected static Logger logger = LogManager.getLogger(StacUtils.class);

    private static final int SCALE = 10;

    /**
     * Create list of bbox, where the first one is the overall bbox
     * @param listOfPolygons - Assume to be EPSG:4326 as this is what GeoJson use
     * @return List of STAC bbox
     */
    public static List<List<BigDecimal>> createStacBBox(List<List<Geometry>> listOfPolygons) {
        List<List<BigDecimal>> result = new ArrayList<>();

        if(listOfPolygons != null) {
            final Envelope overallBoundingBox = new Envelope();
            final AtomicBoolean hasBoundingBoxUpdate = new AtomicBoolean(false);
            listOfPolygons
                    .forEach(polygons -> {
                        for (Geometry polygon : polygons) {
                            // Add polygon one by one to expand the overall bounding box area, this is requirement
                            // of STAC to have an overall bounding box of all smaller area as the first bbox in the list.
                            if (polygon != null && polygon.getEnvelopeInternal() != null) {
                                Envelope env = polygon.getEnvelopeInternal();
                                // Shift envelopes that sit entirely west of the antimeridian to [180, 360],
                                // so a box split across the antimeridian unions into one continuous range.
                                // Envelopes crossing longitude 0 must stay as-is or they flip direction.
                                double minX = env.getMinX();
                                double maxX = env.getMaxX();
                                if (maxX < 0) {
                                    minX += 360.0;
                                    maxX += 360.0;
                                }
                                Envelope normalizedEnv = new Envelope(minX, maxX, env.getMinY(), env.getMaxY());
                                overallBoundingBox.expandToInclude(normalizedEnv);

                                hasBoundingBoxUpdate.set(true);
                            }
                        }
                    });

            // Now write the first box to the head of list only if we have at least on polygon exist, if no polygon
            // exist then we can skip the reset of operation
            if(hasBoundingBoxUpdate.get()) {
                double minX = overallBoundingBox.getMinX();
                double maxX = overallBoundingBox.getMaxX();
                if (maxX - minX >= 360.0) {
                    // Union wraps the whole planet, collapse to the global box
                    minX = -180.0;
                    maxX = 180.0;
                }
                else {
                    // Shift to [-180, 180]
                    if (minX > 180.0) minX -= 360.0;
                    if (maxX > 180.0) maxX -= 360.0;
                    // Ensure maxX >= minX, result can be > 180 but needed as we need to represent box cross
                    // meridian, if you do not allow it you may have bbox longitude flipped in wrong direction
                    if (maxX < minX) maxX += 360.0;
                }
                overallBoundingBox.init(minX, maxX, overallBoundingBox.getMinY(), overallBoundingBox.getMaxY());

                result.add(List.of(
                        BigDecimal.valueOf(overallBoundingBox.getMinX()).setScale(SCALE, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(overallBoundingBox.getMinY()).setScale(SCALE, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(overallBoundingBox.getMaxX()).setScale(SCALE, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(overallBoundingBox.getMaxY()).setScale(SCALE, RoundingMode.HALF_UP)));

                for (List<Geometry> polygons : listOfPolygons) {

                    if (!polygons.isEmpty()) {
                        for (Geometry p : polygons) {
                            final Envelope individualEnvelope = new Envelope();

                            if (p != null && p.getEnvelopeInternal() != null) {
                                individualEnvelope.expandToInclude(p.getEnvelopeInternal());
                            }
                            result.add(List.of(
                                    BigDecimal.valueOf(individualEnvelope.getMinX()).setScale(SCALE, RoundingMode.HALF_UP),
                                    BigDecimal.valueOf(individualEnvelope.getMinY()).setScale(SCALE, RoundingMode.HALF_UP),
                                    BigDecimal.valueOf(individualEnvelope.getMaxX()).setScale(SCALE, RoundingMode.HALF_UP),
                                    BigDecimal.valueOf(individualEnvelope.getMaxY()).setScale(SCALE, RoundingMode.HALF_UP)));
                        }
                    }
                }
            }
        }

        if(result.isEmpty()) {
            logger.warn("No applicable BBOX calculation found");
        }

        return result;
    }
}
