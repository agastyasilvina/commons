import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

// Static check of a Spring Boot repo for what a GraalVM native image needs but can't work out by itself.
//
//   java NativeScan.java /path/to/repo
//
// JDK 17+, nothing to build, no dependencies. Keep this file outside src/ so it isn't compiled into the app.
// Flags code and build setup that need native metadata or files, and checks what it can: Jackson read targets
// against @RegisterReflectionForBinding (per app module), hint classes against the component scan, Dockerfiles
// for the AWT .so files, native build args, the Paketo native-image buildpack version.
// A static scan can't prove completeness. The tracing agent run printed at the end can.
public class NativeScan {

  static final Set<String> SKIP_DIRS = Set.of(".git", "target", "build", "out", "node_modules", ".idea", ".vscode", ".gradle", ".mvn");

  // never need registering for Jackson
  static final Set<String> JDK_TYPES = Set.of(
      "String", "Object", "Map", "HashMap", "LinkedHashMap", "TreeMap", "List", "ArrayList", "LinkedList", "Set",
      "HashSet", "LinkedHashSet", "Collection", "Iterable", "Optional", "Void", "Integer", "Long", "Double", "Float",
      "Short", "Byte", "Boolean", "Character", "Number", "BigDecimal", "BigInteger", "JsonNode", "ObjectNode",
      "ArrayNode", "TextNode", "Instant", "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "ZonedDateTime",
      "Duration", "UUID", "URI", "URL", "Resource", "DataBuffer", "ByteBuffer", "Flux", "Mono", "ResponseEntity",
      "Entry", "MultiValueMap", "LinkedMultiValueMap", "Date", "Timestamp", "Class", "Row", "RowMetadata");

  record Target(String label, boolean strict, Pattern pattern) {}

  record Check(String severity, String label, Pattern pattern, String advice) {}

  record Finding(String severity, String where, String message) {}

  record Owner(String where, Path module, boolean inScan) {}

  // Jackson / Spring Data turn these into objects by reflection; Spring AOT can't see the type
  static final List<Target> READ_TARGETS = List.of(
      new Target("WebClient", true, Pattern.compile("\\.(?:bodyToMono|bodyToFlux|toEntity|toEntityList|toEntityFlux)\\s*\\(\\s*([A-Z][\\w.]*)\\.class")),
      new Target("RestClient", true, Pattern.compile("\\.body\\s*\\(\\s*([A-Z][\\w.]*)\\.class")),
      new Target("RestTemplate", true, Pattern.compile("\\.(?:getForObject|postForObject|patchForObject|getForEntity|postForEntity|exchange)\\s*\\((?:[^;()]|\\([^()]*\\))*?([A-Z][\\w.]*)\\.class")),
      new Target("ObjectMapper", true, Pattern.compile("\\.(?:readValue|convertValue|treeToValue|readerFor|forType)\\s*\\((?:[^;()]|\\([^()]*\\))*?([A-Z][\\w.]*)\\.class")),
      new Target("Redis JSON serializer", true, Pattern.compile("new\\s+Jackson2JsonRedisSerializer\\s*<[^>]*>\\s*\\((?:[^;()]|\\([^()]*\\))*?([A-Z][\\w.]*)\\.class")),
      new Target("type reference", true, Pattern.compile("new\\s+(?:ParameterizedTypeReference|TypeReference)\\s*<(.+?)>\\s*\\(\\s*\\)\\s*\\{")),
      new Target("Spring Data mapping", false, Pattern.compile("\\.(?:as|select|selectOne|mapProperties)\\s*\\(\\s*([A-Z][\\w.]*)\\.class")));

  static final Pattern REGISTRATIONS = Pattern.compile("@RegisterReflectionForBinding\\s*\\(|@RegisterReflection\\s*\\(|\\.registerType\\s*\\(|\\.registerReflectionHints\\s*\\(|\\bTypeReference\\.of\\s*\\(");
  static final Pattern HINT_ANNOTATIONS = Pattern.compile("@ImportRuntimeHints\\b|@RegisterReflectionForBinding\\b|@RegisterReflection\\b");
  static final Pattern BEAN_ANNOTATIONS = Pattern.compile("@(?:Configuration|Component|Service|Repository|RestController|Controller|SpringBootApplication)\\b");

  static final String JAVA2D = "needs libfontmanager, fontconfig/freetype and fonts in the image plus the Java2D JNI list (Quarkus AwtProcessor); the PDF hints don't cover it";

