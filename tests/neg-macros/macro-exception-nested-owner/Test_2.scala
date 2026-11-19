// The trace of an exception that a macro implementation throws ends at the
// implementation's frame, also in an object nested in another (`Outer$Inner$`)
def test = Outer.Inner.boom(1) // error
