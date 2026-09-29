package com.example;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfReportServiceTest {

    @Test
    void rendersFormsAsPdf() throws Exception {
        Map<String, String> data = new LinkedHashMap<>(); // keeps the order
        data.put("title", "Form Summary");
        data.put("Identitas Nasabah", "FORM");
        data.put("Identitas Nasabah --- Nama Lengkap", "Selkie");
        data.put("Identitas Nasabah --- CIF", "CIF-0012345");
        data.put("Produk", "FORM");
        data.put("Produk --- Nama Produk", "Tabungan");
        data.put("Produk --- Gambar Bungkus", samplePngBase64());

        byte[] pdf = new PdfReportService().render(data);

        Files.write(Path.of("target/sample.pdf"), pdf); // open it to check the layout
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    void groupsFieldsUnderTheirForm() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("title", "Form Summary");
        data.put("Produk", "FORM");
        data.put("Identitas Nasabah --- Nama Lengkap", "Selkie");
        data.put("Produk  --- Gambar Bungkus", "(picture)");

        assertEquals(
                Map.of("Produk", Map.of("Gambar Bungkus", "(picture)"),
                       "Identitas Nasabah", Map.of("Nama Lengkap", "Selkie")),
                PdfReportService.groupByForm(data));
    }

    private static String samplePngBase64() throws Exception {
        BufferedImage img = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 160, 90);
        g.setColor(Color.BLUE);
        g.drawOval(10, 10, 140, 70);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
