#include <stdlib.h>
#include <stdio.h>
#include <string.h>

int do_get_signature(int, char**);
int do_mkdir_p(int, char**);
int do_receive_file(int, char**);

int main(int argc, char *argv[]) {
  if (argc < 2) {
    fprintf(stderr, "No command specified\n");
    exit(2);
  }

  const char* command = argv[1];
  char** user_args = argv + 2;
  int count_user_args = argc - 2;

  int retcode;
  if (strcmp(command, "get-signature") == 0) {
    retcode = do_get_signature(count_user_args, user_args);
  } else if (strcmp(command, "receive-file") == 0) {
    retcode = do_receive_file(count_user_args, user_args);
  } else if (strcmp(command, "mkdir-p") == 0) {
    retcode = do_mkdir_p(count_user_args, user_args);
  } else {
    fprintf(stderr, "Unknown command: %s", command);
    retcode = 1;
  }
  fflush(stderr);
  exit(retcode);
}
