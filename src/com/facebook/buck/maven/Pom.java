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

import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.jvm.core.HasMavenCoordinates;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.maven.model.Build;
import org.apache.maven.model.CiManagement;
import org.apache.maven.model.Contributor;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Developer;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.IssueManagement;
import org.apache.maven.model.License;
import org.apache.maven.model.MailingList;
import org.apache.maven.model.Model;
import org.apache.maven.model.Organization;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Prerequisites;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.Scm;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

public class Pom {

  private static final MavenXpp3Writer POM_WRITER = new MavenXpp3Writer();
  private static final DefaultModelBuilderFactory MODEL_BUILDER_FACTORY =
      new DefaultModelBuilderFactory();
  /** Consistent with the value used in the implementation of {@link MavenXpp3Writer#write} */
  private static final String POM_MODEL_VERSION = "4.0.0";

  private final Model model;
  private final MavenPublishable publishable;
  private final SourcePathResolver pathResolver;
  private final Path path;

  public Pom(SourcePathResolver pathResolver, Path path, MavenPublishable buildRule) {
    this.pathResolver = pathResolver;
    this.path = path;
    this.publishable = buildRule;
    this.model = constructModel();
    applyBuildRule();
  }

  public static Path generatePomFile(SourcePathResolver pathResolver, MavenPublishable rule)
      throws IOException {
    Path pom = getPomPath(rule);
    Files.deleteIfExists(pom);
    generatePomFile(pathResolver, rule, pom);
    return pom;
  }

  private static Path getPomPath(HasMavenCoordinates rule) {
    return rule.getProjectFilesystem()
        .resolve(
            BuildTargetPaths.getGenPath(
                rule.getProjectFilesystem(), rule.getBuildTarget(), "%s.pom"));
  }

  @VisibleForTesting
  static void generatePomFile(
      SourcePathResolver pathResolver, MavenPublishable rule, Path optionallyExistingPom)
      throws IOException {
    new Pom(pathResolver, optionallyExistingPom, rule).flushToFile();
  }

  private void applyBuildRule() {
    if (!HasMavenCoordinates.isMavenCoordsPresent(publishable)) {
      throw new IllegalArgumentException(
          "Cannot retrieve maven coordinates for target"
              + publishable.getBuildTarget().getFullyQualifiedName());
    }
    DefaultArtifact artifact = new DefaultArtifact(getMavenCoords(publishable).get());

    Iterable<Artifact> deps =
        FluentIterable.from(publishable.getMavenDeps())
            .filter(HasMavenCoordinates::isMavenCoordsPresent)
            .transform(input -> new DefaultArtifact(input.getMavenCoords().get()));

    updateModel(artifact, deps);
  }

  private Model constructModel(File file, Model model) {
    ModelBuilder modelBuilder = MODEL_BUILDER_FACTORY.newInstance();

    try {
      ModelBuildingRequest req = new DefaultModelBuildingRequest().setPomFile(file);
      ModelBuildingResult modelBuildingResult = modelBuilder.build(req);

      Model constructed = Preconditions.checkNotNull(modelBuildingResult.getRawModel());

      return merge(model, constructed);
    } catch (ModelBuildingException e) {
      throw new RuntimeException(e);
    }
  }

  private Model constructModel() {
    File file = path.toFile();
    Model model = new Model();
    model.setModelVersion(POM_MODEL_VERSION);

    if (publishable.getPomTemplate().isPresent()) {
      model =
          constructModel(
              pathResolver.getAbsolutePath(publishable.getPomTemplate().get()).toFile(), model);
    }

    if (file.isFile()) {
      model = constructModel(file, model);
    }

    return model;
  }

