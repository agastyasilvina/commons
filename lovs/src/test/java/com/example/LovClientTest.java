package com.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** LovClient against a stub UAT on a free local port. The lists load once, before the tests. */
class LovClientTest {
  private static final String SUB_DISTRICTS = IntStream.rangeClosed(1, 5_000)
      .mapToObj(i -> "{\"code\": \"%d\", \"title\": \"Kelurahan Contoh %d\", \"parentCode\": \"317101\"}"
          .formatted(3171010000L + i, i))
      .collect(Collectors.joining(", ", "{\"data\": [", "]}"));

  private static final Map<String, String> CLIENT_IDS = new ConcurrentHashMap<>(); // path -> clientId header it got
  private static final LovMapperCache CACHE = new LovMapperCache();
  private static HttpServer uat;

  @BeforeAll
  static void load() throws IOException {
    uat = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    uat.createContext("/", LovClientTest::answer);
    uat.start();
    LovProperties properties = new LovProperties(
        "http://localhost:" + uat.getAddress().getPort() + "/lov",
        "test-client",
        Map.of(
            "MARITAL_STATUS", "/v1/masters/marriage-status",
            "SUB_DISTRICT", "/v1/locations/sub_districts",
            "OPENING_PURPOSE", "/v1/masters/opening_purposes",
            "BAD_URI", "/v1/masters/{missing}"));
    new LovClient(properties, CACHE).loadAll().block();
  }

  @AfterAll
  static void stop() {
    uat.stop(0);
  }

  @Test
  void savesEachListUnderItsType() {
    assertThat(CACHE.title("MARITAL_STATUS", "1")).contains("Belum Kawin");
    assertThat(CACHE.title("MARITAL_STATUS", "2")).contains("Kawin");
  }

  @Test
  void sendsTheClientIdOnEveryGet() {
    assertThat(CLIENT_IDS).containsOnlyKeys(
        "/lov/v1/masters/marriage-status", "/lov/v1/locations/sub_districts", "/lov/v1/masters/opening_purposes");
    assertThat(CLIENT_IDS.values()).containsOnly("test-client");
  }

  @Test
  void loadsAListBiggerThanWebClientsDefault256Kb() {
    assertThat(SUB_DISTRICTS.length()).isGreaterThan(256 * 1024);
    assertThat(CACHE.title("SUB_DISTRICT", "3171010001")).contains("Kelurahan Contoh 1");
    assertThat(CACHE.title("SUB_DISTRICT", "3171015000")).contains("Kelurahan Contoh 5000");
  }

  @Test
  void skipsAListThatFailsAndKeepsTheOthers() {
    assertThat(CACHE.title("OPENING_PURPOSE", "1")).isEmpty(); // the UAT answers 500
    assertThat(CACHE.title("BAD_URI", "1")).isEmpty(); // the uri can't be built, so it's never sent
    assertThat(CACHE.title("MARITAL_STATUS", "1")).isPresent();
  }

  @Test
  void unknownTypeOrCodeIsEmpty() {
    assertThat(CACHE.title("RELIGION", "1")).isEmpty();
    assertThat(CACHE.title("MARITAL_STATUS", "9")).isEmpty();
    assertThat(CACHE.title("MARITAL_STATUS", null)).isEmpty();
  }

  /** The stub UAT: marriage-status and sub_districts answer, opening_purposes fails with a 500. */
  private static void answer(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    CLIENT_IDS.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("clientId")));
    String body = switch (path) {
      case "/lov/v1/masters/marriage-status" -> """
          {"status": "SUCCESS", "data": [
            {"code": "1", "title": "Belum Kawin", "description": "ignored"},
            {"code": "2", "title": "Kawin"}
          ]}""";
      case "/lov/v1/locations/sub_districts" -> SUB_DISTRICTS;
      default -> null;
    };
    if (body == null) {
      exchange.sendResponseHeaders(500, -1);
    } else {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }
}
