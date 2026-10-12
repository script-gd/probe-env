/*
 * Cross-DSO Godot-style hot-reload host.
 *
 * The host owns the ONLY fake engine instance (`fake_engine.c` is included here,
 * never into a generation image): its process-wide Godot heap, its Engine
 * singleton binding table (holding the cross-generation hub), and the retained
 * custom Callables whose callbacks live in an anonymous executable thunk page.
 *
 * It reproduces the Godot 4.5.2 GDExtensionManager::reload_extension sequence:
 *   prepare (retain the Callable) -> deinitialize CORE/SCENE -> clear only the
 *   extension-token instance bindings (the anchor token stays) -> dlclose ->
 *   prove RTLD_NOLOAD reports the old image gone -> dlopen the next image
 *   RTLD_NOW|RTLD_LOCAL -> initialize -> use the callable again.
 *
 * The host deliberately does NOT link `godot_binding.c` / `gdcc_hrx.c`; it
 * releases Callables directly through `fake_callable_custom_release`.
 */

#define _GNU_SOURCE

#include "fake_engine.c" /* host-only fake engine; no gdcc_hrx.c here */

#include "reload_api.h"

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef GDExtensionBool (*reload_entry_fn)(
        GDExtensionInterfaceGetProcAddress,
        GDExtensionClassLibraryPtr,
        GDExtensionInitialization *);
typedef void *(*reload_hub_fn)(void);
typedef unsigned (*reload_thunk_offset_fn)(int kind);
typedef unsigned (*reload_bundle_bytes_fn)(void);
typedef void (*reload_create_matching_fn)(reload_observer *, godot_Callable *);
typedef void (*reload_create_removed_fn)(godot_Callable *);

static int dso_is_loaded(const char *path) {
    void *probe = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    if (probe == NULL) {
        return 0;
    }
    dlclose(probe); /* NOLOAD still takes a reference: balance it */
    return 1;
}

static int pointer_in_page(const void *p, const void *page, size_t size) {
    const uintptr_t ptr = (uintptr_t)p;
    const uintptr_t base = (uintptr_t)page;
    return ptr >= base && ptr - base < size;
}

/* Mirrors GDExtensionManager::_load_extension_internal: the first load walks
 * every level from CORE, a reload starts at the extension's minimum. */
static void load_extension_levels(GDExtensionInitialization *init, int first_load) {
    const int min_level = first_load ? (int)GDEXTENSION_INITIALIZATION_CORE
                                     : (int)init->minimum_initialization_level;
    for (int i = min_level; i <= (int)GDEXTENSION_INITIALIZATION_EDITOR; i++) {
        init->initialize(init->userdata, (GDExtensionInitializationLevel)i);
    }
}

/* Mirrors GDExtensionManager::_unload_extension_internal: deinitialize from the
 * current level all the way down to CORE. */
static void unload_extension_levels(GDExtensionInitialization *init) {
    for (int i = (int)GDEXTENSION_INITIALIZATION_EDITOR; i >= (int)GDEXTENSION_INITIALIZATION_CORE; i--) {
        init->deinitialize(init->userdata, (GDExtensionInitializationLevel)i);
    }
}

