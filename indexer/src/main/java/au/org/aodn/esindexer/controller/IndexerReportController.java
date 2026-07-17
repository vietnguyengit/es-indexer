package au.org.aodn.esindexer.controller;

import au.org.aodn.esindexer.service.ElasticSearchIndexService;
import au.org.aodn.esindexer.utils.CommonUtils;
import au.org.aodn.esindexer.utils.JaxbUtils;
import au.org.aodn.metadata.geonetwork.service.GeoNetworkService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import au.org.aodn.metadata.iso19115_3_2018.MDMetadataType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/indexer/report/")
@Tag(name="Indexer Report", description = "Generate report for troubleshoot")
@Slf4j
public class IndexerReportController {
    protected ElasticSearchIndexService elasticSearchIndexService;
    protected GeoNetworkService geoNetworkResourceService;
    protected JaxbUtils<MDMetadataType> jaxbUtils;
    protected String indexName;

    @Autowired
    public IndexerReportController(
            @Value("${elasticsearch.index.name}") String indexName,
            JaxbUtils<MDMetadataType> jaxbUtils,
            ElasticSearchIndexService elasticSearchIndexService,
            GeoNetworkService geoNetworkResourceService) {

        this.indexName = indexName;
        this.jaxbUtils = jaxbUtils;
        this.elasticSearchIndexService = elasticSearchIndexService;
        this.geoNetworkResourceService = geoNetworkResourceService;
    }

    @GetMapping(path="/uuid-mismatch")
    public ResponseEntity<List<String>> findUUidNotInElastic() {
        List<String> uuids = new ArrayList<>();
        Iterable<String> records = this.geoNetworkResourceService.getAllMetadataRecords(null);
        Iterator<String> i = records.iterator();

        while(i.hasNext()) {
            String r = i.next();
            try {
                if (r != null) {
                    MDMetadataType doc = jaxbUtils.unmarshal(r);
                    String uuid = CommonUtils.getUUID(doc);

                    log.info("Comparing uuid {}", uuid);
                    if (elasticSearchIndexService.getFirstMatchId(this.indexName, "id", uuid) == null) {
                        uuids.add(uuid);
                    }
                    // Small pause so we do not hammer GeoNetwork and Elastic
                    Thread.sleep(50);
                }
            } catch (Exception e) {
                uuids.add(String.format("%s[%s]", e.getMessage(), r));
            }
        }

        return ResponseEntity.ok().body(uuids);
    }

}
