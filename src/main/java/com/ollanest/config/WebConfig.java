package com.ollanest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.static-dir:./public}")
    private String staticDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static files from ./public directory
        // Note: API routes take precedence since they are defined in controllers first.
        // The /** mapping here is a fallback for assets (CSS, JS, images).
        registry.addResourceHandler("/assets/**", "/*.js", "/*.css", "/*.ico", "/*.png", "/*.svg")
                .addResourceLocations("file:" + staticDir + "/");
    }
}
