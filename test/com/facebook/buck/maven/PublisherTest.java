/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.eclipse.aether.deployment.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Paths;

public class PublisherTest {

  private static final String MVN_COORDS_A = "com.facebook.buck.maven:a:jar:42";
  private static final String MVN_COORDS_B = "com.facebook.buck.maven:b:jar:10";

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private Publisher publisher;
  private BuildRuleResolver ruleResolver;

  @Before
  public void setUp() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    publisher = new Publisher(
        filesystem,
        /* remoteRepoUrl */ Optional.absent(),
        /* dryRun */ true);

    ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());
  }

  @Test
  public void errorRaisedForDuplicateFirstOrderDeps()
      throws DeploymentException, NoSuchBuildTargetException {
    // Construct a graph that looks like this.  A and B have maven coordinates set.
    // A   B
    //  \ /
    //   C
    BuildTarget publishableTargetA = BuildTargetFactory.newInstance("//:a")
        .withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget publishableTargetB = BuildTargetFactory.newInstance("//:b")
        .withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget targetC = BuildTargetFactory.newInstance("//:c");

    JavaLibraryBuilder.createBuilder(targetC)
        .addSrc(Paths.get("c.java"))
        .build(ruleResolver);
    MavenPublishable publishableA = (MavenPublishable) JavaLibraryBuilder
        .createBuilder(publishableTargetA)
        .addSrc(Paths.get("a.java"))
        .setMavenCoords(MVN_COORDS_A)
        .addDep(targetC)
        .build(ruleResolver);
    MavenPublishable publishableB = (MavenPublishable) JavaLibraryBuilder
        .createBuilder(publishableTargetB)
        .addSrc(Paths.get("b.java"))
        .setMavenCoords(MVN_COORDS_B)
        .addDep(targetC)
        .build(ruleResolver);


    expectedException.expect(DeploymentException.class);
    expectedException.expectMessage(
        Matchers.containsString(targetC
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(publishableTargetA
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(publishableTargetB
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));

    publisher.publish(ImmutableSet.of(publishableA, publishableB));
  }

  @Test
  public void errorRaisedForDuplicateFirstOrderAndTransitiveDep()
      throws DeploymentException, NoSuchBuildTargetException {
    // Construct a graph that looks like this.  A and B have maven coordinates set.
    // A   B
    // |   |
    // C   |
    //  \ /
    //   D
    BuildTarget publishableTargetA = BuildTargetFactory.newInstance("//:a")
        .withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget publishableTargetB = BuildTargetFactory.newInstance("//:b")
        .withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget targetC = BuildTargetFactory.newInstance("//:c");
    BuildTarget targetD = BuildTargetFactory.newInstance("//:d");

    JavaLibraryBuilder.createBuilder(targetD)
        .addSrc(Paths.get("d.java"))
        .build(ruleResolver);
    JavaLibraryBuilder.createBuilder(targetC)
        .addSrc(Paths.get("c.java"))
        .addDep(targetD)
        .build(ruleResolver);
    MavenPublishable publishableA = (MavenPublishable) JavaLibraryBuilder
        .createBuilder(publishableTargetA)
        .addSrc(Paths.get("a.java"))
        .setMavenCoords(MVN_COORDS_A)
        .addDep(targetC)
        .build(ruleResolver);
    MavenPublishable publishableB = (MavenPublishable) JavaLibraryBuilder
        .createBuilder(publishableTargetB)
        .addSrc(Paths.get("b.java"))
        .setMavenCoords(MVN_COORDS_B)
        .addDep(targetD)
        .build(ruleResolver);


    expectedException.expect(DeploymentException.class);
    expectedException.expectMessage(
        Matchers.containsString(targetD
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(publishableTargetA
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(publishableTargetB
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));

    publisher.publish(ImmutableSet.of(publishableA, publishableB));  }

  @Test
  public void errorRaisedForDuplicateTransitiveDeps()
      throws DeploymentException, NoSuchBuildTargetException {
    // Construct a graph that looks like this.  A and B have maven coordinates set.
    // A   B
    // |   |
    // C   D
    //  \ /
    //   E
    BuildTarget publishableTargetA = BuildTargetFactory.newInstance("//:a")
        .withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget publishableTargetB = BuildTargetFactory.newInstance("//:b")
        .withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget targetC = BuildTargetFactory.newInstance("//:c");
    BuildTarget targetD = BuildTargetFactory.newInstance("//:d");
    BuildTarget targetE = BuildTargetFactory.newInstance("//:e");

    JavaLibraryBuilder.createBuilder(targetE)
        .addSrc(Paths.get("e.java"))
        .build(ruleResolver);
    JavaLibraryBuilder.createBuilder(targetC)
        .addSrc(Paths.get("c.java"))
        .addDep(targetE)
        .build(ruleResolver);
    JavaLibraryBuilder.createBuilder(targetD)
        .addSrc(Paths.get("d.java"))
        .addDep(targetE)
        .build(ruleResolver);
    MavenPublishable publishableA = (MavenPublishable) JavaLibraryBuilder
        .createBuilder(publishableTargetA)
        .addSrc(Paths.get("a.java"))
        .setMavenCoords(MVN_COORDS_A)
        .addDep(targetC)
        .build(ruleResolver);
    MavenPublishable publishableB = (MavenPublishable) JavaLibraryBuilder
        .createBuilder(publishableTargetB)
        .addSrc(Paths.get("b.java"))
        .setMavenCoords(MVN_COORDS_B)
        .addDep(targetD)
        .build(ruleResolver);


    expectedException.expect(DeploymentException.class);
    expectedException.expectMessage(
        Matchers.containsString(targetE
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(publishableTargetA
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(publishableTargetB
            .getUnflavoredBuildTarget()
            .getFullyQualifiedName()));

    publisher.publish(ImmutableSet.of(publishableA, publishableB));  }
}
