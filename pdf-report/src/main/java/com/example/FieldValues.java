package com.example;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exposed to the template as ${values}: pictures become images, other files a short label. */
public final class FieldValues {

    /** How each picture starts once Base64-encoded, and its MIME type. */
    private static final Map<String, String> PICTURES = Map.of(
            "iVBORw0KGgo", "image/png",
            "/9j/",        "image/jpeg",
            "R0lGOD",      "image/gif",
            "Qk",          "image/bmp");

    /** Files that can't be drawn: how they start once Base64-encoded, and what to print instead. */
    private static final Map<String, String> OTHER_FILES = Map.of(
            "0M8R4KGxGu",  "Outlook message (.msg)",
            "JVBERi0",     "PDF document");

    private static final Pattern IMG_SRC = Pattern.compile("src\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=\\s]+");

    /** The img src when the value is a picture (plain Base64, data URI or img tag), else null. */
    public String srcOf(String value) {
        String b64 = payload(value);
        String type = startsLike(b64, PICTURES);
        return type == null ? null : "data:" + type + ";base64," + b64;
    }

    /** What to print for a value that isn't a picture. */
    public String textOf(String value) {
        if (value == null || value.isBlank()) return "-";
        String b64 = payload(value);
        if (b64.length() > 100 && BASE64.matcher(b64).matches()) { // a file that can't be drawn
            String type = startsLike(b64, OTHER_FILES);
            return (type == null ? "File" : type) + ", " + Math.max(1, b64.length() * 3 / 4 / 1024) + " KB";
        }
        return value;
    }

    /** The Base64 part of a value: unwraps an img tag and a data: prefix. */
    private static String payload(String value) {
        String v = value == null ? "" : value.strip();
        if (v.startsWith("<img")) {
            Matcher m = IMG_SRC.matcher(v);
            v = m.find() ? m.group(1) : "";
        }
        return v.startsWith("data:") ? v.substring(v.indexOf(',') + 1) : v;
    }

    /** The type whose prefix the Base64 starts with; short text never counts as a file. */
    private static String startsLike(String b64, Map<String, String> types) {
        if (b64.length() <= 100) return null;
        return types.entrySet().stream()
                .filter(t -> b64.startsWith(t.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
