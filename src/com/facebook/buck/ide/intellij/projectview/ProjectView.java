/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.ide.intellij.projectview;

import static com.facebook.buck.ide.intellij.projectview.Patterns.capture;
import static com.facebook.buck.ide.intellij.projectview.Patterns.noncapture;
import static com.facebook.buck.ide.intellij.projectview.Patterns.optional;

import com.facebook.buck.config.Config;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

public class ProjectView {

  // region Public API

  public static int run(
      DirtyPrintStreamDecorator stderr,
      boolean dryRun,
      boolean withTests,
      String viewPath,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> buildTargets,
      ActionGraphAndResolver actionGraph,
      Config config) {
    return new ProjectView(
            stderr, dryRun, withTests, viewPath, targetGraph, buildTargets, actionGraph, config)
        .run();
  }

  // endregion Public API

  // region Private implementation

  private static final String ANDROID_MANIFEST = "AndroidManifest.xml";
  private static final String ANDROID_RES = "android_res";
  private static final String ASSETS = "assets";
  private static final String CODE_STYLE_SETTINGS = "codeStyleSettings.xml";
  private static final String DOT_IDEA = ".idea";
  private static final String DOT_XML = ".xml";
  private static final String FONTS = "fonts";
  private static final String RES = "res";

  private final DirtyPrintStreamDecorator stdErr;
  private final String viewPath;
  private final boolean dryRun;
  private final boolean withTests;
  private final TargetGraph targetGraph;
  private final ImmutableSet<BuildTarget> buildTargets;
  private final Config config;

  private final ActionGraphAndResolver actionGraph;

  private final Set<BuildTarget> testTargets = new HashSet<>();
  /** {@code Sets.union(buildTargets, allTargets)} */
  private final Set<BuildTarget> allTargets = new HashSet<>();

  private final String repository = new File("").getAbsolutePath();

  private ProjectView(
      DirtyPrintStreamDecorator stdErr,
      boolean dryRun,
      boolean withTests,
      String viewPath,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> buildTargets,
      ActionGraphAndResolver actionGraph,
      Config config) {
    this.stdErr = stdErr;
    this.viewPath = viewPath;
    this.dryRun = dryRun;
    this.withTests = withTests;

    this.targetGraph = targetGraph;
    this.buildTargets = buildTargets;
    this.actionGraph = actionGraph;

    this.config = config;
  }

  private int run() {
    if (viewPathIsUnderRepository()) {
      stderr("\nView directory %s is under the repo directory %s\n", viewPath, repository);
      return 1;
    }

    getTestTargets();

    List<String> inputs = getPrunedInputs();

    scanExistingView();

    List<String> sourceFiles = new ArrayList<>();
    for (String input : inputs) {
      if (input.startsWith("android_res/")) {
        linkResourceFile(input);
      } else {
        sourceFiles.add(input);
      }
    }
    Set<String> roots = generateRoots(sourceFiles);
    buildRootLinks(roots);

    writeRootDotIml(sourceFiles, roots, buildDotIdeaFolder(inputs));

    buildAllDirectoriesAndSymlinks();

    return 0;
  }

  private boolean viewPathIsUnderRepository() {
    Path view = Paths.get(viewPath).toAbsolutePath();
    Path repo = Paths.get(repository).toAbsolutePath();
    return view.startsWith(repo);
  }

  // region getTestTargets

  private void getTestTargets() {
    if (withTests) {
      AbstractBreadthFirstTraversal.<TargetNode<?, ?>>traverse(
          targetGraph.getAll(buildTargets),
          node -> {
            testTargets.addAll(TargetNodes.getTestTargetsForNode(node));
            return targetGraph.getAll(node.getBuildDeps());
          });
    }

    allTargets.addAll(buildTargets);
    allTargets.addAll(testTargets);
  }

  // endregion getTestTargets

  // region getPrunedInputs()

  private List<String> getPrunedInputs() {
    return pruneInputs(getAllInputs());
  }

  private Collection<String> getAllInputs() {

    Set<String> inputs = new HashSet<>();

    for (TargetNode<?, ?> node : targetGraph.getNodes()) {
      node.getInputs().forEach(input -> inputs.add(input.toString()));
    }

    return inputs
        .stream()
        //ignore non-english strings
        .filter(input -> !(input.contains("/res/values-") && input.endsWith("strings.xml")))
        .collect(Collectors.toList());
  }

