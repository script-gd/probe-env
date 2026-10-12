#include "minicoro.h"
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>

#define CHECK(x) do { if (!(x)) abort(); } while (0)
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready = PTHREAD_COND_INITIALIZER;
static int arrivals;

static void body(mco_coro *co) {
    CHECK(mco_running() == co);
    CHECK(pthread_mutex_lock(&mutex) == 0);
    arrivals++;
    if (arrivals == 2) CHECK(pthread_cond_broadcast(&ready) == 0);
    while (arrivals < 2) CHECK(pthread_cond_wait(&ready, &mutex) == 0);
    CHECK(pthread_mutex_unlock(&mutex) == 0);
    CHECK(mco_running() == co);
    CHECK(mco_yield(co) == MCO_SUCCESS);
    CHECK(mco_running() == co);
}

static void *worker(void *unused) {
    (void)unused;
    mco_coro *co = NULL;
    mco_desc desc = mco_desc_init(body, 0);
    CHECK(mco_running() == NULL);
    CHECK(mco_create(&co, &desc) == MCO_SUCCESS);
    CHECK(mco_resume(co) == MCO_SUCCESS);
    CHECK(mco_running() == NULL);
    CHECK(mco_status(co) == MCO_SUSPENDED);
    CHECK(mco_resume(co) == MCO_SUCCESS);
    CHECK(mco_status(co) == MCO_DEAD);
    CHECK(mco_destroy(co) == MCO_SUCCESS);
    return NULL;
}

int main(void) {
    pthread_t threads[2];
    for (int i = 0; i < 2; i++) CHECK(pthread_create(&threads[i], NULL, worker, NULL) == 0);
    for (int i = 0; i < 2; i++) CHECK(pthread_join(threads[i], NULL) == 0);
    puts("COROUTINE_TLS_PASS");
    return 0;
}
