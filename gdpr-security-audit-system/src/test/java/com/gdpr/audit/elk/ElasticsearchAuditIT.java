package com.gdpr.audit.elk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ElasticsearchAuditIT {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Container
    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.12.2"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void indexesAuditsWithExpectedMappingsAndSearchableFields() throws Exception {
        String index = "audit-it-" + UUID.randomUUID().toString().replace("-", "");
        String baseUrl = "http://localhost:9200";
        String templateName = index + "-template";
        String template = """
                {"index_patterns":["%s*"],"template":{"mappings":{"properties":{
                  "action":{"type":"keyword"},"clientIp":{"type":"ip"},
                  "severity":{"type":"keyword"},"environment":{"type":"keyword"},
                  "username":{"type":"keyword"},"requestBody":{"type":"text"}
                }}}}
                """.formatted(index);

        send("PUT", baseUrl + "/_index_template/" + templateName, template, 200);
        send("PUT", baseUrl + "/" + index, "{}", 200);
        JsonNode health = OBJECT_MAPPER.readTree(send("GET",
                baseUrl + "/_cluster/health/" + index + "?wait_for_status=yellow&timeout=90s", null, 200));
        assertThat(health.path("timed_out").asBoolean())
                .withFailMessage("Elasticsearch primary shard did not become active: %s", health)
                .isFalse();

        String audit = """
                {"action":"DELETE","clientIp":"198.51.100.77","severity":"HIGH",
                 "environment":"test","username":"admin-user","requestBody":"email=a*****e@example.org"}
                """;
        JsonNode indexed = OBJECT_MAPPER.readTree(send("POST", baseUrl + "/" + index + "/_doc", audit, 201));
        String documentId = indexed.path("_id").asText();

        JsonNode stored = OBJECT_MAPPER.readTree(
            send("GET", baseUrl + "/" + index + "/_doc/" + documentId, null, 200));
        assertThat(stored.path("_source").path("severity").asText()).isEqualTo("HIGH");
        send("POST", baseUrl + "/" + index + "/_refresh", null, 200);

        JsonNode mapping = OBJECT_MAPPER.readTree(send("GET", baseUrl + "/" + index + "/_mapping", null, 200));
        JsonNode properties = mapping.path(index).path("mappings").path("properties");
        assertThat(properties.path("action").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("clientIp").path("type").asText()).isEqualTo("ip");

        JsonNode search = OBJECT_MAPPER.readTree(send("GET", baseUrl + "/" + index + "/_search?q=action:DELETE", null, 200));
        assertThat(search.path("hits").path("total").path("value").asInt()).isEqualTo(1);
        JsonNode source = search.path("hits").path("hits").get(0).path("_source");
        assertThat(source.path("severity").asText()).isEqualTo("HIGH");
        assertThat(source.path("environment").asText()).isEqualTo("test");
        assertThat(source.path("requestBody").asText()).doesNotContain("a@example.org");

        String aggregationBody = """
                {"size":0,"aggs":{"by_user":{"terms":{"field":"username","size":10}}}}
                """;
        JsonNode aggregation = OBJECT_MAPPER.readTree(send(
                "POST", baseUrl + "/" + index + "/_search", aggregationBody, 200));
        assertThat(aggregation.path("aggregations").path("by_user").path("buckets").get(0)
                .path("key").asText()).isEqualTo("admin-user");
        assertThat(aggregation.path("aggregations").path("by_user").path("buckets").get(0)
                .path("doc_count").asInt()).isEqualTo(1);
    }

    private String send(String method, String target, String body, int expectedStatus) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of(
                "curl", "--silent", "--show-error", "--write-out", "\\nHTTP_STATUS:%{http_code}",
                "-X", method));
        if (body != null) {
            command.addAll(java.util.List.of("-H", "Content-Type: application/json", "--data-binary", body));
        }
        command.add(target);
        var result = ELASTICSEARCH.execInContainer(command.toArray(String[]::new));
        String output = result.getStdout();
        int statusLine = output.lastIndexOf("\nHTTP_STATUS:");
        String response = statusLine >= 0 ? output.substring(0, statusLine) : output;
        int actualStatus = statusLine >= 0 ? Integer.parseInt(output.substring(statusLine + 13).trim()) : -1;
        assertThat(actualStatus)
                .withFailMessage("Unexpected Elasticsearch response (exit=%s): %s %s",
                        result.getExitCode(), response, result.getStderr())
                .isEqualTo(expectedStatus);
        return response;
    }
}
