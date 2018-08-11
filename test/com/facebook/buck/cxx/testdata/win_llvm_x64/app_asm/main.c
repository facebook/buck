#include <stdio.h>
__int64 add_by_ref(int a, int b, __int64 *r);

int main(){
  __int64 z = 0;
  add_by_ref(21, 21, &z);
  printf("%lld", z);
  return 0;
}
