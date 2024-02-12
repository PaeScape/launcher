package online.paescape.launcher.utils;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
public class CacheDownloader {

    public static final String CACHE_PATH = System.getProperty("user.home") + File.separator + ".paescape" + File.separator + "cache" + File.separator;
    private static final String ZIP_URL = "https://cdn.paescape.online/PaeScapeCache.zip";
    private static final String VERSION_FILE = CACHE_PATH + "cacheVersion.dat";

    public static boolean needsUpdate() {
        return CacheDownloader.getNewestVersion() != CacheDownloader.getCurrentVersion();
    }

    @SuppressWarnings("resource")
    public static long getCurrentVersion() {
        try {
            File versionDir = new File(VERSION_FILE);

            if (!versionDir.exists()) {
                versionDir.getParentFile().mkdirs();
                versionDir.createNewFile();
                return -1;
            }

            return Long.parseLong(new BufferedReader(new InputStreamReader(new FileInputStream(VERSION_FILE))).readLine());
        } catch (Exception e) {
            handleException(e);
            return -1;
        }
    }

    public static long getNewestVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(ZIP_URL).openConnection();
            conn.addRequestProperty("User-Agent", "PaeScape Client");
            return conn.getContentLengthLong();
        } catch (Exception e) {
            handleException(e);
            return -1;
        }
    }

    public static void handleException(Exception e) {
        log.error("Something went wrong with the CacheDownloader.", e);
        StringBuilder strBuff = new StringBuilder();

        strBuff.append("Please Screenshot this message, and send it to an admin!\r\n\r\n");
        strBuff.append(e.getClass().getName()).append(" \"").append(e.getMessage()).append("\"\r\n");

        for (StackTraceElement s : e.getStackTrace()) {
            strBuff.append(s.toString()).append("\r\n");
        }
        if (!System.getProperty("os.version").contains("Android"))
            alert("Exception [" + e.getClass().getSimpleName() + "]", strBuff.toString(), true);
    }

    private static void alert(String title, String msg, boolean error) {
        JOptionPane.showMessageDialog(null, msg, title, (error ? JOptionPane.ERROR_MESSAGE : JOptionPane.PLAIN_MESSAGE));
    }

    public static void updateCache() throws IOException {
        File clientZip = downloadCache();

        if (clientZip != null) {
            unZip(clientZip);
        }

        new FileOutputStream(VERSION_FILE).write(String.valueOf(getNewestVersion()).getBytes());
    }

    private static void unZip(File clientZip) {
        try {
            unZipFile(clientZip, new File(CACHE_PATH));
            Files.delete(clientZip.toPath());
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void unZipFile(File zipFile, File outFile) throws IOException {
        ZipEntry e;

        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {

            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    new File(outFile, e.getName()).mkdirs();
                } else {
                    try (FileOutputStream out = new FileOutputStream(new File(outFile, e.getName()))) {

                        byte[] b = new byte[1024];

                        int len;

                        while ((len = in.read(b, 0, b.length)) > -1) {
                            out.write(b, 0, len);
                        }
                    }
                }
            }
        }

    }

    private static File downloadCache() {
        File ret = new File(CACHE_PATH + "PaeScapeCache.zip");

        try (OutputStream out = new FileOutputStream(ret)) {
            URLConnection conn = new URL(ZIP_URL).openConnection();
            conn.addRequestProperty("User-Agent", "PaeScape Client");
            InputStream in = conn.getInputStream();

            byte[] b = new byte[1024];

            int len;

            while ((len = in.read(b, 0, b.length)) > -1) {
                out.write(b, 0, len);
            }

            out.flush();
            in.close();
            return ret;
        } catch (Exception e) {
            handleException(e);
            ret.delete();
            return null;
        }
    }
}
