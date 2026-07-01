rootProject.name = "DefCoreLib"

// Companion plugins: thin jars that gate content (recipes / banner activation) in the core runtime.
// The root project IS the core (DefCoreLib); these depend on it via compileOnly(project(":")).
include("vslab", "bbanners", "mech")
