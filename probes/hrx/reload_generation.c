/*
 * One content-distinct generation of a hot-reloadable GDExtension image.
 *
 * Built with PROBE_GENERATION=1/2 as separate ELF objects, then linked with
 * cached `gdcc_hrx.c` and `godot_binding.c` objects into distinct DSO images.
 * Every generation:
 *   - installs the real GDExtension SCENE lifecycle through the exported entry;
 *   - brings up / takes over the cross-generation hub via gdcc_hrx;
 *   - exposes explicit create hooks for the probe's identities.
 *
 * Generation 2's rebind table contains the matching identity only. The removed
 * identity is not listed anywhere, so it fails closed after a reload.
 */

#include "reload_api.h"

#include <gdcc_hrx.h>
#include <godot_binding.h>

#include <stddef.h>
#include <string.h>

#ifndef PROBE_GENERATION
#error "reload_generation.c requires -DPROBE_GENERATION=<n>"
#endif

#define PROBE_GEN ((int)PROBE_GENERATION)

/* Resolved from the `library` argument (host-resident bridge received at load). */
static reload_host_bridge *g_bridge = NULL;

/* Stable identities: identical bytes in both images, so a rebind matches by
 * schema and call-site context while the old image is already gone. */
static const gdcc_hrx_identity MATCHING_IDENTITY = {
    .impl_key = "ReloadProbe::matching#0",
    .schema_desc = (const unsigned char *)"matching-v1",
    .schema_desc_len = 11u,
    .argument_count = 0,
    .schema_fingerprint = { 0x52, 0x4C, 0x44, 0x4D, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
    .callsite_context = NULL,
};

static const gdcc_hrx_identity REMOVED_IDENTITY = {
    .impl_key = "ReloadProbe::removed#1",
    .schema_desc = (const unsigned char *)"removed-v1",
    .schema_desc_len = 10u,
    .argument_count = 0,
    .schema_fingerprint = { 0x52, 0x4C, 0x44, 0x52, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
    .callsite_context = NULL,
};

/* ------------------------------------------------------------------ */
/* Matching identity: survives reloads, rebinds in place.              */
/* ------------------------------------------------------------------ */
static void matching_impl(
        void *captures,
        const GDExtensionConstVariantPtr *args,
        GDExtensionInt argc,
        GDExtensionVariantPtr r_return,
        GDExtensionCallError *r_error
) {
    (void)args;
    (void)argc;
    (void)r_return;
    reload_capture *capture = (reload_capture *)captures;
    if (capture != NULL) {
        capture->calls++;
        capture->last_generation = PROBE_GEN;
    }
    if (r_error != NULL) {
        r_error->error = GDEXTENSION_CALL_OK;
        r_error->argument = 0;
        r_error->expected = 0;
    }
}

static GDExtensionBool matching_valid(void *captures) {
    const reload_capture *capture = (const reload_capture *)captures;
    return capture != NULL && capture->magic == RELOAD_CAPTURE_MAGIC;
}

static void matching_destroy(void *captures) {
    reload_capture *capture = (reload_capture *)captures;
    if (capture == NULL) {
        return;
    }
    if (capture->magic == RELOAD_CAPTURE_MAGIC && capture->observer != NULL) {
        capture->observer->destroyed++;
        capture->observer->destroyed_generation = PROBE_GEN;
    }
    godot_mem_free(capture);
}

/* ------------------------------------------------------------------ */
/* Removed identity: no table entry in the next image, NULL captures.  */
/* ------------------------------------------------------------------ */
static void removed_impl(
        void *captures,
        const GDExtensionConstVariantPtr *args,
        GDExtensionInt argc,
        GDExtensionVariantPtr r_return,
        GDExtensionCallError *r_error
) {
    (void)captures;
    (void)args;
    (void)argc;
    (void)r_return;
    if (r_error != NULL) {
        r_error->error = GDEXTENSION_CALL_OK;
        r_error->argument = 0;
        r_error->expected = 0;
    }
}

static GDExtensionBool removed_valid(void *captures) {
    (void)captures;
    return true;
}

static const gdcc_hrx_rebind_entry GENERATION_TABLE[] = {
    { &MATCHING_IDENTITY, matching_impl, matching_destroy, matching_valid },
};

/* ------------------------------------------------------------------ */
/* Godot-shaped lifecycle.                                             */
/* ------------------------------------------------------------------ */
static void reload_generation_initialize(void *userdata, GDExtensionInitializationLevel level) {
    (void)userdata;
    if (level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
    gdcc_hrx_initialize_core(
            (GDExtensionClassLibraryPtr)g_bridge,
            g_bridge->engine_object,
            true, /* is_editor: executable-memory path is enabled */
            gdcc_hrx_execmem_probe(),
            RELOAD_ANCHOR_TOKEN,
            GENERATION_TABLE,
#ifdef PROBE_NO_REBIND
            0
#else
            (uint32_t)(sizeof(GENERATION_TABLE) / sizeof(GENERATION_TABLE[0]))
#endif
    );
}

static void reload_generation_deinitialize(void *userdata, GDExtensionInitializationLevel level) {
    (void)userdata;
    if (level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
    gdcc_hrx_deinitialize();
}

GDE_EXPORT GDExtensionBool reload_generation_entry(
        GDExtensionInterfaceGetProcAddress p_get_proc_address,
        GDExtensionClassLibraryPtr p_library,
        GDExtensionInitialization *r_initialization
) {
    if (!godot_initialize_interface(p_get_proc_address)) {
        return false;
    }
    g_bridge = (reload_host_bridge *)p_library;
    r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE;
    r_initialization->userdata = NULL;
    r_initialization->initialize = reload_generation_initialize;
    r_initialization->deinitialize = reload_generation_deinitialize;
    return true;
}

GDE_EXPORT void *reload_generation_hub(void) {
    return gdcc_hrx_current_hub();
}

GDE_EXPORT unsigned reload_generation_thunk_offset(int kind) {
    return gdcc_hrx_thunk_bundle_offset((gdcc_hrx_thunk_kind)kind);
}

GDE_EXPORT unsigned reload_generation_bundle_bytes(void) {
    const gdcc_hrx_thunk_template *argc_template =
            gdcc_hrx_thunk_template_get(GDCC_HRX_THUNK_GET_ARGUMENT_COUNT);
    return gdcc_hrx_thunk_bundle_offset(GDCC_HRX_THUNK_GET_ARGUMENT_COUNT)
            + (argc_template != NULL ? argc_template->size : 0u);
}

GDE_EXPORT void reload_generation_create_matching(reload_observer *observer, godot_Callable *out_callable) {
    reload_capture *capture = (reload_capture *)godot_mem_alloc(sizeof(reload_capture));
    if (capture == NULL) {
        memset(out_callable, 0, sizeof(*out_callable));
        return;
    }
    capture->magic = RELOAD_CAPTURE_MAGIC;
    capture->calls = 0;
    capture->last_generation = PROBE_GEN;
    capture->observer = observer;
    if (observer != NULL) {
        observer->created++;
    }
    *out_callable = gdcc_hrx_create_lambda(
            capture, 0, matching_impl, matching_valid, matching_destroy,
            0, &MATCHING_IDENTITY, NULL
    );
}

GDE_EXPORT void reload_generation_create_removed(godot_Callable *out_callable) {
    *out_callable = gdcc_hrx_create_lambda(
            NULL, 0, removed_impl, removed_valid, NULL,
            0, &REMOVED_IDENTITY, NULL
    );
}
