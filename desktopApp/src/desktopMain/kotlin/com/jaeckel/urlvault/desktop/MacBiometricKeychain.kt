package com.jaeckel.urlvault.desktop

import com.jaeckel.urlvault.Logger

/**
 * macOS-only Touch ID-gated wrapper around the legacy file-based Keychain.
 *
 * Storage: shells out to `security(1)` to add/find/delete generic password
 * items. The Keychain item lives in `~/Library/Keychains/login.keychain-db`,
 * encrypted at rest by the OS using the user's login password. No entry on
 * disk is in a "predictable" form — the file is meaningless without the
 * user's macOS login session.
 *
 * Biometric gate: before each read, fires `LocalAuthentication.framework`'s
 * `LAPolicyDeviceOwnerAuthenticationWithBiometrics` via JavaScript-for-
 * Automation (`osascript -l JavaScript`). The script blocks until the user
 * authenticates with Touch ID, cancels, or the OS falls back to login
 * password (e.g. on Macs without Touch ID hardware). Only on successful
 * authentication does this class call `security find-generic-password` to
 * release the password to the JVM.
 *
 * Why not the modern data-protection keychain with `SecAccessControl` and
 * `kSecAccessControlBiometryCurrentSet`? Because that API requires the
 * calling process to be code-signed with proper entitlements; running
 * unsigned via Gradle (`./gradlew :desktopApp:run`) hits errSecMissing-
 * Entitlement (-34018). The legacy CLI + LAContext pair gives equivalent UX
 * and works in unsigned development builds. If the app is later signed for
 * distribution, this class can be replaced with a JNA-backed
 * `SecAccessControl` implementation for stricter OS-enforced biometric ACL
 * (e.g. invalidation on biometric re-enrollment).
 */
internal class MacBiometricKeychain {

    fun isSupported(): Boolean {
        if (System.getProperty("os.name")?.lowercase()?.contains("mac") != true) return false
        // Both `security` and `osascript` are shipped with macOS — sanity
        // check anyway in case someone runs in a stripped-down environment.
        return runCommand("security", "help") != null && runCommand("osascript", "-e", "0") != null
    }

    /**
     * Stores [password] in the user's login keychain under (service, account).
     * Replaces any existing item. No biometric prompt on save (typical
     * keychain UX). Returns true on success.
     */
    fun savePassword(service: String, account: String, password: String): Boolean {
        if (!isSupported()) return false
        val out = runCommand(
            "security", "add-generic-password",
            "-s", service,
            "-a", account,
            "-w", password,
            "-U",                      // update if exists
            "-T", "",                  // restrict to /usr/bin/security itself; no other-app access
        )
        if (out == null) {
            Logger.e(TAG, "security add-generic-password failed for service=$service account=$account")
            return false
        }
        return true
    }

    /**
     * Triggers a Touch ID prompt via LAContext. On success, retrieves the
     * password from Keychain and returns it. Returns null if the prompt is
     * cancelled, biometric authentication fails, or the item doesn't exist.
     *
     * Must be called from a non-EDT thread — `osascript` is synchronous and
     * the system-modal Touch ID prompt would otherwise block Compose's UI.
     */
    fun loadPassword(service: String, account: String, reason: String = "URLVault wants to use the Bitwarden master password"): String? {
        if (!isSupported()) return null
        if (!authenticateWithBiometric(reason)) return null
        val out = runCommand(
            "security", "find-generic-password",
            "-s", service,
            "-a", account,
            "-w",                      // print only the password
        ) ?: return null
        return out.trimEnd('\n').takeIf { it.isNotEmpty() }
    }

    fun deletePassword(service: String, account: String): Boolean {
        if (!isSupported()) return false
        val out = runCommand(
            "security", "delete-generic-password",
            "-s", service,
            "-a", account,
        )
        return out != null
    }

    /**
     * Synchronously runs Touch ID via LocalAuthentication. Returns true if the
     * user authenticated; false on cancel / failure / unavailable hardware.
     */
    private fun authenticateWithBiometric(reason: String): Boolean {
        // Escape user-supplied reason for embedding in a JS string literal.
        val safeReason = reason.replace("\\", "\\\\").replace("\"", "\\\"")
        val script = """
            ObjC.import("LocalAuthentication");
            ObjC.import("Foundation");
            (function () {
                var ctx = $.LAContext.alloc.init;
                var errPtr = Ref();
                var policy = 1; // LAPolicyDeviceOwnerAuthenticationWithBiometrics
                if (!ctx.canEvaluatePolicyError(policy, errPtr)) {
                    return "unavailable";
                }
                var resultRef = { done: false, ok: false };
                ctx.evaluatePolicyLocalizedReasonReply(
                    policy,
                    "$safeReason",
                    function (success, evalError) {
                        resultRef.ok = success;
                        resultRef.done = true;
                    }
                );
                var loop = $.NSRunLoop.currentRunLoop;
                var deadline = $.NSDate.dateWithTimeIntervalSinceNow(60.0);
                while (!resultRef.done && $.NSDate.date.compare(deadline) < 0) {
                    loop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.1));
                }
                return resultRef.ok ? "ok" : "denied";
            })();
        """.trimIndent()

        val result = runCommand("osascript", "-l", "JavaScript", "-e", script)?.trim()
        return when (result) {
            "ok" -> true
            "denied", "unavailable", null -> {
                if (result == "unavailable") {
                    Logger.e(TAG, "LAContext biometric policy unavailable on this Mac")
                }
                false
            }
            else -> {
                Logger.e(TAG, "Unexpected biometric result: $result")
                false
            }
        }
    }

    private fun runCommand(vararg command: String): String? {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                stdout
            } else {
                Logger.v(TAG, "command [${redactArgs(command)}] failed code=$exitCode: $stderr")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "runCommand [${redactArgs(command)}] threw: ${e.message}", e)
            null
        }
    }

    /** Redacts values following `-w` so passwords never appear in logs. */
    private fun redactArgs(args: Array<out String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < args.size) {
            if (i > 0) sb.append(' ')
            if (args[i] == "-w" && i + 1 < args.size) {
                sb.append("-w <redacted>")
                i += 2
            } else {
                sb.append(args[i])
                i++
            }
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "MacBiometricKeychain"
    }
}