  static final List<Check> CODE_CHECKS = List.of(
      new Check("FIX", "runtime class generation", Pattern.compile("\\.defineClass\\s*\\(|\\bnet\\.bytebuddy\\.|\\bjavassist\\.|\\borg\\.springframework\\.cglib\\."),
          "a native image can't define classes at run time"),
      new Check("CHECK", "reflection", Pattern.compile("\\bClass\\.forName\\s*\\(|\\.getDeclared(?:Method|Field|Constructor)s?\\s*\\(|\\.get(?:Method|Field)\\s*\\(\\s*\\\"|\\.getConstructor\\s*\\(|(?<!Factory)\\.newInstance\\s*\\(|\\bMethodHandles\\.(?:lookup|privateLookupIn)\\s*\\(|\\bReflectionUtils\\.(?:findMethod|findField|invokeMethod|getField)\\s*\\("),
          "the target needs a reflection hint"),
      new Check("CHECK", "dynamic proxy", Pattern.compile("\\bProxy\\.newProxyInstance\\s*\\("),
          "needs a proxy hint (hints.proxies())"),
      new Check("CHECK", "Java serialization", Pattern.compile("\\bObject(?:Input|Output)Stream\\b|\\bJdkSerializationRedisSerializer\\b|\\bRedisSerializationContext\\.java\\s*\\(|\\bSerializationUtils\\.(?:serialize|deserialize)\\s*\\("),
          "the serialized types need serialization hints (hints.serialization())"),
      new Check("CHECK", "native library", Pattern.compile("\\bSystem\\.load(?:Library)?\\s*\\(|^[ \\t]*(?:(?:public|private|protected|static|final|synchronized)\\s+)*native\\s+[\\w<>\\[\\]]+\\s+\\w+\\s*\\(", Pattern.MULTILINE),
          "the .so must ship next to the binary, and JNI lookups from C need jni hints"),
      new Check("CHECK", "ServiceLoader", Pattern.compile("\\bServiceLoader\\.load\\s*\\("),
          "providers must be visible at build time"),
      new Check("CHECK", "resource bundle", Pattern.compile("\\bResourceBundle\\.getBundle\\s*\\("),
          "needs hints.resources().registerResourceBundle(...)"),
      new Check("CHECK", "Java2D drawing / fonts", Pattern.compile("\\.createGraphics\\s*\\(|\\bGraphics2D\\b|\\bjava\\.awt\\.Font\\b|\\bnew\\s+Font\\s*\\(|\\bFontMetrics\\b|\\bTextLayout\\b|\\.drawImage\\s*\\(|\\.drawString\\s*\\(|\\.getScaledInstance\\s*\\("),
          JAVA2D),
      new Check("CHECK", "POI autoSizeColumn", Pattern.compile("\\.autoSizeColumn\\s*\\("),
          "measures text with AWT fonts (see Java2D / fonts); in native, set column widths instead"),
      new Check("CHECK", "PDFBox page rendering", Pattern.compile("\\bPDFRenderer\\b"),
          "renders pages to images through Java2D: much more JNI and fonts than the PDF hints cover"));

  // one hit per file is enough
  static final List<Check> IMPORT_CHECKS = List.of(
      new Check("CHECK", "iText", Pattern.compile("^[ \\t]*import\\s+com\\.itextpdf\\.", Pattern.MULTILINE),
          "iText loads fonts/CMaps from its jars by name: run the agent over it and register what it records"),
      new Check("CHECK", "Tika", Pattern.compile("^[ \\t]*import\\s+org\\.apache\\.tika\\.", Pattern.MULTILINE),
          "Tika loads tika-mimetypes.xml and uses ServiceLoader: run the agent over it"),
      new Check("CHECK", "Thymeleaf (standalone)", Pattern.compile("\\bClassLoaderTemplateResolver\\b|\\bnew\\s+TemplateEngine\\s*\\("),
          "templates are loaded by name: register them as resources"),
      new Check("CHECK", "AWT imaging", Pattern.compile("^[ \\t]*import\\s+(?:javax\\.imageio\\.|java\\.awt\\.image\\.)", Pattern.MULTILINE),
          "needs the same AWT JNI hints and .so files as the PDF"));

  static final Pattern PDF_IMPORT = Pattern.compile("^[ \\t]*import\\s+(?:com\\.openhtmltopdf\\.|org\\.apache\\.pdfbox\\.)", Pattern.MULTILINE);
  static final Pattern RESOURCE_LOADS = Pattern.compile("(\\.getResource(?:AsStream)?\\s*\\(\\s*\\\"([^\\\"]+)\\\")|new\\s+ClassPathResource\\s*\\(\\s*\\\"([^\\\"]+)\\\"|\\\"classpath\\*?:([^\\\"]+)\\\"");

