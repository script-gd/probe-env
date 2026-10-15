@tool
extends EditorPlugin

const CLASS_NAME = "StringFormatVariantOperand"
const CASES = [[42, "value=42"], ["ARM64", "value=ARM64"], [true, "value=true"]]

func _enter_tree() -> void:
	print("ANDROID_EDITOR_TOOL_ENTER_TREE")
	print("ANDROID_FEATURES::", OS.get_name(), "::", Engine.get_architecture_name(), "::editor_hint=", Engine.is_editor_hint())
	var ok := Engine.is_editor_hint()
	if not ClassDB.class_exists(CLASS_NAME):
		push_error("Generated extension class is missing")
		ok = false
	else:
		var instance = ClassDB.instantiate(CLASS_NAME)
		for pair in CASES:
			var actual = instance.call("render", pair[0])
			if actual != pair[1]:
				push_error("render mismatch: %s != %s" % [actual, pair[1]])
				ok = false
			else:
				print("GDCC_CASE_PASS::", actual)
		instance.free()
	if ok:
		print("GDCC_PROJECT_PASS")
		print("ANDROID_EDITOR_RESULT::PASS")
	else:
		print("GDCC_PROJECT_FAIL")
		print("ANDROID_EDITOR_RESULT::FAIL")
	_quit.call_deferred(ok)

func _quit(success: bool) -> void:
	get_tree().quit(0 if success else 1)
