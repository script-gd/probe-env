#ifndef PROBES_HRX_RELOAD_API_H
#define PROBES_HRX_RELOAD_API_H

/*
 * Shared surface between the reload host (persistent fake engine) and each
 * content-distinct generation DSO. It deliberately contains only ABI-stable
 * data types plus the generation export declarations, so the same header can be
 * consumed by a normal `cc` translation unit and a TinyCC `-shared` build.
 *
 * Generation selection is a compile-time constant (`-DPROBE_GENERATION=<n>`);
 * the two packages are content-distinct DSO images but share this layout.
 */

#include <godot_binding.h>

#include <stdint.h>

/* Anchor token for the Engine-singleton instance binding that owns the hub.
 * Distinct from any extension/class library token so clearing extension-token
 * bindings during a reload can never evict the cross-generation hub. */
#define RELOAD_ANCHOR_TOKEN UINT64_C(0x524C44414E434831) /* "RLDANCH1" */

#define RELOAD_CAPTURE_MAGIC UINT32_C(0x524C4150) /* "RLAP" */

/* Host-owned observation block. The two generation DSOs never allocate it; a
 * capture only stores a pointer to it, so a `destroy` running in a later image
 * can still report back to the still-resident host. */
typedef struct reload_observer {
    int created;
    int destroyed;
    int destroyed_generation;
} reload_observer;

/* Capture block for the matching identity: Godot-heap allocated (through the
 * host `mem_alloc`), so it survives the creating image's dlclose and is only
 * released by whichever generation owns the compatible schema at free time. */
typedef struct reload_capture {
    uint32_t magic;
    int calls;
    int last_generation;
    reload_observer *observer;
} reload_capture;

/* The `library` pointer handed to a generation is this host-resident bridge.
 * Using it as the extension/class token keeps the anchor token distinct and
 * gives the DSO the engine object without linking any host code. */
typedef struct reload_host_bridge {
    GDExtensionObjectPtr engine_object;
    GDExtensionInterfaceGetProcAddress get_proc_address;
    unsigned generation_hint;
} reload_host_bridge;

/* Real GDExtension entry shape: the host drives initialize/deinitialize at the
 * SCENE level exactly like GDExtensionManager::reload_extension does. */
GDExtensionBool reload_generation_entry(
        GDExtensionInterfaceGetProcAddress p_get_proc_address,
        GDExtensionClassLibraryPtr p_library,
        GDExtensionInitialization *r_initialization);

/* Current image-local hub cache (host-owned storage, stable across unload). */
void *reload_generation_hub(void);

/* Host-architecture thunk layout, resolved from the DSO so the host never links
 * gdcc_hrx.c. `kind` is a gdcc_hrx_thunk_kind value. */
unsigned reload_generation_thunk_offset(int kind);
unsigned reload_generation_bundle_bytes(void);

/* Explicit lifecycle: each generation builds the same schema-distinct specs. */
void reload_generation_create_matching(reload_observer *observer, godot_Callable *out_callable);
void reload_generation_create_removed(godot_Callable *out_callable);

#endif /* PROBES_HRX_RELOAD_API_H */
