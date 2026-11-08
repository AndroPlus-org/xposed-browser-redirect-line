package com.hosig.linebrowser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Browser;

import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LINE のアプリ内ブラウザ (InAppBrowserActivity) の起動を横取りし、
 * デフォルトブラウザへの ACTION_VIEW に差し替える。
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "LineExtBrowser: ";
    private static final String LINE_PKG = "jp.naver.line.android";
    private static final String IAB_ACTIVITY = "com.linecorp.line.iab.browser.impl.InAppBrowserActivity";
    private static final String EXTRA_HANDLED = "com.hosig.linebrowser.HANDLED";

    /** LINE 自身の認証・設定ページ等はアプリ内ブラウザのままにする */
    private static final String[] EXCLUDED_HOSTS = {
            "line.me", "line-apps.com", "line-apps-beta.com", "line-apps-rc.com", "naver.jp"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!LINE_PKG.equals(lpparam.packageName)) return;

        // 1) startActivity 系はすべて Instrumentation.execStartActivity を通るので、ここで Intent を差し替える
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Context ctx = null;
                    for (int i = 0; i < param.args.length; i++) {
                        Object a = param.args[i];
                        if (a instanceof Context && ctx == null) ctx = (Context) a;
                        if (!(a instanceof Intent)) continue;
                        Intent in = (Intent) a;
                        if (!isIab(in)) continue;
                        Uri uri = extractUrl(in);
                        if (uri == null) {
                            log("IAB intent without URL: " + describe(in));
                            continue;
                        }
                        if (isExcluded(uri)) {
                            log("excluded: " + uri);
                            continue;
                        }
                        if (ctx == null) ctx = currentContext();
                        param.args[i] = buildExternal(ctx, uri);
                        log("redirect -> " + uri);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        // 2) PendingIntent など上記を経由しない経路の保険: Activity 起動後に外部ブラウザへ投げて閉じる
        try {
            Class<?> iab = XposedHelpers.findClass(IAB_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(iab, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) param.thisObject;
                        if (param.args[0] != null) return; // 再生成時は何もしない
                        Intent in = act.getIntent();
                        if (in == null || in.getBooleanExtra(EXTRA_HANDLED, false)) return;
                        Uri uri = extractUrl(in);
                        if (uri == null || isExcluded(uri)) return;
                        act.startActivity(buildExternal(act, uri));
                        act.finish();
                        log("fallback redirect -> " + uri);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
        } catch (Throwable t) {
            log("IAB class hook failed: " + t);
        }
    }

    private static boolean isIab(Intent in) {
        ComponentName cn = in.getComponent();
        return cn != null && IAB_ACTIVITY.equals(cn.getClassName());
    }

    private static Uri extractUrl(Intent in) {
        Uri data = in.getData();
        if (isWeb(data)) return data;
        Bundle extras;
        try {
            extras = in.getExtras();
        } catch (Throwable t) {
            return null;
        }
        return findUrl(extras, 0);
    }

    private static Uri findUrl(Bundle b, int depth) {
        if (b == null || depth > 3) return null;
        for (String key : b.keySet()) {
            Object v;
            try {
                v = b.get(key);
            } catch (Throwable t) {
                continue;
            }
            Uri u = null;
            if (v instanceof Uri) u = (Uri) v;
            else if (v instanceof String) u = parse((String) v);
            else if (v instanceof Bundle) u = findUrl((Bundle) v, depth + 1);
            else if (v instanceof Parcelable) u = findInObject(v);
            if (isWeb(u)) return u;
        }
        return null;
    }

    /** extras に独自 Parcelable が入っている場合、そのフィールドから URL を探す */
    private static Uri findInObject(Object o) {
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    Uri u = null;
                    if (v instanceof Uri) u = (Uri) v;
                    else if (v instanceof String) u = parse((String) v);
                    if (isWeb(u)) return u;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Uri parse(String s) {
        if (s == null) return null;
        String l = s.toLowerCase(Locale.ROOT);
        if (!l.startsWith("http://") && !l.startsWith("https://")) return null;
        return Uri.parse(s);
    }

    private static boolean isWeb(Uri u) {
        if (u == null) return false;
        String s = u.getScheme();
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s);
    }

    private static boolean isExcluded(Uri u) {
        String host = u.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String h : EXCLUDED_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    private static Intent buildExternal(Context ctx, Uri uri) {
        Intent out = new Intent(Intent.ACTION_VIEW, uri);
        out.addCategory(Intent.CATEGORY_BROWSABLE);
        out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.putExtra(Browser.EXTRA_APPLICATION_ID, LINE_PKG);
        out.putExtra(EXTRA_HANDLED, true);
        String pkg = defaultBrowser(ctx);
        if (pkg != null) out.setPackage(pkg);
        return out;
    }

    /** デフォルトブラウザのパッケージ名。決まっていなければ null (選択ダイアログに任せる) */
    private static String defaultBrowser(Context ctx) {
        if (ctx == null) return null;
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"));
            probe.addCategory(Intent.CATEGORY_BROWSABLE);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return null;
            String p = ri.activityInfo.packageName;
            if ("android".equals(p) || LINE_PKG.equals(p) || p.contains("resolver")) return null;
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context currentContext() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(Intent in) {
        StringBuilder sb = new StringBuilder(in.toString());
        try {
            Bundle b = in.getExtras();
            if (b != null) {
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    sb.append("\n  ").append(k).append(" = ")
                            .append(v == null ? "null" : v.getClass().getName() + ": " + v);
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void log(String s) {
        XposedBridge.log(TAG + s);
    }
}
