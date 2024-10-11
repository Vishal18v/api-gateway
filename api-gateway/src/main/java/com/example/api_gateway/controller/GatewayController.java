package com.example.api_gateway.controller;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/gateway")
public class GatewayController {

    // Redis cache
    private final RedisCommands<String, String> redisCommands;

    // Load balancer nodes for microservices
    private final List<String> helloServiceNodes = Arrays.asList("http://localhost:8081", "http://localhost:8082");
    private final List<String> ciaoServiceNodes = Arrays.asList("http://localhost:8083", "http://localhost:8084");

    // Rate Limiting bucket (e.g., 5 requests per minute)
    private final Bucket bucket = Bucket4j.builder()
            .addLimit(Bandwidth.simple(5, Duration.ofMinutes(1)))
            .build();

    public GatewayController(RedisCommands<String, String> redisCommands) {
        this.redisCommands = redisCommands;
    }

    // API Gateway endpoint
    @GetMapping("/{service}")
    public ResponseEntity<String> routeRequest(
            @PathVariable String service,
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "Client-IP", defaultValue = "default-ip") String clientIp) {

        // Rate Limiting
        if (!bucket.tryConsume(1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Too many requests");
        }

        // Determine the target microservice URL
        String targetUrl = getTargetUrl(service, clientIp);
        if (targetUrl == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Service not found");
        }

        // Check if the response is cached in Redis
        String cacheKey = targetUrl + params.toString();
        String cachedResponse = redisCommands.get(cacheKey);
        if (cachedResponse != null) {
            return ResponseEntity.ok(cachedResponse);
        }

        // Call the appropriate microservice
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response;
        try {
            response = restTemplate.getForEntity(targetUrl + "?" + buildQueryString(params), String.class);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Microservice unavailable");
        }

        // Cache the response in Redis
        redisCommands.setex(cacheKey, 60, response.getBody()); // Cache for 60 seconds

        return ResponseEntity.ok(response.getBody());
    }

    // Determine the target microservice URL using consistent hashing
    private String getTargetUrl(String service, String clientIp) {
        List<String> nodes;
        if ("hello".equals(service)) {
            nodes = helloServiceNodes;
        } else if ("ciao".equals(service)) {
            nodes = ciaoServiceNodes;
        } else {
            return null;
        }

        int nodeIndex = Math.abs(clientIp.hashCode() % nodes.size());
        return nodes.get(nodeIndex) + "/" + service;
    }

    // Helper function to build query string from request parameters
    private String buildQueryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }
}

