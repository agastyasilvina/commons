package com.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What every list uri answers: {"data": [{"code": "...", "title": "..."}]}. Other fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LovResponse(List<Item> data) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(String code, String title) {}

  /** code -> title. An item without a code or a title is skipped; a repeated code keeps its first title. */
  public Map<String, String> titlesByCode() {
    Map<String, String> titles = new HashMap<>();
    if (data != null) {
      for (Item item : data) {
        if (item != null && item.code() != null && item.title() != null) {
          titles.putIfAbsent(item.code(), item.title());
        }
      }
    }
    return titles;
  }
}
