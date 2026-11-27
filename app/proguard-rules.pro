# =====================================================================
# R8 rules (R8 shrinks the release build).
# =====================================================================

# Keep class and method names readable, so the stack traces in crash
# reports point to real code. Unused code is still removed.
-dontobfuscate

# Keep line numbers in stack traces.
-keepattributes SourceFile,LineNumberTable
