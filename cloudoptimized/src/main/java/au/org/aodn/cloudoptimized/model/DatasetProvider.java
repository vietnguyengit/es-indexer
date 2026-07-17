package au.org.aodn.cloudoptimized.model;

import au.org.aodn.cloudoptimized.model.geojson.FeatureCollectionGeoJson;
import au.org.aodn.cloudoptimized.service.DataAccessService;
import au.org.aodn.cloudoptimized.service.DataAccessServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class DatasetProvider {

    protected final int THREADS_COUNT = 10;

    protected Logger log = LoggerFactory.getLogger(DatasetProvider.class);
    protected final String uuid;
    protected final String key;
    protected YearMonth startYearMonth;
    protected final YearMonth endYearMonth;
    protected final DataAccessService dataAccessService;

    // Store in result queue reduce the memory usage and increase processing throughput
    protected final LinkedBlockingQueue<FeatureCollectionTask> resultQueue = new LinkedBlockingQueue<>();
    protected final List<MetadataFields> columns;

    protected ExecutorService executorService = Executors.newFixedThreadPool(THREADS_COUNT, r -> {
        Thread t = new Thread(r, "DatasetProvider-worker");
        t.setDaemon(true);
        return t;
    });
    protected CompletionService<FeatureCollectionTask> completionService = new ExecutorCompletionService<>(executorService);

    // Default value means not yet start processing
    protected AtomicReference<Integer> taskCount = new AtomicReference<>(null);
    protected CountDownLatch countDownLatch = new CountDownLatch(1);

    public DatasetProvider(
            String uuid,
            String key,
            LocalDate startDate,
            LocalDate endDate,
            DataAccessService dataAccessService,
            List<MetadataFields> columns
    ) {
        this.uuid = uuid;
        this.key = key;
        this.dataAccessService = dataAccessService;
        this.startYearMonth = YearMonth.from(startDate);
        this.endYearMonth = YearMonth.from(endDate);
        this.columns = columns;
        Thread t = new Thread(DatasetProvider.this::queryDataByMultiThreads);
        t.setDaemon(true);
        t.start();
    }

    protected record FeatureCollectionTask(YearMonth yearMonth, FeatureCollectionGeoJson featureCollection){}

    protected void queryDataByMultiThreads() {

        int count = 0;
        // Create a list of tasks and submit
        for(YearMonth i = startYearMonth; !i.isAfter(endYearMonth); i = i.plusMonths(1)) {
            final YearMonth current = i;
            completionService.submit(() -> queryFeatureCollection(columns, current));
            count++;
        }

        taskCount.set(count);

        // Allow hasNext below to start processing
        countDownLatch.countDown();

        // invoke all tasks and cache the results
        try {
            for (int i = 0; i < count; i++) {
                Future<FeatureCollectionTask> featureCollectionTask = completionService.take();
                if (featureCollectionTask != null && featureCollectionTask.get() != null) {
                    resultQueue.put(featureCollectionTask.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error while fetching data", e);
        } finally {
            DataAccessServiceImpl.shutdownExecutor(executorService);
        }
    }
    /**
     * It consumes value from queue and depends on which task completed first so the order of the
     * return of which month of year is not deterministic. Reason is to improve the parallel processing
     * @return The json data of that task
     */
    public Iterable<FeatureCollectionGeoJson> getIterator() {

        return () -> new Iterator<>() {

            int i = 0;

            @Override
            public boolean hasNext() {
                try {
                    countDownLatch.await();
                    return i < DatasetProvider.this.taskCount.get();
                }
                catch (InterruptedException e) {
                    return false;
                }
            }

            @Override
            public FeatureCollectionGeoJson next() {
                try {
                    i ++;
                    FeatureCollectionTask c = resultQueue.take();
                    log.debug("Return data for year month: {}", c.yearMonth());
                    return c.featureCollection();
                }
                catch (InterruptedException e) {
                    return null;
                }
            }
        };
    }
    /**
     * Key call to query the data
     * @param columns - The columns to query
     * @param yearMonth - The year month to query
     * @return - The feature collection
     */
    protected FeatureCollectionTask queryFeatureCollection(List<MetadataFields> columns, YearMonth yearMonth) {
        // Log only once per year to prevent log flooding
        if (yearMonth.getMonth().getValue() == 1) {
            log.info("Processing data for year: {}", yearMonth.getYear());
        }
        log.debug("Start querying data for year month: {}", yearMonth);
        FeatureCollectionGeoJson featureCollection;
        if (key.endsWith(".zarr")) {
            // The function will do internal retry on exception
            featureCollection = dataAccessService.getZarrIndexingDataByMonth(uuid, key, yearMonth);
        }
        else if (key.endsWith(".parquet")) {
            // The function will do internal retry on exception
            featureCollection =  dataAccessService.getIndexingDatasetByMonth(
                    uuid,
                    key,
                    yearMonth,
                    columns
            );
        } else {
            throw new UnsupportedOperationException( "Only support .zarr and .parquet dataset for now");
        }

        return new FeatureCollectionTask(yearMonth, featureCollection);
    }
}
