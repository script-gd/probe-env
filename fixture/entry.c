
#include "entry.h"

#include <string.h>


#define GDCC_HRX_ANCHOR_TOKEN UINT64_C(0x8FC19F06F0D4F94C)

GDE_EXPORT GDExtensionBool gdextension_entry(
        GDExtensionInterfaceGetProcAddress p_get_proc_address,
        GDExtensionClassLibraryPtr p_library,
        GDExtensionInitialization* r_initialization
)
{
        if (!godot_initialize_interface(p_get_proc_address)) {
                return false;
        }
        class_library = p_library;

        r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE;
        r_initialization->userdata = NULL;
        r_initialization->initialize = &initialize;
        r_initialization->deinitialize = &deinitialize;

        return true;
}

void initialize(void* userdata, const GDExtensionInitializationLevel p_level)
{
        (void)userdata;
        if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {
                return;
        }
        gdcc_init();
        gdcc_packed_ref_init();
        gdcc_hrx_initialize(class_library, GDCC_HRX_ANCHOR_TOKEN,
                            NULL, 0);
        {
                godot_Variant msg_variant = godot_new_Variant_with_String(GD_STATIC_S(u8"Loading fixture..."));
                godot_print(&msg_variant, NULL, 0);
                godot_Variant_destroy(&msg_variant);
        }
        {
                GDExtensionClassCreationInfo5 creation_info = { 0 };
                creation_info.is_abstract = false;
                creation_info.is_runtime = false;
                creation_info.is_virtual = false;
                creation_info.is_exposed = true;
                creation_info.create_instance_func = StringFormatVariantOperand_class_create_instance;
                creation_info.recreate_instance_func = StringFormatVariantOperand_class_recreate_instance;
                creation_info.free_instance_func = StringFormatVariantOperand_class_free_instance;
                creation_info.get_virtual_call_data_func = StringFormatVariantOperand_class_get_virtual_with_data;
                creation_info.call_virtual_with_data_func = StringFormatVariantOperand_class_call_virtual_with_data;
                creation_info.notification_func = StringFormatVariantOperand_class_notification;
                godot_classdb_register_extension_class5(class_library,
                                                        GD_STATIC_SN(u8"StringFormatVariantOperand"), GD_STATIC_SN(u8"Node"),
                                                        &creation_info);
                StringFormatVariantOperand_class_bind_methods();
        }
}

void deinitialize(void* userdata, GDExtensionInitializationLevel p_level)
{
        (void)userdata;
        if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {
                return;
        }
        {
                godot_Variant msg_variant = godot_new_Variant_with_String(GD_STATIC_S(u8"Unloading fixture..."));
                godot_print(&msg_variant, NULL, 0);
                godot_Variant_destroy(&msg_variant);
        }

        godot_classdb_unregister_extension_class(class_library, GD_STATIC_SN(u8"StringFormatVariantOperand"));
        gdcc_sn_registry_destroy_all();
        gdcc_s_registry_destroy_all();
        gdcc_standalone_callable_registry_destroy_all();
        gdcc_hrx_deinitialize();
}




void StringFormatVariantOperand_class_bind_methods()
{
        godot_StringName* class_name = GD_STATIC_SN(u8"StringFormatVariantOperand");
        // Methods
        gdcc_bind_method_StringFormatVariantOperand_1_arg_Variant_ret_String(class_name, GD_STATIC_SN(u8"render"), StringFormatVariantOperand_render,
                        GD_STATIC_SN(u8"value"), GDEXTENSION_VARIANT_TYPE_NIL);
        // Properties
        // Signals
}

// Object pointer helpers for GDCC wrapper layout
static inline GDExtensionObjectPtr StringFormatVariantOperand_object_ptr(StringFormatVariantOperand* self)
{
if (self == NULL) {
        return NULL;
}
return self->_object;
}

static inline void StringFormatVariantOperand_set_object_ptr(StringFormatVariantOperand* self, GDExtensionObjectPtr obj)
{
if (self == NULL) {
        return;
}
self->_object = obj;
}


// GdExtension Methods for each class

