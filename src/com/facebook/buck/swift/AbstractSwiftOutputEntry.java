package com.facebook.buck.swift;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.immutables.value.Value;

import java.nio.file.Path;

@Value.Immutable
@BuckStyleImmutable
@JsonSerialize(as = SwiftOutputEntry.class)
abstract class AbstractSwiftOutputEntry {

  @JsonIgnore
  @Value.Parameter
  public abstract Path outputPath();

  @JsonIgnore
  @Value.Parameter
  public abstract Path source();

  @JsonProperty("swiftmodule")
  @Value.Default
  public Path getSwiftModule() {
    return appendSuffix("~partial.swiftmodule");
  }

  @JsonProperty("object")
  @Value.Default
  public Path getObject() {
    return appendSuffix(".o");
  }

  @JsonProperty("llvm-bc")
  @Value.Default
  public Path getLLVMBitCode() {
    return appendSuffix(".bc");
  }

  @JsonProperty("diagnostics")
  @Value.Default
  public Path getDiagnostics() {
    return appendSuffix(".dia");
  }

  @JsonProperty("dependencies")
  @Value.Default
  public Path getDependencies() {
    return appendSuffix(".d");
  }

  @JsonProperty("swift-dependencies")
  @Value.Default
  public Path getSwiftDependencies() {
    return appendSuffix(".swiftdeps");
  }

  private Path appendSuffix(String suffix) {
    return outputPath()
            .resolve(MorePaths.getNameWithoutExtension(source()) + suffix);
  }
}
