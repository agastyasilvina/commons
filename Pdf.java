package example.backend.submission.service;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.List;

// native image: what openhtmltopdf / PDFBox load at runtime for ReportPdfService (fonts, CSS, PNG and JPEG decoding)
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(ReportPdfNativeHints.Hints.class)
public class ReportPdfNativeHints {

  static class Hints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      hints.resources()
          // PDFBox: metrics of the 14 standard fonts (sans-serif is Helvetica), glyph names, the fallback font
          .registerPattern("org/apache/pdfbox/resources/afm/*.afm")
          .registerPattern("org/apache/pdfbox/resources/glyphlist/*.txt")
          .registerPattern("org/apache/pdfbox/resources/ttf/*.ttf")
          // openhtmltopdf: its default CSS and XML catalog
          .registerPattern("resources/css/*.css")
          .registerPattern("resources/schema/openhtmltopdf/*.xml");
      hints.resources().registerResourceBundle("sun.awt.resources.awt");

      // ImageIO's native code reads the logos (PNG) and photos (JPEG) through these
      for (String type : List.of(
          "com.sun.imageio.plugins.jpeg.JPEGImageReader",
          "javax.imageio.plugins.jpeg.JPEGHuffmanTable",
          "javax.imageio.plugins.jpeg.JPEGQTable",
          "java.awt.GraphicsEnvironment",
          "java.awt.image.BufferedImage",
          "java.awt.image.ColorModel",
          "java.awt.image.Raster",
          "java.awt.image.SampleModel",
          "java.awt.image.SinglePixelPackedSampleModel",
          "sun.awt.image.ByteComponentRaster",
          "sun.awt.image.IntegerComponentRaster",
          "sun.java2d.Disposer",
          "java.lang.Boolean",
          "java.lang.System")) {
        hints.jni().registerType(TypeReference.of(type), MemberCategory.DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS);
      }
    }
  }
}
