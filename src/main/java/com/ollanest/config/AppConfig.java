package com.ollanest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Value("${app.version:v2026.1.0}")
    private String appVersion;

    @Value("${app.default-admin-email:admin@ollanest.local}")
    private String defaultAdminEmail;

    @Value("${app.default-admin-password:CHANGE_ME_ON_FIRST_BOOT}")
    private String defaultAdminPassword;

    @Value("${app.default-user-password:CHANGE_ME_ON_FIRST_BOOT}")
    private String defaultUserPassword;

    @Value("${app.data-dir:./data}")
    private String dataDir;

    @Value("${app.static-dir:./public}")
    private String staticDir;

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public String getAppVersion() { return appVersion; }
    public String getDefaultAdminEmail() { return defaultAdminEmail; }
    public String getDefaultAdminPassword() { return defaultAdminPassword; }
    public String getDefaultUserPassword() { return defaultUserPassword; }
    public String getDataDir() { return dataDir; }
    public String getStaticDir() { return staticDir; }
}