GDExtensionObjectPtr StringFormatVariantOperand_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize)
{
        GDExtensionObjectPtr obj = godot_classdb_construct_object2(GD_STATIC_SN(u8"Node"));
        StringFormatVariantOperand* self = godot_mem_alloc(sizeof(StringFormatVariantOperand));
        StringFormatVariantOperand_set_object_ptr(self, obj);
        self->_gdcc_destructed = false;
        godot_object_set_instance(obj, GD_STATIC_SN(u8"StringFormatVariantOperand"), self);
        godot_object_set_instance_binding(obj, class_library, self, &StringFormatVariantOperand_class_binding_callbacks);
        if (p_notify_postinitialize) {
                godot_Object_notification(obj, godot_Object_NOTIFICATION_POSTINITIALIZE(), false);
        }
        return obj;
}

void StringFormatVariantOperand_class_init_fields(StringFormatVariantOperand* self)
{
        if (self == NULL) {
                return;
        }
}

GDExtensionClassInstancePtr StringFormatVariantOperand_class_recreate_instance(void* p_class_userdata, GDExtensionObjectPtr p_object)
{
        (void)p_class_userdata;
        if (p_object == NULL) {
                return NULL;
        }
        StringFormatVariantOperand* self = godot_mem_alloc(sizeof(StringFormatVariantOperand));
        if (self == NULL) {
                GDCC_PRINT_RUNTIME_ERROR("gdcc: out of memory recreating extension instance", __func__, __FILE__, __LINE__);
                return NULL;
        }
        StringFormatVariantOperand_set_object_ptr(self, p_object);
        self->_gdcc_destructed = false;
        godot_object_set_instance_binding(p_object, class_library, self, &StringFormatVariantOperand_class_binding_callbacks);
        StringFormatVariantOperand_class_init_fields(self);
        return self;
}

void StringFormatVariantOperand_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance)
{
        if (p_instance == NULL) {
                return;
        }
        StringFormatVariantOperand* self = p_instance;
        if (!self->_gdcc_destructed) {
                StringFormatVariantOperand_class_destructor(self);
        }
        godot_mem_free(self);
}

void StringFormatVariantOperand_class_constructor(StringFormatVariantOperand* self)
{
        if (self == NULL) {
                return;
        }
}

void StringFormatVariantOperand_class_destruct_fields(StringFormatVariantOperand* self)
{
        if (self == NULL) {
                return;
        }
}

void StringFormatVariantOperand_class_destructor(StringFormatVariantOperand* self)
{
        if (self == NULL) {
                return;
        }
        if (self->_gdcc_destructed) {
                return;
        }
        self->_gdcc_destructed = true;
        StringFormatVariantOperand_class_destruct_fields(self);
}

void StringFormatVariantOperand_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed)
{
        StringFormatVariantOperand* self = p_instance;
        if (p_what == godot_Object_NOTIFICATION_POSTINITIALIZE()) {
                StringFormatVariantOperand_class_constructor(self);
        } else if (p_what == godot_Object_NOTIFICATION_PREDELETE()) {
                StringFormatVariantOperand_class_destructor(self);
        }
}

void* StringFormatVariantOperand_class_get_virtual_with_data(void* p_class_userdata, GDExtensionConstStringNamePtr p_name,
                uint32_t p_hash)
{
        (void)p_class_userdata;
        (void)p_hash;
        // Bind virtual methods
        return NULL;
}

void StringFormatVariantOperand_class_call_virtual_with_data(GDExtensionClassInstancePtr p_instance,
                GDExtensionConstStringNamePtr p_name,
                void* p_virtual_call_userdata,
                const GDExtensionConstTypePtr* p_args,
                GDExtensionTypePtr r_ret)
{
        (void)p_name;
        // Call virtual methods
}

// Methods for StringFormatVariantOperand

