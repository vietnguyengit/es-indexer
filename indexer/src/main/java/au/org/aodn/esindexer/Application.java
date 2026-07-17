package au.org.aodn.esindexer;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.TimeZone;

import static au.org.aodn.esindexer.batch.CLIRunner.BATCH;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration.class )
public class Application {
    @PostConstruct
    void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Application.class);

        boolean isBatchMode = Arrays.asList(args).contains("--" + BATCH);

        if(isBatchMode) {
            String currentProfiles = System.getProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE"));
            builder
                    .web(WebApplicationType.NONE)
                    .profiles(currentProfiles, "batch");    // Do not change order, batch must be last to override some setting in other profile
        }
        builder.build().run(args);
    }
}