  // Spring Boot registers these itself
  static final List<String> BOOT_RESOURCES = List.of(
      "application[^/]*\\.(?:properties|ya?ml)", "config/.*", "banner\\.txt", "META-INF/.*", "db/migration/.*",
      "templates/.*", "static/.*", "public/.*", "resources/.*", "logback[^/]*\\.xml", "log4j2[^/]*\\.xml",
      "messages[^/]*\\.properties", "schema[^/]*\\.sql", "data[^/]*\\.sql");

  static final Pattern BUILD_COMMANDS = Pattern.compile("native:compile|-Pnative|build-image|bootBuildImage|nativeCompile|native-image\\b|docker\\s+build|buildah|kaniko|podman\\s+build");

  static final Pattern PACKAGE = Pattern.compile("^[ \\t]*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
  static final Pattern SCAN_BASE_PACKAGES = Pattern.compile("scanBasePackages\\s*=\\s*\\{?([^})]*)");
  static final Pattern QUOTED_PACKAGE = Pattern.compile("\\\"([\\w.]+)\\\"");
  static final Pattern CLASS_LITERAL = Pattern.compile("([A-Z][\\w.]*)\\.class");
  static final Pattern QUOTED_TYPE = Pattern.compile("\\\"([\\w.$]+)\\\"");
  static final Pattern QUOTED = Pattern.compile("\\\"([^\\\"\\n]+)\\\"");
  static final Pattern REGISTER_PATTERN = Pattern.compile("registerPattern\\s*\\(\\s*\\\"([^\\\"]+)\\\"");
  static final Pattern JSON_PATTERN = Pattern.compile("\\\"(pattern|glob)\\\"\\s*:\\s*\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"");
  static final Pattern JSON_NAME = Pattern.compile("\\\"(?:name|type)\\\"\\s*:\\s*\\\"([\\w.$]+)\\\"");
  static final Pattern REQUEST_BODY = Pattern.compile("@RequestBody\\s+(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:final\\s+)?([A-Z][\\w.<>, ]*)");
  static final Pattern TYPE_PATH = Pattern.compile("[A-Z][\\w.]*");
  static final Pattern TYPE_NAME = Pattern.compile("[A-Z]\\w*");
  static final List<Pattern> BUILD_ARGS = List.of(
      Pattern.compile("<buildArg>([^<]*)</buildArg>"),
      Pattern.compile("<buildArgs>([^<]*)</buildArgs>"),
      Pattern.compile("<BP_NATIVE_IMAGE_BUILD_ARGUMENTS>([^<]*)</BP_NATIVE_IMAGE_BUILD_ARGUMENTS>"));
  static final Pattern BUILDPACK_VERSION = Pattern.compile("native-image:v?(\\d+)\\.(\\d+)\\.(\\d+)");
  static final Pattern BUILDER = Pattern.compile("<(builder|runImage)>([^<]*)</(?:builder|runImage)>");
  static final Pattern DOCKER_COPY = Pattern.compile("^[ \\t]*(?:COPY|ADD)\\b.*target/[\\w.-]+[ \\t]+\\S+[ \\t]*\\r?$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
  static final Pattern ARCHIVE = Pattern.compile("\\.(?:jar|war|zip)\\b");
  static final Pattern NATIVE_WORDS = Pattern.compile("native|graalvm|mandrel", Pattern.CASE_INSENSITIVE);
  static final Pattern DOCKER_FROM = Pattern.compile("^[ \\t]*FROM\\s+(\\S+)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
  static final Pattern SHIPS_LIBS = Pattern.compile("\\.so\\b|target/\\*|target/?[ \\t]+\\S+[ \\t]*\\r?$", Pattern.MULTILINE);

  static final String FOOTER = """

      Static scan only: it finds the patterns above, not everything. For the complete list, run the app once on the
      GraalVM JDK with the tracing agent, go through every flow (approvals with real uploads, /report, ...), then Ctrl-C:
        mvn -Pnative -pl backend -am package -DskipTests -DskipNativeBuild=true
        java -Dspring.aot.enabled=true -agentlib:native-image-agent=config-output-dir=agent-out -jar backend/target/backend-0.0.1-SNAPSHOT.jar
      agent-out/ then holds every reflection, JNI and resource access the run made.""";

  final Path root;
  final Set<Finding> findings = new LinkedHashSet<>();
  final List<JavaFile> javaFiles = new ArrayList<>();
  final List<Path> poms = new ArrayList<>();
  final List<Path> dockerfiles = new ArrayList<>();
  final List<Path> buildFiles = new ArrayList<>();
  final List<Path> nativeConfigs = new ArrayList<>();
  final Set<Path> modules = new HashSet<>();
  final Map<Path, Set<String>> appBases = new TreeMap<>(); // module -> packages its @SpringBootApplication scans
  final Map<String, List<Owner>> registered = new HashMap<>();
  final Map<Path, Set<String>> hintStrings = new HashMap<>();
  final Map<Path, List<String>> patterns = new HashMap<>();

  NativeScan(Path root) {
    this.root = root;
    modules.add(root);
  }

  final class JavaFile {
    final String rel;
    final String raw;
    final String code; // comments blanked, strings kept: for class names and paths
    final String bare; // comments and string contents blanked: for code patterns
    final String pkg;
    final boolean isHints;
    final Path module;
    final int[] lineStarts;

    JavaFile(Path path) throws IOException {
      rel = rel(path);
      raw = read(path);
      code = strip(raw, false);
      bare = strip(raw, true);
      List<Integer> starts = new ArrayList<>(List.of(0));
      for (int i = 0; i < code.length(); i++) {
        if (code.charAt(i) == '\n') {
          starts.add(i + 1);
        }
      }
      lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
      Matcher m = PACKAGE.matcher(code);
      pkg = m.find() ? m.group(1) : "";
      isHints = bare.contains("RuntimeHintsRegistrar");
      module = moduleOf(path);
    }

    int line(int offset) {
      int index = Arrays.binarySearch(lineStarts, offset);
      return index >= 0 ? index + 1 : -index - 1;
    }

    String snippet(int offset) {
      int start = code.lastIndexOf('\n', offset - 1) + 1;
      int end = code.indexOf('\n', offset);
      return cut(raw.substring(start, end < 0 ? raw.length() : end).strip());
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: java NativeScan.java /path/to/repo");
      System.exit(1);
    }
    NativeScan scan = new NativeScan(Path.of(args[0]).toAbsolutePath().normalize());
    scan.run();
    String apps = scan.appBases.isEmpty() ? "no app found" : scan.appBases.entrySet().stream()
        .map(e -> scan.rel(e.getKey()) + " scans " + String.join(", ", e.getValue()))
        .collect(Collectors.joining("; "));
    System.out.println("native scan of " + scan.root + ": " + scan.javaFiles.size() + " Java files, " + scan.poms.size()
        + " poms, " + scan.dockerfiles.size() + " Dockerfiles");
    System.out.println("apps: " + apps);
    Map<String, String> titles = Map.of("FIX", "FIX (will fail in the native image)", "CHECK", "CHECK (may need a hint)", "INFO", "INFO");
    for (String severity : List.of("FIX", "CHECK", "INFO")) {
      List<Finding> items = scan.findings.stream()
          .filter(finding -> finding.severity().equals(severity))
          .sorted(Comparator.comparing(Finding::where).thenComparing(Finding::message))
          .toList();
      if (items.isEmpty()) {
        continue;
      }
      System.out.println();
      System.out.println(titles.get(severity) + ": " + items.size());
      for (Finding item : items) {
        System.out.println("  " + item.where());
        System.out.println("      " + item.message());
      }
    }
    System.out.println(FOOTER);
  }

  void run() throws IOException {
    collect();
    registrations();
    checkHintClasses();
    checkReadTargets();
    checkCode();
    boolean pdfUsed = checkPdf();
    checkResources();
    checkPoms();
    checkDockerfiles();
    checkBuildFiles();
    if (appBases.isEmpty()) {
      add("INFO", ".", "no @SpringBootApplication found: component-scan checks skipped");
    }
    if (pdfUsed && dockerfiles.isEmpty() && findings.stream().noneMatch(finding -> finding.message().contains("buildpacks"))) {
      add("CHECK", ".", "no Dockerfile or buildpack config in this repo: make sure whatever builds the image copies the lib*.so files next to the binary");
    }
  }

  // ---- files, modules, where Spring looks

  void collect() throws IOException {
    List<Path> files = new ArrayList<>();
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        boolean skip = !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString());
        return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        files.add(file);
        String name = file.getFileName().toString();
        if (name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")) {
          modules.add(file.getParent());
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        return FileVisitResult.CONTINUE;
      }
    });
    files.sort(Comparator.comparing(Path::toString));
    for (Path path : files) {
      String name = path.getFileName().toString();
      String posix = path.toString().replace('\\', '/');
      if (name.endsWith(".java")) {
        if (!posix.contains("/src/test/") && !name.matches(".*(Test|Tests|IT)\\.java") && !name.equals("NativeScan.java")) {
          javaFiles.add(new JavaFile(path));
        }
      } else if (name.equals("pom.xml")) {
        poms.add(path);
      } else if (name.startsWith("Dockerfile") || name.startsWith("Containerfile") || name.toLowerCase().endsWith(".dockerfile")) {
        dockerfiles.add(path);
      } else if (posix.contains("/META-INF/native-image/") && name.endsWith(".json") && !posix.contains("/src/test/")) {
        nativeConfigs.add(path);
      } else if ((name.matches(".*\\.(ya?ml|groovy|sh|gradle|kts)") || name.startsWith("Jenkinsfile") || name.equals("Makefile"))
          && !name.startsWith("application")) {
        buildFiles.add(path);
      }
    }
    for (JavaFile f : javaFiles) {
      if (f.bare.contains("@SpringBootApplication")) {
        Set<String> bases = appBases.computeIfAbsent(f.module, key -> new TreeSet<>());
        bases.add(f.pkg);
        for (String list : groups(SCAN_BASE_PACKAGES, f.code, 1)) {
          bases.addAll(groups(QUOTED_PACKAGE, list, 1));
        }
      }
    }
  }

  Path moduleOf(Path path) {
    for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
      if (modules.contains(parent)) {
        return parent;
      }
    }
    return root;
  }

