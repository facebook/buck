#include <unistd.h>
#include "test2.h"

int main()
{
  while (1) {
    sleep(sleep_time());
  }
  return 0;
}
