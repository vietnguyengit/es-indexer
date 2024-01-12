package au.org.aodn.esindexer.service;

import org.springframework.http.ResponseEntity;

public interface GeoNetworkService {
    void setServer(String server);
    String getServer();

    void setIndexName(String i);
    String getIndexName();

    String searchRecordBy(String uuid);

    long getMetadataRecordsCount();
    Iterable<String> getAllMetadataRecords();
}