  private List<String> pruneInputs(Collection<String> allInputs) {
    Pattern resource = Pattern.compile("/res/(?!(?:values(?:-[^/]+)?)/)");

    List<String> result = new ArrayList<>();
    Map<String, List<String>> resources = new HashMap<>();

    for (String input : allInputs) {
      Matcher matcher = resource.matcher(input);
      if (matcher.find()) {
        String basename = basename(input);
        List<String> candidates = resources.get(basename);
        if (candidates == null) {
          resources.put(basename, candidates = new ArrayList<>());
        }
        candidates.add(input);
      } else {
        result.add(input);
      }
    }

    for (Map.Entry<String, List<String>> mapping : resources.entrySet()) {
      List<String> candidateList = mapping.getValue();
      Stream<String> candidateStream = candidateList.stream();
      if (candidateList.size() > 1) {
        candidateStream = candidateStream.sorted();
      }
      result.add(candidateStream.findFirst().get());
    }

    return result;
  }

  // endregion getPrunedInputs()

  // region linkResourceFile

  private static final String DASH_PART = "-[^/]+";
  private static final String NONCAPTURE_DASH_PART = optional(noncapture(DASH_PART));

  private static final Patterns SIMPLE_RESOURCE_PATTERNS =
      Patterns.builder()
          // These are ordered based on the frequency in two large Android projects.
          // This ordering will not be ideal for every project, but it's probably not too far off.
          .add("/res/", capture("drawable", NONCAPTURE_DASH_PART), "/")
          .add("/res/", capture("layout", NONCAPTURE_DASH_PART), "/")
          .add("/res/", capture("raw", NONCAPTURE_DASH_PART), "/")
          .add("/res/", capture("anim", NONCAPTURE_DASH_PART), "/")
          .add("/res/", capture("xml", NONCAPTURE_DASH_PART), "/")
          .add("/res/", capture("menu", NONCAPTURE_DASH_PART), "/")
          .add("/res/", capture("animator"), "/")
          .build();

  private static final String CAPTURE_ALL = capture(".*");
  private static final String CAPTURE_DASH_PART = optional(capture(DASH_PART));

  private static final Patterns MANGLED_RESOURCE_PATTERNS =
      Patterns.builder()
          // These are also ordered based on the frequency in the same two large Android projects.
          .add("^android_res/", CAPTURE_ALL, "res/(values)", CAPTURE_DASH_PART, "/")
          .add("^android_res/", CAPTURE_ALL, "res/(color)", CAPTURE_DASH_PART, "/")
          .build();

  // Group 1 has any path under ...//assets/ while group 2 has the filename
  private static final Patterns ASSETS_RES =
      Patterns.build("/assets/", capture(noncapture("[^/]+/"), "*"), CAPTURE_ALL);

  private static final Patterns FONTS_RES = Patterns.build("/fonts/", capture(".*\\.\\w+"));

  private void linkResourceFile(String input) {
    // TODO(shemitz) Convert (say) "res/drawable-hdpi/" to "res/drawable/"

    if (SIMPLE_RESOURCE_PATTERNS.onAnyMatch(input, this::simpleResourceLink)) {
      return;
    }

    if (MANGLED_RESOURCE_PATTERNS.onAnyMatch(input, this::mangledResourceLink)) {
      return;
    }

    if (ASSETS_RES.onAnyMatch(input, this::assetsLink)) {
      return;
    }

    if (FONTS_RES.onAnyMatch(input, this::fontsLink)) {
      return;
    }

    if (input.contains(".")) {
      stderr("Can't handle %s\n", input);
    }
  }

  private void simpleResourceLink(Matcher match, String input) {
    String name = basename(input);

    String directory = fileJoin(viewPath, RES, flattenResourceDirectoryName(match.group(1)));
    mkdir(directory);

    symlink(fileJoin(repository, input), fileJoin(directory, name));
  }

  private void mangledResourceLink(Matcher match, String input) {
    String fileName = basename(input);
    //its safe to assume input is .xml file
    String name = fileName.substring(0, fileName.length() - DOT_XML.length());

    String path = match.group(1).replace('/', '_');

    String configQualifier = match.groupCount() > 2 ? match.group(3) : "";

    String directory = fileJoin(viewPath, RES, match.group(2));
    mkdir(directory);

    symlink(
        fileJoin(repository, input), fileJoin(directory, path + name + configQualifier + DOT_XML));
  }

  private static String flattenResourceDirectoryName(String name) {
    int dash = name.indexOf('-');
    return dash < 0 ? name : name.substring(0, dash);
  }

