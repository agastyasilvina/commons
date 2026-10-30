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
      // colour management: JPEGs with an embedded ICC profile go through LittleCMS on the header read
      "java.awt.color.ColorSpace",
      "java.awt.color.ICC_ColorSpace",
      "java.awt.color.ICC_Profile",
      "java.awt.color.ICC_ProfileRGB",
      "java.awt.color.ICC_ProfileGray",
      "java.awt.color.CMMException",
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
        // constructors as well: the native code throws IIOException / CMMException through JNI
        hints.jni().registerType(TypeReference.of(type),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS);
      }
    }
  }
}
