@tool
extends EditorPlugin

const CLASS_NAME = "StringFormatVariantOperand"
const CASES = [[42, "value=42"], ["ARM64", "value=ARM64"], [true, "value=true"]]
const JAVA25_JAR = "/sdcard/probe-env/java25/java25-probe-dex.jar"
const JAVA25_BRIDGE = "/sdcard/probe-env/java25/libgdcc_java25_bridge.so"
const JAVA25_STATUS = "/sdcard/probe-env/java25.status"
const GDCC_EXTENSION = "res://probe.gdextension"

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
	if ok and FileAccess.file_exists(JAVA25_JAR) and FileAccess.file_exists(JAVA25_BRIDGE):
		_run_java25_reload.call_deferred()
	else:
		_finish(ok)

func _run_java25_reload() -> void:
	var directory := "user://java25-runtime"
	var error := DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(directory))
	if error != OK or not _copy_to_user(JAVA25_JAR, directory + "/java25-probe-dex.jar") or \
			not _copy_to_user(JAVA25_BRIDGE, directory + "/libgdcc_java25_bridge.so"):
		push_error("Could not stage the Java 25 DEX jar and JNI bridge in app-private storage")
		_finish(false)
		return

	var java_file = JavaClassWrapper.wrap("java.io.File")
	var dex_path := ProjectSettings.globalize_path(directory + "/java25-probe-dex.jar")
	var bridge_path := ProjectSettings.globalize_path(directory + "/libgdcc_java25_bridge.so")
	if not java_file.File(dex_path).setReadOnly() or not java_file.File(bridge_path).setReadOnly():
		push_error("Could not make dynamically loaded DEX/native files read-only")
		_finish(false)
		return

	var optimized_dir := ProjectSettings.globalize_path("user://java25-optimized")
	java_file.File(optimized_dir).mkdirs()
	var loader_class = JavaClassWrapper.wrap("dalvik.system.DexClassLoader")
	var parent = JavaClassWrapper.wrap("java.lang.ClassLoader").getSystemClassLoader()
	var loader = loader_class.DexClassLoader(dex_path, optimized_dir, ProjectSettings.globalize_path(directory),
		parent)
	var class_class = JavaClassWrapper.wrap("java.lang.Class")
	class_class.forName("gdcc.probe.Java25TccProbe", true, loader)
	print("JAVA25_ART_CLASS_INITIALIZED")

	for attempt in range(240):
		if FileAccess.file_exists(JAVA25_STATUS):
			var status := FileAccess.get_file_as_string(JAVA25_STATUS)
			if status.begins_with("PASS"):
				break
			if status.begins_with("FAIL"):
				push_error("Java 25/JNI/libtcc probe failed: " + status)
				_finish(false)
				return
		if attempt == 239:
			push_error("Timed out waiting for Java 25/JNI/libtcc compilation")
			_finish(false)
			return
		await get_tree().create_timer(0.25).timeout

	var remove_error := DirAccess.remove_absolute("/sdcard/probe-env/bin/libprobe.so")
	if remove_error != OK:
		push_error("Could not remove the prior extension from shared storage: %s" % remove_error)
		_finish(false)
		return
	var copy_error := DirAccess.copy_absolute("/sdcard/probe-env/bin/libprobe-v2.so",
		"/sdcard/probe-env/bin/libprobe.so")
	if copy_error != OK:
		push_error("Could not stage the Java-generated extension for Godot reload: %s" % copy_error)
		_finish(false)
		return

	var reload_status: int = GDExtensionManager.reload_extension(GDCC_EXTENSION)
	print("JAVA25_GDEXTENSION_RELOAD_STATUS::", reload_status)
	var ok := reload_status == GDExtensionManager.LOAD_STATUS_OK and ClassDB.class_exists(CLASS_NAME)
	if ok:
		var instance = ClassDB.instantiate(CLASS_NAME)
		var actual = instance.call("render", 42)
		ok = actual == "java25=42"
		if ok:
			print("JAVA25_GDCC_CASE_PASS::", actual)
		else:
			push_error("Reloaded Java 25-generated extension returned %s" % actual)
		instance.free()
	if ok:
		print("JAVA25_ART_RELOAD_PASS")
	_finish(ok)

func _copy_to_user(source: String, target: String) -> bool:
	var input = FileAccess.open(source, FileAccess.READ)
	if input == null:
		return false
	var data := input.get_buffer(input.get_length())
	input.close()
	if data.is_empty():
		return false
	var output = FileAccess.open(target, FileAccess.WRITE)
	if output == null:
		return false
	output.store_buffer(data)
	var ok := output.get_error() == OK
	output.close()
	return ok

func _finish(ok: bool) -> void:
	if ok:
		print("GDCC_PROJECT_PASS")
		print("ANDROID_EDITOR_RESULT::PASS")
	else:
		print("GDCC_PROJECT_FAIL")
		print("ANDROID_EDITOR_RESULT::FAIL")
	_quit.call_deferred(ok)

func _quit(success: bool) -> void:
	get_tree().quit(0 if success else 1)
