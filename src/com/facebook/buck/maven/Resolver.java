/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.maven;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_FAIL;
import static org.eclipse.aether.util.artifact.JavaScopes.TEST;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.common.io.Resources;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionScheme;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupString;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class Resolver {

  private static final String TEMPLATE =
      Resolver.class.getPackage().getName().replace(".", "/") + "/build-file.st";
  public static final String ARTIFACT_FILE_NAME_FORMAT = "%s-%s.%s";
  public static final String ARTIFACT_FILE_NAME_REGEX_FORMAT =
      ARTIFACT_FILE_NAME_FORMAT.replace(".", "\\.");
  public static final String VERSION_REGEX_GROUP = "([^-]+)";

  private final Path buckRepoRoot;
  private final Path buckThirdPartyRelativePath;
  private final LocalRepository localRepo;
  private final ImmutableList<RemoteRepository> repos;
  private final ServiceLocator locator;
  private final VersionScheme versionScheme = new GenericVersionScheme();

  public Resolver(
      Path buckRepoRoot,
      Path relativeThirdParty,
      Path localRepoPath,
      String... repoUrls) {
    this.buckRepoRoot = buckRepoRoot;
    this.buckThirdPartyRelativePath = relativeThirdParty;
    this.localRepo = new LocalRepository(localRepoPath.toFile());

    ImmutableList.Builder<RemoteRepository> builder = ImmutableList.builder();
    for (int i = 0; i < repoUrls.length; i++) {
      RemoteRepository.Builder remote =
          new RemoteRepository.Builder("remote " + i, "default", repoUrls[i])
              .setPolicy(new RepositoryPolicy(true, null, CHECKSUM_POLICY_FAIL));
      builder.add(remote.build());
    }
    this.repos = builder.build();

    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
    this.locator = locator;
  }

  public void resolve(String... mavenCoords) throws RepositoryException, IOException {
    RepositorySystem repoSys = locator.getService(RepositorySystem.class);
    RepositorySystemSession session = newSession(repoSys);

    ImmutableMap<String, Artifact> knownDeps = getRunTimeTransitiveDeps(
        repoSys,
        session,
        mavenCoords);

    // We now have the complete set of dependencies. Build the graph of dependencies. We'd like
    // aether to do this for us, but it doesn't preserve the complete dependency information we need
    // to accurately construct build files.
    MutableDirectedGraph<Artifact> graph = buildDependencyGraph(repoSys, session, knownDeps);

    // Now we have the graph, grab the sources and jars for each dependency, as well as the relevant
    // checksums (which are download by default. Yay!)

    Map<Path, SortedSet<Prebuilt>> buckFiles = new HashMap<>();

    for (Artifact artifact : graph.getNodes()) {
      downloadArtifact(artifact, repoSys, session, buckFiles, graph);
    }

    createBuckFiles(buckFiles);
  }

  private void downloadArtifact(
      final Artifact artifactToDownload,
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Map<Path, SortedSet<Prebuilt>> buckFiles,
      MutableDirectedGraph<Artifact> graph)
      throws IOException, ArtifactResolutionException, InvalidVersionSpecificationException {
    String projectName = getProjectName(artifactToDownload);
    Path project = buckRepoRoot.resolve(buckThirdPartyRelativePath).resolve(projectName);
    Files.createDirectories(project);

    SortedSet<Prebuilt> libs = buckFiles.get(project);
    if (libs == null) {
      libs = new TreeSet<>();
      buckFiles.put(project, libs);
    }

    Prebuilt library = resolveLib(artifactToDownload, repoSys, session, project);
    libs.add(library);

    // Populate deps
    Iterable<Artifact> incoming = graph.getIncomingNodesFor(artifactToDownload);
    for (Artifact artifact : incoming) {
      String groupName = getProjectName(artifact);
      if (projectName.equals(groupName)) {
        library.addDep(String.format(":%s", artifact.getArtifactId()));
      } else {
        library.addDep(buckThirdPartyRelativePath, artifact);
      }
    }

    // Populate visibility
    Iterable<Artifact> outgoing = graph.getOutgoingNodesFor(artifactToDownload);
    for (Artifact artifact : outgoing) {
      String groupName = getProjectName(artifact);
      if (!groupName.equals(projectName)) {
        library.addVisibility(buckThirdPartyRelativePath, artifact);
      }
    }
  }

  private Prebuilt resolveLib(
      Artifact artifact,
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Path project) throws ArtifactResolutionException, IOException {
    Artifact jar = new DefaultArtifact(
        artifact.getGroupId(),
        artifact.getArtifactId(),
        "jar",
        artifact.getVersion());

    Path relativePath = resolveArtifact(jar, repoSys, session, project);

    Prebuilt library = new Prebuilt(jar.getArtifactId(), relativePath);

    downloadSources(jar, repoSys, session, project, library);
    return library;
  }

  /**
   * @return {@link Path} to the file
   */
  private Path resolveArtifact(
      Artifact artifact,
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Path project)
      throws ArtifactResolutionException, IOException {
    Optional<Path> newerVersionFile = getNewerVersionFile(artifact, project);
    if (newerVersionFile.isPresent()) {
      return newerVersionFile.get();
    }
    ArtifactResult result = repoSys.resolveArtifact(
        session,
        new ArtifactRequest(artifact, repos, null));
    return copy(result, project);
  }

  /**
   * @return {@link Path} to the file in {@code project} with filename consistent with the given
   * {@link Artifact}, but with a newer version. If no such file exists, {@link Optional#absent} is
   * returned. If multiple such files are present one with the newest version will be returned.
   */
  @VisibleForTesting
  Optional<Path> getNewerVersionFile(
      final Artifact artifactToDownload,
      Path project) throws IOException {
    final Version artifactToDownloadVersion;
    try {
      artifactToDownloadVersion = versionScheme.parseVersion(artifactToDownload.getVersion());
    } catch (InvalidVersionSpecificationException e) {
      throw Throwables.propagate(e);
    }

    final Pattern versionExtractor = Pattern.compile(
        String.format(
            ARTIFACT_FILE_NAME_REGEX_FORMAT,
            artifactToDownload.getArtifactId(),
            VERSION_REGEX_GROUP,
            artifactToDownload.getExtension()));
    Iterable<Version> versionsPresent = FluentIterable
        .from(Files.newDirectoryStream(project))
        .transform(new Function<Path, Version>() {
          @Nullable
          @Override
          public Version apply(Path input) {
            Matcher matcher = versionExtractor.matcher(input.getFileName().toString());
            if (matcher.matches()) {
              try {
                return versionScheme.parseVersion(matcher.group(1));
              } catch (InvalidVersionSpecificationException e) {
                throw Throwables.propagate(e);
              }
            } else {
              return null;
            }
          }
        })
        .filter(Predicates.notNull());

    List<Version> newestPresent = Ordering.natural().greatestOf(versionsPresent, 1);
    if (newestPresent.isEmpty() || newestPresent.get(0).compareTo(artifactToDownloadVersion) <= 0) {
      return Optional.absent();
    } else {
      return Optional.of(
          project.resolve(
              String.format(
                  ARTIFACT_FILE_NAME_FORMAT,
                  artifactToDownload.getArtifactId(),
                  newestPresent.get(0).toString(),
                  artifactToDownload.getExtension())));
    }
  }

  private void downloadSources(
      Artifact artifact,
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Path project,
      Prebuilt library) throws IOException {
    Artifact srcs = new SubArtifact(artifact, "sources", "jar");
    try {
      Path relativePath = resolveArtifact(srcs, repoSys, session, project);
      library.setSourceJar(relativePath);
    } catch (ArtifactResolutionException e) {
      System.err.println("Skipping sources for: " + srcs);
    }
  }

  /**
   *  com.example:foo:1.0 -> "example"
   */
  private static String getProjectName(Artifact artifact) {
    int index = artifact.getGroupId().lastIndexOf('.');
    String projectName = artifact.getGroupId();
    if (index != -1) {
      projectName = projectName.substring(index + 1);
    }
    return projectName;
  }

  private void createBuckFiles(Map<Path, SortedSet<Prebuilt>> buckFilesData) throws IOException {
    URL templateUrl = Resources.getResource(TEMPLATE);
    String template = Resources.toString(templateUrl, UTF_8);
    STGroupString groups = new STGroupString("prebuilt-template", template);

    for (Map.Entry<Path, SortedSet<Prebuilt>> entry : buckFilesData.entrySet()) {
      Path buckFile = entry.getKey().resolve("BUCK");
      if (Files.exists(buckFile)) {
        Files.delete(buckFile);
      }

      ST st = Preconditions.checkNotNull(groups.getInstanceOf("/prebuilts"));
      st.add("data", entry.getValue());
      Files.write(buckFile, st.render().getBytes(UTF_8));
    }
  }

  private Path copy(ArtifactResult result, Path destDir) throws IOException {
    Path source = result.getArtifact().getFile().toPath();
    Path sink = destDir.resolve(source.getFileName());

    if (!Files.exists(sink)) {
      Files.copy(source, sink);
    }

    return sink.getFileName();
  }

  private  MutableDirectedGraph<Artifact> buildDependencyGraph(
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Map<String, Artifact> knownDeps) throws ArtifactDescriptorException {
    MutableDirectedGraph<Artifact> graph;
    graph = new MutableDirectedGraph<>();
    for (Map.Entry<String, Artifact> entry : knownDeps.entrySet()) {
      String key = entry.getKey();
      Artifact artifact = entry.getValue();

      graph.addNode(artifact);

      List<Dependency> dependencies = getDependenciesOf(repoSys, session, artifact);

      for (Dependency dependency : dependencies) {
        if (dependency.getArtifact() == null) {
          System.out.println("Skipping because artifact missing: " + dependency);
          continue;
        }

        String depKey = buildKey(dependency.getArtifact());
        Artifact actualDep = knownDeps.get(depKey);
        if (actualDep == null) {
          continue;
        }
        // It's possible that the runtime dep of an artifact is the test time dep of another.
        if (isTestTime(dependency)) {
          continue;
        }

        // TODO(simons): Do we always want optional dependencies?
//        if (dependency.isOptional()) {
//          continue;
//        }

        Preconditions.checkNotNull(
            actualDep,
            key + " -> " + artifact + " in " + knownDeps.keySet());
        graph.addNode(actualDep);
        graph.addEdge(actualDep, artifact);
      }
    }
    return graph;
  }

  private List<Dependency> getDependenciesOf(
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Artifact dep) throws ArtifactDescriptorException {
    ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
    descriptorRequest.setArtifact(dep);
    descriptorRequest.setRepositories(repos);
    descriptorRequest.setRequestContext(JavaScopes.RUNTIME);

    ArtifactDescriptorResult result = repoSys.readArtifactDescriptor(session, descriptorRequest);
    return result.getDependencies();
  }

  private boolean isTestTime(Dependency dependency) {
    return TEST.equals(dependency.getScope());
  }

  private ImmutableMap<String, Artifact> getRunTimeTransitiveDeps(
      RepositorySystem repoSys,
      RepositorySystemSession session,
      String... mavenCoords)
      throws RepositoryException {

    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRequestContext(JavaScopes.RUNTIME);
    collectRequest.setRepositories(repos);

    for (String coord : mavenCoords) {
      DefaultArtifact artifact = new DefaultArtifact(coord);
      collectRequest.addDependency(new Dependency(artifact, JavaScopes.RUNTIME));

      ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
      descriptorRequest.setArtifact(artifact);
      // Setting this appears to have exactly zero effect on the returned values. *sigh*
//      descriptorRequest.setRequestContext(JavaScopes.RUNTIME);
      descriptorRequest.setRepositories(repos);
      ArtifactDescriptorResult descriptorResult = repoSys.readArtifactDescriptor(
          session,
          descriptorRequest);

      for (Dependency dependency : descriptorResult.getDependencies()) {
        if (isTestTime(dependency)) {
          continue;
        }
        collectRequest.addDependency(dependency);
      }
      for (Dependency dependency : descriptorResult.getManagedDependencies()) {
        if (isTestTime(dependency)) {
          continue;
        }
        collectRequest.addManagedDependency(dependency);
      }
    }

    DependencyFilter filter = DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME);
    DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, filter);

    DependencyResult dependencyResult = repoSys.resolveDependencies(session, dependencyRequest);

    ImmutableSortedMap.Builder<String, Artifact> knownDeps = ImmutableSortedMap.naturalOrder();
    for (ArtifactResult artifactResult : dependencyResult.getArtifactResults()) {
      Artifact node = artifactResult.getArtifact();
      knownDeps.put(buildKey(node), node);
    }
    return knownDeps.build();
  }

  private RepositorySystemSession newSession(RepositorySystem repoSys) {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

    session.setLocalRepositoryManager(repoSys.newLocalRepositoryManager(session, localRepo));
    session.setReadOnly();

    return session;
  }

  /**
   * Construct a key to identify the artifact, less its version
   */
  private String buildKey(Artifact artifact) {
    return artifact.getGroupId() +
        ':' + artifact.getArtifactId() +
        ':' + artifact.getExtension() +
        ':' + artifact.getClassifier();
  }

  public static void main(String[] args) throws RepositoryException, IOException {
    if (args.length < 5) {
      System.err.println("Usage: java -jar resolver.jar buck-repo third-party " +
              "maven-local-repo maven-url junit:junit:jar:4.12...");
      System.exit(1);
    }

    Path buckRepoRoot = Paths.get(args[0]);
    Path thirdParty = Paths.get(args[1]);
    Path m2 = Paths.get(args[2]);
    String mavenCentral = args[3];
    String[] coords = Arrays.copyOfRange(args, 4, args.length);

    new Resolver(
        buckRepoRoot,
        thirdParty,
        m2,
        mavenCentral)
        .resolve(coords);
  }

  /**
   * Holds data for creation of a BUCK file for a given dependency
   */
  private static class Prebuilt implements Comparable<Prebuilt> {

    private final String name;
    private final Path binaryJar;
    private Path sourceJar;
    private final SortedSet<String> deps = new TreeSet<>(new BuckDepComparator());
    private final SortedSet<String> visibilities = new TreeSet<>(new BuckDepComparator());

    public Prebuilt(String name, Path binaryJar) {
      this.name = name;
      this.binaryJar = binaryJar;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public String getName() {
      return name;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public Path getBinaryJar() {
      return binaryJar;
    }

    public void setSourceJar(Path sourceJar) {
      this.sourceJar = sourceJar;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public Path getSourceJar() {
      return sourceJar;
    }

    public void addDep(String dep) {
      this.deps.add(dep);
    }

    public void addDep(Path buckThirdPartyRelativePath, Artifact artifact) {
      this.addDep(formatDep(buckThirdPartyRelativePath, artifact));
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public SortedSet<String> getDeps() {
      return deps;
    }

    public void addVisibility(String dep) {
      this.visibilities.add(dep);
    }
    public void addVisibility(Path buckThirdPartyRelativePath, Artifact artifact) {
      this.addVisibility(formatDep(buckThirdPartyRelativePath, artifact));
    }

    private String formatDep(Path buckThirdPartyRelativePath, Artifact artifact) {
      return String.format(
          "//%s/%s:%s",
          buckThirdPartyRelativePath,
          getProjectName(artifact),
          artifact.getArtifactId());
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public SortedSet<String> getVisibility() {
      return visibilities;
    }

    @Override
    public int compareTo(Prebuilt that) {
      return this.name.compareTo(that.name);
    }
  }
}
