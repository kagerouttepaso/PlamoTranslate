#pragma once

#ifdef __ANDROID__
#include <sched.h>

inline int allow_all_cpus() {
    cpu_set_t mask;
    CPU_ZERO(&mask);
    for (int i = 0; i < CPU_SETSIZE; ++i) CPU_SET(i, &mask);
    return sched_setaffinity(0, sizeof(mask), &mask);
}
#endif
