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

package com.facebook.buck.jvm.java;

import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.immutables.BuckStyleStep;
import com.google.common.base.Joiner;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import difflib.DiffUtils;
import difflib.Patch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.immutables.value.Value;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

@Value.Immutable
@BuckStyleStep
abstract class AbstractDiffAbisStep implements Step {
  private static final Logger LOG = Logger.get(AbstractDiffAbisStep.class);

  @Value.Parameter
  protected abstract Path getClassAbiPath();

  @Value.Parameter
  protected abstract Path getSourceAbiPath();

  @Value.Parameter
  protected abstract JavaBuckConfig.SourceAbiVerificationMode getVerificationMode();

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    File classAbiJarFile = getClassAbiPath().toFile();
    File sourceAbiJarFile = getSourceAbiPath().toFile();
    boolean filesEqual = Files.equal(classAbiJarFile, sourceAbiJarFile);

    if (filesEqual) {
      return StepExecutionResult.SUCCESS;
    }

    List<String> classAbiDump = dumpAbiJar(classAbiJarFile);
    List<String> sourceAbiDump = dumpAbiJar(sourceAbiJarFile);

    Patch<String> diff = DiffUtils.diff(classAbiDump, sourceAbiDump);
    List<String> unifiedDiff =
        DiffUtils.generateUnifiedDiff(
            classAbiJarFile.getAbsolutePath(),
            sourceAbiJarFile.getAbsolutePath(),
            classAbiDump,
            diff,
            4);

    String message = String.format("Files differ:\n%s", Joiner.on('\n').join(unifiedDiff));
    JavaBuckConfig.SourceAbiVerificationMode verificationMode = getVerificationMode();
    switch (verificationMode) {
      case OFF:
        return StepExecutionResult.SUCCESS;
      case LOG:
        LOG.warn(message);
        return StepExecutionResult.SUCCESS;
      case FAIL:
        return StepExecutionResult.ERROR.withStderr(message);
      default:
        throw new AssertionError(String.format("Unknown verification mode: %s", verificationMode));
    }
  }

  private List<String> dumpAbiJar(File abiJarFile) {
    List<String> result = new ArrayList<>();
    result.add("File order:");
    try (JarFile abiJar = new JarFile(abiJarFile)) {
      abiJar.stream().map(JarEntry::toString).forEach(result::add);

      result.add("");
      abiJar
          .stream()
          .flatMap(
              entry ->
                  Stream.concat(
                      Stream.of(String.format("%s:", entry.getName())),
                      Stream.concat(dumpFile(abiJar, entry), Stream.of(""))))
          .forEach(result::add);
    } catch (IOException e) {
      try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
          PrintWriter pw = new PrintWriter(bos)) {
        e.printStackTrace(pw);
        result.add(new String(bos.toByteArray(), StandardCharsets.UTF_8));
      } catch (IOException e2) {
        throw new RuntimeException(e2);
      }
    }

    return result;
  }

  private static Stream<String> dumpFile(JarFile file, JarEntry entry) {
    try {
      String fileName = entry.getName();
      if (fileName.endsWith(".class")) {
        return dumpClassFile(file, entry);
      } else if (fileName.equals(JarFile.MANIFEST_NAME)) {
        return dumpTextFile(file, entry);
      } else {
        return dumpBinaryFile(file, entry);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Stream<String> dumpClassFile(JarFile file, JarEntry entry) throws IOException {

    byte[] textifiedClass;
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(bos);
        InputStream inputStream = file.getInputStream(entry)) {
      ClassReader reader = new ClassReader(inputStream);
      TraceClassVisitor traceVisitor = new TraceClassVisitor(null, new Textifier(), pw);
      reader.accept(traceVisitor, 0);
      textifiedClass = bos.toByteArray();
    }

    try (InputStreamReader streamReader =
        new InputStreamReader(new ByteArrayInputStream(textifiedClass))) {
      return CharStreams.readLines(streamReader).stream();
    }
  }

  private static Stream<String> dumpTextFile(JarFile file, JarEntry entry) throws IOException {
    try (InputStreamReader streamReader = new InputStreamReader(file.getInputStream(entry))) {
      return CharStreams.readLines(streamReader).stream();
    }
  }

  private static Stream<String> dumpBinaryFile(JarFile file, JarEntry entry) throws IOException {
    try (HashingInputStream is =
        new HashingInputStream(Hashing.murmur3_128(), file.getInputStream(entry))) {
      ByteStreams.exhaust(is);
      return Stream.of(String.format("Murmur3-128: %s", entry.getName(), is.hash().toString()));
    }
  }

  @Override
  public String getShortName() {
    return "diff_abi_jars";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("diff %s %s", getClassAbiPath(), getSourceAbiPath());
  }
}