  private Model merge(Model first, @Nullable Model second) {
    if (second == null) {
      return first;
    }

    Model model = first.clone();

    // ---- Values from ModelBase

    List<String> modules = second.getModules();
    if (modules != null) {
      for (String module : modules) {
        model.addModule(module);
      }
    }

    DistributionManagement distributionManagement = second.getDistributionManagement();
    if (distributionManagement != null) {
      model.setDistributionManagement(distributionManagement);
    }

    Properties properties = second.getProperties();
    if (properties != null) {
      for (Map.Entry<Object, Object> entry : properties.entrySet()) {
        model.addProperty((String) entry.getKey(), (String) entry.getValue());
      }
    }

    DependencyManagement dependencyManagement = second.getDependencyManagement();
    if (dependencyManagement != null) {
      model.setDependencyManagement(dependencyManagement);
    }

    List<Dependency> dependencies = second.getDependencies();
    if (dependencies != null) {
      for (Dependency dependency : dependencies) {
        model.addDependency(dependency);
      }
    }

    List<Repository> repositories = second.getRepositories();
    if (repositories != null) {
      for (Repository repository : repositories) {
        model.addRepository(repository);
      }
    }

    List<Repository> pluginRepositories = second.getPluginRepositories();
    if (pluginRepositories != null) {
      for (Repository pluginRepository : pluginRepositories) {
        model.addPluginRepository(pluginRepository);
      }
    }

    // Ignore reports, reporting, and locations

    // ----- From Model
    Parent parent = second.getParent();
    if (parent != null) {
      model.setParent(parent);
    }

    Organization organization = second.getOrganization();
    if (organization != null) {
      model.setOrganization(organization);
    }

    List<License> licenses = second.getLicenses();
    Set<String> currentLicenseUrls = new HashSet<>();
    if (model.getLicenses() != null) {
      for (License license : model.getLicenses()) {
        currentLicenseUrls.add(license.getUrl());
      }
    }
    if (licenses != null) {
      for (License license : licenses) {
        if (!currentLicenseUrls.contains(license.getUrl())) {
          model.addLicense(license);
          currentLicenseUrls.add(license.getUrl());
        }
      }
    }

    List<Developer> developers = second.getDevelopers();
    Set<String> currentDevelopers = new HashSet<>();
    if (model.getDevelopers() != null) {
      for (Developer developer : model.getDevelopers()) {
        currentDevelopers.add(developer.getName());
      }
    }
    if (developers != null) {
      for (Developer developer : developers) {
        if (!currentDevelopers.contains(developer.getName())) {
          model.addDeveloper(developer);
          currentDevelopers.add(developer.getName());
        }
      }
    }

    List<Contributor> contributors = second.getContributors();
    Set<String> currentContributors = new HashSet<>();
    if (model.getContributors() != null) {
      for (Contributor contributor : model.getContributors()) {
        currentDevelopers.add(contributor.getName());
      }
    }
    if (contributors != null) {
      for (Contributor contributor : contributors) {
        if (!currentContributors.contains(contributor.getName())) {
          model.addContributor(contributor);
          currentContributors.add(contributor.getName());
        }
      }
    }

    List<MailingList> mailingLists = second.getMailingLists();
    if (mailingLists != null) {
      for (MailingList mailingList : mailingLists) {
        model.addMailingList(mailingList);
      }
    }

    Prerequisites prerequisites = second.getPrerequisites();
    if (prerequisites != null) {
      model.setPrerequisites(prerequisites);
    }

    Scm scm = second.getScm();
    if (scm != null) {
      model.setScm(scm);
    }

    String url = second.getUrl();
    if (url != null) {
      model.setUrl(url);
    }

    String description = second.getDescription();
    if (description != null) {
      model.setDescription(description);
    }

    IssueManagement issueManagement = second.getIssueManagement();
    if (issueManagement != null) {
      model.setIssueManagement(issueManagement);
    }

    CiManagement ciManagement = second.getCiManagement();
    if (ciManagement != null) {
      model.setCiManagement(ciManagement);
    }

    Build build = second.getBuild();
    if (build != null) {
      model.setBuild(build);
    }

    List<Profile> profiles = second.getProfiles();
    Set<String> currentProfileIds = new HashSet<>();
    if (model.getProfiles() != null) {
      for (Profile profile : model.getProfiles()) {
        currentProfileIds.add(profile.getId());
      }
    }
    if (profiles != null) {
      for (Profile profile : profiles) {
        if (!currentProfileIds.contains(profile.getId())) {
          model.addProfile(profile);
          currentProfileIds.add(profile.getId());
        }
      }
    }

    return model;
  }

  private void updateModel(Artifact mavenCoordinates, Iterable<Artifact> deps) {
    model.setGroupId(mavenCoordinates.getGroupId());
    model.setArtifactId(mavenCoordinates.getArtifactId());
    model.setVersion(mavenCoordinates.getVersion());
    if (Strings.isNullOrEmpty(model.getName())) {
      model.setName(mavenCoordinates.getArtifactId()); // better than nothing
    }

    // Dependencies
    ImmutableMap<DepKey, Dependency> depIndex =
        Maps.uniqueIndex(getModel().getDependencies(), DepKey::new);
    for (Artifact artifactDep : deps) {
      DepKey key = new DepKey(artifactDep);
      Dependency dependency = depIndex.get(key);
      if (dependency == null) {
        dependency = key.createDependency();
        getModel().addDependency(dependency);
      }
      updateDependency(dependency, artifactDep);
    }
  }

  private static void updateDependency(Dependency dependency, Artifact providedMavenCoordinates) {
    dependency.setVersion(providedMavenCoordinates.getVersion());
    dependency.setClassifier(providedMavenCoordinates.getClassifier());
  }

  public void flushToFile() throws IOException {
    getModel(); // Ensure model is initialized, reading file if necessary
    Files.createDirectories(getPath().getParent());
    try (Writer writer = Files.newBufferedWriter(getPath(), StandardCharsets.UTF_8)) {
      POM_WRITER.write(writer, getModel());
    }
  }

  private static Optional<String> getMavenCoords(BuildRule buildRule) {
    if (buildRule instanceof HasMavenCoordinates) {
      return ((HasMavenCoordinates) buildRule).getMavenCoords();
    }
    return Optional.empty();
  }

  public Model getModel() {
    return model;
  }

  public Path getPath() {
    return path;
  }

  private static final class DepKey {
    private final String groupId;
    private final String artifactId;

    public DepKey(Artifact artifact) {
      groupId = artifact.getGroupId();
      artifactId = artifact.getArtifactId();
      validate();
    }

    public DepKey(Dependency dependency) {
      groupId = dependency.getGroupId();
      artifactId = dependency.getArtifactId();
      validate();
    }

    private void validate() {
      Preconditions.checkNotNull(groupId);
      Preconditions.checkNotNull(artifactId);
    }

    public Dependency createDependency() {
      Dependency dependency = new Dependency();
      dependency.setGroupId(groupId);
      dependency.setArtifactId(artifactId);
      return dependency;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof DepKey)) {
        return false;
      }

      DepKey depKey = (DepKey) o;

      return Objects.equals(groupId, depKey.groupId)
          && Objects.equals(artifactId, depKey.artifactId);
    }

    @Override
    public int hashCode() {
      int result = groupId.hashCode();
      result = 31 * result + artifactId.hashCode();
      return result;
    }
  }
}
