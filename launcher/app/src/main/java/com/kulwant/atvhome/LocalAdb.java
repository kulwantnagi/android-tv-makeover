package com.kulwant.atvhome;

import android.content.Context;

import java.io.File;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * Runs shell commands through the TV's own network ADB, with the key KeyInjector already had the
 * TV allow. For the things an ordinary app may not do: reading media sessions, turning the screen off.
 */
final class LocalAdb {

    private static final Object LOCK = new Object();
    private static Dadb adb;

    private LocalAdb() {}

    /** Blocks (up to ~15 s): call off the main thread. */
    static String shell(Context c, String cmd) throws Exception {
        synchronized (LOCK) {
            try {
                if (adb == null) {
                    File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
                    if (!priv.isFile() || !pub.isFile()) AdbKeyPair.Companion.generate(priv, pub);
                    adb = Dadb.Companion.create("127.0.0.1", 5555, AdbKeyPair.Companion.read(priv, pub), 5_000, 10_000);
                }
                return adb.shell(cmd).getAllOutput();
            } catch (Exception e) {
                close();   // the TV restarted ADB or went to sleep: reconnect next time
                throw e;
            }
        }
    }

    private static void close() {
        try {
            if (adb != null) adb.close();
        } catch (Exception ignored) {
        }
        adb = null;
    }
}
