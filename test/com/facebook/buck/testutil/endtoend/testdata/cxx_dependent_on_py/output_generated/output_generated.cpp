#include "output_generated.h"

#include <iostream>

#include <generate_cpp/generated.h>

using namespace std;

void output_generated() {
    Generated generated;
    cout << generated.generated_fcn() << endl;
}
