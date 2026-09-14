# BumpPay merchant terminal — R8 configuration.
#
# Small surface: the terminal holds no keys and does no cryptography of its own. What must
# survive minification is the activity itself (the launcher entry point) and the shared
# codec in :core, which reads and writes byte layouts that reflection-free code cannot
# break — so the interesting rules are in the app module.

-keep class app.bumppay.terminal.MainActivity { *; }

# Keep line numbers so a release-mode failure during a live tap is still diagnosable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