  Set<String> bases(JavaFile f) {
    if (appBases.containsKey(f.module)) {
      return appBases.get(f.module);
    }
    Set<String> all = new TreeSet<>();
    appBases.values().forEach(all::addAll);
    return all;
  }

  boolean inScan(JavaFile f) {
    Set<String> bases = bases(f);
    return bases.isEmpty() || bases.stream().anyMatch(base -> f.pkg.equals(base) || f.pkg.startsWith(base + "."));
  }

  // hints count for their own app module; hints in a library module count for every app
  boolean countsFor(Path owner, Path module) {
    return owner.equals(module) || !appBases.containsKey(owner);
  }

  void registrations() throws IOException {
    for (JavaFile f : javaFiles) {
      boolean inScan = inScan(f);
      Matcher m = REGISTRATIONS.matcher(f.bare);
      while (m.find()) {
        String args = argsOf(f.code, m.end() - 1);
        for (String name : groups(CLASS_LITERAL, args, 1)) {
          register(name, f.rel, f.module, inScan);
        }
        for (String name : groups(QUOTED_TYPE, args, 1)) {
          register(name, f.rel, f.module, inScan);
        }
      }
      if (inScan && (f.isHints || HINT_ANNOTATIONS.matcher(f.bare).find())) {
        hintStrings.computeIfAbsent(f.module, key -> new HashSet<>()).addAll(groups(QUOTED, f.code, 1));
        for (String glob : groups(REGISTER_PATTERN, f.code, 1)) {
          patterns.computeIfAbsent(f.module, key -> new ArrayList<>()).add(globToRegex(glob));
        }
      }
    }
    for (Path config : nativeConfigs) {
      String text = read(config);
      Path module = moduleOf(config);
      Matcher m = JSON_PATTERN.matcher(text);
      while (m.find()) {
        String value = jsonUnescape(m.group(2));
        patterns.computeIfAbsent(module, key -> new ArrayList<>()).add(m.group(1).equals("pattern") ? value : globToRegex(value));
      }
      for (String name : groups(JSON_NAME, text, 1)) {
        register(name, rel(config), module, true);
      }
    }
  }

