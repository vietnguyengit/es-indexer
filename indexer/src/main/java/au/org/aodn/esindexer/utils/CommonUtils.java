package au.org.aodn.esindexer.utils;

import java.util.Optional;
import java.util.function.Supplier;

public class CommonUtils {

    public static <T> Optional<T> safeGet(Supplier<T> supplier) {
        try {
            return Optional.of(supplier.get());
        } catch (NullPointerException | IndexOutOfBoundsException ignored) {
            return Optional.empty();
        }
    }
}