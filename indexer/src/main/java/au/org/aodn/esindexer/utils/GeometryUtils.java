package au.org.aodn.esindexer.utils;

import au.org.aodn.metadata.iso19115_3_2018.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.locationtech.jts.precision.GeometryPrecisionReducer;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.geojson.geom.GeometryJSON;
import org.locationtech.jts.algorithm.Area;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.opengis.feature.simple.SimpleFeature;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.function.Function;

public class GeometryUtils {

    public enum PointOrientation {
        CLOCKWISE,
        COUNTER_CLOCKWISE,
        FLAT
    }

    protected static Logger logger = LogManager.getLogger(GeometryUtils.class);
    protected static GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 4326);
    protected static ObjectMapper objectMapper = new ObjectMapper();
    protected static Geometry landGeometry;

    // This must be hard code and cannot change, the geonetwork comes with some very long decimal coordinate
    // if we do not preserve this, we will result polygon rejected by elastic due to not having 3 non-collinear
    // points after rounding by the GeometryJson
    protected static GeometryJSON geometryJson = new GeometryJSON(15);
    // A value based on trial and error, this is a default value and user can override it with the yaml file
    @Getter
    @Setter
    protected static double coastalPrecision = 0.1;
    // A value based on trial and error, to simplify the polygon to avoid complex geojson that takes time to transfer
    // By default is set to null to disable it so test case do not need to change, but each env have this value set
    // to reduce processing time.
    @Getter
    @Setter
    protected static Double reducerPrecision = null;

    protected static GeometryPrecisionReducer reducer = null;

    // Load a coastline shape file so that we can get a spatial extents that cover sea only
    public static synchronized void init() {
        try {
            // Reset reducer
            reducer = null;

            // shp file depends on shx, so need to have shx appear in temp folder.
            FileUtils.saveResourceToTemp("land/ne_10m_land.shx", "shapefile.shx");
            File tempFile = FileUtils.saveResourceToTemp("land/ne_10m_land.shp", "shapefile.shp");

            // Load the shapefile from the temporary file using ShapefileDataStore
            URL tempFileUrl = tempFile.toURI().toURL();
            ShapefileDataStoreFactory dataStoreFactory = new ShapefileDataStoreFactory();
            ShapefileDataStore dataStore = (ShapefileDataStore) dataStoreFactory.createDataStore(tempFileUrl);
            SimpleFeatureSource featureSource = dataStore.getFeatureSource();

            // Step 3: Extract the land geometry from the shapefile
            SimpleFeatureCollection featureCollection = featureSource.getFeatures();
            List<Geometry> geometries = new ArrayList<>();

            if(getReducerPrecision() != null) {
                PrecisionModel pm = new PrecisionModel(getReducerPrecision()); // 1 / 1000 meters ~= 1km
                reducer = new GeometryPrecisionReducer(pm);
            }

            try (SimpleFeatureIterator iterator = featureCollection.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Geometry landFeatureGeometry = (Geometry) feature.getDefaultGeometry();

                    // This will reduce the points of the shape file for faster processing, this
                    // simplification may cause polygon invalid and therefore need to use buffer(0.0) later
                    // during processing to work around this issue. Another choice is TopologyPreservingSimplifier
                    // but the polygon output is a bit complicated
                    Geometry simplifiedGeometry = DouglasPeuckerSimplifier
                            .simplify(landFeatureGeometry, getCoastalPrecision()); // Adjust tolerance

                    geometries.add(reducer != null ? reducer.reduce(simplifiedGeometry) : simplifiedGeometry);
                }
            }
            // Faster to use union list rather than union by geometry one by one.
            landGeometry = UnaryUnionOp.union(geometries);
        }
        catch(IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }
    /**
     * @param polygons - Assume to be EPSG:4326, as GeoJson always use this encoding.
     * @return - Map that represent the geojson
     */
    protected static Map<?,?> createGeoShapeJson(List<List<Geometry>> polygons) {

        if(!polygons.isEmpty()) {
            // Convert list<list<polygon>> to list<polygon>
            List<Geometry> reduced = polygons.stream().flatMap(List::stream).toList();
            Geometry[] orientedPolygons = reduced.stream()
                    .map(geometry -> {
                        Geometry result = geometry;
                        if (geometry instanceof Polygon polygon) {
                            // Ensure all polygons follow the right-hand rule
                            // The right-hand rule is a convention used to maintain consistency in the orientation of vertices for polygons.
                            // For a polygon, adhering to the right-hand rule means that its vertices are ordered counterclockwise for the exterior ring
                            // and clockwise for any interior rings (holes).
                            // Standard: https://www.rfc-editor.org/rfc/rfc7946#section-3.1.6
                            result = GeometryUtils.ensureCounterClockwise(polygon, factory);
                        }
                        return result;
                    })
                    .filter(r -> !r.isEmpty())
                    .toArray(Geometry[]::new);

            GeometryCollection collection = new GeometryCollection(orientedPolygons, factory);
            try (StringWriter writer = new StringWriter()) {
                geometryJson.write(collection, writer);

                Map<?, ?> values = objectMapper.readValue(writer.toString(), HashMap.class);

                if(values == null)  {
                    logger.warn("Convert geometry to JSON result in null, {}", writer.toString());
                }
                else {
                    logger.debug("Created geometry {}", values);
                }
                return values;
            } catch (IOException | StringIndexOutOfBoundsException e) {
                logger.error("Error create geometry {} ",collection, e);
                return null;
            }
        }
        return null;
    }
    /**
     * Checks if the vertices of a polygon are ordered counterclockwise using the Shoelace formula.
     * The Shoelace formula (or Gauss's area formula) calculates the signed area
     * of the polygon. If the result is negative, the vertices are ordered
     * counterclockwise; if positive, they are ordered clockwise.
     *
     * @param coordinates Array of polygon coordinates.
     * @return True if vertices are ordered counterclockwise, False otherwise.
     */
    protected static PointOrientation orientation(Coordinate[] coordinates) {
        // Computes the signed area for a ring. The signed area is positive if the ring is oriented CW,
        // negative if the ring is oriented CCW, and zero if the ring is degenerate or flat.
        double orientation = Area.ofRingSigned(coordinates);
        if(orientation > 0) {
            return PointOrientation.CLOCKWISE;
        }
        else if(orientation < 0) {
            return PointOrientation.COUNTER_CLOCKWISE;
        }
        else {
            return PointOrientation.FLAT;
        }
    }
    /**
     * Ensures that a polygon's vertices are ordered counterclockwise.
     * This method checks if the exterior ring (shell) of the polygon is ordered
     * counterclockwise and reorders it if necessary. It also checks all interior
     * rings (holes) and ensures they are ordered clockwise to maintain the right-hand rule.
     *
     * @param polygon Polygon whose vertex order needs to be ensured.
     * @param factory GeometryFactory to create new LinearRing objects if reordering is needed.
     * @return Polygon with correctly ordered vertices.
     */
    protected static Polygon ensureCounterClockwise(Polygon polygon, GeometryFactory factory) {
        // Check and reorder exterior ring if necessary
        LinearRing shell = polygon.getExteriorRing();
        if (orientation(shell.getCoordinates()) == PointOrientation.CLOCKWISE) {
            shell = shell.reverse();
        }

        // Check and reorder each interior ring (hole) if necessary
        LinearRing[] holes = new LinearRing[polygon.getNumInteriorRing()];
        for (int i = 0; i < holes.length; i++) {
            LinearRing hole = polygon.getInteriorRingN(i);
            if (orientation(hole.getCoordinates()) == PointOrientation.COUNTER_CLOCKWISE) {
                hole = hole.reverse();
            }
            holes[i] = hole;
        }

        return factory.createPolygon(shell, holes);
    }
    /**
     * Special function to convert a MuliplePolygon to List<Geometry>
     * @param multipolygon - A multipolygon
     * @return - List of polygon the represent the incoming Multiple Polygon
     */
    protected static List<Geometry> convertToListGeometry(Geometry multipolygon) {
        // The geometry is expected to be multipolygon
        // The above map will result in a multi-polygon, we need to flatten
        // it to List<Geometry> which is what the later processing expected.
        List<Geometry> geo = new ArrayList<>();

        // Iterate over the geometries in the MultiPolygon
        for (int i = 0; i < multipolygon.getNumGeometries(); i++) {
            Geometry geometry = multipolygon.getGeometryN(i);
            geo.add(geometry);
        }
        return geo;

    }
    /**
     * The geometry from geonetwork can be pretty bad that it cover area of sea and land, this function is use
     * to remove the land part
     * @param geoList - The polygon after parsing Goenetwork XML
     * @return - Polygons with land area removed and split into grid aka list of polygon. Geometry is the base class for Polygon
     */
    protected static List<List<Geometry>> removeLandAreaFromGeometry(List<List<Geometry>> geoList) {
        // Do not flatten the array in geometries level, otherwise the map will not display the grid boundary
        return geoList.stream()
                .map(geometries ->
                    geometries.stream()
                            .filter(Objects::nonNull)
                            // Try fixing it with buffer(0), which often fixes small topological errors
                            // it fixed the non-noded intersection issue
                            .map(geometry -> geometry.isValid() ? geometry : geometry.buffer(0))
                            // Special case where some spatial area do appear on land only, so if you make a diff and result is empty,
                            // that means it is pure land area, in this case we should include it.
                            .map(geometry -> geometry.difference(landGeometry).isEmpty() ? geometry : geometry.difference(landGeometry))
                            .map(geometry -> reducer != null ? reducer.reduce(geometry) : geometry)
                            .map(GeometryUtils::convertToListGeometry)
                            .flatMap(Collection::stream)
                            .toList()
                )
                .toList();
    }
    /**
     * Function to locate the geometry field in source and pass it to the handler for processing
     * @param source - A parsed XML from geonetwork
     * @param handler - The handler to create the item you needed give source
     * @return - The target item
     * @param <R> - Type that align with the handler return type
     */
    public static <R> R createGeometryItems(
            MDMetadataType source,
            Function<List<List<AbstractEXGeographicExtentType>>, R> handler) {

        List<MDDataIdentificationType> items = MapperUtils.findMDDataIdentificationType(source);
        // Primary: MDDataIdentification; Fallback: SVServiceIdentification (e.g. GA records)
        List<? extends AbstractMDIdentificationType> identifications = items.isEmpty()
                ? MapperUtils.findSVServiceIdentificationType(source)
                : items;
        if(!identifications.isEmpty()) {
            if(identifications.size() > 1) {
                logger.warn("!! More than 1 block of MDDataIdentificationType, data will be missed !!");
            }
            // Assume only 1 block of <mri:MD_DataIdentification> or <srv:SV_ServiceIdentification>
            // We only concern geographicElement here
            List<EXExtentType> ext = identifications.get(0)
                    .getExtent()
                    .stream()
                    .filter(f -> f.getAbstractExtent() != null)
                    .filter(f -> f.getAbstractExtent().getValue() != null)
                    .filter(f -> f.getAbstractExtent().getValue() instanceof EXExtentType)
                    .map(f -> (EXExtentType) f.getAbstractExtent().getValue())
                    .filter(f -> f.getGeographicElement() != null)
                    .toList();

            // We want to get a list of item where each item contains multiple, (aka list) of
            // (EXGeographicBoundingBoxType or EXBoundingPolygonType)
            List<List<AbstractEXGeographicExtentType>> rawInput = ext.stream()
                    .map(EXExtentType::getGeographicElement)
                    .map(l ->
                            /*
                                l = List<AbstractEXGeographicExtentPropertyType>
                                For each AbstractEXGeographicExtentPropertyType, we get the tag that store the
                                coordinate, it is either a EXBoundingPolygonType or EXGeographicBoundingBoxType
                             */
                            l.stream()
                                    .map(AbstractEXGeographicExtentPropertyType::getAbstractEXGeographicExtent)
                                    .filter(Objects::nonNull)
                                    .filter(m -> (m.getValue() instanceof EXBoundingPolygonType || m.getValue() instanceof EXGeographicBoundingBoxType))
                                    .map(m -> {
                                        if (m.getValue() instanceof EXBoundingPolygonType exBoundingPolygonType) {
                                            if (!exBoundingPolygonType.getPolygon().isEmpty() && exBoundingPolygonType.getPolygon().get(0).getAbstractGeometry() != null) {
                                                return exBoundingPolygonType;
                                            }
                                        } else if (m.getValue() instanceof EXGeographicBoundingBoxType) {
                                            return m.getValue();
                                        }
                                        return null; // Handle other cases or return appropriate default value
                                    })
                                    .filter(Objects::nonNull) // Filter out null values if any
                                    .toList()
                    )
                    .toList();
            return handler.apply(rawInput);
        }
        return null;
    }

    protected static List<List<Geometry>> createGeometryWithoutLand(List<List<AbstractEXGeographicExtentType>> rawInput) {
        return removeLandAreaFromGeometry(
                GeometryBase.findPolygonsFrom(GeometryBase.COORDINATE_SYSTEM_CRS84, rawInput)
        );
    }
    /**
     * A preprocessed centroid point based on spatial extents area. In order not to have a point that fall into the
     * land, we remove the area contain land area. Then we spit it into grid, in each grid we calculate the centroid.
     * Multiple point will be created for the map to process, this is needed because when map drag, some spatial extents
     * that across multiple region should have a point that represent it.
     * @param rawInput - The parsed XML block that contains the spatial extents area
     * @return - Centroid point which will not appear on land.
     */
    public static Map<?, ?> createGeometryNoLandFrom(List<List<AbstractEXGeographicExtentType>> rawInput) {
        List<List<Geometry>> polygon = createGeometryWithoutLand(rawInput);
        return !polygon.isEmpty() ? createGeoShapeJson(polygon) : null;
    }
    /**
     * Create the spatial extents area given the XML info, it will not remove land area for speed reason. Otherwise,
     * too many polygon created plus this is not what the metadata provides. Also, it may create non-collinear
     * or self-intersecting polygon.
     * @param rawInput - The parsed XML block that contains the spatial extents area
     * @return - Map that represent rawInput
     */
    public static Map<?, ?> createGeometryFrom(List<List<AbstractEXGeographicExtentType>> rawInput) {
        // The return polygon is in EPSG:4326, so we can call createGeoJson directly

        // Un-remark this line and remark the line below if you want to visualize the polygon on map, change this
        // line will cause the spatial extends draw on map with land removed.
        // List<List<Geometry>> polygon = createGeometryWithoutLand(rawInput);

        List<List<Geometry>> polygon = GeometryBase.findPolygonsFrom(GeometryBase.COORDINATE_SYSTEM_CRS84, rawInput);
        return !polygon.isEmpty() ? createGeoShapeJson(polygon) : null;
    }
}