  private void assetsLink(Matcher match, String input) {
    String inside = match.group(1); // everything between .../assets/ and filename
    String name = match.group(2); // basename(input)

    String directory = fileJoin(viewPath, ASSETS, inside);
    mkdir(directory);

    symlink(fileJoin(repository, input), fileJoin(directory, name));
  }

  private void fontsLink(Matcher match, String input) {
    String target = fileJoin(viewPath, FONTS, match.group(1));
    String path = dirname(target);
    mkdir(path);
    symlink(fileJoin(repository, input), target);
  }

  // endregion linkResourceFile

  // region roots

  private Set<String> generateRoots(List<String> sourceFiles) {
    final Set<String> roots = new HashSet<>();
    final RootsHelper helper = new RootsHelper();

    for (String sourceFile : sourceFiles) {
      String path = dirname(sourceFile);
      if (!isNullOrEmpty(path)) {
        helper.addSourcePath(path);
      }
    }
    final List<String> paths = helper.getSortedSourcePaths();

    for (int index = 0, size = paths.size(); index < size; /*increment in loop*/ ) {
      final String path = paths.get(index);

      // This folder could be a root, but so could any of its parents. The best root is the one that
      // requires the fewest excludedFolder tags
      int lowestCost = helper.excludesUnder(path);
      String bestRoot = path;
      String parent = dirname(path);
      while (!isNullOrEmpty(parent)) {
        int cost = helper.excludesUnder(parent);
        if (cost < lowestCost) {
          lowestCost = cost;
          bestRoot = parent;
        }
        parent = dirname(parent);
      }
      roots.add(bestRoot);

      index += 1;
      String prefix = bestRoot.endsWith("/") ? bestRoot : bestRoot + '/';
      while (index < size && paths.get(index).startsWith(prefix)) {
        index += 1;
      }
    }

    return roots;
  }

  private void buildRootLinks(Set<String> roots) {
    for (String root : roots) {
      symlink(fileJoin(repository, root), fileJoin(viewPath, root));
    }
  }

  /** Maintains a set of source pathes, and a map of paths -> excludes */
  private class RootsHelper {
    private final Set<String> sourcePaths = new HashSet<>();
    private final Map<String, Integer> excludes = new HashMap<>();

    void addSourcePath(String sourcePath) {
      sourcePaths.add(sourcePath);
    }

    boolean isSourcePath(String sourcePath) {
      return sourcePaths.contains(sourcePath);
    }

    List<String> getSortedSourcePaths() {
      return sourcePaths.stream().sorted().collect(Collectors.toList());
    }

    int excludesUnder(String path) {
      if (excludes.containsKey(path)) {
        return excludes.get(path);
      }

      int sum = 0;
      File absolute = new File(repository, path);
      String[] files = absolute.list(neitherDotOrDotDot);
      if (files != null) {
        for (String entry : files) {
          String child = fileJoin(path, entry);
          if (isDirectory(fileJoin(repository, child))) {
            if (!isSourcePath(child)) {
              sum += 1;
            }
            sum += excludesUnder(child);
          }
        }
      }
      excludes.put(path, sum);
      return sum;
    }
  }

  // endregion roots

  // region .idea folder

  private static final String BUCK_OUT = "buck-out";
  private static final String COMPONENT = "component";
  private static final String CONTENT = "content";
  private static final String EXCLUDE_FOLDER = "excludeFolder";
  private static final String IS_TEST_SOURCE = "isTestSource";
  private static final String LIBRARY = "library";
  private static final String MODULES = "modules";
  private static final String NAME = "name";
  private static final String OPTION = "option";
  private static final String ORDER_ENTRY = "orderEntry";
  private static final String ROOT_IML = "root.iml";
  private static final String SOURCE_FOLDER = "sourceFolder";
  private static final String TYPE = "type";
  private static final String URL = "url";
  private static final String VALUE = "value";
  private static final String VERSION = "version";

  private static final String MODULE_DIR = "$MODULE_DIR$";
  private static final String FILE_MODULE_DIR = "file://" + MODULE_DIR;

  // region XML utilities

  private enum XML {
    DECLARATION,
    NO_DECLARATION
  }

  private static Document newDocument(Element root) {
    return new Document(root);
  }

