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

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
      String viewPath,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> buildTargets) {
    return new ProjectView(stderr, dryRun, viewPath, targetGraph, buildTargets).run();
  }

  // endregion Public API

  // region Private implementation

  private static final String ANDROID_MANIFEST = "AndroidManifest.xml";
  private static final String ANDROID_RES = "android_res";
  private static final String ASSETS = "assets";
  private static final String CODE_STYLE_SETTINGS = "codeStyleSettings.xml";
  private static final String DOT_IDEA = ".idea";
  private static final String FONTS = "fonts";
  private static final String RES = "res";

  private final DirtyPrintStreamDecorator stdErr;
  private final String viewPath;
  private final boolean dryRun;
  private final TargetGraph targetGraph;

  private final ImmutableSet<BuildTarget> buildTargets;
  private final String repository = new File("").getAbsolutePath();

  private ProjectView(
      DirtyPrintStreamDecorator stdErr,
      boolean dryRun,
      String viewPath,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> buildTargets) {
    this.stdErr = stdErr;
    this.viewPath = viewPath;
    this.dryRun = dryRun;

    this.targetGraph = targetGraph;
    this.buildTargets = buildTargets;
  }

  private int run() {
    List<String> inputs = getPrunedInputs();

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

    return 0;
  }

  // region getPrunedInputs()

  private List<String> getPrunedInputs() {
    return pruneInputs(getAllInputs());
  }

  private Collection<String> getAllInputs() {

    Set<String> inputs = new HashSet<>();

    AbstractBreadthFirstTraversal.<TargetNode<?, ?>>traverse(
        targetGraph.getAll(buildTargets),
        node -> {
          node.getInputs().forEach(input -> inputs.add(input.toString()));
          return targetGraph.getAll(node.getBuildDeps());
        });

    return inputs;
  }

  private List<String> pruneInputs(Collection<String> allInputs) {
    Pattern resource = Pattern.compile("/res/(?!values/)");

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
      List<String> candidates = mapping.getValue();
      if (candidates.size() > 1) {
        Collections.sort(candidates);
      }
      result.add(candidates.get(0));
    }

    return result;
  }

  // endregion getPrunedInputs()

  // region linkResourceFile

  private static final Pattern ANIM_RES = Pattern.compile("/res/(anim(?:-[^/]+)?)/");
  private static final Pattern ANIMATOR_RES = Pattern.compile("/res/(animator)/");
  private static final Pattern DRAWABLE_RES = Pattern.compile("/res/(drawable(?:-[^/]+)?)/");
  private static final Pattern LAYOUT_RES = Pattern.compile("/res/(layout(?:-[^/]+)?)/");
  private static final Pattern MENU_RES = Pattern.compile("/res/(menu(?:-[^/]+)?)/");
  private static final Pattern RAW_RES = Pattern.compile("/res/(raw)/");
  private static final Pattern XML_RES = Pattern.compile("/res/(xml(?:-[^/]+)?)/");

  private static final Pattern[] SIMPLE_RESOURCE_PATTERNS =
      new Pattern[] {ANIM_RES, ANIMATOR_RES, DRAWABLE_RES, LAYOUT_RES, MENU_RES, RAW_RES, XML_RES};

  private static final Pattern COLOR_RES = Pattern.compile("^android_res/(.*)res/(color)/");
  private static final Pattern VALUES_RES =
      Pattern.compile("^android_res/(.*)res/(values(?:-[^/]+)?)/");

  private static final Pattern[] MANGLED_RESOURCE_PATTERNS = new Pattern[] {COLOR_RES, VALUES_RES};

  private static final Pattern ASSETS_RES = Pattern.compile("/assets/");
  private static final Pattern FONTS_RES = Pattern.compile("/fonts/(.*\\.\\w+)");

  private void linkResourceFile(String input) {
    Matcher match;

    // TODO(shemitz) Convert (say) "res/drawable-hdpi/" to "res/drawable/"

    match = firstMatch(SIMPLE_RESOURCE_PATTERNS, input);
    if (match != null) {
      simpleResourceLink(match, input);
      return;
    }

    match = firstMatch(MANGLED_RESOURCE_PATTERNS, input);
    if (match != null) {
      mangledResourceLink(match, input);
      return;
    }

    match = getMatcherOnFind(ASSETS_RES, input);
    if (match != null) {
      assetsLink(input);
      return;
    }

    match = getMatcherOnFind(FONTS_RES, input);
    if (match != null) {
      fontsLink(match, input);
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
    String name = basename(input);

    String path = match.group(1).replace('/', '_');

    String directory = fileJoin(viewPath, RES, flattenResourceDirectoryName(match.group(2)));
    mkdir(directory);

    symlink(fileJoin(repository, input), fileJoin(directory, path + name));
  }

  private static String flattenResourceDirectoryName(String name) {
    int dash = name.indexOf('-');
    return dash < 0 ? name : name.substring(0, dash);
  }

  private void assetsLink(String input) {
    String directory = fileJoin(viewPath, ASSETS);
    mkdir(directory);

    String name = basename(input);
    symlink(fileJoin(repository, input), fileJoin(directory, name));
  }

  private void fontsLink(Matcher match, String input) {
    String target = fileJoin(viewPath, FONTS, match.group(1));
    String path = dirname(target);
    mkdir(path);
    symlink(fileJoin(repository, input), target);
  }

  @Nullable
  private static Matcher firstMatch(Pattern[] patterns, String target) {
    for (Pattern pattern : patterns) {
      Matcher match = pattern.matcher(target);
      if (match.find()) {
        return match;
      }
    }
    return null;
  }

  @Nullable
  private static Matcher getMatcherOnFind(Pattern pattern, String target) {
    Matcher match = pattern.matcher(target);
    return match.find() ? match : null;
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
      String prefix = bestRoot.endsWith(File.separator) ? bestRoot : bestRoot + File.separator;
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

  /** Maintains a set of source paths, and a map of paths -> excludes */
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
      List<String> paths = new ArrayList<>(sourcePaths);
      Collections.sort(paths);
      return paths;
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
    mkdir(dotIdea);

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
    // TODO(shemitz) Should really get ProjectRootManager values from .buckconfig
    addElement(
        project,
        COMPONENT,
        attribute(NAME, "ProjectRootManager"),
        attribute("VERSION", 2),
        attribute("languageLevel", "JDK_1_7"),
        attribute("assert-keyword", true),
        attribute("jdk-15", true),
        attribute("project-jdk-name", "Android API 23 Platform"),
        attribute("project-jdk-type", "Android SDK"));

    saveDocument(dotIdea, "misc.xml", XML.DECLARATION, project);
  }

  private List<String> buildDotIdeaDotLibrariesFolder(String dotIdea, List<String> inputs) {
    String libraries = fileJoin(dotIdea, "libraries");
    mkdir(libraries);

    Map<String, List<String>> directories = new HashMap<>();
    List<String> jars =
        inputs.stream().filter((input) -> input.endsWith(".jar")).collect(Collectors.toList());
    for (String jar : jars) {
      String dirname = dirname(jar);
      String basename = basename(jar);
      List<String> basenames = directories.get(dirname);
      if (basenames == null) {
        basenames = new ArrayList<>();
        directories.put(dirname, basenames);
      }
      basenames.add(basename);
    }

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

    String app = null, target = null;
    Pattern targetPath = Pattern.compile("//([^:]*):.*");
    for (BuildTarget buildTarget : buildTargets) {
      String aTarget = buildTarget.toString();
      Matcher matcher = targetPath.matcher(aTarget);
      if (matcher.matches()) {
        String candidate = fileJoin(buckOut, "android", matcher.group(1), "gen");
        if (isDirectory(candidate)) {
          app = candidate;
          target = aTarget;
          break;
        }
      }
    }
    if (app == null) {
      throw new RuntimeException("Didn't find any target in buck-out/android");
    }
    app = /**/ File.separator + /**/ getRelativePath(viewPath, app);

    String manifestPath = fileJoin(File.separator, RES, ANDROID_MANIFEST);
    symlink(fileJoin(repository, ANDROID_RES, ANDROID_MANIFEST), fileJoin(viewPath, manifestPath));

    String apkPath = target.substring(1).replace(':', '/'); // strip leading /, turn : into /
    apkPath = fileJoin(buckOut, "gen", apkPath);
    apkPath = /**/ File.separator + /**/ getRelativePath(viewPath, apkPath + ".apk");

    Element module = newElement("module", attribute(TYPE, "JAVA_MODULE"), attribute(VERSION, 4));

    Element facetManager = addElement(module, COMPONENT, attribute(NAME, "FacetManager"));
    Element facet =
        addElement(facetManager, "facet", attribute(TYPE, "android"), attribute(NAME, "Android"));

    Element configuration = addElement(facet, "configuration");

    addElement(
        configuration,
        OPTION,
        attribute(NAME, "GEN_FOLDER_RELATIVE_PATH_APT"),
        attribute(VALUE, app));
    addElement(
        configuration,
        OPTION,
        attribute(NAME, "GEN_FOLDER_RELATIVE_PATH_AIDL"),
        attribute(VALUE, app));
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
    addElement(configuration, OPTION, attribute(NAME, "APK_PATH"), attribute(VALUE, apkPath));
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

    app = fileJoin(FILE_MODULE_DIR, app);
    Element content = addElement(rootManager, CONTENT, attribute(URL, app));
    addElement(content, SOURCE_FOLDER, attribute(URL, app), attribute(IS_TEST_SOURCE, false));

    Element folders = addElement(rootManager, CONTENT, attribute(URL, FILE_MODULE_DIR));

    Set<String> sourceFolders =
        sourceFiles.stream().map((folder) -> dirname(folder)).collect(Collectors.toSet());
    sourceFolders.remove(null);

    for (String source : sortSourceFolders(sourceFolders)) {
      List<Attribute> attributes = new ArrayList<>(3);
      attributes.add(attribute(URL, fileJoin(FILE_MODULE_DIR, source)));
      attributes.add(attribute("isTestSource", false));

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

    saveDocument(viewPath, ROOT_IML, XML.DECLARATION, module);
  }

  private List<String> sortSourceFolders(Set<String> sourceFolders) {
    List<String> folders = new ArrayList<>(sourceFolders);
    Collections.sort(folders);
    return folders;
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

  // endregion .idea folder

  // region symlinks, mkdir, and other file utilities

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

  private static String fileJoin(Iterable<String> components) {
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
    File file = new File(name);
    if (file.isDirectory()) {
      return;
    }
    if (dryRun) {
      stderr("mkdir(%s)\n", name);
    } else {
      file.mkdirs();
    }
  }

  private void symlink(String filename, String linkname) {
    if (dryRun) {
      stderr("symlink(%s, %s)\n", filename, linkname);
    } else {
      File link = new File(linkname);
      Path linkPath = link.toPath();
      if (Files.isSymbolicLink(linkPath)) {
        return; // already exists
      }
      File file = new File(filename);
      mkdir(dirname(link));
      createSymbolicLink(file.toPath(), linkPath);
    }
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

  private static boolean isDirectory(String name) {
    File file = new File(name);
    return file.isDirectory();
  }

  private static String getRelativePath(String referencePath, String otherPath) {
    List<String> reference = Arrays.asList(referencePath.split(File.separator));
    List<String> other = Arrays.asList(otherPath.split(File.separator));

    int index = 0;
    final int limit = Math.min(reference.size(), other.size());
    while (index < limit && reference.get(index).equals(other.get(index))) {
      index += 1;
    }
    reference = reference.subList(index, reference.size());
    other = other.subList(index, other.size());

    List<String> dots = reference.stream().map((node) -> "..").collect(Collectors.toList());
    return fileJoin(fileJoin(dots), fileJoin(other));
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