godot_String StringFormatVariantOperand_render(
        gdcc_StringFormatVariantOperand_fat_ptr $self,
        godot_Variant* $value
)
{
        godot_String $cfg_tmp_v1;
        godot_Variant $cfg_tmp_v2;
        godot_String $cfg_tmp_text_0;
        godot_String $cfg_tmp_v3;
        godot_String $text;
        goto __prepare__;
        __prepare__: // __prepare__
        godot_String _return_val;
        $cfg_tmp_v1 = godot_new_String_with_utf8_chars(u8"");
        $cfg_tmp_v2 = godot_new_Variant_nil();
        $cfg_tmp_text_0 = godot_new_String_with_utf8_chars(u8"");
        $cfg_tmp_v3 = godot_new_String_with_utf8_chars(u8"");
        $text = godot_new_String_with_utf8_chars(u8"");
        goto seq_0;
        seq_0: // seq_0
        godot_String_destroy(&$cfg_tmp_v1);
        $cfg_tmp_v1 = godot_new_String_with_utf8_chars(u8"value=%s");
        godot_Variant __gdcc_tmp_variant_0 = godot_new_Variant_with_Variant($value);
        godot_Variant_destroy(&$cfg_tmp_v2);
        $cfg_tmp_v2 = __gdcc_tmp_variant_0;
        godot_Variant __gdcc_tmp_op_left_variant_1;
        __gdcc_tmp_op_left_variant_1 = godot_new_Variant_with_String(&$cfg_tmp_v1);
        godot_Variant __gdcc_tmp_op_eval_result_2;
        godot_bool __gdcc_tmp_op_eval_valid_3 = false;
        godot_variant_evaluate(GDEXTENSION_VARIANT_OP_MODULE, &__gdcc_tmp_op_left_variant_1, &$cfg_tmp_v2, &__gdcc_tmp_op_eval_result_2, &__gdcc_tmp_op_eval_valid_3);
        if (!__gdcc_tmp_op_eval_valid_3) {
                GDCC_PRINT_RUNTIME_ERROR("godot_variant_evaluate failed for operator 'MODULE'", __func__, __FILE__, __LINE__);
                godot_Variant __gdcc_tmp_variant_4 = godot_new_Variant_nil();
                __gdcc_tmp_op_eval_result_2 = godot_new_Variant_with_Variant(&__gdcc_tmp_variant_4);
                godot_Variant_destroy(&__gdcc_tmp_variant_4);
                godot_Variant_destroy(&__gdcc_tmp_op_eval_result_2);
                godot_Variant_destroy(&__gdcc_tmp_op_left_variant_1);
                godot_String __gdcc_tmp_string_5 = godot_new_String();
                _return_val = godot_new_String_with_String(&__gdcc_tmp_string_5);
                godot_String_destroy(&__gdcc_tmp_string_5);
                goto __finally__;
        }
        if (!(gdcc_check_variant_type_builtin(&__gdcc_tmp_op_eval_result_2, GDEXTENSION_VARIANT_TYPE_STRING))) {
                GDCC_PRINT_RUNTIME_ERROR("variant_evaluate type check failed for operator 'MODULE': expected String", __func__, __FILE__, __LINE__);
                godot_Variant_destroy(&__gdcc_tmp_op_eval_result_2);
                godot_String __gdcc_tmp_string_6 = godot_new_String();
                _return_val = godot_new_String_with_String(&__gdcc_tmp_string_6);
                godot_String_destroy(&__gdcc_tmp_string_6);
                goto __finally__;
        }
        godot_String_destroy(&$cfg_tmp_text_0);
        $cfg_tmp_text_0 = godot_new_String_with_Variant(&__gdcc_tmp_op_eval_result_2);
        godot_String_destroy(&$text);
        $text = godot_new_String_with_String(&$cfg_tmp_text_0);
        godot_String_destroy(&$cfg_tmp_v3);
        $cfg_tmp_v3 = godot_new_String_with_String(&$text);
        goto stop_1;
        stop_1: // stop_1
        _return_val = godot_new_String_with_String(&$cfg_tmp_v3);
        goto __finally__;
        __finally__: // __finally__
        godot_String_destroy(&$cfg_tmp_v1);
        godot_Variant_destroy(&$cfg_tmp_v2);
        godot_String_destroy(&$cfg_tmp_text_0);
        godot_String_destroy(&$cfg_tmp_v3);
        godot_String_destroy(&$text);
        return _return_val;

}


