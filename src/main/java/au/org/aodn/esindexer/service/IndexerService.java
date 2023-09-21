package au.org.aodn.esindexer.service;

import org.json.JSONObject;
import java.io.IOException;

public interface IndexerService {
    void ingestNewDocument(JSONObject metadataValues);
    void createIndexFromMappingJSONFile();
    void deleteDocumentByUUID(String uuid);
    void updateDocumentByUUID(String uuid, JSONObject metadataValues);
    void indexAllMetadataRecordsFromGeoNetwork();
}
