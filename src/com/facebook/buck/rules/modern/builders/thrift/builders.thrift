namespace java com.facebook.buck.rules.modern.builders.thrift

# These structures are the thrift equivalent of Bazel's remote execution protos.
# See https://docs.google.com/document/d/1AaGk7fOPByEvpAbqeXIyE8HX_A3_axxNnvroblTZ_6s/preview

# The content addressed storage maps Digests to byte[] (e.g. file contents, serialized struct).
struct Digest {
  1: string hash;
  2: i64 size;
}

struct Status {
  1: i32 code;
  2: string message;
}

struct Directory {
  1: list<FileNode> files;
  2: list<DirectoryNode> directories;
  3: list<SymlinkNode> symlinks;
}

struct FileNode {
  1: string name;
  2: Digest digest;
  3: bool isExecutable;
}

struct DirectoryNode {
  1: string name;
  2: Digest digest;
}

struct SymlinkNode {
  1: string name;
  2: string target;
}

struct OutputFile {
  1: string path;
  2: Digest digest;
  3: binary content; # optional inlined content.
  4: bool isExecutable;
}

struct OutputDirectory {
  1: string path;
  // 2: deprecated
  3: Digest treeDigest;
}

# A tree is a "flattened" merkle directory tree.
struct Tree {
  1: Directory root;
  # children contains all transitive subdirectories of root.
  2: list<Directory> children;
}

struct ActionResult {
  // 1: deprecated
  2: list<OutputFile> outputFiles;
  3: list<OutputDirectory> outputDirectories;
  4: i32 exitCode;
  5: binary stdoutRaw; # optional inlined stdout.
  6: Digest stdoutDigest;
  7: binary stderrRaw; # optional inlined stderr.
  8: Digest stderrDigest;
}

struct EnvironmentVariable {
  1: string name;
  2: string value;
}

struct Command {
  1: list<string> arguments;
  2: list<EnvironmentVariable> environmentVariables;
}