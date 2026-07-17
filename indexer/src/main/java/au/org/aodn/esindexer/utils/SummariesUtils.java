package au.org.aodn.esindexer.utils;

import au.org.aodn.metadata.iso19115_3_2018.LILineageType;
import au.org.aodn.metadata.iso19115_3_2018.MDDataIdentificationType;
import au.org.aodn.metadata.iso19115_3_2018.MDMetadataType;
import au.org.aodn.metadata.iso19115_3_2018.MDProgressCodePropertyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static au.org.aodn.esindexer.utils.CommonUtils.safeGet;

public class SummariesUtils {

    protected static Logger logger = LoggerFactory.getLogger(SummariesUtils.class);

    public static String getStatus(MDMetadataType source) {
        List<MDDataIdentificationType> items = MapperUtils.findMDDataIdentificationType(source);
        if (!items.isEmpty()) {
            List<String> temp = new ArrayList<>();
            for (MDDataIdentificationType i : items) {
                // status
                // mdb:identificationInfo/mri:MD_DataIdentification/mri:status/mcc:MD_ProgressCode/@codeListValue
                for (MDProgressCodePropertyType s : i.getStatus()) {
                    // An empty <mri:status/> element has no progress code
                    if (s.getMDProgressCode() != null) {
                        temp.add(s.getMDProgressCode().getCodeListValue());
                    }
                }
            }
            return String.join(" | ", temp);
        }
        logger.warn("Unable to find status metadata record: {}", CommonUtils.getUUID(source));
        return null;
    }

    public static String getStatement(MDMetadataType source) {
        var lineages = MapperUtils.findMDResourceLineage(source);
        if (lineages.isEmpty()) {
            return null;
        }
        for (var lineage : lineages) {
            var abstractLiLineage = lineage.getAbstractLineageInformation().getValue();
            if (!(abstractLiLineage instanceof LILineageType liLineage)) {
                continue;
            }
            var statement = safeGet(() -> liLineage.getStatement().getCharacterString().getValue().toString());
            if (statement.isEmpty()) {
                continue;
            }
            return statement.get();
        }
        return null;
    }
}
