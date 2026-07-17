package au.org.aodn.esindexer.utils;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class StacUtilsTest {

    @Test
    public void testCreateStacBBoxWithProvidedInput() {
        // Arrange: Create input geometries from provided bounding boxes
        GeometryFactory factory = new GeometryFactory();
        List<List<Geometry>> inputPolygons = Arrays.asList(
                Collections.singletonList(factory.toGeometry(new Envelope(70.0, 180.0, -70.0, 20.0))),
                Collections.singletonList(factory.toGeometry(new Envelope(-180.0, -170.0, -70.0, 20.0)))
        );

        // Act: Call the method
        List<List<BigDecimal>> result = StacUtils.createStacBBox(inputPolygons);

        // Assert: Verify the result
        assertNotNull(result, "Result should not be null");
        assertEquals(3, result.size(), "Result should contain 3 bounding boxes (1 overall + 2 individual)");

        // Expected overall bounding box
        // Input envelopes: [70.0, -70.0, 180.0, 20.0] and [-180.0, -70.0, -170.0, 20.0]
        // Normalized to [0, 360]: [70.0, -70.0, 180.0, 20.0] and [180.0, -70.0, 190.0, 20.0]
        // Overall: [70.0, -70.0, 190.0, 20.0]
        // Shift to [-180, 180]: [70.0, -70.0, -170.0, 20.0] (since 190 - 360 = -170)
        List<BigDecimal> expectedOverall = Arrays.asList(
                BigDecimal.valueOf(70.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(-70.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(190.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(20.0).setScale(10, RoundingMode.HALF_UP)
        );
        assertEquals(expectedOverall, result.get(0), "Overall bounding box is incorrect");
    }

    @Test
    public void testCreateStacBBoxCrossingLongitudeZero() {
        GeometryFactory factory = new GeometryFactory();
        List<List<Geometry>> inputPolygons = Collections.singletonList(
                Collections.singletonList(factory.toGeometry(new Envelope(-10.0, 10.0, -20.0, 20.0)))
        );

        List<List<BigDecimal>> result = StacUtils.createStacBBox(inputPolygons);

        // A box crossing longitude 0 must keep its shape, not flip to the far side of the planet
        List<BigDecimal> expectedOverall = Arrays.asList(
                BigDecimal.valueOf(-10.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(-20.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(10.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(20.0).setScale(10, RoundingMode.HALF_UP)
        );
        assertEquals(expectedOverall, result.get(0), "Overall bounding box is incorrect");
    }

    @Test
    public void testCreateStacBBoxGlobalExtent() {
        GeometryFactory factory = new GeometryFactory();
        List<List<Geometry>> inputPolygons = Collections.singletonList(
                Collections.singletonList(factory.toGeometry(new Envelope(-180.0, 180.0, -90.0, 90.0)))
        );

        List<List<BigDecimal>> result = StacUtils.createStacBBox(inputPolygons);

        List<BigDecimal> expectedOverall = Arrays.asList(
                BigDecimal.valueOf(-180.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(-90.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(180.0).setScale(10, RoundingMode.HALF_UP),
                BigDecimal.valueOf(90.0).setScale(10, RoundingMode.HALF_UP)
        );
        assertEquals(expectedOverall, result.get(0), "Overall bounding box is incorrect");
    }
}
