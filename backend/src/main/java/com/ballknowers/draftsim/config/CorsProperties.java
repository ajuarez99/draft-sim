package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "draftsim.cors")
public record CorsProperties(List<String> allowedOrigins) {}
