package net.legacylauncher.bootstrap;

import com.github.zafarkhaja.semver.Version;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpecBuilder;
import lombok.extern.slf4j.Slf4j;
import net.legacylauncher.bootstrap.exception.FatalExceptionType;
import net.legacylauncher.bootstrap.ipc.BootstrapIPC;
import net.legacylauncher.bootstrap.ipc.BootstrapIPCProvider;
import net.legacylauncher.bootstrap.launcher.*;
import net.legacylauncher.bootstrap.meta.*;
import net.legacylauncher.bootstrap.ssl.FixSSL;
import net.legacylauncher.bootstrap.task.Task;
import net.legacylauncher.bootstrap.task.TaskInterruptedException;
import net.legacylauncher.bootstrap.ui.HeadlessInterface;
import net.legacylauncher.bootstrap.ui.IInterface;
import net.legacylauncher.bootstrap.ui.UserInterface;
import net.legacylauncher.bootstrap.ui.flatlaf.FlatLaf;
import net.legacylauncher.bootstrap.update.GitHubReleases;
import net.legacylauncher.bootstrap.util.*;
import net.legacylauncher.bootstrap.util.stream.OutputRedirectBuffer;
import net.legacylauncher.util.shared.FlatLafConfiguration;
import net.legacylauncher.util.shared.JavaVersion;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public final class Bootstrap {
    static {
        FixSSL.addLetsEncryptCertSupportIfNeeded();
    }

    static Bootstrap createBootstrap(String[] rawArgs) throws InterruptedException {
        log.info("Starting bootstrap...");

        LocalBootstrapMeta localBootstrapMeta = LocalBootstrapMeta.getInstance();

        OptionParser bootstrapParser = new OptionParser();
        ArgumentAcceptingOptionSpec<Path> targetFileParser =
                bootstrapParser.accepts("targetJar", "points to the targetJar").withRequiredArg().withValuesConvertedBy(new PathValueConverter());
        ArgumentAcceptingOptionSpec<Path> targetLibFolderParser =
                bootstrapParser.accepts("targetLibFolder", "points to the library folder").withRequiredArg().withValuesConvertedBy(new PathValueConverter());
        ArgumentAcceptingOptionSpec<String> brandParser =
                bootstrapParser.accepts("brand", "defines brand name").withRequiredArg().ofType(String.class);
        OptionSpecBuilder forceUpdateParser =
                bootstrapParser.accepts("ignoreUpdate", "defines if bootstrap should ignore launcher update processes");
        OptionSpecBuilder ignoreSelfUpdateParser =
                bootstrapParser.accepts("ignoreSelfUpdate", "defines if bootstrap should ignore self update processes");
        OptionSpecBuilder forceHeadlessMode =
                bootstrapParser.accepts("headlessMode", "defines if bootstrap should run without UI");
        ArgumentAcceptingOptionSpec<String> packageMode =
                bootstrapParser.accepts("packageMode", "defines if bootstrap runs inside a package").withOptionalArg();
        ArgumentAcceptingOptionSpec<String> restartExec =
                bootstrapParser.accepts("restartExec", "instructs the bootstrap to run this executable after self update").withRequiredArg().ofType(String.class);
        OptionSpecBuilder requireMinecraftAccount =
                bootstrapParser.accepts("requireMinecraftAccount", "require minecraft account to use any other account kinds");
        OptionSpecBuilder fork =
                bootstrapParser.accepts("fork", "run the launcher in the separate jvm");

        OptionParser launcherParser = new OptionParser();
        launcherParser.allowsUnrecognizedOptions();
        ArgumentAcceptingOptionSpec<String> settings =
                launcherParser.accepts("settings").withRequiredArg();

        SplitArgs args;
        try {
            args = SplitArgs.splitArgs(rawArgs);
        } catch (RuntimeException rE) {
            throw new RuntimeException("couldn't split args: " + Arrays.toString(rawArgs), rE);
        }

        Path bootstrapJar = BootstrapStarter.getCurrentJarLocation().getPath();

        RunningConditionsResult runningConditions = checkRunningConditions(bootstrapJar);
        runningConditions.showErrorIfNeeded();

        CombinedOptionSet bootstrapParsed = new CombinedOptionSet(parseJvmArgs(bootstrapParser), bootstrapParser.parse(args.getBootstrap()));

        if (bootstrapParsed.has(brandParser)) {
            String brand = bootstrapParsed.valueOf(brandParser);
            log.info("Picked up brand from arguments: {}", brand);
            localBootstrapMeta.setShortBrand(brand);
        }
        log.info("Short brand: {}", localBootstrapMeta.getShortBrand());

        OptionSet launcherParsed = launcherParser.parse(args.getLauncher());

        Path configFile;
        if (launcherParsed.has(settings)) {
            configFile = Paths.get(launcherParsed.valueOf(settings));
        } else {
            configFile = TargetConfig.getDefaultConfigFilePath(localBootstrapMeta.getShortBrand());
        }

        TargetConfig targetConfig = TargetConfig.readConfigFromFile(configFile);

        Bootstrap bootstrap = new Bootstrap(args.getLauncher(), bootstrapJar, targetConfig);

        log.info("Version: {}", localBootstrapMeta.getVersion());

        bootstrap.setupUserInterface(bootstrapParsed.has(forceHeadlessMode));

        Path targetJar = bootstrapParsed.valueOf(targetFileParser); // can be null
        if (targetJar == null) {
            targetJar = Objects.requireNonNull(LocalLauncher.getDefaultFileLocation(localBootstrapMeta.getShortBrand()), "defaultFileLocation");
        }
        bootstrap.setTargetJar(targetJar);
        log.info("Target jar: {}", bootstrap.getTargetJar());

        Path targetLibFolder = bootstrapParsed.valueOf(targetLibFolderParser); // can be null
        if (targetLibFolder == null) {
            if (bootstrap.getTargetJar().getParent() == null) {
                targetLibFolder = Paths.get("lib");
            } else {
                /*
                    .tlauncher/bin/legacy.jar ->
                    .tlauncher/bin ->
                    .tlauncher/bin/lib
                 */
                targetLibFolder = bootstrap.getTargetJar().getParent().resolve("lib");
            }
        }
        bootstrap.setTargetLibFolder(targetLibFolder);
        log.info("Target lib folder: {}", bootstrap.getTargetLibFolder());

        bootstrap.setIgnoreUpdate(bootstrapParsed.has(forceUpdateParser));
        log.info("Ignore launcher update: {}", bootstrap.getIgnoreUpdate());

        bootstrap.setIgnoreSelfUpdate(bootstrapParsed.has(ignoreSelfUpdateParser));
        log.info("Ignore self update: {}", bootstrap.getIgnoreSelfUpdate());

        bootstrap.setPackageMode(bootstrapParsed.valueOf(packageMode));
        log.info("Package mode: {}", bootstrap.isPackageMode() ? bootstrap.getPackageMode() : "false");
        bootstrap.whenIPCReady(ipc -> ipc.setMetadata("package_mode", bootstrap.isPackageMode() ? bootstrap.getPackageMode() : ""));

        log.info("Require Minecraft account: {}", bootstrapParsed.has(requireMinecraftAccount));
        bootstrap.whenIPCReady(ipc -> ipc.setMetadata("require_minecraft_account", bootstrapParsed.has(requireMinecraftAccount)));

        log.info("Fork: {}", bootstrapParsed.has(fork));
        bootstrap.setFork(bootstrapParsed.has(fork));

        if (bootstrapParsed.has(restartExec)) {
            bootstrap.setRestartCmd(Collections.singletonList(bootstrapParsed.valueOf(restartExec)));
            log.info("Restart cmd on self update: {}", bootstrap.getRestartCmd());
        } else if ("dmg".equals(bootstrap.getPackageMode())) {
            String appPath = System.getProperty("jpackage.app-path");
            if (appPath == null) {
                log.warn("Current package mode is dmg, but jpackage.app-path is not set");
            } else {
                final String expectedPathSuffix = "/Contents/MacOS/TL";
                if (appPath.endsWith(expectedPathSuffix)) {
                    appPath = appPath.substring(0, appPath.length() - expectedPathSuffix.length());
                    bootstrap.setRestartCmd(
                            Arrays.asList(
                                    "sh",
                                    appPath + "/Contents/app/restart.sh",
                                    appPath
                            )
                    );
                    log.info("Picked up restart exec for jpackage: {}", bootstrap.getRestartCmd());
                    String finalAppPath = appPath;
                    bootstrap.whenIPCReady(ipc -> ipc.setMetadata("dmg-app-path", finalAppPath));
                } else {
                    log.warn("jpackage.app-path is not recognized: {}", appPath);
                    log.warn("Auto-restart will not be enabled");
                }
            }
        }

        if (bootstrap.isPackageMode()) {
            boolean ignoreUpdate = false;
            switch (bootstrap.getPackageMode()) {
                case "aur":
                    ignoreUpdate = true;
                    break;
            }
            if (ignoreUpdate) {
                bootstrap.setIgnoreSelfUpdate(true);
                bootstrap.setIgnoreUpdate(true);
                log.info("Package mode: Ignore self update set to {}", bootstrap.getIgnoreSelfUpdate());
                log.info("Package mode: Ignore update set to {}", bootstrap.getIgnoreUpdate());
            }
        }

        return bootstrap;
    }

    private static OptionSet parseJvmArgs(OptionParser parser) {
        List<String> jvmArgs = new ArrayList<>();

        for (String key : parser.recognizedOptions().keySet()) {
            String value = System.getProperty("tlauncher.bootstrap." + key);
            if (value != null) {
                jvmArgs.add("--" + key);
                jvmArgs.add(value);
                log.info("Found JVM arg: {} = {}", key, value);
            }
        }

        return parser.parse(jvmArgs.toArray(new String[0]));
    }

    public static void main(String[] args) {
        System.setOut(OutputRedirectBuffer.createRedirect(System.out));
        System.setErr(OutputRedirectBuffer.createRedirect(System.err));

        Bootstrap bootstrap = null;

        try {
            bootstrap = createBootstrap(args);
            bootstrap.defTask().call();
        } catch (TaskInterruptedException interrupted) {
            log.warn("Default task was interrupted");
        } catch (InterruptedException interrupted) {
            log.warn("Interrupted");
        } catch (Exception e) {
            log.error("Fatal error", e);
            handleFatalError(bootstrap, e);
            System.exit(-1);
        }
        System.exit(0);
    }

    static void handleFatalError(Bootstrap bootstrap, Throwable e) {
        FatalExceptionType exceptionType = FatalExceptionType.getType(e);
        if (bootstrap != null) {
            bootstrap.getUserInterface().dispose();
        }
        UserInterface.showFatalError(exceptionType);
    }

    private final InternalLauncher internal;
    private BootstrapIPC ipc;
    private final List<Consumer<BootstrapIPC>> ipcReady = new CopyOnWriteArrayList<>();
    private final TargetConfig config;

    private IInterface ui;
    private final String[] launcherArgs;
    private final Path bootstrapJar;
    private Path targetJar;
    private Path targetLibFolder;
    private String packageMode;
    private boolean ignoreUpdate, ignoreSelfUpdate;
    private List<String> restartCmd;
    private boolean fork;

    Bootstrap(String[] launcherArgs, Path bootstrapJar, TargetConfig config, Path targetJar, Path targetLibFolder) {
        this.launcherArgs = launcherArgs;
        this.bootstrapJar = bootstrapJar;
        this.config = config;

        InternalLauncher internal = null;
        try {
            internal = new InternalLauncher();
        } catch (LauncherNotFoundException e) {
            log.info("Internal launcher is not located in the classpath");
        }
        this.internal = internal;

        setTargetJar(targetJar);
        setTargetLibFolder(targetLibFolder);

        whenIPCReady(ipc -> ipc.setMetadata("jna", Boolean.TRUE));

        // there is no beta channel: updates come from GitHub releases
        whenIPCReady(ipc -> ipc.setMetadata("can_switch_to_beta_branch", Boolean.FALSE));

        whenIPCReady(ipc -> ipc.setMetadata("has_flatlaf", Boolean.TRUE));
        SwingUtilities.invokeLater(() -> {
            FlatLafConfiguration flatLafConfig = FlatLafConfiguration.parseFromMap(
                    Bootstrap.this.config.isEmpty() || Bootstrap.this.config.isFirstRun() ?
                            FlatLafConfiguration.getDefaults() : Bootstrap.this.config.asMap()
            );
            String guiSystemLookAndFeel = Bootstrap.this.config.get("gui.systemlookandfeel");
            boolean preFlatLafConfiguration = !flatLafConfig.getState().isPresent() && guiSystemLookAndFeel != null;
            if (preFlatLafConfiguration) {
                log.info("Detected pre-FlatLaf configuration");
            }
            if (preFlatLafConfiguration && "true".equals(guiSystemLookAndFeel) || flatLafConfig.getState().filter(s -> s == FlatLafConfiguration.State.SYSTEM).isPresent()) {
                log.info("Using system L&F");
                UserInterface.setSystemLookAndFeel();
            } else if (preFlatLafConfiguration && "false".equals(guiSystemLookAndFeel)) {
                log.info("Not setting L&F on pre-FlatLaf configuration because gui.systemlookandfeel == false");
            } else {
                if (flatLafConfig.isEnabled()) {
                    log.info("Using FlatLaf configuration");
                    FlatLaf.initialize(flatLafConfig);
                } else {
                    log.info("Not setting L&F because FlatLaf is not enabled");
                }
            }
            // flags bootstrap took care of setting L&F
            whenIPCReady(ipc -> ipc.setMetadata("set_laf", Boolean.TRUE));
        });
    }

    public Bootstrap(String[] launcherArgs, Path bootstrapJar, TargetConfig targetConfig) {
        this(launcherArgs, bootstrapJar, targetConfig, null, null);
    }

    public void setupUserInterface(boolean forceHeadlessMode) throws InterruptedException {
        if (ui != null) {
            return;
        }

        log.info("Setting up user interface");
        if (forceHeadlessMode) {
            log.info("Forcing headless mode");
        } else {
            log.info("Trying to load user interface");
            try {
                ui = UserInterface.createInterface();
                log.info("UI loaded");
                return;
            } catch (RuntimeException e) {
                log.warn("User interface is not loaded:", e);
            }
        }

        ui = new HeadlessInterface();
        log.info("Headless mode loaded");
    }

    IInterface getUserInterface() {
        return ui;
    }

    public Path getTargetJar() {
        return targetJar;
    }

    private void setTargetJar(Path file) {
        this.targetJar = file;
    }

    public Path getTargetLibFolder() {
        return targetLibFolder;
    }

    private void setTargetLibFolder(Path targetLibFolder) {
        this.targetLibFolder = targetLibFolder;
    }

    public boolean getIgnoreUpdate() {
        return ignoreUpdate;
    }

    private void setIgnoreUpdate(boolean ignore) {
        this.ignoreUpdate = ignore;
    }

    public boolean getIgnoreSelfUpdate() {
        return ignoreSelfUpdate;
    }

    public void setIgnoreSelfUpdate(boolean ignoreSelfUpdate) {
        this.ignoreSelfUpdate = ignoreSelfUpdate;
    }

    public String getPackageMode() {
        return packageMode;
    }

    public boolean isPackageMode() {
        return packageMode != null;
    }

    public void setPackageMode(String packageMode) {
        this.packageMode = packageMode;
    }

    public List<String> getRestartCmd() {
        return restartCmd;
    }

    public void setRestartCmd(List<String> restartCmd) {
        this.restartCmd = restartCmd;
    }

    public boolean isFork() {
        return fork;
    }

    public void setFork(boolean fork) {
        this.fork = fork;
    }

    BootstrapIPC getBootstrapIPC() {
        return ipc;
    }

    Task<Void> prepareLibraries(LocalLauncherMeta localLauncherMeta) {
        return new Task<Void>("prepareLibraries") {
            @Override
            protected Void execute() throws Exception {
                List<Library> libraries = localLauncherMeta.getLibraries();
                Path libDir = getTargetLibFolder();
                for (int i = 0; i < libraries.size(); i++) {
                    updateProgress((double) i / libraries.size());
                    checkInterrupted();
                    libraries.get(i).prepare(libDir);
                }
                return null;
            }
        };
    }

    Task<LocalLauncherTask> prepareLauncher() {
        return new Task<LocalLauncherTask>("prepareLauncher") {
            @Override
            protected LocalLauncherTask execute() throws Exception {
                LocalLauncherTask localLauncherTask = bindTo(getLocalLauncher(), .0, .25);
                LocalLauncher localLauncher = localLauncherTask.getLauncher();
                LocalLauncherMeta localLauncherMeta = localLauncher.getMeta();
                log.info("Local launcher: {}", localLauncher);
                printVersion(localLauncherMeta);

                log.info("Preparing libraries...");
                bindTo(prepareLibraries(localLauncherMeta), .25, 1.);

                return localLauncherTask;
            }
        };
    }

    Task<Void> startLauncher(final LocalLauncher localLauncher) {
        return new Task<Void>("startLauncher") {
            @Override
            protected Void execute() throws Exception {
                log.info("Starting launcher...");

                ipc.addListener(new BootstrapIPC.Listener() {
                    @Override
                    public void onBootProgress(String stepName, double percentage) {
                        updateProgress(percentage);
                    }
                });

                return bindTo(ipc.start(localLauncher), 0., 1.);
            }
        };
    }

    private Task<Void> defTask() {
        return new Task<Void>("defTask") {
            {
                if (ui != null) {
                    ui.bindToTask(this);
                }
            }

            @Override
            protected Void execute() throws Exception {
                printVersion(null);
                lowerRequirementsIfNeeded();

                if (bindTo(checkForUpdates(), .0, .25)) {
                    return null; // the updater restarts or exits the application
                }

                LocalLauncherTask localLauncherTask = bindTo(prepareLauncher(), .25, .75);
                LocalLauncher localLauncher = localLauncherTask.getLauncher();

                initIPC(localLauncher);

                bindTo(startLauncher(localLauncher), 0.75, 1.);

                checkInterrupted();

                log.info("Idle state: Waiting for launcher the close");
                ipc.waitUntilClose();

                return null;
            }
        };
    }

    /**
     * Asks the user to update if there's a newer release on GitHub.
     *
     * @return true if the update has been applied and the application should not continue
     */
    private Task<Boolean> checkForUpdates() {
        return new Task<Boolean>("checkUpdate") {
            @Override
            protected Boolean execute() throws Exception {
                if (internal == null) {
                    log.info("Not a release build (no embedded launcher), skipping update check");
                    return false;
                }
                if (getIgnoreSelfUpdate()) {
                    log.info("Self update is disabled, skipping update check");
                    return false;
                }
                Optional<GitHubReleases.Release> latest = bindTo(
                        GitHubReleases.fetchLatest(BuildConfig.UPDATE_REPOSITORY), .0, 1.
                );
                if (!latest.isPresent()) {
                    return false;
                }
                GitHubReleases.Release release = latest.get();
                Version current = LocalBootstrapMeta.getInstance().getVersion();
                log.info("Latest release: {}, current version: {}", release, current);
                if (!release.getVersion().isHigherThan(current)) {
                    return false;
                }
                String question = String.format(
                        UserInterface.getLString("update.available",
                                "%s %s is available (you have %s). Update now?"),
                        BuildConfig.PRODUCT_NAME,
                        release.getVersion(),
                        current.getNormalVersion()
                );
                if (!UserInterface.askYesNo(question)) {
                    log.info("User declined the update");
                    return false;
                }
                Updater updater = new Updater("selfUpdate", bootstrapJar, new DownloadEntry(
                        GitHubReleases.ASSET_NAME,
                        Collections.singletonList(release.getDownloadUrl()),
                        release.getSha256()
                ));
                updater.restartOnFinish(getRestartCmd() != null ? getRestartCmd() : defaultRestartCmd());
                bindTo(updater, .0, 1.);
                return true;
            }
        };
    }

    private List<String> defaultRestartCmd() {
        Path javaBin = Paths.get(System.getProperty("java.home"), "bin");
        Path java = javaBin.resolve(OS.WINDOWS.isCurrent() ? "javaw.exe" : "java");
        if (!Files.isRegularFile(java)) {
            java = javaBin.resolve(OS.WINDOWS.isCurrent() ? "java.exe" : "java");
        }
        return Arrays.asList(java.toString(), "-jar", bootstrapJar.toAbsolutePath().toString());
    }

    private void lowerRequirementsIfNeeded() throws Exception {
        FileStat bootstrapStat = fileStat(bootstrapJar);
        if (!bootstrapStat.writeable && !getIgnoreSelfUpdate()) {
            log.warn("Bootstrap jar not writeable, disable self updating");
            setIgnoreSelfUpdate(true);
        }

        Path targetJarParent = getTargetJar().getParent();
        if (targetJarParent != null) {
            Files.createDirectories(targetJarParent);
        }

        FileStat launcherStat = fileStat(getTargetJar());
        if (launcherStat.exists && !launcherStat.writeable && !getIgnoreUpdate()) {
            log.warn("Launcher jar not writeable, disable updating");
            setIgnoreUpdate(true);
        }

        Files.createDirectories(getTargetLibFolder());

        FileStat libStat = fileStat(getTargetLibFolder());
        if (!libStat.writeable && !getIgnoreUpdate()) {
            log.warn("Libs directory not writeable, disable updating");
            setIgnoreUpdate(true);
        }
    }

    private void printVersion(LocalLauncherMeta localLauncherMeta) {
        HeadlessInterface.printVersion(LocalBootstrapMeta.getInstance().getVersion().toString(), localLauncherMeta == null ? null : localLauncherMeta.getVersion().toString());
    }

    private Task<LocalLauncherTask> getLocalLauncher() {
        return new Task<LocalLauncherTask>("getLocalLauncher") {
            @Override
            protected LocalLauncherTask execute() throws Exception {
                updateProgress(0.);
                log.info("Getting local launcher...");

                Path targetJar = getTargetJar();
                if (internal == null) {
                    // dev runs and packages that ship the launcher next to the bootstrap
                    return new LocalLauncherTask(new LocalLauncher(targetJar, getTargetLibFolder()));
                }

                String embeddedChecksum = internal.getChecksum();
                if (Files.isRegularFile(targetJar) && embeddedChecksum.equalsIgnoreCase(Sha256Sign.calc(targetJar))) {
                    log.info("Local launcher matches the embedded one");
                    return new LocalLauncherTask(new LocalLauncher(targetJar, getTargetLibFolder()));
                }

                if (getIgnoreUpdate()) {
                    log.warn("Can't write {}, running the embedded launcher from a temporary file", targetJar);
                    return new LocalLauncherTask(new LocalLauncher(internal.getTempFile(), getTargetLibFolder()));
                }

                log.info("Extracting the embedded launcher to {}", targetJar);
                return new LocalLauncherTask(
                        bindTo(internal.toLocalLauncher(targetJar, getTargetLibFolder()), .0, 1.),
                        true
                );
            }
        };
    }

    private static class RunningConditionsResult {
        public boolean javaVersionUnsupported = false;
        public Path brokenPath = null;
        public boolean tempDirUnwriteable = false;
        public boolean tempDirNotEnoughSpace = false;

        public String formatMessage() {
            StringBuilder message = new StringBuilder();
            if (javaVersionUnsupported) {
                appendLine(message, "Your Java version is not supported. Please install at least " + SUPPORTED_JAVA_VERSION.getVersion() + " from Java.com");
                appendLine(message, "Ваша версия Java не поддерживается. Пожалуйста, установите как минимум " + SUPPORTED_JAVA_VERSION.getVersion() + " с сайта Java.com");
            }
            if (brokenPath != null) {
                appendLine(message, "Please do not run (any) Java application which path contains folder name that ends with «!»");
                appendLine(message, "Не запускайте Java-приложения в директориях, чей путь содержит «!». Переместите Lonely Launcher в другую папку.");
            }
            if (tempDirUnwriteable) {
                appendLine(message, "Could not access temporary folder. Please check your hard drive.");
                appendLine(message, "Не удалось создать временный файл. Проверьте диск на наличие ошибок.");
            }
            if (tempDirNotEnoughSpace) {
                appendLine(message, "Insufficient disk space on partition storing temporary folder.");
                appendLine(message, "Недостаточно места на системном диске. Пожалуйста, освободите место и попробуйте снова.");
            }
            return message.toString();
        }

        private static void appendLine(StringBuilder buffer, String line) {
            if (buffer.length() != 0) {
                buffer.append("\n");
            }
            buffer.append(line);
        }

        public void showErrorIfNeeded() {
            String message = formatMessage();
            if (!message.isEmpty()) {
                log.error("Preconditions failed: {}", message);
                UserInterface.showError(message, brokenPath);
                throw new RuntimeException("precodintions failed");
            }
        }
    }

    private static final JavaVersion SUPPORTED_JAVA_VERSION = JavaVersion.create(1, 8, 0, 45);

    private static RunningConditionsResult checkRunningConditions(Path bootstrapJar) {
        RunningConditionsResult result = new RunningConditionsResult();

        JavaVersion current = JavaVersion.getCurrent();

        if (current != JavaVersion.UNKNOWN && current.compareTo(SUPPORTED_JAVA_VERSION) < 0) {
            result.javaVersionUnsupported = true;
            return result;
        }

        if (bootstrapJar.toAbsolutePath().toString().contains("!" + File.separatorChar)) {
            result.brokenPath = bootstrapJar;
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("bootstrap", null);
            FileStat tempFileStat = fileStat(tempFile);
            if (!tempFileStat.writeable) {
                result.tempDirUnwriteable = true;
            }
            if (!tempFileStat.enoughSpace) {
                result.tempDirNotEnoughSpace = true;
            }
        } catch (IOException e) {
            result.tempDirUnwriteable = true;
        } finally {
            if (tempFile != null) {
                try {
                    Files.delete(tempFile);
                } catch (IOException ignored) {
                }
            }
        }

        return result;
    }

    private static class FileStat {
        public final boolean exists;
        public final boolean readable;
        public final boolean writeable;
        public final boolean enoughSpace;

        private FileStat(boolean exists, boolean readable, boolean writeable, boolean enoughSpace) {
            this.exists = exists;
            this.readable = readable;
            this.writeable = writeable;
            this.enoughSpace = enoughSpace;
        }
    }

    private static FileStat fileStat(Path path) {
        final boolean readable = Files.isReadable(path);
        final boolean writeable = Files.isWritable(path);
        final boolean enoughSpace = queryFreeSpace(path) > 64 * 1024L;
        return new FileStat(Files.exists(path), readable, writeable, enoughSpace);
    }

    private static long queryFreeSpace(Path path) {
        FileSystem fileSystem = path.getFileSystem();
        if (fileSystem.isReadOnly()) {
            log.warn("Filesystem is read-only for {}", path);
            return 0;
        }
        FileStore fileStore;
        try {
            fileStore = fileSystem.provider().getFileStore(path);
        } catch (IOException e) {
            log.warn("Couldn't get file store of {}", path, e);
            return -1;
        }
        if (fileStore.isReadOnly()) {
            log.warn("File store is read-only {}", fileStore);
            return 0;
        }
        try {
            return fileStore.getUsableSpace();
        } catch (IOException e) {
            log.warn("Can't query usable space on {}", fileStore, e);
            return -1;
        }
    }

    private void whenIPCReady(Consumer<BootstrapIPC> callback) {
        if (ipc != null) {
            callback.accept(ipc);
            return;
        }
        ipcReady.add(callback);
    }

    void initIPC(LocalLauncher localLauncher) {
        if (this.ipc != null) {
            throw new IllegalStateException("IPC already initialized");
        }
        Version version = LocalBootstrapMeta.getInstance().getVersion();
        String bootstrapVersion = version.toString();
        this.ipc = BootstrapIPCProvider.createIPC(bootstrapVersion, launcherArgs, this, localLauncher);
        for (Consumer<BootstrapIPC> callback : ipcReady) {
            callback.accept(ipc);
        }
        ipcReady.clear();
    }
}
