package me.piebridge.brevent.ui;

import android.accounts.NetworkErrorException;
import android.app.Application;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.support.v4.util.ArrayMap;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dalvik.system.PathClassLoader;
import me.piebridge.SimpleSu;
import me.piebridge.brevent.BuildConfig;
import me.piebridge.brevent.R;
import me.piebridge.brevent.override.HideApiOverride;
import me.piebridge.brevent.override.HideApiOverrideN;
import me.piebridge.brevent.protocol.BreventConfiguration;
import me.piebridge.brevent.protocol.BreventProtocol;
import me.piebridge.brevent.protocol.BreventResponse;
import me.piebridge.donation.DonateActivity;
import me.piebridge.stats.StatsUtils;

/**
 * Created by thom on 2017/2/7.
 */
public class BreventApplication extends Application {

    private boolean mSupportStopped = true;

    private boolean mSupportStandby = false;

    private boolean mSupportUpgrade = true;

    private boolean mSupportAppops = false;

    private boolean copied;

    long mDaemonTime;

    long mServerTime;

    int mUid;

    public static final boolean IS_OWNER = HideApiOverride.getUserId() == getOwner();

    private WeakReference<Handler> handlerReference;

    private boolean eventMade;

    private static long id;

    private static final Object LOCK = new Object();

    private final Object lockAdb = new Object();

    private Boolean play;

    private static BigInteger modulus;

    private ExecutorService executor = new ScheduledThreadPoolExecutor(0x1);

    private boolean needClose;
    private boolean needStop;
    private boolean fixAdb;
    private boolean started;

    private void setSupportStopped(boolean supportStopped) {
        if (mSupportStopped != supportStopped) {
            mSupportStopped = supportStopped;
        }
    }

    public boolean supportStopped() {
        return mSupportStopped;
    }

    private File getBootstrapFile() {
        try {
            PackageManager packageManager = getPackageManager();
            String nativeLibraryDir = packageManager
                    .getApplicationInfo(BuildConfig.APPLICATION_ID, 0).nativeLibraryDir;
            File file = new File(nativeLibraryDir, "libbrevent.so");
            if (file.exists()) {
                return file;
            }
            UILog.d("no libbrevent.so");
        } catch (PackageManager.NameNotFoundException e) {
            UILog.d("Can't find " + BuildConfig.APPLICATION_ID, e);
        }
        return null;
    }

