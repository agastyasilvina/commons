package com.example;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * One WebClient for all the LOV lists: GETs every uri in lov.uris with the clientId header and saves each list to
 * {@link LovMapperCache} under its TYPE.
 */
@Component
@EnableConfigurationProperties(LovProperties.class)
public class LovClient {
  private static final Logger log = LoggerFactory.getLogger(LovClient.class);

  private static final String CLIENT_ID_HEADER = "clientId";
  private static final Duration TIMEOUT = Duration.ofSeconds(30); // per list
  private static final int MAX_BODY_BYTES = 16 * 1024 * 1024; // WebClient's default 256 KB fails at a few thousand items

  private final WebClient webClient;
  private final Map<String, String> uris;
  private final LovMapperCache cache;

  public LovClient(LovProperties properties, LovMapperCache cache) {
    this.webClient = WebClient.builder()
        .baseUrl(properties.baseUrl())
        .defaultHeader(CLIENT_ID_HEADER, properties.clientId())
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES))
        .build();
    this.uris = properties.uris();
    this.cache = cache;
  }

  /** Fills LovMapperCache when this bean is created, before the app serves anything. */
  @PostConstruct
  public void loadOnStartup() {
    loadAll().block();
  }

  /** GETs all the lists at once. A list that fails is logged and skipped; the others still load. */
  public Mono<Void> loadAll() {
    return Flux.fromIterable(uris.entrySet())
        .flatMap(entry -> load(entry.getKey(), entry.getValue()))
        .then();
  }

  private Mono<Void> load(String type, String uri) {
    // defer: a bad uri throws while the request is built, and should fail only its own list
    return Mono.defer(() -> webClient.get().uri(uri).retrieve().bodyToMono(LovResponse.class))
        .timeout(TIMEOUT)
        .doOnNext(response -> {
          Map<String, String> titles = response.titlesByCode();
          cache.put(type, titles);
          log.info("LOV {}: {} codes from {}", type, titles.size(), uri);
        })
        .onErrorResume(error -> {
          log.warn("LOV {}: GET {} failed, list not loaded: {}", type, uri, error.toString());
          return Mono.empty();
        })
        .then();
  }
}
