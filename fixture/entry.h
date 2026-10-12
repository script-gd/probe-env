#ifndef GDEXTENSION_FIXTURE_ENTRY_H
#define GDEXTENSION_FIXTURE_ENTRY_H

#include <godot_binding.h>
static GDExtensionClassLibraryPtr class_library = NULL;
#include <gdcc_helper.h>

struct GDExtensionInitializationStatus {
        godot_bool initialized;
};

void initialize(void* userdata, GDExtensionInitializationLevel p_level);
void deinitialize(void* userdata, GDExtensionInitializationLevel p_level);

// Class declarations

typedef struct StringFormatVariantOperand StringFormatVariantOperand;

#include "object_fat_ptr_types.h"


// Class definition for StringFormatVariantOperand

struct StringFormatVariantOperand {
        GDExtensionObjectPtr _object;
        GDExtensionBool _gdcc_destructed;
};


static inline GDExtensionObjectPtr StringFormatVariantOperand_object_ptr(StringFormatVariantOperand* self);
static inline void StringFormatVariantOperand_set_object_ptr(StringFormatVariantOperand* self, GDExtensionObjectPtr obj);

const GDExtensionInstanceBindingCallbacks StringFormatVariantOperand_class_binding_callbacks = {
        NULL,
        NULL,
        NULL,
};

static void StringFormatVariantOperand_class_bind_methods();

GDExtensionObjectPtr StringFormatVariantOperand_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize);

GDExtensionClassInstancePtr StringFormatVariantOperand_class_recreate_instance(void* p_class_userdata, GDExtensionObjectPtr p_object);

void StringFormatVariantOperand_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance);

void StringFormatVariantOperand_class_constructor(StringFormatVariantOperand* self);

void StringFormatVariantOperand_class_init_fields(StringFormatVariantOperand* self);

void StringFormatVariantOperand_class_destructor(StringFormatVariantOperand* self);

void StringFormatVariantOperand_class_destruct_fields(StringFormatVariantOperand* self);

void StringFormatVariantOperand_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed);

void* StringFormatVariantOperand_class_get_virtual_with_data(void* p_class_userdata, GDExtensionConstStringNamePtr p_name, uint32_t p_hash);

void StringFormatVariantOperand_class_call_virtual_with_data(GDExtensionClassInstancePtr p_instance, GDExtensionConstStringNamePtr p_name, void* p_virtual_call_userdata, const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_ret);

// Methods for StringFormatVariantOperand

godot_String StringFormatVariantOperand_render(
        gdcc_StringFormatVariantOperand_fat_ptr $self,
        godot_Variant* $value
);



#include "engine_method_binds.h"

// Method binding helpers

static void call_StringFormatVariantOperand_1_arg_Variant_ret_String(
        void* method_userdata,
        GDExtensionClassInstancePtr p_instance, const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
        GDExtensionVariantPtr r_return, GDExtensionCallError* r_error)
{
if (p_argument_count < 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;
        r_error->expected = 1;
        return;
}
if (p_argument_count > 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = 1;
        return;
}




// Extract the argument. Wrapper-owned non-object locals stay mutable so the
// cleanup epilogue below can destroy them before returning to Godot.
godot_Variant arg0 = godot_new_Variant_with_Variant((GDExtensionVariantPtr)p_args[0]);

// Call the function. Instance methods receive owner fat self; static methods omit self.
// Wrapper-local non-object values materialized above must be destroyed here.
gdcc_StringFormatVariantOperand_fat_ptr self_fat = gdcc_StringFormatVariantOperand_fat_ptr_from_raw(StringFormatVariantOperand_object_ptr((StringFormatVariantOperand*)p_instance));
godot_String (*function)(gdcc_StringFormatVariantOperand_fat_ptr, godot_Variant*) = method_userdata;
godot_String r = function(self_fat, &arg0);
godot_Variant ret = godot_new_Variant_with_String(&r);
godot_variant_new_copy(r_return, &ret);
godot_Variant_destroy(&ret);
godot_String_destroy(&r);
godot_Variant_destroy(&arg0);
}

static void ptrcall_StringFormatVariantOperand_1_arg_Variant_ret_String(
        void* method_userdata, GDExtensionClassInstancePtr p_instance,
        const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_return)
{
// Object args/returns use raw Godot pointer slots; self is owner fat for instance methods.
// Packed args arrive as raw struct slots (the ptrcall identity-isolation exception): each is materialized
// into a wrapper-local Variant via whitelist (a) and destroyed on the way out, so callee
// mutation stays isolated from the caller's array across this boundary.
gdcc_StringFormatVariantOperand_fat_ptr self_fat = gdcc_StringFormatVariantOperand_fat_ptr_from_raw(StringFormatVariantOperand_object_ptr((StringFormatVariantOperand*)p_instance));
godot_String (*function)(gdcc_StringFormatVariantOperand_fat_ptr, godot_Variant*) = method_userdata;
*((godot_String*)r_return) = function(self_fat, &(*((godot_Variant*)p_args[0])));
}

static void gdcc_bind_method_StringFormatVariantOperand_1_arg_Variant_ret_String(
        godot_StringName* class_name,
        godot_StringName* method_name,
        void* function,
        const godot_StringName* arg0_name,
        const GDExtensionVariantType arg0_type

)
{

GDExtensionClassMethodCall call_func = call_StringFormatVariantOperand_1_arg_Variant_ret_String;
GDExtensionClassMethodPtrCall ptrcall_func = ptrcall_StringFormatVariantOperand_1_arg_Variant_ret_String;

// Bound-slot outward metadata stays centralized in CGenHelper so Variant, typed Array and typed
// Dictionary keep sharing one backend-owned contract instead of growing template-local special cases.
GDExtensionPropertyInfo args_info[] = {
        gdcc_make_property_full(arg0_type, arg0_name, godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8""), GD_STATIC_SN(u8""), godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT),
};
GDExtensionClassMethodArgumentMetadata args_metadata[] = {
        GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE,
};

// Return outward metadata reuses the same helper path as arguments so typed Array / typed
// Dictionary hints stay consistent across both sides of the method boundary.
GDExtensionPropertyInfo return_info = gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_STRING, GD_STATIC_SN(u8""), godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8""), GD_STATIC_SN(u8""), godot_PROPERTY_USAGE_DEFAULT);
GDExtensionClassMethodInfo method_info = { 0 };
method_info.name = method_name;
method_info.method_userdata = function;
method_info.call_func = call_func;
method_info.ptrcall_func = ptrcall_func;
method_info.method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT;
method_info.has_return_value = true;
method_info.return_value_info = &return_info;
method_info.return_value_metadata = GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE;
method_info.argument_count = 1;
method_info.arguments_info = args_info;
method_info.arguments_metadata = args_metadata;
godot_classdb_register_extension_class_method(class_library, class_name, &method_info);
// Clean up
gdcc_destruct_property(&args_info[0]);
gdcc_destruct_property(&return_info);
}

#endif //GDEXTENSION_FIXTURE_ENTRY_H
