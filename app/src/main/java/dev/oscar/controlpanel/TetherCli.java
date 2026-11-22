package dev.oscar.controlpanel;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Command-line entry point, run as root or shell (Shizuku) through app_process:
 * {@code CLASSPATH=<apk> app_process /system/bin dev.oscar.controlpanel.TetherCli start|stop}
 *
 * Switching the USB function to rndis only exposes a network adapter; DHCP and NAT come from
 * the tethering service, which has to be asked through TetheringManager.startTethering.
 * That needs TETHER_PRIVILEGED, which root (uid 0) and shell (uid 2000) both have.
 * All of these are hidden/system APIs, so they're called by reflection.
 */
public final class TetherCli {
    private static final int TETHERING_USB = 1;

    public static void main(String[] args) {
        boolean start = args.length == 0 || !"stop".equals(args[0]);
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            System.exit(start ? startUsb() : stopUsb());
        } catch (Throwable t) {
            System.out.println("ERROR " + t);
            Throwable c = t.getCause();
            if (c != null) System.out.println("CAUSE " + c);
            System.exit(2);
        }
    }

    /** Package name that matches our uid, so the tethering service's caller check passes. */
    private static String[] callerPackages() {
        int uid = android.os.Process.myUid();
        if (uid == 0) return new String[] {"root", "com.android.shell", "android"};
        return new String[] {"com.android.shell", "android"};
    }

    private static Object newManager(final String pkg) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        final Method getService = sm.getMethod("getService", String.class);
        Supplier<IBinder> supplier = () -> {
            try {
                return (IBinder) getService.invoke(null, "tethering");
            } catch (Exception e) {
                return null;
            }
        };
        if (supplier.get() == null) throw new IllegalStateException("tethering service not found");

        // TetheringManager only reads the caller's package name and attribution tag from the context.
        Context fake = new ContextWrapper(null) {
            @Override public String getOpPackageName() { return pkg; }
            @Override public String getPackageName() { return pkg; }
            @Override public String getAttributionTag() { return null; }
            @Override public Context getApplicationContext() { return this; }
        };
        Class<?> tm = Class.forName("android.net.TetheringManager");
        Constructor<?> ctor = tm.getDeclaredConstructor(Context.class, Supplier.class);
        ctor.setAccessible(true);
        return ctor.newInstance(fake, supplier);
    }

    private static int startUsb() throws Exception {
        Class<?> cbClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        for (String pkg : callerPackages()) {
            Object mgr = newManager(pkg);
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<>("TIMEOUT");
            Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class<?>[] {cbClass},
                    (proxy, m, a) -> {
                        if ("onTetheringStarted".equals(m.getName())) {
                            result.set("OK");
                            done.countDown();
                        } else if ("onTetheringFailed".equals(m.getName())) {
                            result.set("FAIL " + (a != null && a.length > 0 ? a[0] : "?"));
                            done.countDown();
                        } else if ("hashCode".equals(m.getName())) {
                            return System.identityHashCode(proxy);
                        } else if ("equals".equals(m.getName())) {
                            return proxy == a[0];
                        } else if ("toString".equals(m.getName())) {
                            return "TetherCliCallback";
                        }
                        return null;
                    });
            Method startM = mgr.getClass().getMethod("startTethering",
                    int.class, java.util.concurrent.Executor.class, cbClass);
            startM.invoke(mgr, TETHERING_USB, (java.util.concurrent.Executor) Runnable::run, cb);
            done.await(8, TimeUnit.SECONDS);
            System.out.println("pkg=" + pkg + " uid=" + android.os.Process.myUid() + " " + result.get());
            if ("OK".equals(result.get())) return 0;
        }
        return 1;
    }

    private static int stopUsb() throws Exception {
        Object mgr = newManager(callerPackages()[0]);
        mgr.getClass().getMethod("stopTethering", int.class).invoke(mgr, TETHERING_USB);
        System.out.println("stop requested uid=" + android.os.Process.myUid());
        return 0;
    }
}
