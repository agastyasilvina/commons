package com.example;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/** lov.* in application.yml: one base URL and clientId for every list, and each list as TYPE: uri. */
@ConfigurationProperties(prefix = "lov")
public record LovProperties(String baseUrl, String clientId, Map<String, String> uris) {
  public LovProperties {
    Assert.hasText(baseUrl, "lov.base-url is not set (LOV_BASE_URL)");
    Assert.hasText(clientId, "lov.client-id is not set (LOV_CLIENT_ID)");
    uris = uris == null ? Map.of() : uris;
  }
}
