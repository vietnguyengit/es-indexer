package au.org.aodn.esindexer.configuration;

import au.org.aodn.esindexer.security.APIKeyAuthFilter;
import au.org.aodn.esindexer.security.JwtAuthenticationEntryPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

@Configuration
@ConditionalOnWebApplication
@EnableWebSecurity
@Order(1)
public class SecurityConfig {

    @Value("${app.http.auth-token-header-name}")
    private String principalRequestHeader;

    @Value("${app.http.authToken}")
    private String principalRequestValue;

    @Autowired
    private JwtAuthenticationEntryPoint unauthorizedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        APIKeyAuthFilter filter = new APIKeyAuthFilter(principalRequestHeader);

        filter.setAuthenticationManager(
            authentication -> {
                String principal = (String) authentication.getPrincipal();
                // Constant-time comparison so the key cannot be probed via timing
                if (principal == null || !MessageDigest.isEqual(
                        principalRequestValue.getBytes(StandardCharsets.UTF_8),
                        principal.getBytes(StandardCharsets.UTF_8))) {
                    throw new BadCredentialsException("Invalid API key.");
                }
                authentication.setAuthenticated(true);
                return authentication;
            });


        http.cors(withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(config -> config.authenticationEntryPoint(unauthorizedHandler))
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilter(filter)
            .authorizeHttpRequests((requests) -> requests
                    .requestMatchers(antMatcher("/error")).permitAll()
                    .requestMatchers(antMatcher("/")).permitAll()
                    .requestMatchers(antMatcher("/favicon.ico")).permitAll()
                    .requestMatchers(antMatcher("/**/*.png")).permitAll()
                    .requestMatchers(antMatcher("/**/*.gif")).permitAll()
                    .requestMatchers(antMatcher("/**/*.svg")).permitAll()
                    .requestMatchers(antMatcher("/**/*.jpg")).permitAll()
                    .requestMatchers(antMatcher("/**/*.html")).permitAll()
                    .requestMatchers(antMatcher("/**/*.css")).permitAll()
                    .requestMatchers(antMatcher("/**/*.js")).permitAll()
                    .requestMatchers(antMatcher(HttpMethod.GET,"/api/v1/indexer/index/gn_records/**")).permitAll()
                    .requestMatchers(antMatcher(HttpMethod.GET,"/api/v1/indexer/index/records/**")).permitAll()
                    .requestMatchers(antMatcher("/v3/api-docs/**")).permitAll()
                    .requestMatchers(antMatcher("/swagger-resources/**")).permitAll()
                    .requestMatchers(antMatcher("/swagger-ui.html")).permitAll()
                    .requestMatchers(antMatcher("/manage/**")).permitAll()
                    .anyRequest().authenticated()
            );

        return http.build();
    }
}
