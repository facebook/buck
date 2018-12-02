/*
 * Copyright 2018-present Facebook, Inc.
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
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

int do_get_signature(int, char**);
int do_mkdir_p(int, char**);
int do_multi_receive_file(int, char**);

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
  } else if (strcmp(command, "multi-receive-file") == 0) {
    retcode = do_multi_receive_file(count_user_args, user_args);
  } else if (strcmp(command, "mkdir-p") == 0) {
    retcode = do_mkdir_p(count_user_args, user_args);
  } else {
    fprintf(stderr, "Unknown command: %s", command);
    retcode = 1;
  }
  fflush(stderr);
  exit(retcode);
}
