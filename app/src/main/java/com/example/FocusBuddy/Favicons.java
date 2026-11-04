package com.example.FocusBuddy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a website's own icon straight from that site (no third-party icon service) and keeps a
 * copy in the app's cache, so it shows instantly, and offline, the next time.
 * Everything here blocks on the network: call it from a background thread.
 */
public final class Favicons {
    private Favicons() {}

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_IMAGE_BYTES = 256 * 1024;
    private static final int MAX_HTML_BYTES = 200 * 1024;
    private static final Pattern LINK_TAG =
            Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_ICON =
            Pattern.compile("rel\\s*=\\s*[\"'][^\"']*icon[^\"']*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** The site's icon, or null if it has none we can read. */
    public static Drawable load(Context c, String host) {
        File cached = cacheFile(c, host);
        Bitmap bmp = cached.exists() ? BitmapFactory.decodeFile(cached.getPath()) : null;
        if (bmp == null) {
            bmp = download(host);
            if (bmp != null) save(cached, bmp);
        }
        return bmp == null ? null : new BitmapDrawable(c.getResources(), prepare(bmp));
    }

    // ---------- Download ----------

    private static Bitmap download(String host) {
        String base = "https://" + host + "/";
        Bitmap bmp = decode(fetch(base + "favicon.ico", MAX_IMAGE_BYTES));
        if (bmp != null) return bmp;

        // No /favicon.ico: look for an icon the home page declares
        byte[] html = fetch(base, MAX_HTML_BYTES);
        if (html == null) return null;
        for (String href : iconLinks(new String(html))) {
            try {
                String url = new URL(new URL(base), href).toString();
                if (!url.startsWith("https://")) continue;
                bmp = decode(fetch(url, MAX_IMAGE_BYTES));
                if (bmp != null) return bmp;
            } catch (IOException ignored) {
                // Bad link, try the next one
            }
        }
        return null;
    }

    private static List<String> iconLinks(String html) {
        List<String> out = new ArrayList<>();
        Matcher tags = LINK_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (!REL_ICON.matcher(tag).find()) continue;
            Matcher href = HREF.matcher(tag);
            // BitmapFactory can't draw SVG
            if (href.find() && !href.group(1).toLowerCase().contains(".svg")) {
                out.add(href.group(1));
            }
        }
        return out;
    }

    private static byte[] fetch(String url, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FocusBuddy");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) return null;
                }
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decode(byte[] data) {
        return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    // ---------- Cache and display ----------

    private static File cacheFile(Context c, String host) {
        File dir = new File(c.getCacheDir(), "favicons");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, host.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png");
    }

    private static void save(File file, Bitmap bmp) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) {
            // Not cached; we'll just fetch it again next time
        }
    }

    /** Favicons are often 16x16; scale them up smoothly so they don't look blocky in the list. */
    private static Bitmap prepare(Bitmap bmp) {
        return bmp.getWidth() >= 64
                ? bmp
                : Bitmap.createScaledBitmap(bmp, 96, 96, true);
    }
}