  void register(String name, String where, Path module, boolean inScan) {
    registered.computeIfAbsent(simple(name), key -> new ArrayList<>()).add(new Owner(where, module, inScan));
  }

  // ---- checks

  void checkHintClasses() {
    for (JavaFile f : javaFiles) {
      if (!HINT_ANNOTATIONS.matcher(f.bare).find()) {
        continue;
      }
      if (!inScan(f)) {
        add("FIX", f.rel, "package " + f.pkg + " is outside the component scan (" + String.join(", ", bases(f))
            + "): Spring never reads its native hints");
      } else if (!BEAN_ANNOTATIONS.matcher(f.bare).find()) {
        add("CHECK", f.rel, "native hint annotations only count on a Spring bean (@Configuration, @Component, ...)");
      }
    }
  }

  void checkReadTargets() {
    Set<String> requestBodies = new HashSet<>();
    for (JavaFile f : javaFiles) {
      for (String type : groups(REQUEST_BODY, f.bare, 1)) {
        requestBodies.addAll(groups(TYPE_NAME, type, 0));
      }
    }
    Set<String> seen = new HashSet<>();
    for (JavaFile f : javaFiles) {
      for (Target target : READ_TARGETS) {
        Matcher m = target.pattern().matcher(f.bare);
        while (m.find()) {
          Set<String> names = new TreeSet<>();
          for (String type : groups(TYPE_PATH, m.group(1), 0)) {
            names.add(simple(type));
          }
          for (String name : names) {
            if (JDK_TYPES.contains(name) || requestBodies.contains(name) || !seen.add(f.rel + " " + name)) {
              continue;
            }
            List<Owner> owners = registered.getOrDefault(name, List.of()).stream()
                .filter(owner -> countsFor(owner.module(), f.module))
                .toList();
            if (owners.stream().anyMatch(Owner::inScan)) {
              continue;
            }
            String where = f.rel + ":" + f.line(m.start());
            if (!owners.isEmpty()) {
              add("FIX", where, target.label() + " reads " + name + "; it's registered only in " + owners.get(0).where()
                  + ", which is outside the component scan");
            } else if (target.strict()) {
              add("FIX", where, target.label() + " reads " + name + ", nothing registers it: add it to an "
                  + "@RegisterReflectionForBinding (nested types come along)");
            } else {
              add("CHECK", where, target.label() + " maps rows into " + name + " by reflection: fine if it's a "
                  + "repository/entity type, otherwise register it");
            }
          }
        }
      }
    }
  }

