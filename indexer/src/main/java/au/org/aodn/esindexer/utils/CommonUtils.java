package au.org.aodn.esindexer.utils;

import au.org.aodn.metadata.iso19115_3_2018.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
public class CommonUtils {

    public static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("Executor did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static <T> Optional<T> safeGet(Supplier<T> supplier) {
        try {
            return Optional.of(supplier.get());
        } catch (
                NullPointerException
                | IndexOutOfBoundsException
                | ClassCastException ignored) {
            return Optional.empty();
        }
    }

    public static String getUUID(MDMetadataType source) {
        return source
                .getMetadataIdentifier()
                .getMDIdentifier()
                .getCode()
                .getCharacterString()
                .getValue()
                .toString();
    }

    public static String getTitle(MDMetadataType source) {
        // Primary: try to extract title from MD_DataIdentification blocks
        List<MDDataIdentificationType> items = MapperUtils.findMDDataIdentificationType(source);
        String title = extractTitleFromIdentifications(items);

        if (title.isBlank()) {
            // Fallback: try to extract title from SV_ServiceIdentification blocks
            // Some records use srv:SV_ServiceIdentification instead of mri:MD_DataIdentification
            List<SVServiceIdentificationType> serviceItems = MapperUtils.findSVServiceIdentificationType(source);
            title = extractTitleFromIdentifications(serviceItems);
        }

        return title;
    }

    private static String extractTitleFromIdentifications(List<? extends AbstractMDIdentificationType> items) {
        if (!items.isEmpty()) {
            return items.stream()
                    .map(item -> safeGet(() -> item.getCitation().getAbstractCitation().getValue()))
                    // If valid
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(ac -> {
                        // Try to find the title from these places
                        if (ac instanceof CICitationType2 type2) {
                            return type2.getTitle().getCharacterString().getValue().toString();
                        } else if (ac instanceof CICitationType type1) {
                            // Backward compatible
                            return type1.getTitle().getCharacterString().getValue().toString();
                        } else {
                            return "";
                        }
                    })
                    // If blank that means not found in map and need to filter out
                    .filter(s -> !s.isBlank())
                    // Just need to find the first valid one
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    public static String getDescription(MDMetadataType source) {
        List<MDDataIdentificationType> items = MapperUtils.findMDDataIdentificationType(source);
        String description = extractDescriptionFromIdentifications(items);

        if (description.isBlank()) {
            List<SVServiceIdentificationType> serviceItems = MapperUtils.findSVServiceIdentificationType(source);
            description = extractDescriptionFromIdentifications(serviceItems);
        }

        return description;
    }

    private static String extractDescriptionFromIdentifications(List<? extends AbstractMDIdentificationType> items) {
        if (!items.isEmpty()) {
            for (AbstractMDIdentificationType i : items) {
                return safeGet(() -> i.getAbstract().getCharacterString().getValue().toString())
                        .orElse("");
            }
        }
        return "";
    }
}
