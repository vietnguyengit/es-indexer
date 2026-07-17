# `es-indexer` - Elasticsearch Indexer

![flow1](https://github.com/user-attachments/assets/39d873eb-a2a5-41d1-9cd0-a21bf4fb7745)

This `es-indexer` for ingesting GeoNetwork4 metadata records into an Elasticsearch index. The index schema adheres to the STAC schema but includes some customisations.

Although GeoNetwork4 itself comes with a default Elasticsearch index (`gn_records`), the OGC APIs will use the `es-indexer`-created index to retrieve data for the new AODN portal.

## Development

This application is built with `Spring Boot 3` and `Java 17`.

There are required environment variables to run the `es-indexer`:

```env
# Client calling the Indexer API must provide this token in the Authorization header, these value is set
# in [appdeply](https://github.com/aodn/appdeploy/blob/main/tg/edge/es-indexer/ecs/variables.yaml) for edge env
# under environment_variables:

APP_HTTP_AUTH_TOKEN=sampletoken

SERVER_PORT=8080

ELASTICSEARCH_INDEX_NAME=sampleindex
ELASTICSEARCH_SERVERURL=http://localhost:9200
ELASTICSEARCH_APIKEY=sampleapikey

GEONETWORK_HOST=http://localhost:8080
```

### Maven build

```bash
$ mvn clean install # [-DskipTests]
```

If you do not use `-DskipTests`, then autotest will run where it will create a docker geonetwork instance, inject the
sample data and then run the indexer. You can treat this as kind of integration testing.

This project container 3 submodules:
* **geonetwork** - This is used to compile JAXB lib to handle XML return from GEONetowrk, it is iso19115 standard
* **stacmodel** - A group of java class that create the STAC json which store in elastic search, so if app needs to read
  STAC from elastic, use this lib
* **indexer** - The main app that do the transformation.

### Docker

Start a local instance of indexer (build the jar first)

```bash
$ ./mvnw package -DskipTests
$ docker-compose up # [-d: in daemon mode | --build: to rebuild the image]
```

### Endpoints:

| Description                                            | Endpoints                              | Environment | Header                                                                                                | Param                                                                   |
|--------------------------------------------------------|----------------------------------------|-------------|-------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| Logfile                                                | `/manage/logfile`                      | Edge        |                                                                                                       |                                                                         |
| Beans info                                             | `/manage/beans`                        | Edge        |                                                                                                       |                                                                         |
| Env info                                               | `/manage/env`                          | Edge        |                                                                                                       |                                                                         |
| Info  (Show version)                                   | `/manage/info`                         | Edge        |                                                                                                       |                                                                         |
| Health check                                           | `/manage/health`                       | Edge        |                                                                                                       |                                                                         |
| POST/GET/DELETE index metadata against specific record | `/api/v1/indexer/index/{uuid}`         | All         |                                                                                                       | withCO - set true will call index cloud optimized before index metadata |
| POST Index cloud optimized data on specific record     | `/api/v1/indexer/index/{uuid}/cloud    | All         | Accept: text/event-stream, Content-Type: text/event-stream;charset=utf-8, X-API-Key: (Please ask Dev) |                                                                         |
| Bulk index                                             | `/api/v1/indexer/index/all`            | All         |                                                                                                       |                                                                         |
| Bulk index Async metadata on all                       | `/api/v1/indexer/index/async/all       | All         |                                                                                                       |                                                                         |
| POST Index Async cloud optimized data on all           | `/api/v1/indexer/index/async/all-cloud | All         | Accept: text/event-stream, Content-Type: text/event-stream;charset=utf-8, X-API-Key: (Please ask Dev) |                                                                         |
| Swagger UI:                                            | `/swagger-ui/index.html`               | All         |                                                                                                       |                                                                         |

> The 'async/all' endpoints use SSE (Server Side Events) to avoid gateway timeout, you should use
> postman version 10.2 or above (there is a bug with SSE for previous version), or use the web based
> postman (pref), once you issue the call, you should see event come back in the body at regular time.
>
> The call header should contains
> * X-API-Key  (Check with dev)
> * Accept = text/event-stream
> * Content-Type = text/event-stream;charset=utf-8
> * Method = POST

## Notes
### Centroid Calculation
The calculation of centroid isn't happens here, the indexer creates a spatial extents area with land removed. The
resulting spatial extents is store in geometry_noland. The centroid point is calculated in the OGC api, please refer
to the README in ogcapi for details.

### ARDC Vocabulary
When indexer starts, it will try to fetch vocabs from ARDC, please check code under [ardcvocabs](ardcvocabs). The url
to the API call always points to the "current", this current is maintained manually by Nat. For each vocabs, system needs
to call two separated API, one target the root level, and the other target all node. In order to avoid un-necessary call,
the indexer will check the "current" version is diff from the saved version in Elastic, if version is the same then it
will skip the download.

There is a [gcmd-mapping.csv](indexer/src/main/resources/config_files/gcmd-mapping.csv) file which map the GCMD keywords
to the AODN vocabs, this allow dataset having GCMD keyword searchable using AODN keywords. The mapping is created
manually by Nat, right now store here [excel](https://universitytasmania.sharepoint.com/:x:/r/sites/tier2-imos-AODN-Team/_layouts/15/Doc.aspx?sourcedoc=%7B0FB939CA-9881-4C33-9254-F59430DA5EFB%7D&file=non_unique_last_term_full_term_gcmd_keywords.xlsx&fromShare=true&action=default&mobileredirect=true)

The vocab is assigned to the metadata manually, and is part of the suggested words. That means user can type vocabs in
the search box, and able to select some known keywords. Although vocabs have multiple level, so far we only use level 1
and level 2.

### Organization Vocabulary
This vocab provides value for organization filter and generates the acronym for the organization. The acronym is
expanded during elastic search via build in function. In the application.yaml you can see additional entries for
acronym which is used to fill in the missing from organization vocab.

```yaml
acronyms:
  name: portal-acronyms
  manual:
  - "nrmn => national reef monitoring network"

```

## Branching name

- `hotfix/`: for quickly fixing critical issues,
- `usually/`: with a temporary solution
- `bugfix/`: for fixing a bug
- `feature/`: for adding, removing or modifying a feature
- `test/`: for experimenting something which is not an issue
- `wip/`: for a work in progress

And add the issue id after an `/` followed with an explanation of the task.

Example of use:
`feature/6709-add-GN-protocol-to-link-mapping`