  void checkCode() {
    for (JavaFile f : javaFiles) {
      if (f.isHints) {
        continue; // the hints themselves
      }
      for (Check check : CODE_CHECKS) {
        Matcher m = check.pattern().matcher(f.bare);
        while (m.find()) {
          add(check.severity(), f.rel + ":" + f.line(m.start()), check.label() + ": " + f.snippet(m.start()) + "  -> " + check.advice());
        }
      }
      for (Check check : IMPORT_CHECKS) {
        Matcher m = check.pattern().matcher(f.bare);
        if (m.find()) {
          add(check.severity(), f.rel + ":" + f.line(m.start()), check.label() + ": " + f.snippet(m.start()) + "  -> " + check.advice());
        }
      }
    }
  }

  boolean checkPdf() {
    Map<Path, List<JavaFile>> byModule = new TreeMap<>();
    for (JavaFile f : javaFiles) {
      if (!f.isHints && PDF_IMPORT.matcher(f.bare).find()) {
        byModule.computeIfAbsent(f.module, key -> new ArrayList<>()).add(f);
      }
    }
    for (Map.Entry<Path, List<JavaFile>> entry : byModule.entrySet()) {
      Path module = entry.getKey();
      List<JavaFile> users = entry.getValue();
      Set<String> strings = new HashSet<>();
      hintStrings.forEach((owner, values) -> {
        if (countsFor(owner, module)) {
          strings.addAll(values);
        }
      });
      List<String> known = new ArrayList<>();
      patterns.forEach((owner, values) -> {
        if (countsFor(owner, module)) {
          known.addAll(values);
        }
      });
      String where = users.stream().limit(3).map(f -> f.rel).collect(Collectors.joining(", ")) + (users.size() > 3 ? " ..." : "");
      if (!strings.contains("java.awt.GraphicsEnvironment")) {
        add("FIX", where, "openhtmltopdf/PDFBox in use but no JNI hints for AWT in this app: every render dies "
            + "(\"Fatal error reported via JNI\") once libawt.so loads. Add ReportPdfNativeHints");
      } else {
        if (!matchesAny(known, "sun/java2d/cmm/profiles/sRGB.pf")) {
          add("CHECK", where, "AWT hints found but not sun/java2d/cmm/profiles/*.pf: PNGs with an sRGB chunk fail");
        }
        if (!strings.contains("java.lang.IllegalArgumentException")) {
          add("CHECK", where, "AWT hints found but not java.lang.IllegalArgumentException: a broken JPEG colour "
              + "profile hangs the render instead of being skipped");
        }
      }
      add("INFO", where, "PDF needs the lib*.so files from the native build next to the binary at run time "
          + "(see the Dockerfile / buildpack findings)");
    }
    return !byModule.isEmpty();
  }