  private void saveDocument(String path, String filename, XML mode, Document document) {
    if (path != null) {
      filename = fileJoin(path, filename);
    }

    if (dryRun) {
      stderr("Writing %s\n", filename);
      return;
    }

    Format prettyFormat = Format.getPrettyFormat();
    prettyFormat.setOmitDeclaration(mode == XML.NO_DECLARATION);
    XMLOutputter outputter = new XMLOutputter(prettyFormat);
    try (Writer writer = new FileWriter(filename)) {
      outputter.output(document, writer);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void saveDocument(String path, String filename, XML mode, Element root) {
    saveDocument(path, filename, mode, newDocument(root));
  }

  private static Element addElement(Element parent, String name, Attribute... attributes) {
    Element child = newElement(name, attributes);
    parent.addContent(child);
    return child;
  }

  private static Element addElement(Element parent, String name, List<Attribute> attributes) {
    return addElement(parent, name, attributes.toArray(new Attribute[attributes.size()]));
  }

  private static Attribute attribute(String name, String value) {
    return new Attribute(name, value);
  }

  private static Attribute attribute(String name, Object value) {
    return attribute(name, value.toString());
  }

  private static Element newElement(String name, Attribute attribute) {
    Element element = new Element(name);
    element.setAttribute(attribute);
    return element;
  }

  private static Element newElement(String name, Attribute... attributes) {
    Element element = new Element(name);
    if (attributes != null) {
      for (Attribute attribute : attributes) {
        element.setAttribute(attribute);
      }
    }
    return element;
  }

  // endregion XML utilities

  private List<String> buildDotIdeaFolder(List<String> inputs) {
    String dotIdea = fileJoin(viewPath, DOT_IDEA);
    immediateMkdir(dotIdea);

    writeModulesXml(dotIdea);
    writeMiscXml(dotIdea);

    symlink(
        fileJoin(repository, DOT_IDEA, CODE_STYLE_SETTINGS),
        fileJoin(dotIdea, CODE_STYLE_SETTINGS));

    return buildDotIdeaDotLibrariesFolder(dotIdea, inputs);
  }

  private void writeModulesXml(String dotIdea) {
    String filepath = fileJoin("$PROJECT_DIR$", ROOT_IML);
    String fileurl = "file://" + filepath;

    Element project = newElement("project", attribute(VERSION, 4));
    Element component = addElement(project, COMPONENT, attribute(NAME, "ProjectModuleManager"));
    Element modules = addElement(component, MODULES);
    addElement(
        modules,
        "module",
        attribute("fileurl", fileurl),
        attribute("filepath", filepath),
        attribute("group", MODULES));

    saveDocument(dotIdea, "modules.xml", XML.DECLARATION, project);
  }

  private void writeMiscXml(String dotIdea) {
    Element project = newElement("project", attribute(VERSION, 4));
    addElement(
        project,
        COMPONENT,
        attribute(NAME, "FrameworkDetectionExcludesConfiguration"),
        attribute("detection-enabled", false));

    String languageLevel = getIntellijSectionValue(INTELLIJ_LANGUAGE_LEVEL, "JDK_1_7");
    String jdkName = getIntellijSectionValue(INTELLIJ_JDK_NAME, "Android API 23 Platform");
    String jdkType = getIntellijSectionValue(INTELLIJ_JDK_TYPE, "Android SDK");

    addElement(
        project,
        COMPONENT,
        attribute(NAME, "ProjectRootManager"),
        attribute("VERSION", 2),
        attribute("languageLevel", languageLevel),
        attribute("assert-keyword", true),
        attribute("jdk-15", jdk15(languageLevel)),
        attribute("project-jdk-name", jdkName),
        attribute("project-jdk-type", jdkType));

    saveDocument(dotIdea, "misc.xml", XML.DECLARATION, project);
  }

  // region .buckconfig wrappers

  /** All the values we currently read come from the [intellij] section of the .buckconfig file */
  private static final String INTELLIJ_SECTION = "intellij";

  // Values in the [intellij] section
  private static final String INTELLIJ_LANGUAGE_LEVEL = "language_level";
  private static final String INTELLIJ_JDK_NAME = "jdk_name";
  private static final String INTELLIJ_JDK_TYPE = "jdk_type";

  private Optional<String> getIntellijSectionValue(String propertyName) {
    return config.getValue(INTELLIJ_SECTION, propertyName);
  }

  private String getIntellijSectionValue(String propertyName, String defaultValue) {
    return getIntellijSectionValue(propertyName).orElse(defaultValue);
  }

  /**
   * Tries to parse the language level. Will always return {@code false} if the string doesn't look
   * like {@code /JDK_\d_\d/}. Otherwise, will return {@code true} iff language level {@literal >=}
   * 1.5
   */
  private static boolean jdk15(String languageLevel) {
    if (languageLevel.length() < 7 || !languageLevel.startsWith("JDK_")) {
      return false;
    }
    if (languageLevel.charAt(3) != '_' || languageLevel.charAt(5) != '_') {
      return false;
    }
    char major = languageLevel.charAt(4);
    char minor = languageLevel.charAt(6);
    if (!Character.isDigit(major) || !Character.isDigit(minor)) {
      return false;
    }

    // If we get here, languageLevel looks like /JDK_\d_\d/
    int majorValue = Character.getNumericValue(major);
    int minorValue = Character.getNumericValue(minor);
    if (majorValue < 1 || minorValue < 1) {
      // We passed the isDigit() tests, but either MIGHT be 0 ...
      return false;
    }
    if (majorValue > 1) {
      return true;
    }
    // majorValue == 1
    return minorValue >= 5;
  }

  // endregion .buckconfig wrappers

  private List<String> buildDotIdeaDotLibrariesFolder(String dotIdea, List<String> inputs) {
    String libraries = fileJoin(dotIdea, "libraries");
    immediateMkdir(libraries);

    Map<String, List<String>> directories = new HashMap<>();
    inputs
        .stream()
        .filter((input) -> input.endsWith(".jar"))
        .forEach(
            jar -> {
              String dirname = dirname(jar);
              String basename = basename(jar);
              List<String> basenames = directories.get(dirname);
              if (basenames == null) {
                basenames = new ArrayList<>();
                directories.put(dirname, basenames);
              }
              basenames.add(basename);
            });

    List<String> libraryXmls = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : directories.entrySet()) {
      libraryXmls.add(buildLibraryFile(libraries, entry.getKey(), entry.getValue()));
    }
    return libraryXmls;
  }

  private String buildLibraryFile(String libraries, String directory, List<String> jars) {
    String filename = "library_" + directory.replace('-', '_').replace('/', '_');
    List<String> urls =
        jars.stream()
            .map((jar) -> fileJoin("jar://$PROJECT_DIR$", directory, jar) + "!/")
            .collect(Collectors.toList());

    Element component = newElement(COMPONENT, attribute(NAME, "libraryTable"));
    Element library = addElement(component, LIBRARY, attribute(NAME, filename));
    Element classes = addElement(library, "CLASSES"); // believe it or not, case matters, here!
    for (String url : urls) {
      addElement(classes, "root", attribute(URL, url));
    }
    addElement(library, "JAVADOC");
    addElement(library, "SOURCES");

    saveDocument(libraries, filename + ".xml", XML.NO_DECLARATION, component);

    return filename;
  }

  private void writeRootDotIml(
      List<String> sourceFiles, Set<String> roots, List<String> libraries) {
    String buckOut = fileJoin(viewPath, BUCK_OUT);
    symlink(fileJoin(repository, BUCK_OUT), buckOut);

    String apkPath = null;
    Map<BuildTarget, String> outputs = getOutputs();
    // Find the 1st target that has output
    for (BuildTarget target : buildTargets) {
      String output = outputs.get(target);
      if (output != null && output.endsWith(".apk")) {
        apkPath = File.separator + output;

        break;
      }
    }

    String manifestPath = fileJoin(File.separator, RES, ANDROID_MANIFEST);
    symlink(fileJoin(repository, ANDROID_RES, ANDROID_MANIFEST), fileJoin(viewPath, manifestPath));

    Element module = newElement("module", attribute(TYPE, "JAVA_MODULE"), attribute(VERSION, 4));

    Element facetManager = addElement(module, COMPONENT, attribute(NAME, "FacetManager"));
    Element facet =
        addElement(facetManager, "facet", attribute(TYPE, "android"), attribute(NAME, "Android"));

    Element configuration = addElement(facet, "configuration");

    String genFolder = fileJoin(File.separator, BUCK_OUT, "gen");
    addElement(
        configuration,
        OPTION,
        attribute(NAME, "GEN_FOLDER_RELATIVE_PATH_APT"),
        attribute(VALUE, genFolder));
    addElement(
        configuration,
        OPTION,
        attribute(NAME, "GEN_FOLDER_RELATIVE_PATH_AIDL"),
        attribute(VALUE, fileJoin(genFolder, "aidl")));

    addElement(
        configuration,
        OPTION,
        attribute(NAME, "MANIFEST_FILE_RELATIVE_PATH"),
        attribute(VALUE, manifestPath));
    addElement(
        configuration,
        OPTION,
        attribute(NAME, "RES_FOLDERS_RELATIVE_PATH"),
        attribute(VALUE, "/res"));
    if (apkPath != null) {
      addElement(configuration, OPTION, attribute(NAME, "APK_PATH"), attribute(VALUE, apkPath));
    }
    addElement(
        configuration,
        OPTION,
        attribute(NAME, "ENABLE_SOURCES_AUTOGENERATION"),
        attribute(VALUE, true));
    addElement(configuration, "includeAssetsFromLibraries").addContent("true");

    Element rootManager =
        addElement(
            module,
            COMPONENT,
            attribute(NAME, "NewModuleRootManager"),
            attribute("inherit-compiler-output", true));
    addElement(rootManager, "exclude-output");

    Element folders = addElement(rootManager, CONTENT, attribute(URL, FILE_MODULE_DIR));

    Set<String> sourceFolders =
        sourceFiles.stream().map((folder) -> dirname(folder)).collect(Collectors.toSet());
    sourceFolders.remove(null);

    for (String source : sortSourceFolders(sourceFolders)) {
      List<Attribute> attributes = new ArrayList<>(3);
      attributes.add(attribute(URL, fileJoin(FILE_MODULE_DIR, source)));
      attributes.add(attribute(IS_TEST_SOURCE, false));

      String packagePrefix = getPackage(fileJoin(repository, source));
      if (packagePrefix != null) {
        attributes.add(attribute("packagePrefix", packagePrefix));
      }
      addElement(folders, SOURCE_FOLDER, attributes);
    }

    for (String excluded : getExcludedFolders(sourceFolders, roots)) {
      addElement(folders, EXCLUDE_FOLDER, attribute(URL, fileJoin(FILE_MODULE_DIR, excluded)));
    }

    addElement(rootManager, ORDER_ENTRY, attribute(TYPE, "inheritedJdk"));
    addElement(
        rootManager, ORDER_ENTRY, attribute(TYPE, SOURCE_FOLDER), attribute("forTests", false));

    for (String library : libraries) {
      addElement(
          rootManager,
          ORDER_ENTRY,
          attribute(TYPE, LIBRARY),
          attribute(NAME, library),
          attribute("level", "project"));
    }

    for (String relativeFolder : getAnnotationAndGeneratedFolders()) {
      String folder = fileJoin(FILE_MODULE_DIR, relativeFolder);
      Attribute url = attribute(URL, folder);
      Element content = addElement(rootManager, CONTENT, url);
      addElement(
          content,
          SOURCE_FOLDER,
          url.clone(),
          attribute(IS_TEST_SOURCE, false),
          attribute("generated", true));
    }

    saveDocument(viewPath, ROOT_IML, XML.DECLARATION, module);
  }

  private Map<BuildTarget, String> getOutputs() {
    Map<BuildTarget, String> outputs = new HashMap<>(buildTargets.size());

    BuildRuleResolver ruleResolver = actionGraph.getResolver();
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    for (BuildTarget target : buildTargets) {
      BuildRule rule = ruleResolver.getRule(target);
      SourcePath sourcePathToOutput = rule.getSourcePathToOutput();
      if (sourcePathToOutput == null) {
        continue;
      }
      Path outputPath = pathResolver.getRelativePath(sourcePathToOutput);
      outputs.put(target, outputPath.toString());
    }

    return outputs;
  }

  private List<String> sortSourceFolders(Set<String> sourceFolders) {
    return sourceFolders.stream().sorted().collect(Collectors.toList());
  }

  private static Set<String> getExcludedFolders(Set<String> sourceFolders, Set<String> roots) {
    Set<String> rootFolders = allFoldersUnder(roots);
    rootFolders.removeAll(sourceFolders);
    return rootFolders;
  }

  private static Set<String> allFoldersUnder(Set<String> roots) {
    Set<String> result = new HashSet<>();
    for (String root : roots) {
      result.addAll(foldersUnder(root));
    }
    return result;
  }

  private static Set<String> foldersUnder(String root) {
    // TODO(shemitz) This really should use Files.find() ...
    Set<String> result = new HashSet<>();
    File directory = new File(root);
    if (directory.isDirectory()) {
      String[] children = directory.list(neitherDotOrDotDot);
      if (children != null) {
        for (String child : children) {
          String qualified = fileJoin(root, child);
          if (isDirectory(qualified)) {
            result.add(qualified);
            result.addAll(foldersUnder(qualified));
          }
        }
      }
    }
    return result;
  }

  private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w\\.]+);");

  @Nullable
  private static String getPackage(String path) {
    File folder = new File(path);
    File[] files = folder.listFiles((child) -> child.isFile() && child.getName().endsWith(".java"));
    if (files != null) {
      for (File file : files) {
        String text;
        try {
          text = new String(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
          continue;
        }
        Matcher matcher = PACKAGE.matcher(text);
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    return null;
  }

  private Collection<String> getAnnotationAndGeneratedFolders() {
    Collection<String> folders = new HashSet<>();

    getAnnotationFolders(folders);
    getGeneratedFolders(folders);

    return folders.stream().sorted().collect(Collectors.toList());
  }

  private void getAnnotationFolders(Collection<String> folders) {
    for (BuildRule buildRule : actionGraph.getActionGraph().getNodes()) {
      if (buildRule instanceof JavaLibrary) {
        Optional<Path> generatedSourcePath = ((JavaLibrary) buildRule).getGeneratedSourcePath();
        if (generatedSourcePath.isPresent()) {
          folders.add(generatedSourcePath.get().toString());
        }
      }
    }
  }

  private void getGeneratedFolders(Collection<String> folders) {
    Map<String, String> labelToGeneratedSourcesMap =
        config.getMap(INTELLIJ_SECTION, "generated_sources_label_map");
    Pattern name = Pattern.compile("%name%");

    AbstractBreadthFirstTraversal.<TargetNode<?, ?>>traverse(
        targetGraph.getAll(allTargets),
        node -> {
          ProjectFilesystem filesystem = node.getFilesystem();
          Set<BuildTarget> buildDeps = node.getBuildDeps();
          for (BuildTarget buildTarget : buildDeps) {
            Object constructorArg = node.getConstructorArg();
            if (constructorArg instanceof CommonDescriptionArg) {
              CommonDescriptionArg commonDescriptionArg = (CommonDescriptionArg) constructorArg;
              folders.addAll(
                  commonDescriptionArg
                      .getLabels()
                      .stream()
                      .map(labelToGeneratedSourcesMap::get)
                      .filter(Objects::nonNull)
                      .map(
                          pattern ->
                              name.matcher(pattern)
                                  .replaceAll(buildTarget.getShortNameAndFlavorPostfix()))
                      .map(
                          (String path) ->
                              BuildTargets.getGenPath(filesystem, buildTarget, path).toString())
                      .collect(Collectors.toSet()));
            }
          }
          return targetGraph.getAll(buildDeps);
        });
  }

  // endregion .idea folder

  // region symlinks, mkdir, and other file utilities

  /**
   * This is <em>not</em> all directories in the view; this is all 'terminals' that have symlinks.
   * That is, if we have {@code foo/bar/baz/link}, we will record {@code foo/bar/baz} but not {@code
   * foo/bar} or {@code foo}.
   */
  private final Set<Path> existingDirectories = new HashSet<>();
  /** basefile -> link */
  private final Map<Path, Path> existingSymlinks = new HashMap<>();

  private final Set<Path> directoriesToMake = new HashSet<>();
  /** basefile -> link */
  private final Map<Path, Path> symlinksToCreate = new HashMap<>();

  private void scanExistingView() {
    Path root = Paths.get(viewPath);
    if (!Files.exists(root)) {
      return;
    }
    try {
      Files.find(
              root,
              Integer.MAX_VALUE,
              (Path ignored, BasicFileAttributes attributes) ->
                  attributes.isDirectory() || attributes.isSymbolicLink())
          .forEach(
              path -> {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                  if (hasSymbolicLink(path)) {
                    existingDirectories.add(path.toAbsolutePath());
                  }
                } else if (Files.isSymbolicLink(path)) {
                  try {
                    existingSymlinks.put(Files.readSymbolicLink(path), path);
                  } catch (IOException e) {
                    stderr("'%s' reading %s", e.getMessage(), path);
                  }
                }
              });
    } catch (IOException e) {
      stderr("'%s' scanning the existing links and directories\n", e.getMessage());
    }
  }

  private boolean hasSymbolicLink(Path path) {
    try {
      return Files.list(path).anyMatch(p -> Files.isSymbolicLink(p));
    } catch (IOException e) {
      stderr("'%s' enumerating %s", e.getMessage(), path);
      return true;
    }
  }

  private void buildAllDirectoriesAndSymlinks() {
    Set<Path> deletedDirectories = new HashSet<>();

    // Delete any directories that should no longer exist
    for (Path path : existingDirectories) {
      if (!directoriesToMake.contains(path)) {
        if (dryRun) {
          stderr("rm -rf %s\n", path);
        } else {
          deleteAll(path);
          deletedDirectories.add(path);
        }
      }
    }

    // Make any directories that don't already exist
    for (Path path : directoriesToMake) {
      if (!existingDirectories.contains(path)) {
        immediateMkdir(path);
      }
    }

    // Delete any symlinks that should no longer exist; remove existing links from Map
    existingSymlinks.forEach(
        (filePath, linkPath) -> {
          if (linkPath.equals(symlinksToCreate.get(filePath))) {
            symlinksToCreate.remove(filePath);
          } else {
            if (dryRun) {
              stderr("rm %s\n", linkPath);
            } else {
              try {
                Files.delete(linkPath);
              } catch (IOException e) {
                if (!linkInDeletedDirectories(deletedDirectories, linkPath)) {
                  stderr("'%s' deleting symlink %s\n", e.getMessage(), linkPath);
                }
              }
            }
          }
        });

    // Create any symlinks that don't already exist
    symlinksToCreate.forEach(
        (filePath, linkPath) -> {
          if (dryRun) {
            stderr("symlink(%s, %s)\n", filePath, linkPath);
          } else {
            createSymbolicLink(filePath, linkPath);
          }
        });
  }

  private boolean linkInDeletedDirectories(Set<Path> deletedDirectories, Path linkPath) {
    Path linkDirectory = linkPath;
    while ((linkDirectory = linkDirectory.getParent()) != null) {
      if (deletedDirectories.contains(linkDirectory)) {
        return true;
      }
    }
    return false;
  }

  private static String basename(File file) {
    return file.getName();
  }

  private static String basename(String filename) {
    return basename(new File(filename));
  }

  private static String dirname(File file) {
    return file.getParent();
  }

  private static String dirname(String filename) {
    return dirname(new File(filename));
  }

  private static String fileJoin(String... components) {
    StringBuilder join = new StringBuilder();
    if (components != null) {
      for (String component : components) {
        if (needSeparator(join, component)) {
          join.append(File.separatorChar);
        }
        join.append(component);
      }
    }
    return join.toString();
  }

  private static boolean needSeparator(StringBuilder join, String next) {
    int length = join.length();
    if (length == 0) {
      return false;
    }
    if (join.charAt(length - 1) == File.separatorChar) {
      return false;
    }
    return !next.startsWith(File.separator);
  }

  private void mkdir(String name) {
    directoriesToMake.add(Paths.get(name));
  }

  private void immediateMkdir(String path) {
    immediateMkdir(Paths.get(path));
  }

  private void immediateMkdir(Path path) {
    if (dryRun) {
      stderr("mkdir(%s)\n", path);
    } else {
      try {
        Files.createDirectories(path);
      } catch (IOException e) {
        stderr("'%s' creating directory %s\n", e.getMessage(), path);
      }
    }
  }

  private void symlink(String filename, String linkname) {
    File link = new File(linkname);
    Path linkPath = link.toPath();
    mkdir(dirname(link));

    Path filePath = Paths.get(filename);

    symlinksToCreate.put(filePath, linkPath);
  }

  /** Parameter order is compatible with Ruby library code, for porting transparency */
  private void createSymbolicLink(Path oldPath, Path newPath) {
    try {
      Files.createSymbolicLink(newPath, oldPath);
    } catch (IOException e) {
      stderr(
          "createSymbolicLink(%s, %s)\n%s:\n%s\n\n",
          oldPath, newPath, e.getClass().getSimpleName(), e.getMessage());
    }
  }

  private void deleteAll(Path root) {
    try {
      Files.walk(root)
          .sorted(Comparator.reverseOrder()) // foo/bar before foo
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  stderr("'%s' deleting %s\n", e.getMessage(), p);
                }
              });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static boolean isDirectory(String name) {
    File file = new File(name);
    return file.isDirectory();
  }

  private static final FilenameFilter neitherDotOrDotDot =
      new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return !(name.equals(".") || name.equals(".."));
        }
      };

  // endregion symlinks, mkdir, and other file utilities

  // region Console IO

  private void stderr(String pattern, Object... parameters) {
    stdErr.format(pattern, parameters);
  }

  // endregion Console IO

  // region Random crap

  private static boolean isNullOrEmpty(String s) {
    return s == null || s.isEmpty();
  }

  // endregion Random crap

  // endregion Private implementation
}