    public String copyBrevent() {
        File brevent = getBootstrapFile();
        if (brevent == null) {
            return null;
        }
        File parent = getFilesDir().getParentFile();
        if (parent.canRead()) {
            String father = parent.getParent();
            try {
                father = Os.readlink(father);
                parent = new File(father, parent.getName());
            } catch (ErrnoException e) {
                UILog.d("Can't read link for " + father, e);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            parent = createDeviceProtectedStorageContext().getFilesDir().getParentFile();
        }
        UILog.d("parent: " + parent + ", canRead: " + parent.canRead());
        if (!parent.canRead()) {
            return null;
        }
        File output = new File(parent, "brevent.sh");
        String path = output.getAbsolutePath();
        if (!copied) {
            try (
                    InputStream is = getResources().openRawResource(R.raw.brevent);
                    OutputStream os = new FileOutputStream(output);
                    PrintWriter pw = new PrintWriter(os)
            ) {
                pw.println("path=" + brevent);
                pw.println("abi64=" + brevent.getPath().contains("64"));
                pw.println();
                pw.flush();
                byte[] bytes = new byte[0x400];
                int length;
                while ((length = is.read(bytes)) != -1) {
                    os.write(bytes, 0, length);
                }
                copied = true;
            } catch (IOException e) {
                UILog.w("Can't copy brevent", e);
                return null;
            }
        }
        try {
            Os.chmod(parent.getPath(), 00755);
            Os.chmod(path, 00755);
        } catch (ErrnoException e) {
            UILog.w("Can't chmod brevent", e);
            return null;
        }
        return path;
    }

    private void setSupportStandby(boolean supportStandby) {
        mSupportStandby = supportStandby;
    }

    public boolean supportStandby() {
        return mSupportStandby;
    }

    private void setSupportUpgrade(boolean supportUpgrade) {
        if (mSupportUpgrade != supportUpgrade) {
            mSupportUpgrade = supportUpgrade;
        }
    }

    public boolean supportUpgrade() {
        return mSupportUpgrade;
    }

    private void setSupportAppops(boolean supportAppops) {
        mSupportAppops = supportAppops;
    }

    public boolean supportAppops() {
        return mSupportAppops;
    }

    public String getInstaller() {
        String installer = getPackageManager().getInstallerPackageName(BuildConfig.APPLICATION_ID);
        if (TextUtils.isEmpty(installer)) {
            return "unknown";
        } else {
            return installer;
        }
    }

    public void updateStatus(BreventResponse breventResponse) {
        boolean shouldUpdated = breventResponse.mDaemonTime != 0
                && mDaemonTime != breventResponse.mDaemonTime;
        mDaemonTime = breventResponse.mDaemonTime;
        mServerTime = breventResponse.mServerTime;
        mUid = breventResponse.mUid;
        setSupportStandby(breventResponse.mSupportStandby);
        setSupportStopped(breventResponse.mSupportStopped);
        setSupportUpgrade(breventResponse.mSupportUpgrade);
        setSupportAppops(breventResponse.mSupportAppops);
        if (BuildConfig.RELEASE && shouldUpdated) {
            long days = TimeUnit.MILLISECONDS.toDays(mServerTime - mDaemonTime);
            long living = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - mDaemonTime);
            UILog.d("days: " + days + ", living: " + living);
            Map<String, String> attributes = new ArrayMap<>();
            attributes.put("standby", Boolean.toString(mSupportStandby));
            attributes.put("stopped", Boolean.toString(mSupportStopped));
            attributes.put("appops", Boolean.toString(mSupportAppops));
            attributes.put("days", Long.toString(days));
            attributes.put("living", Long.toString(living));
            attributes.put("installer", getInstaller());
            if (SimpleSu.hasSu()) {
                StatsUtils.logInvite(attributes);
            } else {
                StatsUtils.logLogin(attributes);
            }
        }
    }