  void checkResources() {
    for (JavaFile f : javaFiles) {
      List<String> known = new ArrayList<>(BOOT_RESOURCES);
      patterns.forEach((owner, values) -> {
        if (countsFor(owner, f.module)) {
          known.addAll(values);
        }
      });
      Matcher m = RESOURCE_LOADS.matcher(f.code);
      while (m.find()) {
        String path = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
        if (m.group(1) != null && !path.startsWith("/")
            && !f.code.substring(Math.max(0, m.start() - 40), m.start()).contains("ClassLoader")) {
          path = f.pkg.replace('.', '/') + "/" + path; // Class.getResource is relative to the package
        }
        path = path.replaceFirst("^/+", "");
        String where = f.rel + ":" + f.line(m.start());
        if (path.contains("*") || path.contains("{")) {
          add("CHECK", where, "loads resources by pattern " + path + ": register them");
        } else if (!matchesAny(known, path)) {
          add("CHECK", where, "loads " + path + " by name, no resource hint covers it: hints.resources().registerPattern(\"" + path + "\")");
        }
      }
    }
  }

  void checkPoms() throws IOException {
    for (Path pom : poms) {
      String text = blankXmlComments(read(pom));
      String rel = rel(pom);
      List<String> args = new ArrayList<>();
      for (Pattern pattern : BUILD_ARGS) {
        for (String chunk : groups(pattern, text, 1)) {
          for (String arg : chunk.trim().split("\\s+")) {
            if (!arg.isEmpty()) {
              args.add(arg);
            }
          }
        }
      }
      for (String arg : args) {
        if (arg.equals("--enable-awt")) {
          add("FIX", rel, "--enable-awt is not a GraalVM option: remove it");
        } else if (arg.equals("--static") || arg.equals("-H:+StaticExecutable") || arg.startsWith("--libc=musl")) {
          add("FIX", rel, arg + ": a fully static binary can't load the AWT .so files (use the default, or -H:+StaticExecutableWithDynamicLibC)");
        } else if (arg.startsWith("-Djava.awt.headless")) {
          add("INFO", rel, arg + " only applies while building; Spring Boot sets headless at run time");
        }
      }
      Matcher version = BUILDPACK_VERSION.matcher(text);
      while (version.find()) {
        int[] found = {Integer.parseInt(version.group(1)), Integer.parseInt(version.group(2)), Integer.parseInt(version.group(3))};
        if (Arrays.compare(found, new int[] {5, 14, 2}) < 0) {
          add("FIX", rel, "Paketo native-image buildpack " + version.group(1) + "." + version.group(2) + "." + version.group(3)
              + " drops the .so files; 5.14.2+ copies them next to the binary");
        }
      }
      Matcher builder = BUILDER.matcher(text);
      while (builder.find()) {
        add("INFO", rel, "buildpacks " + builder.group(1) + " " + builder.group(2).strip() + ": its build log line 'Paketo Buildpack "
            + "for Native Image x.y.z' must be 5.14.2+ or the .so files are dropped");
      }
    }
  }

  void checkDockerfiles() throws IOException {
    for (Path path : dockerfiles) {
      String text = read(path);
      String rel = rel(path);
      // an extensionless file copied out of target/ is the native binary, even if "native" is never written
      List<Integer> copies = new ArrayList<>();
      Matcher copy = DOCKER_COPY.matcher(text);
      while (copy.find()) {
        if (!ARCHIVE.matcher(copy.group()).find()) {
          copies.add(copy.start());
        }
      }
      boolean nativeImage = !copies.isEmpty() || NATIVE_WORDS.matcher(text).find();
      if (!nativeImage) {
        continue;
      }
      Matcher from = DOCKER_FROM.matcher(text);
      while (from.find()) {
        String image = from.group(1).toLowerCase();
        if (image.equals("scratch") || image.contains("distroless/static")) {
          add("FIX", rel, "FROM " + from.group(1) + ": no glibc, the AWT .so files can't load there");
        } else if (image.contains("alpine")) {
          add("CHECK", rel, "FROM " + from.group(1) + ": musl; the JDK's AWT .so files are glibc builds");
        }
      }
      boolean shipsLibs = SHIPS_LIBS.matcher(text).find();
      if (!copies.isEmpty() && !shipsLibs) {
        long line = text.substring(0, copies.get(0)).chars().filter(c -> c == '\n').count() + 1;
        add("FIX", rel + ":" + line, "copies the native binary but not the lib*.so files next to it: PDFs fail "
            + "with \"No awt in java.library.path\". Also COPY target/*.so into the same directory");
      } else if (shipsLibs) {
        add("INFO", rel, "ships .so files / the target directory with the binary");
      }
    }
  }

