package com.example;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Every LOV list in memory as TYPE -> (code -> title), filled by {@link LovClient}. */
@Component
public class LovMapperCache {
  private final Map<String, Map<String, String>> lists = new ConcurrentHashMap<>();

  /** Saves a TYPE's list, replacing the one it had. */
  public void put(String type, Map<String, String> titlesByCode) {
    lists.put(type, Map.copyOf(titlesByCode));
  }

  /** The title of a code in a TYPE's list; empty when that list isn't loaded or doesn't have the code. */
  public Optional<String> title(String type, String code) {
    if (type == null || code == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(lists.getOrDefault(type, Map.of()).get(code));
  }
}
