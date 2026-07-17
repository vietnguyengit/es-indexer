package au.org.aodn.esindexer.utils;

import au.org.aodn.metadata.iso19115_3_2018.MDMetadataType;
import jakarta.xml.bind.JAXBException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static au.org.aodn.esindexer.BaseTestClass.readResourceFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GeometryUtilsTest {
    protected Logger logger = LoggerFactory.getLogger(GeometryUtilsTest.class);
    protected JaxbUtils<MDMetadataType> jaxb;

    public GeometryUtilsTest() throws JAXBException {
        jaxb = new JaxbUtils<>(MDMetadataType .class);
    }

    @BeforeEach
    public void init() {
        GeometryUtils.setCoastalPrecision(0.03);
    }

    @Test
    public void verifyLandStrippedFromSpatialExtents() throws IOException, JAXBException {
        GeometryUtils.setReducerPrecision(null);
        GeometryUtils.init();

        String xml = readResourceFile("classpath:canned/sample_complex_area.xml");
        MDMetadataType source = jaxb.unmarshal(xml);
        // Whole spatial extends
        List<List<Geometry>> withLand = GeometryUtils.createGeometryItems(
                source,
                (rawInput, size) -> GeometryBase.findPolygonsFrom(GeometryBase.COORDINATE_SYSTEM_CRS84, rawInput),
                null
        );

        List<List<Geometry>> l = Objects.requireNonNull(withLand);

        assertEquals(1, l.size(), "Land have 1 polygon array");
        assertEquals(8, l.get(0).size(), "Size 8 with land");

        Geometry le = l.get(0).get(0);
        Coordinate[] coors = le.getCoordinates();

        assertEquals(118.0, coors[0].getX(), 0.001);
        assertEquals(-36.0, coors[0].getY(), 0.001);

        assertEquals(118.0, coors[1].getX(), 0.001);
        assertEquals(-34.0, coors[1].getY(), 0.001);

        assertEquals(122.0, coors[2].getX(), 0.001);
        assertEquals(-34.0, coors[2].getY(), 0.001);

        assertEquals(122.0, coors[3].getX(), 0.001);
        assertEquals(-32.0, coors[3].getY(), 0.001);
        // Strip the land away.
        List<List<Geometry>> noLand = GeometryUtils.createGeometryItems(
                source,
                (rawInput, s) -> GeometryUtils.createGeometryWithoutLand(rawInput),
                null
        );

        List<List<Geometry>> nl = Objects.requireNonNull(noLand);

        assertEquals(1, nl.size(), "No Land have 1 polygon array");
        assertEquals(11, nl.get(0).size(), "Size 11 with land");

        Geometry nle = nl.get(0).get(0).getEnvelope();
        Coordinate[] ncoors = nle.getCoordinates();

        // The envelope of the two polygon should match given one is the original and the other just strip the land
        assertEquals(118.0, ncoors[0].getX(), 0.01);
        assertEquals(-35.9999, ncoors[0].getY(), 0.01);

        assertEquals(118.0, ncoors[1].getX(), 0.01);
        assertEquals(-32.2787, ncoors[1].getY(), 0.01);

        assertEquals(126, ncoors[2].getX(), 0.01);
        assertEquals(-32.2787, ncoors[2].getY(), 0.01);

        assertEquals(126.0, ncoors[3].getX(), 0.01);
        assertEquals(-35.9999, ncoors[3].getY(), 0.01);
    }
    /**
     * This test turn on the reducer to further reduce the complexity of the land area after
     * DouglasPeuckerSimplifier simplifier, this further reduce the number of digit to get a smaller geojson
     * which should improve transfer speed.
     *
     * @throws IOException - Not expect to throw
     * @throws JAXBException - Not expect to throw
     */
    @Test
    public void verifyLandStrippedFromSpatialExtentsWithReducerOn() throws IOException, JAXBException {
        GeometryUtils.setReducerPrecision(4.0);
        GeometryUtils.init();

        String xml = readResourceFile("classpath:canned/sample_complex_area.xml");
        MDMetadataType source = jaxb.unmarshal(xml);

        // Strip the land away.
        List<List<Geometry>> noLand = GeometryUtils.createGeometryItems(
                source,
                (rawInput, s) -> GeometryUtils.createGeometryWithoutLand(rawInput),
                null
        );

        List<List<Geometry>> nl = Objects.requireNonNull(noLand);

        assertEquals(1, nl.size(), "No Land have 1 polygon array");
        assertEquals(16, nl.get(0).size(), "Size 16 with land");

        Geometry nle = nl.get(0).get(0).getEnvelope();
        Coordinate[] ncoors = nle.getCoordinates();

        // The envelope of the two polygon should match given one is the original and the other just strip the land
        assertEquals(118.0, ncoors[0].getX(), 0.00);
        assertEquals(-36.0, ncoors[0].getY(), 0.00);

        assertEquals(118.0, ncoors[1].getX(), 0.00);
        assertEquals(-32.25, ncoors[1].getY(), 0.00);

        assertEquals(126, ncoors[2].getX(), 0.00);
        assertEquals(-32.25, ncoors[2].getY(), 0.00);

        assertEquals(126.0, ncoors[3].getX(), 0.00);
        assertEquals(-36.0, ncoors[3].getY(), 0.00);

        assertEquals(118.0, ncoors[4].getX(), 0.00);
        assertEquals(-36.0, ncoors[4].getY(), 0.00);
    }
}
