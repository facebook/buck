#include <stdio.h>

extern "C" {

#include "clib/cc/cc.h"

void cc_print() {
    fflush(stdout);
    printf("cc_print: Hi there!\n");
    fflush(stdout);
}

}