    public static int getOwner() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? HideApiOverrideN.USER_SYSTEM
                : HideApiOverride.USER_OWNER;
    }

    public boolean isRunningAsRoot() {
        return handlerReference != null && handlerReference.get() != null;
    }

    public void setHandler(Handler handler) {
        if (handlerReference == null || handlerReference.get() != handler) {
            handlerReference = new WeakReference<>(handler);
        }
    }

    public void notifyRootCompleted(List<String> output) {
        if (handlerReference != null) {
            Handler handler = handlerReference.get();
            if (handler != null) {
                Message message = handler.obtainMessage(BreventActivity.MESSAGE_ROOT_COMPLETED,
                        output);
                handler.sendMessageDelayed(message, 0x400);
            }
            handlerReference = null;
        }
    }

    public boolean isEventMade() {
        return eventMade;
    }

    public void makeEvent() {
        if (!eventMade) {
            eventMade = true;
        }
    }

    public void resetEvent() {
        if (eventMade) {
            eventMade = false;
        }
    }

    public static long getId(Application application) {
        if (id == 0) {
            synchronized (LOCK) {
                if (id == 0) {
                    id = doGetId(application);
                }
            }
        }
        return id;
    }

    private static long doGetId(Application application) {
        String androidId = Settings.Secure.getString(application.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        if (TextUtils.isEmpty(androidId) || "9774d56d682e549c".equals(androidId)) {
            androidId = PreferencesUtils.getPreferences(application)
                    .getString(Settings.Secure.ANDROID_ID, "0");
        }
        long breventId;
        try {
            breventId = new BigInteger(androidId, 16).longValue();
        } catch (NumberFormatException e) {
            breventId = 0;
            UILog.d("Can't parse " + androidId, e);
        }
        if (breventId == 0) {
            breventId = 0xdeadbeef00000000L | new SecureRandom().nextInt();
            PreferencesUtils.getPreferences(application).edit()
                    .putString(Settings.Secure.ANDROID_ID, Long.toHexString(breventId)).apply();
        }
        return breventId;
    }

    public boolean isUnsafe() {
        if (AppsDisabledFragment.isEmulator()) {
            return true;
        }
        String clazzServer = String.valueOf(BuildConfig.SERVER);
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        try {
            String sourceDir = getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
                    .sourceDir;
            PathClassLoader classLoader = new PathClassLoader(sourceDir, systemClassLoader);
            return (boolean) classLoader.loadClass(clazzServer).getMethod(String.valueOf('c'))
                    .invoke(null);
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException e) { // NOSONAR
            // do nothing
            return false;
        }
    }

    public static double decode(Application application, String message, boolean auto) {
        if (TextUtils.isEmpty(message)) {
            return 0d;
        }
        try {
            if (auto) {
                return decode(application, message, new BigInteger(1, BuildConfig.DONATE_M));
            } else {
                return decode(application, message, getSignature(application));
            }
        } catch (NumberFormatException e) {
            UILog.d("Can't decode, auto: " + auto, e);
            return 0d;
        }
    }

    public static BigInteger getSignature(Context context) {
        if (modulus != null) {
            return modulus;
        }
        Signature[] signatures = BreventActivity.getSignatures(context.getPackageManager(),
                BuildConfig.APPLICATION_ID);
        if (signatures == null || signatures.length != 1) {
            return null;
        }
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            final ByteArrayInputStream bais = new ByteArrayInputStream(signatures[0].toByteArray());
            final Certificate cert = certFactory.generateCertificate(bais);
            modulus = ((RSAPublicKey) cert.getPublicKey()).getModulus();
        } catch (GeneralSecurityException e) {
            UILog.d("Can't get signature", e);
            return null;
        }
        return modulus;
    }

    private static double decode(Application application, String message, BigInteger module) {
        if (module == null) {
            return 0d;
        }
        byte[] m = {1, 0, 1};
        byte[] bytes = new BigInteger(message, 16).modPow(new BigInteger(1, m), module).toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(25);
        buffer.put(bytes, bytes.length == 25 ? 0 : 1, 25);
        buffer.flip();
        byte v = buffer.get();
        long breventId = buffer.getLong();
        if (breventId != 0 && breventId != getId(application)) {
            UILog.d("id: " + Long.toHexString(breventId) + " != " +
                    Long.toHexString(getId(application)));
            return 0d;
        } else {
            double d = Double.longBitsToDouble(buffer.getLong());
            if (v == 1) {
                return d / 5;
            } else if (v == 2) {
                return d / 6.85;
            } else {
                return d / 6.5;
            }
        }
    }

    public boolean hasPlay() {
        return getPackageManager().getLaunchIntentForPackage(DonateActivity.PACKAGE_PLAY) != null;
    }

    public boolean isPlay() {
        if (play != null) {
            return play;
        }
        play = VersionPreference.isPlay(this);
        return play;
    }

    public double decodeFromClipboard() {
        double donate2 = 0d;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null && text.toString().startsWith("br")) {
                String alipay2 = text.subSequence(2, text.length()).toString().trim();
                donate2 = decode(this, alipay2, false);
                if (DecimalUtils.isPositive(donate2)) {
                    PreferencesUtils.getPreferences(this)
                            .edit().putString("alipay2", alipay2).apply();
                    String format = DecimalUtils.format(donate2);
                    String message = getString(R.string.toast_donate, format);
                    clipboard.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            }
        }
        return donate2;
    }

    public static int getPlayDonation(Application application) {
        BigInteger modulus = new BigInteger(1, BuildConfig.DONATE_PLAY);
        Collection<String> purchased = DonateActivity.getPurchased(application, UILog.TAG, modulus);
        return BreventSettings.getPlayDonation(purchased);
    }

    public static double getDonation(Application application) {
        SharedPreferences preferences = PreferencesUtils.getPreferences(application);
        String alipay1 = preferences.getString("alipay1", "");
        double donate1 = decode(application, alipay1, true);
        if (donate1 < 0) {
            donate1 = 0;
            preferences.edit().remove("alipay1").apply();
        }
        String alipay2 = preferences.getString("alipay2", "");
        double donate2 = decode(application, alipay2, false);
        if (donate2 < 0) {
            donate2 = 0;
            preferences.edit().remove("alipay2").apply();
        }
        return Math.max(donate1, donate2);
    }

    public static byte[] dumpsys(String serviceName, String[] args) {
        String clazzServer = String.valueOf(BuildConfig.SERVER);
        try {
            ClassLoader classLoader = BreventApplication.class.getClassLoader();
            return (byte[]) classLoader.loadClass(clazzServer)
                    .getMethod(String.valueOf('c'), String.class, String[].class)
                    .invoke(null, serviceName, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            UILog.w("Can't dumpsys " + serviceName + " " + Arrays.toString(args), e);
            return null;
        }
    }

    public static void dumpsys(String serviceName, String[] args, File file) throws IOException {
        byte[] results = dumpsys(serviceName, args);
        if (results != null && file != null) {
            try (FileOutputStream os = new FileOutputStream(file)) {
                os.write(results);
            }
        }
    }

    public boolean checkPort() throws NetworkErrorException {
        return checkPort(false);
    }

    public boolean checkPort(final boolean io) throws NetworkErrorException {
        Future<Boolean> future = executor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    boolean started = BreventProtocol.checkPortSync();
                    UILog.v("connected, started: " + started);
                    return started;
                } catch (ConnectException e) {
                    UILog.v("Can't connect to localhost:" + BreventProtocol.PORT, e);
                    return false;
                } catch (IOException e) {
                    UILog.w("io error to localhost:" + BreventProtocol.PORT, e);
                    return io;
                }
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new NetworkErrorException(e);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleUtils.updateResources(base));
    }

    public void setAdb(boolean needClose, boolean needStop) {
        this.needClose = needClose;
        this.needStop = needStop;
        this.fixAdb = true;
        this.started = false;
    }

    public void unsetFixAdb() {
        this.fixAdb = false;
    }

    public void stopAdbIfNeeded() {
        synchronized (lockAdb) {
            stopAdbIfNeededSync();
        }
    }

    private void stopAdbIfNeededSync() {
        if (fixAdb && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            SimpleSu.su("pbd=`pidof brevent_daemon`; " +
                    "pbs=`pidof brevent_server`; " +
                    "pin=`pidof installd`; " +
                    "echo $pbd > /acct/uid_0/pid_$pin/tasks; " +
                    "echo $pbd > /acct/uid_0/pid_$pin/cgroup.procs; " +
                    "echo $pbs > /acct/uid_0/pid_$pin/tasks; " +
                    "echo $pbs > /acct/uid_0/pid_$pin/cgroup.procs");
            fixAdb = false;
        }
        if (!needClose) {
            needClose = "1".equals(SystemProperties.get("service.adb.brevent.close", ""));
        }
        if (needClose) {
            needClose = false;
            String command = needStop ? "setprop ctl.stop adbd" : "setprop ctl.restart adbd";
            SimpleSu.su("setprop service.adb.tcp.port -1; " +
                    "setprop service.adb.brevent.close 0; " + command);
        }
    }

    public boolean isStarted() {
        return started;
    }

    public void onStarted() {
        if (!this.started) {
            this.started = true;
            if (SimpleSu.hasSu()) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        stopAdbIfNeeded();
                    }
                }).start();
            }
        }
        hideNotification();
    }

    void hideNotification() {
        stopService(new Intent(this, BreventIntentService.class));
        NotificationManager nm = BreventIntentService.getNotificationManager(this);
        nm.cancel(BreventIntentService.ID);
        nm.cancel(BreventIntentService.ID2);
        nm.cancel(BreventIntentService.ID3);
    }

}
