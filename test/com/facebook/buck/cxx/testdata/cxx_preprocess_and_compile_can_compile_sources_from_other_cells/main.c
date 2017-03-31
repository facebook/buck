#include <stdio.h>
#include <library/exported_file.h>

int main(int argc, char *argv[]) {
  puts(exported_file_function());
  return 0;
}
