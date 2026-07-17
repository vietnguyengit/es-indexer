package au.org.aodn.esindexer.utils;

import au.org.aodn.metadata.iso19115_3_2018.DecimalPropertyType;
import au.org.aodn.metadata.iso19115_3_2018.EXGeographicBoundingBoxType;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.math.BigDecimal;
import java.util.Optional;


import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

public class GeometryBaseTest {

    protected static final GeometryFactory factory = new GeometryFactory();
    protected static final Logger logger = LoggerFactory.getLogger(GeometryBaseTest.class);

    @Test
    public void verifyGetCoordinatesPoint() {
        EXGeographicBoundingBoxType boundingBoxType = new EXGeographicBoundingBoxType();

        DecimalPropertyType a = new DecimalPropertyType();
        a.setDecimal(BigDecimal.valueOf(146.85));

        boundingBoxType.setEastBoundLongitude(a);
        boundingBoxType.setWestBoundLongitude(a);

        DecimalPropertyType b = new DecimalPropertyType();
        b.setDecimal(BigDecimal.valueOf(-19.168333));

        boundingBoxType.setSouthBoundLatitude(b);
        boundingBoxType.setNorthBoundLatitude(b);

        Optional<Geometry> point = GeometryBase.getCoordinates(boundingBoxType);
        assertInstanceOf(Point.class, point.orElse(null), "We found a point");
    }

    @Test
    public void verifyGetCoordinatesPolygon() {
        EXGeographicBoundingBoxType boundingBoxType = new EXGeographicBoundingBoxType();

        DecimalPropertyType w = new DecimalPropertyType();
        w.setDecimal(BigDecimal.valueOf(60));
        boundingBoxType.setWestBoundLongitude(w);

        DecimalPropertyType s = new DecimalPropertyType();
        s.setDecimal(BigDecimal.valueOf(-68));
        boundingBoxType.setSouthBoundLatitude(s);

        DecimalPropertyType e = new DecimalPropertyType();
        e.setDecimal(BigDecimal.valueOf(78));
        boundingBoxType.setEastBoundLongitude(e);

        DecimalPropertyType n = new DecimalPropertyType();
        n.setDecimal(BigDecimal.valueOf(-66));
        boundingBoxType.setNorthBoundLatitude(n);

        Optional<Geometry> point = GeometryBase.getCoordinates(boundingBoxType);
        assertInstanceOf(Polygon.class, point.orElse(null), "We found a polygon");
    }

    @Test
    public void testIsCounterClockwise() {
        Coordinate[] ccwCoords = new Coordinate[] {
                new Coordinate(0, 0),
                new Coordinate(1, 0),
                new Coordinate(1, 1),
                new Coordinate(0, 1),
                new Coordinate(0, 0)
        };

        Coordinate[] cwCoords = new Coordinate[] {
                new Coordinate(0, 0),
                new Coordinate(0, 1),
                new Coordinate(1, 1),
                new Coordinate(1, 0),
                new Coordinate(0, 0)
        };

        assertEquals(
                GeometryUtils.orientation(ccwCoords),
                GeometryUtils.PointOrientation.COUNTER_CLOCKWISE,
                "CCW coords"
        );

        assertEquals(
                GeometryUtils.orientation(cwCoords),
                GeometryUtils.PointOrientation.CLOCKWISE,
                "CW coords"
        );
    }

    @Test
    public void testEnsureCounterClockwise1() {
        // A clockwise polygon
        Coordinate[] cwCoords = new Coordinate[] {
                new Coordinate(0, 0),
                new Coordinate(0, 1),
                new Coordinate(1, 1),
                new Coordinate(1, 0),
                new Coordinate(0, 0)
        };
        LinearRing cwRing = factory.createLinearRing(cwCoords);
        Polygon cwPolygon = factory.createPolygon(cwRing);

        assertEquals(
                GeometryUtils.orientation(cwCoords),
                GeometryUtils.PointOrientation.CLOCKWISE,
                "CW"
        );

        Polygon ccwPolygon = GeometryUtils.ensureCounterClockwise(cwPolygon, factory);
        assertEquals(
                GeometryUtils.orientation(ccwPolygon.getExteriorRing().getCoordinates()),
                GeometryUtils.PointOrientation.COUNTER_CLOCKWISE,
                "CCW"
        );

        // A counterclockwise polygon should remain unchanged
        Coordinate[] ccwCoords = new Coordinate[] {
                new Coordinate(0, 0),
                new Coordinate(1, 0),
                new Coordinate(1, 1),
                new Coordinate(0, 1),
                new Coordinate(0, 0)
        };
        LinearRing ccwRing = factory.createLinearRing(ccwCoords);
        Polygon originalCcwPolygon = factory.createPolygon(ccwRing);

        Polygon ensuredCcwPolygon = GeometryUtils.ensureCounterClockwise(originalCcwPolygon, factory);
        assertEquals(
                GeometryUtils.orientation(ensuredCcwPolygon.getExteriorRing().getCoordinates()),
                GeometryUtils.PointOrientation.COUNTER_CLOCKWISE,
                "CCW"
        );
    }

    @Test
    public void testEnsureCounterClockwise2() {
        Coordinate[] coordinates = new Coordinate[] {
                new Coordinate(118.000, -35.999),
                new Coordinate(118.000, -34),
                new Coordinate(112, -34),
                new Coordinate(112, -32),
                new Coordinate(126, -32),
                new Coordinate(126, -34),
                new Coordinate(123.999, -34),
                new Coordinate(123.999, -35.999),
                new Coordinate(118.000, -35.999),
        };
        LinearRing cwRing = factory.createLinearRing(coordinates);
        Polygon cwPolygon = factory.createPolygon(cwRing);

        Polygon ccwPolygon = GeometryUtils.ensureCounterClockwise(cwPolygon, factory);
        assertEquals(
                GeometryUtils.orientation(ccwPolygon.getExteriorRing().getCoordinates()),
                GeometryUtils.PointOrientation.COUNTER_CLOCKWISE,
                "CCW"
        );
    }

    @Test
    public void testEnsureCounterClockwise3() {
        Coordinate[] coordinates = new Coordinate[] {
                new Coordinate(134.000, -37.999),
                new Coordinate(134.000, -35.999),
                new Coordinate(132.000, -35.999),
                new Coordinate(132.0, -34),
                new Coordinate(130, -34),
                new Coordinate(130, -32),
                new Coordinate(136.0, -32),
                new Coordinate(136.0, -34),
                new Coordinate(140, -34),
                new Coordinate(140, -37.999),
                new Coordinate(134, -37.999),
        };
        LinearRing cwRing = factory.createLinearRing(coordinates);
        Polygon cwPolygon = factory.createPolygon(cwRing);

        Polygon ccwPolygon = GeometryUtils.ensureCounterClockwise(cwPolygon, factory);
        assertEquals(
                GeometryUtils.orientation(ccwPolygon.getExteriorRing().getCoordinates()),
                GeometryUtils.PointOrientation.COUNTER_CLOCKWISE,
                "CCW"
        );
    }
}
