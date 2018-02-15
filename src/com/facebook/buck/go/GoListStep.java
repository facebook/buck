package com.facebook.buck.go;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class GoListStep extends ShellStep {
  enum FileType {
    GoFiles,
    CgoFiles,
    SFiles,
    HFiles,
    TestGoFiles,
    XTestGoFiles
  }

  private final GoToolchain goToolchain;
  private final List<FileType> fileTypes;

  public GoListStep(
      BuildTarget buildTarget,
      Path workingDirectory,
      GoToolchain goToolchain,
      List<FileType> fileTypes) {
    super(Optional.of(buildTarget), workingDirectory);
    this.goToolchain = goToolchain;
    this.fileTypes = fileTypes;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder =
        ImmutableList.<String>builder()
            .add(goToolchain.getGoRoot().resolve("bin").resolve("go").toString())
            .add("list")
            .add("-f")
            .add(
                String.join(
                    ":",
                    fileTypes
                        .stream()
                        .map(fileType -> "{{join ." + fileType.name() + " \":\"}}")
                        .collect(Collectors.toList())));

    return commandBuilder.build();
  }

  @Override
  public String getShortName() {
    return "go list";
  }

  @Override
  protected void addOptions(ImmutableSet.Builder<Option> options) {
    super.addOptions(options);
    options.add(Option.EXPECTING_STD_OUT);
  }

  public Set<Path> getSourceFiles() {
    String stdout = getStdout();
    return Arrays.stream(stdout.trim().split(":"))
        .map(workingDirectory::resolve)
        .collect(Collectors.toSet());
  }
}