  void checkBuildFiles() throws IOException {
    for (Path path : buildFiles) {
      String[] lines = read(path).split("\\R", -1);
      for (int i = 0; i < lines.length; i++) {
        if (BUILD_COMMANDS.matcher(lines[i]).find()) {
          add("INFO", rel(path) + ":" + (i + 1), "image build: " + cut(lines[i].strip()));
        }
      }
    }
  }

  // ---- helpers

  void add(String severity, String where, String message) {
    findings.add(new Finding(severity, where, message));
  }

  String rel(Path path) {
    String rel = root.relativize(path).toString().replace('\\', '/');
    return rel.isEmpty() ? "." : rel;
  }

  static String read(Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  static String cut(String text) {
    return text.length() > 110 ? text.substring(0, 110) : text;
  }

  static List<String> groups(Pattern pattern, String text, int group) {
    List<String> out = new ArrayList<>();
    Matcher m = pattern.matcher(text);
    while (m.find()) {
      out.add(m.group(group));
    }
    return out;
  }

  static boolean matchesAny(List<String> regexes, String path) {
    for (String regex : regexes) {
      try {
        if (Pattern.compile(regex).matcher(path).matches()) {
          return true;
        }
      } catch (PatternSyntaxException e) {
        // a pattern we can't read: ignore it
      }
    }
    return false;
  }

  // blank out comments (and string contents if asked), keep offsets
  static String strip(String src, boolean strings) {
    char[] out = src.toCharArray();
    int i = 0;
    int n = src.length();
    while (i < n) {
      if (src.startsWith("\"\"\"", i)) {
        int end = src.indexOf("\"\"\"", i + 3);
        end = end < 0 ? n : end;
        if (strings) {
          blank(out, i + 3, end);
        }
        i = end + 3;
      } else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
        char quote = src.charAt(i);
        int j = i + 1;
        while (j < n && src.charAt(j) != quote && src.charAt(j) != '\n') {
          j += src.charAt(j) == '\\' ? 2 : 1;
        }
        if (strings) {
          blank(out, i + 1, j);
        }
        i = j + 1;
      } else if (src.startsWith("//", i)) {
        int end = src.indexOf('\n', i);
        end = end < 0 ? n : end;
        blank(out, i, end);
        i = end;
      } else if (src.startsWith("/*", i)) {
        int end = src.indexOf("*/", i + 2);
        end = end < 0 ? n : end + 2;
        blank(out, i, end);
        i = end;
      } else {
        i++;
      }
    }
    return new String(out);
  }

  static void blank(char[] out, int start, int end) {
    for (int k = start; k < Math.min(end, out.length); k++) {
      if (out[k] != '\n') {
        out[k] = ' ';
      }
    }
  }

  static String blankXmlComments(String text) {
    char[] out = text.toCharArray();
    int start = text.indexOf("<!--");
    while (start >= 0) {
      int end = text.indexOf("-->", start + 4);
      if (end < 0) {
        break;
      }
      blank(out, start, end + 3);
      start = text.indexOf("<!--", end + 3);
    }
    return new String(out);
  }

  static String argsOf(String text, int openParen) {
    int depth = 0;
    for (int i = openParen; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return text.substring(openParen + 1, i);
      }
    }
    return text.substring(Math.min(openParen + 1, text.length()));
  }

  static String simple(String name) {
    String[] parts = name.split("[.$]");
    return parts.length == 0 ? name : parts[parts.length - 1];
  }

  static String globToRegex(String glob) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < glob.length()) {
      if (glob.startsWith("**", i)) {
        out.append(".*");
        i += 2;
      } else if (glob.charAt(i) == '*') {
        out.append("[^/]*");
        i++;
      } else {
        out.append(Pattern.quote(String.valueOf(glob.charAt(i))));
        i++;
      }
    }
    return out.toString();
  }

  static String jsonUnescape(String s) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\\' || i + 1 >= s.length()) {
        out.append(c);
        continue;
      }
      char escaped = s.charAt(++i);
      switch (escaped) {
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case 'r' -> out.append('\r');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'u' -> {
          if (i + 4 < s.length() && s.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
            i += 4;
          } else {
            out.append('u');
          }
        }
        default -> out.append(escaped);
      }
    }
    return out.toString();
  }
}
