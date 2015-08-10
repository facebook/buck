#include <stdlib.h>

int* func_ret_null() {
	return NULL;
}

void func_bad() {
	int *p = func_ret_null();
	*p += 1;
}
