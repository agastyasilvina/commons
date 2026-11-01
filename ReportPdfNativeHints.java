package com.example.bootleg.submission.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.NativeDetector;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

// native image support for ReportPdfService (openhtmltopdf -> PDFBox -> AWT):
// - Hints: what the AWT native code (libawt, libjavajpeg, liblcms), PDFBox and openhtmltopdf need at runtime
// - run(): at startup, loads each native library once and logs the first real error
// the lib*.so files the native build writes next to the binary must also be next to the binary in the image.
// no hint replaces that: without them every render fails with "No awt in java.library.path"
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(ReportPdfNativeHints.Hints.class)
public class ReportPdfNativeHints implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ReportPdfNativeHints.class);

  // Reached through JNI by libawt, libjavajpeg and liblcms.
  // Image + colour subset of the list Quarkus maintains for headless AWT (extensions/awt AwtProcessor).
  static final List<String> AWT_JNI_TYPES = List.of(
      // loading libawt, native cleanup
      "java.lang.System",
      "java.lang.Boolean",
      "java.awt.GraphicsEnvironment",
      "java.awt.Rectangle",
      "java.awt.Color",
      "sun.java2d.Disposer",
      "sun.java2d.DefaultDisposerRecord",
      // JPEG reader
      "com.sun.imageio.plugins.jpeg.JPEGImageReader",
      "javax.imageio.plugins.jpeg.JPEGHuffmanTable",
      "javax.imageio.plugins.jpeg.JPEGQTable",
      "javax.imageio.IIOException",
      // image buffers: each PNG layout lands on different classes, each with its own initIDs
      "java.awt.image.BufferedImage",
      "java.awt.image.ColorModel",
      "java.awt.image.IndexColorModel",          // palette PNGs
      "java.awt.image.ComponentColorModel",
      "java.awt.image.DirectColorModel",
      "java.awt.image.PackedColorModel",
      "java.awt.image.Raster",
      "java.awt.image.SampleModel",
      "java.awt.image.ComponentSampleModel",
      "java.awt.image.PixelInterleavedSampleModel",
      "java.awt.image.SinglePixelPackedSampleModel",
      "java.awt.image.MultiPixelPackedSampleModel",
      "java.awt.image.BandedSampleModel",
      "java.awt.image.Kernel",
      "sun.awt.image.SunWritableRaster",
      "sun.awt.image.ByteComponentRaster",
      "sun.awt.image.ByteInterleavedRaster",
      "sun.awt.image.ByteBandedRaster",
      "sun.awt.image.BytePackedRaster",          // 1/2/4-bit PNGs
      "sun.awt.image.IntegerComponentRaster",
      "sun.awt.image.IntegerInterleavedRaster",
      "sun.awt.image.ShortComponentRaster",      // 16-bit PNGs
      "sun.awt.image.ShortInterleavedRaster",
      "sun.awt.image.ShortBandedRaster",
      "sun.awt.image.ImagingLib",
      // colour management: JPEGs with an embedded ICC profile go through LittleCMS on the header read,
      // PNGs with an sRGB chunk get the JDK's sRGB profile embedded
      "java.awt.color.ColorSpace",
      "java.awt.color.ICC_ColorSpace",
      "java.awt.color.ICC_Profile",
      "java.awt.color.ICC_ProfileRGB",
      "java.awt.color.ICC_ProfileGray",
      "java.awt.color.CMMException",
      // LCMS throws it for a broken profile through its (String, Throwable) constructor, GraalVM only registers (String)
      "java.lang.IllegalArgumentException",
      "sun.java2d.cmm.lcms.LCMS",
      "sun.java2d.cmm.lcms.LCMSImageLayout",
      "sun.java2d.cmm.lcms.LCMSProfile",
      "sun.java2d.cmm.lcms.LCMSTransform");

  static class Hints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      hints.resources()
          // PDFBox: metrics of the 14 standard fonts (sans-serif is Helvetica), glyph names, the fallback font
          .registerPattern("org/apache/pdfbox/resources/afm/*.afm")
          .registerPattern("org/apache/pdfbox/resources/glyphlist/*.txt")
          .registerPattern("org/apache/pdfbox/resources/ttf/*.ttf")
          // FontBox: CMap read whenever a TTF is embedded via builder.useFont(...)
          .registerPattern("org/apache/fontbox/cmap/Identity-H")
          .registerPattern("org/apache/fontbox/cmap/Identity-V")
          // openhtmltopdf: default CSS, the XML catalog and the .ent files the catalog points to
          .registerPattern("resources/css/*.css")
          .registerPattern("resources/schema/openhtmltopdf/*")
          // JDK (java.desktop): built-in ICC profiles, ImageIO plugin messages
          .registerPattern("sun/java2d/cmm/profiles/*.pf")
          .registerPattern("com/sun/imageio/plugins/*/iio-plugin*.properties");
      hints.resources().registerResourceBundle("sun.awt.resources.awt");

      for (String type : AWT_JNI_TYPES) {
        // constructors as well: the native code throws IIOException / CMMException / IllegalArgumentException through JNI
        hints.jni().registerType(TypeReference.of(type),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS);
      }
    }
  }

  // native only. no "pdf self-check" lines at startup = this class isn't scanned, so the hints above aren't in the build.
  // if the log stops right after a "checking" line, that check killed the process (a JNI hint is missing)
  @Override
  public void run(ApplicationArguments args) {
    if (!NativeDetector.inNativeImage()) {
      return;
    }
    log.info("pdf self-check: {}", libraries());
    check("libawt (PDDocument)", () -> new PDDocument().close());
    check("libjavajpeg (JPEG reader)", () -> ImageIO.getImageReadersByFormatName("jpeg").next().dispose());
    check("liblcms + sRGB.pf (colour profiles)", () -> ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
  }

  // the binary looks for its .so files next to itself first, then on java.library.path
  private static String libraries() {
    try {
      Path dir = Path.of("/proc/self/exe").toRealPath().getParent();
      try (Stream<Path> files = Files.list(dir)) {
        return dir + " has " + files.map(file -> file.getFileName().toString())
            .filter(name -> name.endsWith(".so"))
            .sorted()
            .toList() + ", java.library.path=" + System.getProperty("java.library.path");
      }
    } catch (Exception e) {
      return "can't list the binary's directory: " + e;
    }
  }

  private static void check(String what, Check check) {
    log.info("pdf self-check: checking {}", what);
    try {
      check.run();
      log.info("pdf self-check: ok {}", what);
    } catch (Throwable e) {
      // Throwable on purpose, the failures here are LinkageErrors
      log.error("pdf self-check: FAILED {}", what, e);
    }
  }

  private interface Check {
    void run() throws Exception;
  }
}
