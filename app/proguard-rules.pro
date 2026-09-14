# BumpPay R8 configuration.
#
# The release build is minified because the publishing CLI rejects debug builds and an
# unminified release is a needlessly large APK. Minification is also the most likely way to
# break a working debug build at the worst possible moment (the night before submission), so
# the rules below are the ones that actually matter for this app.

# ---------------------------------------------------------------------------------------
# BouncyCastle / Ed25519
#
# The lightweight Ed25519 API is reached reflectively in places (the provider SPI) and R8
# cannot see through it. Losing this shows up as NoSuchAlgorithmException only in release,
# which is precisely the class of bug that ruins a demo.
# ---------------------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---------------------------------------------------------------------------------------
# Mobile Wallet Adapter
#
# The client library talks to the wallet over its own JSON-RPC protocol. Any type that gets
# serialized across that boundary must survive obfuscation with its member names intact.
# ---------------------------------------------------------------------------------------
-keep class com.solana.mobilewalletadapter.** { *; }
-dontwarn com.solana.mobilewalletadapter.**

# ---------------------------------------------------------------------------------------
# Host card emulation
#
# The system binds this service by name from the manifest. R8 cannot always see the
# manifest reference, and stripping or renaming the class means taps silently stop routing.
# ---------------------------------------------------------------------------------------
-keep class app.bumppay.hce.BumpPayApduService { *; }

# ---------------------------------------------------------------------------------------
# Kotlin metadata / coroutines
# ---------------------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Keep line numbers so a release-mode crash in a tap is still debuggable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
