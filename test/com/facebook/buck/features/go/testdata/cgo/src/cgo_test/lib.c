#include "lib.h"

#include <stdlib.h>
#include <stdio.h>


void simple_hello(void) {
	printf("simple_hello()\n");
}

void print_int(int i) {
	printf("print_int(%d)\n", i);
}

char *complex_func(char *s) {
	printf("complex_func(%s)\n", s);

	char *buf = malloc(150);
	sprintf(buf, "%s", s);
	return buf;
}
