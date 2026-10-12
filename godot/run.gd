extends SceneTree

func _initialize() -> void:
    if not ClassDB.class_exists("StringFormatVariantOperand"):
        push_error("Generated extension class is missing")
        quit(1)
        return
    var instance = ClassDB.instantiate("StringFormatVariantOperand")
    var cases = [[42, "value=42"], ["ARM64", "value=ARM64"], [true, "value=true"]]
    for pair in cases:
        var actual = instance.call("render", pair[0])
        if actual != pair[1]:
            push_error("render mismatch: %s != %s" % [actual, pair[1]])
            instance.free()
            quit(1)
            return
        print("GDCC_CASE_PASS::", actual)
    instance.free()
    print("GDCC_PROJECT_PASS")
    quit(0)
