package com.plm.gateway;

import com.plm.gateway.filter.GatewayAccessLogFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class GatewayRoutingConfigIT {

    @Autowired
    private Environment environment;

    @Autowired
    private GatewayProperties gatewayProperties;

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private GatewayAccessLogFilter gatewayAccessLogFilter;

        private WebTestClient webTestClient;

    @Test
    void gatewayShouldExposeExpectedLocalRoutes() {
        Assertions.assertEquals("8080", environment.getProperty("server.port"));
        Assertions.assertEquals(2, gatewayProperties.getRoutes().size());

        var authRoute = gatewayProperties.getRoutes().stream()
                .filter(route -> "auth-service".equals(route.getId()))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("http://localhost:8081", authRoute.getUri().toString());
        Assertions.assertTrue(authRoute.getPredicates().stream()
                .anyMatch(predicate -> predicate.getName().equals("Path")
                        && predicate.getArgs().values().stream().anyMatch(value -> "/auth/**".equals(value))));

        var attributeRoute = gatewayProperties.getRoutes().stream()
                .filter(route -> "attribute-service".equals(route.getId()))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("http://localhost:8082", attributeRoute.getUri().toString());
        Assertions.assertTrue(attributeRoute.getPredicates().stream()
                .anyMatch(predicate -> predicate.getName().equals("Path")
                        && predicate.getArgs().values().stream().anyMatch(value -> "/api/meta/**".equals(value))));

                Assertions.assertEquals("true", environment.getProperty("spring.cloud.gateway.server.webflux.globalcors.add-to-simple-url-handler-mapping"));
        Assertions.assertTrue(applicationContext.containsBean("gatewayAccessLogFilter"));
        Assertions.assertNotNull(gatewayAccessLogFilter);
    }

        @Test
        void gatewayShouldReturnJsonForUnknownRoute() {
                client().get()
                                .uri("/gateway/not-found")
                                .accept(MediaType.APPLICATION_JSON)
                                .exchange()
                                .expectStatus().isNotFound()
                                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                                .expectBody()
                                .jsonPath("$.status").isEqualTo(404)
                                .jsonPath("$.code").isEqualTo("GATEWAY_ROUTE_NOT_FOUND")
                                .jsonPath("$.message").isEqualTo("gateway route not found")
                                .jsonPath("$.path").isEqualTo("/gateway/not-found");
        }

        @Test
        void gatewayShouldReturnJsonWhenDownstreamUnavailable() {
                client().get()
                                .uri("/__gateway-test__/downstream")
                                .accept(MediaType.APPLICATION_JSON)
                                .exchange()
                                .expectStatus().isEqualTo(502)
                                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                                .expectBody()
                                .jsonPath("$.status").isEqualTo(502)
                                .jsonPath("$.code").isEqualTo("GATEWAY_DOWNSTREAM_UNAVAILABLE")
                                .jsonPath("$.message").isEqualTo("downstream service is unavailable")
                                .jsonPath("$.path").isEqualTo("/__gateway-test__/downstream");
        }

        private WebTestClient client() {
                if (webTestClient == null) {
                        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                                        .configureClient()
                                        .build();
                }
                return webTestClient;
        }

        @TestConfiguration(proxyBeanMethods = false)
        static class GatewayRoutingConfigITConfiguration {

                @Bean
                RouteLocator gatewayRoutingConfigITRoutes(RouteLocatorBuilder builder) {
                        return builder.routes()
                                        .route("gateway-test-unavailable-service",
                                                        route -> route.path("/__gateway-test__/downstream")
                                                                        .uri("http://127.0.0.1:65534"))
                                        .build();
                }
        }
}