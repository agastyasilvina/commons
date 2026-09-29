package com.example;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PdfReportService {

    /** Relative paths in the template (images/header.png) resolve next to the template file. */
    private static final String BASE_URI =
            PdfReportService.class.getResource("/templates/dynamic-report.html").toExternalForm();

    private final TemplateEngine templateEngine = new TemplateEngine();
    private final FieldValues values = new FieldValues();

    public PdfReportService() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        templateEngine.setTemplateResolver(resolver);
    }

    /**
     * Keys: "title", form markers ("Produk" = "FORM") and fields ("Produk --- Gambar Bungkus" = value).
     * Each form becomes a heading with its fields as "Field : value"; pictures show as images.
     * Blocking: call it off the event loop.
     */
    public byte[] render(Map<String, String> data) {
        Context ctx = new Context();
        ctx.setVariable("title", data.get("title"));
        ctx.setVariable("forms", groupByForm(data));
        ctx.setVariable("values", values);
        String html = templateEngine.process("dynamic-report", ctx);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new PdfRendererBuilder()
                    .useFastMode()
                    .withHtmlContent(html, BASE_URI)
                    .toStream(out)
                    .run();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render PDF", e);
        }
        return out.toByteArray();
    }

    /** Form name -> (field -> value), in the order they come. Keys without a form go in an untitled group. */
    static Map<String, Map<String, String>> groupByForm(Map<String, String> data) {
        Map<String, Map<String, String>> forms = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            int sep = key.indexOf("---");
            if (key.equals("title")) {
                return;
            } else if (sep >= 0) {
                forms.computeIfAbsent(key.substring(0, sep).strip(), f -> new LinkedHashMap<>())
                        .put(key.substring(sep + 3).strip(), value);
            } else if ("FORM".equalsIgnoreCase(String.valueOf(value).strip())) {
                forms.computeIfAbsent(key.strip(), f -> new LinkedHashMap<>()); // fixes the form's position
            } else {
                forms.computeIfAbsent("", f -> new LinkedHashMap<>()).put(key.strip(), value);
            }
        });
        return forms;
    }
}
