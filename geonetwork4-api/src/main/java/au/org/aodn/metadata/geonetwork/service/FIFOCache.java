package au.org.aodn.metadata.geonetwork.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * A simple FIFO cache to store a few records to improve querying speed.
 * Shared by concurrent indexing threads, hence the synchronized wrapper.
 * @param <K>
 * @param <V>
 */
public class FIFOCache<K, V> {

    private final Map<K, V> cache;

    public FIFOCache(int maxSize) {
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(maxSize + 1, 1.0f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        });
    }

    public V get(K key) {
        return cache.get(key);
    }

    public void put(K key, V value) {
        cache.put(key, value);
    }
}
