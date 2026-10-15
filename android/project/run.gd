extends SceneTree

const CLASS_NAME = "StringFormatVariantOperand"
const CASES = [[42, "value=42"], ["ARM64", "value=ARM64"], [true, "value=true"]]

func _initialize() -> void:
	if not ClassDB.class_exists(CLASS_NAME):
		push_error("Generated extension class is missing")
		print("GDCC_PROJECT_FAIL")
		quit(1)
		return
	var instance = ClassDB.instantiate(CLASS_NAME)
	var ok := true
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
		quit(0)
	else:
		print("GDCC_PROJECT_FAIL")
		quit(1)
