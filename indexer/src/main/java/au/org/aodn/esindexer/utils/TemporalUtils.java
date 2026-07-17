package au.org.aodn.esindexer.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TemporalUtils {

    protected static final Logger logger = LogManager.getLogger(TemporalUtils.class);

    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /**
     * Refer to the spec why we need to do this, in short we need an overall temporal that cover the full range
     * of all the discrete start/end
     * @param temporals
     * @return
     */
    public static List<List<String>> concatOverallTemporalRange(List<List<String>> temporals) {
        ZonedDateTime min = null;
        ZonedDateTime max = null;
        // A null end date means ongoing, which outranks any concrete end date
        boolean openEnded = false;

        if(temporals != null) {
            for (List<String> temporal : temporals) {
                if (temporal.get(0) != null) {
                    ZonedDateTime t = ZonedDateTime.parse(temporal.get(0));
                    min = (min == null || min.isAfter(t)) ? t : min;
                }

                if (temporal.get(1) != null) {
                    ZonedDateTime t = ZonedDateTime.parse(temporal.get(1));
                    max = (max == null || max.isBefore(t)) ? t : max;
                }
                else {
                    openEnded = true;
                }
            }
            // Append the overall to the front
            List<List<String>> f = new ArrayList<>();

            List<String> overall = new ArrayList<>(2);
            overall.add(min == null ? null : min.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            overall.add(openEnded || max == null ? null : max.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            f.add(overall);

            f.addAll(temporals);
            return f;
        }
        else {
            return null;
        }
    }
}
