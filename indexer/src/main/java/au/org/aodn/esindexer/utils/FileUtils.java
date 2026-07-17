package au.org.aodn.esindexer.utils;

import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class FileUtils {
    public static File saveResourceToTemp(String resourceName, String filename) {
        String tempDir = System.getProperty("java.io.tmpdir");
        ClassPathResource resource = new ClassPathResource(resourceName);

        File tempFile = new File(tempDir, filename);
        try(InputStream input = resource.getInputStream()) {
            tempFile.deleteOnExit();  // Ensure the file is deleted when the JVM exits

            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                input.transferTo(outputStream);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return tempFile;
    }
}
