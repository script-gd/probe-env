#ifndef GDEXTENSION_FIXTURE_OBJECT_FAT_PTR_TYPES_H
#define GDEXTENSION_FIXTURE_OBJECT_FAT_PTR_TYPES_H

#include <godot_binding.h>
#include <gdcc_helper.h>

typedef struct StringFormatVariantOperand StringFormatVariantOperand;
// Object fat pointer declarations

typedef struct gdcc_StringFormatVariantOperand_fat_ptr {
        StringFormatVariantOperand *ptr;
        GDObjectInstanceID instance_id;
} gdcc_StringFormatVariantOperand_fat_ptr;

static inline GDExtensionObjectPtr StringFormatVariantOperand_object_ptr(StringFormatVariantOperand *self);
// Object fat pointer helpers
// Query helpers below are ownership-neutral (no retain/release). They may observe ObjectDB
// state; pure/const attributes are applied only where the runtime header already does so for
// shared helpers. `_to_variant` constructs a new Variant and is therefore side-effecting.

/// Canonical null fat pointer `{ NULL, 0 }`. Ownership-neutral constructor.
static inline gdcc_StringFormatVariantOperand_fat_ptr gdcc_StringFormatVariantOperand_fat_ptr_null(void)
{
gdcc_StringFormatVariantOperand_fat_ptr result = { NULL, 0 };
return result;
}

/// Captures a live raw Godot object pointer into a fat pointer (ownership-neutral).
/// Callers must pass NULL or a live raw pointer; dangling raw pointers are undefined.
static inline gdcc_StringFormatVariantOperand_fat_ptr gdcc_StringFormatVariantOperand_fat_ptr_from_raw(GDExtensionObjectPtr raw)
{
if (raw == NULL) {
        return gdcc_StringFormatVariantOperand_fat_ptr_null();
}
GDObjectInstanceID id = godot_object_get_instance_id(raw);
StringFormatVariantOperand *ptr = (StringFormatVariantOperand *)gdcc_object_from_godot_object_ptr(raw);
gdcc_StringFormatVariantOperand_fat_ptr result = { ptr, id };
return result;
}

/// Reads an OBJECT Variant into a fat pointer (ownership-neutral materialization).
/// The original instance ID is preserved; freed payloads keep the ID but have a NULL typed pointer.
/// This relies on ObjectDB removing freed entries so `gdcc_object_live_ptr` returns NULL.
static inline gdcc_StringFormatVariantOperand_fat_ptr gdcc_StringFormatVariantOperand_fat_ptr_from_variant(const godot_Variant *value)
{
if (value == NULL || godot_variant_get_type(value) != GDEXTENSION_VARIANT_TYPE_OBJECT) {
        return gdcc_StringFormatVariantOperand_fat_ptr_null();
}
GDObjectInstanceID id = godot_variant_get_object_instance_id(value);
if (id == 0) {
        return gdcc_StringFormatVariantOperand_fat_ptr_null();
}
GDExtensionObjectPtr raw = gdcc_object_live_ptr(id);
if (raw == NULL) {
        gdcc_StringFormatVariantOperand_fat_ptr result = { NULL, id };
        return result;
}
StringFormatVariantOperand *ptr = (StringFormatVariantOperand *)gdcc_object_from_godot_object_ptr(raw);
gdcc_StringFormatVariantOperand_fat_ptr result = { ptr, id };
return result;
}

/// Returns the validated raw Godot object pointer for this fat pointer (query; no ownership change).
/// RefCountedStatus specializes the fast path: YES uses the cached pointer, NO uses ObjectDB,
/// UNKNOWN checks the ObjectID reference bit at runtime.
static inline GDExtensionObjectPtr gdcc_StringFormatVariantOperand_fat_ptr_live_object(gdcc_StringFormatVariantOperand_fat_ptr value)
{
return gdcc_object_live_ptr(value.instance_id);
}

/// Returns the validated statically-typed pointer for this fat pointer (query; no ownership change).
static inline StringFormatVariantOperand *gdcc_StringFormatVariantOperand_fat_ptr_live_ptr(gdcc_StringFormatVariantOperand_fat_ptr value)
{
GDExtensionObjectPtr raw = gdcc_object_live_ptr(value.instance_id);
if (raw == NULL) {
        return NULL;
}
return (StringFormatVariantOperand *)gdcc_object_from_godot_object_ptr(raw);
}

/// Packs a fat pointer into an OBJECT Variant (side-effecting: constructs a new Variant).
/// Freed fat pointers degrade to OBJECT/null because public ABI cannot construct an ID-only Variant.
static inline godot_Variant gdcc_StringFormatVariantOperand_fat_ptr_to_variant(gdcc_StringFormatVariantOperand_fat_ptr value)
{
return godot_new_Variant_with_Object(gdcc_StringFormatVariantOperand_fat_ptr_live_object(value));
}

#endif
