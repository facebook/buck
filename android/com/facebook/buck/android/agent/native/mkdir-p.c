#include <stdlib.h>

#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>


// Returns 0 on success.
int mkdir_p(const char* orig_path) {
  size_t path_len = strlen(orig_path);
  if (path_len > PATH_MAX) {
    fprintf(stderr, "Path too long.\n");
    return 1;
  }

  char path_buf[PATH_MAX + 1];
  // Start of the current path component.
  char* cur = path_buf;
  // Finds the of the current path component.
  char* end = path_buf + 1;
  memcpy(path_buf, orig_path, path_len + 1);

  // For each path component
  for (;;) {
    // Find the end of the component.
    while (*end != '\0' && *end != '/') {
      end++;
    }
    // Note whether this is the last component, then null-terminate it.
    int last_component = (*end == '\0');
    *end = '\0';

    // Make the directory, then cd into it.
    int ret = mkdir(cur, 0755);
    if (ret != 0 && errno != EEXIST) {
      fprintf(stderr, "mkdir(%s) failed: %s\n", cur, strerror(errno));
      return ret;
    }
    ret = chdir(cur);
    if (ret != 0) {
      fprintf(stderr, "chdir(%s) failed: %s\n", cur, strerror(errno));
      return ret;
    }

    if (last_component) {
      return 0;
    }
    cur = end + 1;
    end = end + 1;
  }
}


int do_mkdir_p(int num_args, char** args) {
  if (num_args != 1 || args[0][0] == '\0') {
    fprintf(stderr, "usage: mkdir-p PATH\n");
    return 1;
  }

  return mkdir_p(args[0]);
}