static void *reload_dummy_create(void *token, void *instance) {
    (void)token;
    (void)instance;
    return (void *)(uintptr_t)0xD00D;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <generation1.so> <generation2.so>\n", argv[0]);
        return 2;
    }
    const char *path_gen1 = argv[1];
    const char *path_gen2 = argv[2];
    const size_t page_size = (size_t)sysconf(_SC_PAGESIZE);

    reload_host_bridge bridge;
    bridge.engine_object = (GDExtensionObjectPtr)&g_engine_marker;
    bridge.get_proc_address = (GDExtensionInterfaceGetProcAddress)fake_get_proc_address;
    bridge.generation_hint = 0;
    GDExtensionClassLibraryPtr extension_token = (GDExtensionClassLibraryPtr)&bridge;

    log_event("host-start");

    /* ---------------- load generation 1 ---------------- */
    void *hd1 = dlopen(path_gen1, RTLD_NOW | RTLD_LOCAL);
    CHECK(hd1 != NULL, "gen1 dlopen failed");
    reload_entry_fn entry1 = (reload_entry_fn)dlsym(hd1, "reload_generation_entry");
    reload_hub_fn hub_fn1 = (reload_hub_fn)dlsym(hd1, "reload_generation_hub");
    reload_thunk_offset_fn thunk_offset_fn1 =
            (reload_thunk_offset_fn)dlsym(hd1, "reload_generation_thunk_offset");
    reload_bundle_bytes_fn bundle_bytes_fn1 =
            (reload_bundle_bytes_fn)dlsym(hd1, "reload_generation_bundle_bytes");
    CHECK(entry1 != NULL && hub_fn1 != NULL && thunk_offset_fn1 != NULL
                    && bundle_bytes_fn1 != NULL,
            "gen1 exports missing");

    GDExtensionInitialization init1;
    memset(&init1, 0, sizeof(init1));
    CHECK(entry1(bridge.get_proc_address, extension_token, &init1), "gen1 entry refused");
    CHECK(init1.initialize != NULL && init1.deinitialize != NULL, "gen1 lifecycle missing");
    load_extension_levels(&init1, 1);
    log_event("gen1-initialized");

    const int anchor_free_null = g_anchor_free_cb_was_null;
    const int anchor_ref_null = g_anchor_reference_cb_was_null;
    CHECK(anchor_free_null == 1, "anchor must not retain image-local free callback");
    CHECK(anchor_ref_null == 1, "anchor must not retain image-local reference callback");
    CHECK(g_create_callback_calls == 1, "hub must be created exactly once, on first init");

    gdcc_hrx_hub *hub1 = (gdcc_hrx_hub *)hub_fn1();
    CHECK(hub1 != NULL && hub1->thunk_page != NULL, "gen1 hub/thunk page missing");
    const uint8_t *page = (const uint8_t *)hub1->thunk_page;

    const int balance_base = g_mem_balance; /* post-init baseline: hub + intern table */

    /* ---------------- create the two identities ---------------- */
    reload_create_matching_fn create_matching1 =
            (reload_create_matching_fn)dlsym(hd1, "reload_generation_create_matching");
    reload_create_removed_fn create_removed1 =
            (reload_create_removed_fn)dlsym(hd1, "reload_generation_create_removed");
    CHECK(create_matching1 != NULL && create_removed1 != NULL, "gen1 create exports missing");

    reload_observer observer1 = {0, 0, 0};
    godot_Callable cb_match;
    godot_Callable cb_removed;
    create_matching1(&observer1, &cb_match);
    create_removed1(&cb_removed);
    reload_observer live_observer = {0, 0, 0};
    godot_Callable cb_live;
    create_matching1(&live_observer, &cb_live);
    FakeCallableCustom *live_custom = fake_callable_custom_of(&cb_live);
    CHECK(live_custom != NULL, "gen1 live capture missing");

    FakeCallableCustom *cm = fake_callable_custom_of(&cb_match);
    FakeCallableCustom *cr = fake_callable_custom_of(&cb_removed);
    CHECK(cm != NULL && cr != NULL, "fake custom objects missing");
    gdcc_hrx_spec *spec_m = (gdcc_hrx_spec *)cm->userdata;
    gdcc_hrx_spec *spec_r = (gdcc_hrx_spec *)cr->userdata;
    CHECK(spec_m != NULL && spec_r != NULL, "specs missing");
    CHECK(spec_r->captures == NULL, "removed identity must carry NULL captures (no leak)");
    reload_capture *cap1 = (reload_capture *)spec_m->captures;
    CHECK(cap1 != NULL && cap1->magic == RELOAD_CAPTURE_MAGIC, "matching capture missing");

    /* all four role thunks live in the single anonymous page and are shared */
    GDExtensionCallableCustomCall saved_call = cm->call_func;
    GDExtensionCallableCustomIsValid saved_valid = cm->is_valid_func;
    GDExtensionCallableCustomFree saved_free = cm->free_func;
    GDExtensionCallableCustomGetArgumentCount saved_argc = cm->argc_func;
    CHECK(saved_call == cr->call_func && saved_valid == cr->is_valid_func
                    && saved_free == cr->free_func && saved_argc == cr->argc_func,
            "all specs must share the same role thunk addresses");
    CHECK(pointer_in_page((void *)saved_call, page, page_size)
                    && pointer_in_page((void *)saved_valid, page, page_size)
                    && pointer_in_page((void *)saved_free, page, page_size)
                    && pointer_in_page((void *)saved_argc, page, page_size),
            "the four callbacks must live inside the anonymous thunk page");
    CHECK((void *)saved_call == (void *)(page + thunk_offset_fn1((int)GDCC_HRX_THUNK_CALL)),
            "call thunk must sit at the frozen bundle offset");

    unsigned char snapshot[256];
    const unsigned bundle_bytes = bundle_bytes_fn1();
    CHECK(bundle_bytes <= sizeof(snapshot), "snapshot buffer too small");
    memcpy(snapshot, page, bundle_bytes);

    /* gen1 code runs through the thunk on the host-owned capture */
    GDExtensionCallError err;
    memset(&err, 0, sizeof(err));
    saved_call(cm->userdata, NULL, 0, NULL, &err);
    CHECK(err.error == GDEXTENSION_CALL_OK, "gen1 call must succeed");
    CHECK(cap1->calls == 1 && cap1->last_generation == 1, "gen1 implementation must run");

    /* ---------------- prepare/retain ---------------- */
    cm->refs++; /* the reload manager's retention of the surviving Callable */
    log_event("prepared-retained");

    /* ---------------- deinitialize generation 1 (down to CORE) ---------------- */
    unload_extension_levels(&init1);

    CHECK(saved_valid(cm->userdata) == false, "deinitialized generation must be invalid");
    memset(&err, 0, sizeof(err));
    saved_call(cm->userdata, NULL, 0, NULL, &err);
    CHECK(err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD,
            "invalid callable must report INVALID_METHOD");
    CHECK(cap1->calls == 1, "no stale calls after deinitialize");
    GDExtensionBool valid_flag = true;
    GDExtensionInt argc_value = saved_argc(cm->userdata, &valid_flag);
    CHECK(valid_flag == false && argc_value == 0, "argc must fail closed after deinitialize");

    /* ---------------- clear only extension-token bindings ---------------- */
    const GDExtensionInstanceBindingCallbacks dummy = {
        .create_callback = reload_dummy_create,
        .free_callback = NULL,
        .reference_callback = NULL,
    };
    void *ext_slot = fake_get_binding(
            (GDExtensionObjectPtr)&g_engine_marker, (void *)extension_token, &dummy);
    CHECK(ext_slot == (void *)(uintptr_t)0xD00D, "extension-token binding not created");
    fake_free_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)extension_token);
    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)extension_token, NULL) == NULL,
            "extension-token bindings must be cleared");
    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)RELOAD_ANCHOR_TOKEN, NULL)
                    == (void *)hub1,
            "anchor binding must survive extension-token clearing");

    /* ---------------- unload generation 1 ---------------- */
    CHECK(dlclose(hd1) == 0, "gen1 dlclose failed");
    CHECK(!dso_is_loaded(path_gen1), "gen1 must really be unloaded");
    log_event("gen1-unloaded");

    /* between images: retained callable must stay fail-closed, never stale */
    CHECK(saved_valid(cm->userdata) == false, "retained callable invalid between images");
    memset(&err, 0, sizeof(err));
    saved_call(cm->userdata, NULL, 0, NULL, &err);
    CHECK(err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD, "between-images call must be invalid");
    CHECK(cap1->calls == 1, "no stale call leaked from the unloaded image");
    valid_flag = true;
    argc_value = saved_argc(cm->userdata, &valid_flag);
    CHECK(valid_flag == false && argc_value == 0, "argc invalid between images");

    /* ---------------- load generation 2 ---------------- */
    void *hd2 = dlopen(path_gen2, RTLD_NOW | RTLD_LOCAL);
    CHECK(hd2 != NULL, "gen2 dlopen failed");
    reload_entry_fn entry2 = (reload_entry_fn)dlsym(hd2, "reload_generation_entry");
    reload_hub_fn hub_fn2 = (reload_hub_fn)dlsym(hd2, "reload_generation_hub");
    reload_create_matching_fn create_matching2 =
            (reload_create_matching_fn)dlsym(hd2, "reload_generation_create_matching");
    CHECK(entry2 != NULL && hub_fn2 != NULL && create_matching2 != NULL, "gen2 exports missing");

    GDExtensionInitialization init2;
    memset(&init2, 0, sizeof(init2));
    CHECK(entry2(bridge.get_proc_address, extension_token, &init2), "gen2 entry refused");
    load_extension_levels(&init2, 0);
    log_event("gen2-initialized");

    gdcc_hrx_hub *hub2 = (gdcc_hrx_hub *)hub_fn2();
    CHECK(hub2 == hub1, "gen2 must adopt the same host-owned hub");
    CHECK(hub2->thunk_page == (void *)page, "gen2 must reuse the same thunk page");
    CHECK(memcmp(snapshot, hub2->thunk_page, bundle_bytes) == 0,
            "thunk page bytes must be unchanged across reload");
    CHECK(cm->call_func == saved_call && cm->is_valid_func == saved_valid
                    && cm->free_func == saved_free && cm->argc_func == saved_argc,
            "retained callable must keep the same four callback pointers");
    CHECK(g_anchor_free_cb_was_null == 1 && g_anchor_reference_cb_was_null == 1,
            "anchor callbacks stay NULL across reload");
    CHECK(spec_m->captures == (void *)cap1, "rebind must keep the Gen1 capture block");
    CHECK(spec_m->impl_ptr != NULL && spec_m->binding_state == GDCC_HRX_BOUND_COMPATIBLE,
            "matching identity must rebind compatibly");
    CHECK(spec_r->impl_ptr == NULL, "removed identity must fail closed after reload");

    /* use the retained callable: gen2 code must run on the gen1 capture */
    CHECK(saved_valid(cm->userdata) == true, "rebound callable must be valid");
    memset(&err, 0, sizeof(err));
    saved_call(cm->userdata, NULL, 0, NULL, &err);
    CHECK(err.error == GDEXTENSION_CALL_OK, "gen2 call must succeed");
    CHECK(cap1->calls == 2 && cap1->last_generation == 2,
             "gen2 implementation must run on the retained Gen1 capture (not reused Gen1)");
    fake_callable_custom_release(live_custom);
    CHECK(live_observer.destroyed == 1 && live_observer.destroyed_generation == 2,
            "gen2 must destroy a Gen1 capture while Gen1 is still unloaded");
    CHECK(!dso_is_loaded(path_gen1), "compatible destruction must not reload Gen1");

    /* removed identity remains fail-closed: invoke then release */
    memset(&err, 0, sizeof(err));
    cr->call_func(cr->userdata, NULL, 0, NULL, &err);
    CHECK(err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD, "removed identity must be invalid");

    /* ---------------- compatible capture destroyed while gen2 loaded ---------------- */
    reload_observer observer2 = {0, 0, 0};
    godot_Callable cb_gen2;
    create_matching2(&observer2, &cb_gen2);
    FakeCallableCustom *cm2 = fake_callable_custom_of(&cb_gen2);
    CHECK(cm2 != NULL && cm2->userdata != NULL, "gen2 fresh spec missing");
    reload_capture *cap2 = (reload_capture *)((gdcc_hrx_spec *)cm2->userdata)->captures;
    CHECK(cap2 != NULL && cap2 != cap1, "gen2 must build an independent capture");
    fake_callable_custom_release(cm2);
    CHECK(observer2.destroyed == 1 && observer2.destroyed_generation == 2,
            "compatible Gen2 capture must be destroyed by the live Gen2 sweeper");

    /* release the removed identity too: NULL captures -> reclaimed, no leak */
    fake_callable_custom_release(cr);
    CHECK(hub2->leaked_capture_count == 0, "no deliberate capture leak may occur");

    /* ---------------- unload generation 2 (down to CORE) ---------------- */
    unload_extension_levels(&init2);
    CHECK(dlclose(hd2) == 0, "gen2 dlclose failed");
    CHECK(!dso_is_loaded(path_gen2), "gen2 must really be unloaded");
    log_event("gen2-unloaded");

    /* final free after final unload: sweeper detached, spec becomes pending */
    fake_callable_custom_release(cm); /* refs 2 -> 1 (drops the retention) */
    fake_callable_custom_release(cm); /* refs 1 -> 0: free thunk queues the spec */
    CHECK(observer1.destroyed == 0, "no capture destroy without a live sweeper");
    CHECK(g_mem_balance > balance_base, "pending spec must survive until the next load");

    /* ---------------- next load reclaims the pending spec ---------------- */
    void *hd3 = dlopen(path_gen1, RTLD_NOW | RTLD_LOCAL);
    CHECK(hd3 != NULL, "reclaim-gen dlopen failed");
    reload_entry_fn entry3 = (reload_entry_fn)dlsym(hd3, "reload_generation_entry");
    CHECK(entry3 != NULL, "reclaim-gen entry missing");
    GDExtensionInitialization init3;
    memset(&init3, 0, sizeof(init3));
    CHECK(entry3(bridge.get_proc_address, extension_token, &init3), "reclaim-gen entry refused");
    load_extension_levels(&init3, 0);
    CHECK(observer1.destroyed == 1 && observer1.destroyed_generation == 1,
            "pending spec must be reclaimed (destroyed) on the next load");
    CHECK(g_mem_balance == balance_base, "spec memory balance must return to post-init baseline");

    unload_extension_levels(&init3);
    CHECK(dlclose(hd3) == 0, "reclaim-gen dlclose failed");
    CHECK(!dso_is_loaded(path_gen1), "reclaim-gen must really be unloaded");

    printf("RELOAD_PASS\n");
    fflush(stdout);
    return 0;
}
