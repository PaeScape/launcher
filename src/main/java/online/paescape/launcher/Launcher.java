/*
 * Copyright (c) 2016-2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package online.paescape.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.vdurmont.semver4j.Semver;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.extern.slf4j.Slf4j;
import online.paescape.launcher.beans.*;
import online.paescape.launcher.utils.CacheDownloader;
import online.paescape.launcher.utils.GitHubUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class Launcher {
    private static final File PAESCAPE_DIR = new File(System.getProperty("user.home"), ".paescape");
    public static final File LOGS_DIR = new File(PAESCAPE_DIR, "logs");
    private static final File REPO_DIR = new File(PAESCAPE_DIR, "repository2");
    public static final File CRASH_FILES = new File(LOGS_DIR, "jvm_crash_pid_%p.log");
    static final String LAUNCHER_BUILD = "https://raw.githubusercontent.com/PaeScape/paescape-launcher/master/build.gradle.kts";
    static final String USER_AGENT = "PaeScape/" + LauncherProperties.getVersion();

    static final String CLIENT_MAIN_CLASS = "net.runelite.client.RuneLite";

    public static void main(String[] args) throws IOException {
        List<GHContent> repoContent = GitHubUtils.getRepo().getDirectoryContent("/");
        Map<String, BootstrapMode> availableBootstraps = repoContent.stream()
                .filter(f -> f.getName().startsWith("bootstrap-"))
                .map(f ->
                {
                    try {
                        return new BootstrapMode(f.getName().replaceAll("bootstrap-", "")
                                .replaceAll(".json", ""), f.getDownloadUrl());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toMap(BootstrapMode::getMode, Function.identity()));

        OptionParser parser = new OptionParser(false);
        parser.allowsUnrecognizedOptions();
        parser.accepts("postinstall", "Perform post-install tasks");
        parser.accepts("clientargs", "Arguments passed to the client").withRequiredArg();
        parser.accepts("nojvm", "Launch the client in this VM instead of launching a new VM");
        parser.accepts("debug", "Enable debug logging");
        parser.accepts("insecure-skip-tls-verification", "Disable TLS certificate and hostname verification");
        parser.accepts("use-jre-truststore", "Use JRE cacerts truststore instead of the Windows Trusted Root Certificate Authorities (only on Windows)");
        parser.accepts("scale", "Custom scale factor for Java 2D").withRequiredArg();
        parser.accepts("help", "Show this text (use --clientargs --help for client help)").forHelp();

        availableBootstraps.values().forEach(b -> parser.accepts(b.getMode(), b.getMode() + " version."));

        if (OS.getOs() == OS.OSType.MacOS) {
            // Parse macos PSN, eg: -psn_0_352342
            parser.accepts("p").withRequiredArg();
        }

        Properties prop = new Properties();

        try {
            prop.load(new FileInputStream(new File(PAESCAPE_DIR, "settings.properties")));
        } catch (IOException ignored) {
        }

        boolean askmode = Optional.ofNullable(prop.getProperty("paescape.askMode")).map(Boolean::valueOf).orElse(true);
        String bootstrapModeProp = prop.getProperty("paescape.bootstrapMode");
        boolean disableHw = Boolean.parseBoolean(prop.getProperty("paescape.disableHw"));

        HardwareAccelerationMode defaultMode;

        if (disableHw) {
            defaultMode = HardwareAccelerationMode.OFF;
        } else {
            switch (OS.getOs()) {
                case Windows:
                    defaultMode = HardwareAccelerationMode.DIRECTDRAW;
                    break;
                case MacOS:
                    defaultMode = HardwareAccelerationMode.OPENGL;
                    break;
                case Linux:
                default:
                    defaultMode = HardwareAccelerationMode.OFF;
                    break;
            }
        }

        // Create typed argument for the hardware acceleration mode
        final ArgumentAcceptingOptionSpec<HardwareAccelerationMode> mode = parser.accepts("mode")
                .withRequiredArg()
                .ofType(HardwareAccelerationMode.class)
                .defaultsTo(defaultMode);

        final OptionSet options;
        final HardwareAccelerationMode hardwareAccelerationMode;
        try {
            options = parser.parse(args);
            hardwareAccelerationMode = options.valueOf(mode);
        } catch (OptionException ex) {
            log.error("unable to parse arguments", ex);

            throw ex;
        }

        if (options.has("help")) {
            try {
                parser.printHelpOn(System.out);
            } catch (IOException e) {
                log.error(null, e);
            }

            System.exit(0);
        }

        String selectedBootstrap = null;

        if (!askmode) {
            selectedBootstrap = bootstrapModeProp.toLowerCase(Locale.ROOT);
        }

        for (BootstrapMode bootstrapMode : availableBootstraps.values()) {
            if (options.has(bootstrapMode.getMode())) {
                selectedBootstrap = bootstrapMode.getMode();
            }
        }

        // Setup debug
        final boolean isDebug = options.has("debug");
        LOGS_DIR.mkdirs();

        if (isDebug) {
            final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            logger.setLevel(Level.DEBUG);
        }

        if (selectedBootstrap == null || availableBootstraps.get(selectedBootstrap) == null) {
            PaeScapeSplashScreen.init(null);
            PaeScapeSplashScreen.barMessage(null);
            PaeScapeSplashScreen.message(null);

            for (BootstrapMode bootstrapMode : availableBootstraps.values()) {
                JButton button = PaeScapeSplashScreen.addButton(StringUtils.capitalize(bootstrapMode.getMode()));
                if (button != null) {
                    button.addActionListener(e -> {
                        PaeScapeSplashScreen.close();
                        Runnable task = () -> launch(hardwareAccelerationMode, options, prop, bootstrapMode);
                        Thread thread = new Thread(task);
                        thread.start();
                    });
                }
            }

            PaeScapeSplashScreen.showButtons();
        } else {
            launch(hardwareAccelerationMode, options, prop, availableBootstraps.get(selectedBootstrap));
        }
    }

    private static void launch(HardwareAccelerationMode mode, OptionSet options, Properties prop, BootstrapMode bootstrapMode) {
        // RTSS triggers off of the CreateWindow event, so this needs to be in place early, prior to splash screen

        PaeScapeSplashScreen.init(bootstrapMode.getMode());

        try {
            PaeScapeSplashScreen.stage(0, "Setting up environment");

            log.info("PaeScape Launcher version {}", LauncherProperties.getVersion());

            final List<String> jvmProps = new ArrayList<>();
            if (options.has("scale")) {
                // On Vista+ this calls SetProcessDPIAware(). Since the RuneLite.exe manifest is DPI unaware
                // Windows will scale the application if this isn't called. Thus the default scaling mode is
                // Windows scaling due to being DPI unaware.
                // https://docs.microsoft.com/en-us/windows/win32/hidpi/high-dpi-desktop-application-development-on-windows
                jvmProps.add("-Dsun.java2d.dpiaware=true");
                // This sets the Java 2D scaling factor, overriding the default behavior of detecting the scale via
                // GetDpiForMonitor.
                jvmProps.add("-Dsun.java2d.uiScale=" + options.valueOf("scale"));
            }

            log.info("Setting hardware acceleration to {}", mode);
            jvmProps.addAll(mode.toParams(OS.getOs()));

            // Always use IPv4 over IPv6
            jvmProps.add("-Djava.net.preferIPv4Stack=true");
            jvmProps.add("-Djava.net.preferIPv4Addresses=true");

            // As of JDK-8243269 (11.0.8) and JDK-8235363 (14), AWT makes macOS dark mode support opt-in so interfaces
            // with hardcoded foreground/background colours don't get broken by system settings. Considering the native
            // Aqua we draw consists a window border and an about box, it's safe to say we can opt in.
            if (OS.getOs() == OS.OSType.MacOS) {
                jvmProps.add("-Dapple.awt.application.appearance=system");
            }

            // Stream launcher version
            jvmProps.add("-D" + LauncherProperties.getVersionKey() + "=" + LauncherProperties.getVersion());

            final boolean insecureSkipTlsVerification = options.has("insecure-skip-tls-verification");

            if (insecureSkipTlsVerification) {
                jvmProps.add("-Dpaescape.insecure-skip-tls-verification=true");
            }

            if (OS.getOs() == OS.OSType.Windows && !options.has("use-jre-truststore")) {
                // Use the Windows Trusted Root Certificate Authorities instead of the bundled cacerts.
                // Corporations, schools, antivirus, and malware commonly install root certificates onto
                // machines for security or other reasons that are not present in the JRE certificate store.
                jvmProps.add("-Djavax.net.ssl.trustStoreType=Windows-ROOT");
            }

            // java2d properties have to be set prior to the graphics environment startup
            setJvmParams(jvmProps);

            List<String> jvmParams = new ArrayList<>();
            // Set hs_err_pid location. This is a jvm param and can't be set at runtime.
            log.debug("Setting JVM crash log location to {}", CRASH_FILES);
            jvmParams.add("-XX:ErrorFile=" + CRASH_FILES.getAbsolutePath());

            if (insecureSkipTlsVerification) {
                setupInsecureTrustManager();
            }

            final boolean postInstall = options.has("postinstall");

            if (postInstall) {
                postInstall(jvmParams, bootstrapMode);
                return;
            }

            // Print out system info
            if (log.isDebugEnabled()) {
                log.debug("Java Environment:");
                final Properties p = System.getProperties();
                final Enumeration<Object> keys = p.keys();

                while (keys.hasMoreElements()) {
                    final String key = (String) keys.nextElement();
                    final String value = (String) p.get(key);
                    log.debug("  {}: {}", key, value);
                }
            }

            PaeScapeSplashScreen.stage(.05, "Downloading bootstrap");
            Bootstrap bootstrap;
            try {
                bootstrap = getBootstrap(bootstrapMode);
            } catch (IOException ex) {
                log.error("error fetching bootstrap", ex);
                PaeScapeSplashScreen.setError("Error while downloading the bootstrap!", "You have encountered an issue, please check your log files for a more detailed error message.");
                return;
            }

            PaeScapeSplashScreen.stage(.10, "Tidying dependencies");

            boolean launcherTooOld = bootstrap.getRequiredLauncherVersion() != null &&
                    compareVersion(bootstrap.getRequiredLauncherVersion(), LauncherProperties.getVersion()) > 0;

            boolean jvmTooOld = false;
            try {
                if (bootstrap.getRequiredJVMVersion() != null) {
                    jvmTooOld = Runtime.Version.parse(bootstrap.getRequiredJVMVersion())
                            .compareTo(Runtime.version()) > 0;
                }
            } catch (IllegalArgumentException e) {
                log.warn("Unable to parse bootstrap version", e);
            }

            boolean nojvm = Boolean.parseBoolean(prop.getProperty("paescape.noJvm")) || "true".equals(System.getProperty("paescape.launcher.nojvm")) || "true".equals(System.getProperty("paescape.launcher.nojvm"));

            if (launcherTooOld || (nojvm && jvmTooOld)) {
                PaeScapeSplashScreen.setError("Error while downloading the client!", "You have encountered an issue, please check your log files for a more detailed error message.");
                return;
            }
            if (jvmTooOld) {
                PaeScapeSplashScreen.setError("Your Java installation is too old", "PaeScape now requires Java " +
                        bootstrap.getRequiredJVMVersion() + " to run. You can get a platform specific version from paescape.com," +
                        " or install a newer version of Java.");
                return;
            }

            if (!checkVersion(bootstrap)) {
                log.error("launcher version too low");
                PaeScapeSplashScreen.setError("Your launcher is outdated!", "The launcher you're using is oudated. Please either download a newer version from https://github.com/PaeScape/paescape-launcher or by clicking the update button on the right hand side.");
                return;
            }

            // update packr vmargs. The only extra vmargs we need to write to disk are the ones which cannot be set
            // at runtime, which currently is just the vm errorfile.
            PackrConfig.updateLauncherArgs(bootstrap, jvmParams);

            REPO_DIR.mkdirs();

            // Determine artifacts for this OS
            List<Artifact> artifacts = Arrays.stream(bootstrap.getArtifacts())
                    .filter(a ->
                    {
                        if (a.getPlatform() == null) {
                            return true;
                        }

                        final String os = System.getProperty("os.name");
                        final String arch = System.getProperty("os.arch");
                        for (Platform platform : a.getPlatform()) {
                            if (platform.getName() == null) {
                                continue;
                            }

                            OS.OSType platformOs = OS.parseOs(platform.getName());
                            if ((platformOs == OS.OSType.Other ? platform.getName().equals(os) : platformOs == OS.getOs())
                                    && (platform.getArch() == null || platform.getArch().equals(arch))) {
                                return true;
                            }
                        }

                        return false;
                    })
                    .collect(Collectors.toList());

            // Clean out old artifacts from the repository
            clean(artifacts);

            try {
                download(artifacts);
            } catch (IOException ex) {
                log.error("unable to download artifacts", ex);
                PaeScapeSplashScreen.setError("Error while downloading the client!", "You have encountered an issue, please check your log files for a more detailed error message.");
                return;
            }

            PaeScapeSplashScreen.stage(.50, "Verifying");
            try {
                verifyJarHashes(artifacts);
            } catch (VerificationException ex) {
                log.error("Unable to verify artifacts", ex);
                PaeScapeSplashScreen.setError("Error while verifying downloaded files!", "You have encountered an issue, please check your log files for a more detailed error message.");
                return;
            }

            final Collection<String> clientArgs = getClientArgs(options);

            if (log.isDebugEnabled()) {
                clientArgs.add("--debug");
            }

            PaeScapeSplashScreen.stage(.60, "Checking cache version");

            try {
                if (CacheDownloader.needsUpdate()) {
                    PaeScapeSplashScreen.stage(.75, "Updating cache");
                    CacheDownloader.updateCache();
                }

            } catch (Exception e) {
                CacheDownloader.handleException(e);
                throw e;
            }

            PaeScapeSplashScreen.stage(.90, "Starting the client");

            List<File> classpath = artifacts.stream()
                    .map(dep -> new File(REPO_DIR, dep.getName()))
                    .collect(Collectors.toList());

            // packr doesn't let us specify command line arguments
            if (nojvm || options.has("nojvm")) {
                try {
                    ReflectionLauncher.launch(classpath, clientArgs);
                } catch (MalformedURLException ex) {
                    log.error("unable to launch client", ex);
                }
            } else {
                try {
                    JvmLauncher.launch(bootstrap, classpath, clientArgs, jvmProps, jvmParams);
                } catch (IOException ex) {
                    log.error("unable to launch client", ex);
                }
            }
        } catch (
                Exception e) {
            log.error("Failure during startup", e);
            final boolean postInstall = options.has("postinstall");
            if (!postInstall) {
                PaeScapeSplashScreen.setError("Error during startup!", "PaeScape has encountered an unexpected " +
                        "error during startup, please check your log files for a more detailed error message.");
            }
        } catch (
                Error e) {
            // packr seems to eat exceptions thrown out of main, so at least try to log it
            log.error("Failure during startup", e);
            throw e;
        } finally {
            PaeScapeSplashScreen.close();
        }
    }

    private static boolean checkVersion(Bootstrap bootstrap) {
        if (bootstrap.getMinimumLauncherVersion() == null || LauncherProperties.getVersion() == null) {
            return true;
        }
        Semver minimum = new Semver(bootstrap.getMinimumLauncherVersion()).withClearedSuffixAndBuild();
        Semver ours = new Semver(LauncherProperties.getVersion()).withClearedSuffixAndBuild();
        return !ours.isLowerThan(minimum);
    }

    private static void setJvmParams(final Collection<String> params) {
        for (String param : params) {
            final String[] split = param.replace("-D", "").split("=");
            System.setProperty(split[0], split[1]);
        }
    }

    private static Bootstrap getBootstrap(BootstrapMode bootstrapMode) throws IOException {
        URL u = new URL(bootstrapMode.getJsonUrl());

        log.info(String.valueOf(u));

        URLConnection conn = u.openConnection();

        conn.setRequestProperty("User-Agent", USER_AGENT);

        try (InputStream i = conn.getInputStream()) {
            byte[] bytes = ByteStreams.toByteArray(i);

            Gson g = new Gson();
            return g.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes)), Bootstrap.class);
        }
    }

    private static Collection<String> getClientArgs(OptionSet options) {
        final Collection<String> args = options.nonOptionArguments().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toCollection(ArrayList::new));

        String clientArgs = System.getenv("PAESCAPE_ARGS");
        if (!Strings.isNullOrEmpty(clientArgs)) {
            args.addAll(Splitter.on(' ').omitEmptyStrings().trimResults().splitToList(clientArgs));
        }

        clientArgs = (String) options.valueOf("clientargs");
        if (!Strings.isNullOrEmpty(clientArgs)) {
            args.addAll(Splitter.on(' ').omitEmptyStrings().trimResults().splitToList(clientArgs));
        }

        return args;
    }

    private static void download(List<Artifact> artifacts) throws IOException {
        List<Artifact> toDownload = new ArrayList<>(artifacts.size());
        Map<Artifact, Diff> diffs = new HashMap<>();
        int totalDownloadBytes = 0;

        for (Artifact artifact : artifacts) {
            File dest = new File(REPO_DIR, artifact.getName());

            String hash;
            try {
                hash = hash(dest);
            } catch (FileNotFoundException ex) {
                hash = null;
            }

            if (Objects.equals(hash, artifact.getHash())) {
                log.debug("Hash for {} up to date", artifact.getName());
                continue;
            }

            int downloadSize = artifact.getSize();

            toDownload.add(artifact);
            totalDownloadBytes += downloadSize;
        }

        final double START_PROGRESS = .15;
        int downloaded = 0;
        PaeScapeSplashScreen.stage(START_PROGRESS, "Downloading");

        for (Artifact artifact : toDownload) {
            File dest = new File(REPO_DIR, artifact.getName());
            final int total = downloaded;

            log.debug("Downloading {}", artifact.getName());

            try (FileOutputStream fout = new FileOutputStream(dest)) {
                final int totalBytes = totalDownloadBytes;
                download(artifact.getPath(), artifact.getHash(), (completed) ->
                                PaeScapeSplashScreen.stage(START_PROGRESS, .80, artifact.getName(), total + completed, totalBytes, true),
                        fout);
                downloaded += artifact.getSize();
            } catch (VerificationException e) {
                log.warn("unable to verify jar {}", artifact.getName(), e);
            }
        }
    }

    private static void clean(List<Artifact> artifacts) {
        File[] existingFiles = REPO_DIR.listFiles();

        if (existingFiles == null) {
            return;
        }

        Set<String> artifactNames = new HashSet<>();
        for (Artifact artifact : artifacts) {
            artifactNames.add(artifact.getName());
            if (artifact.getDiffs() != null) {
                // Keep around the old files which diffs are from
                for (Diff diff : artifact.getDiffs()) {
                    artifactNames.add(diff.getFrom());
                }
            }
        }

        for (File file : existingFiles) {
            if (file.isFile() && !artifactNames.contains(file.getName())) {
                if (file.delete()) {
                    log.debug("Deleted old artifact {}", file);
                } else {
                    log.warn("Unable to delete old artifact {}", file);
                }
            }
        }
    }

    private static void verifyJarHashes(List<Artifact> artifacts) throws VerificationException {
        for (Artifact artifact : artifacts) {
            String expectedHash = artifact.getHash();
            String fileHash;
            try {
                fileHash = hash(new File(REPO_DIR, artifact.getName()));
            } catch (IOException e) {
                throw new VerificationException("unable to hash file", e);
            }

            if (!fileHash.equals(expectedHash)) {
                log.warn("Expected {} for {} but got {}", expectedHash, artifact.getName(), fileHash);
                throw new VerificationException("Expected " + expectedHash + " for " + artifact.getName() + " but got " + fileHash);
            }

            log.info("Verified hash of {}", artifact.getName());
        }
    }

    private static String hash(File file) throws IOException {
        HashFunction sha256 = Hashing.sha256();
        return Files.asByteSource(file).hash(sha256).toString();
    }

    @VisibleForTesting
    static int compareVersion(String a, String b) {
        Pattern tok = Pattern.compile("[^0-9a-zA-Z]");
        return Arrays.compare(tok.split(a), tok.split(b), (x, y) ->
        {
            Integer ix = null;
            try {
                ix = Integer.parseInt(x);
            } catch (NumberFormatException e) {
            }

            Integer iy = null;
            try {
                iy = Integer.parseInt(y);
            } catch (NumberFormatException e) {
            }

            if (ix == null && iy == null) {
                return x.compareToIgnoreCase(y);
            }

            if (ix == null) {
                return -1;
            }
            if (iy == null) {
                return 1;
            }

            if (ix > iy) {
                return 1;
            }
            if (ix < iy) {
                return -1;
            }

            return 0;
        });
    }

    private static void download(String path, String hash, IntConsumer progress, OutputStream out) throws IOException, VerificationException {
        URL url = new URL(path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.getResponseCode();

        InputStream err = conn.getErrorStream();
        if (err != null) {
            err.close();
            throw new IOException("Unable to download " + path + " - " + conn.getResponseMessage());
        }

        int downloaded = 0;
        HashingOutputStream hout = new HashingOutputStream(Hashing.sha256(), out);
        try (InputStream in = conn.getInputStream()) {
            int i;
            byte[] buffer = new byte[1024 * 1024];
            while ((i = in.read(buffer)) != -1) {
                hout.write(buffer, 0, i);
                downloaded += i;
                progress.accept(downloaded);
            }
        }

        HashCode hashCode = hout.hash();
        if (!hash.equals(hashCode.toString())) {
            throw new VerificationException("Unable to verify resource " + path + " - expected " + hash + " got " + hashCode);
        }
    }

    static boolean isJava17() {
        // 16 has the same module restrictions as 17, so we'll use the 17 settings for it
        return Runtime.version().feature() >= 16;
    }

    private static void setupInsecureTrustManager() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager trustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        };

        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }

    private static void postInstall(List<String> jvmParams, BootstrapMode bootstrapMode) {
        Bootstrap bootstrap;
        try {
            bootstrap = getBootstrap(bootstrapMode);
        } catch (IOException ex) {
            log.error("error fetching bootstrap", ex);
            return;
        }

        PackrConfig.updateLauncherArgs(bootstrap, jvmParams);

        log.info("Performed postinstall steps");
    }
}