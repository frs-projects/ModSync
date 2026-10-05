package net.frsprojects.modsync.client;

import net.frsprojects.modsync.ModSync;
import net.frsprojects.modsync.core.config.ModSyncConfig;
import net.frsprojects.modsync.core.diff.ActionKind;
import net.frsprojects.modsync.core.diff.SyncAction;
import net.frsprojects.modsync.core.diff.SyncPlan;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.net.DownloadProgress;
import net.frsprojects.modsync.core.net.Downloader;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.security.SandboxException;
import net.frsprojects.modsync.core.sync.ManualImports;
import net.frsprojects.modsync.core.sync.OptionalChoices;
import net.frsprojects.modsync.core.sync.SyncSession;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shown instead of the connect screen when the server has a manifest configured: checks the
 * manifest, asks which recommended and optional files the player wants, shows what would
 * change, downloads on request and journals the swap.
 *
 * <p>Files whose authors only allow downloads from CurseForge's own site get one more step
 * before the download: the player opens each one's page in their browser, and the screen
 * watches the downloads folder (and accepts files dropped onto the window) until every one has
 * arrived with the right hash.
 *
 * <p>All network and disk work runs on a worker thread; results come back to the render
 * thread through {@code minecraft.execute}. A screen the player has left ignores late results.
 *
 * <p>Version-gated in two places only, both where the GUI API changed after 1.20.1: who draws
 * the background, and the shape of {@code mouseScrolled}.
 */
public final class SyncScreen extends Screen {

    /** Everything needed to run one sync, captured when the player clicked join. */
    public record Target(ModSyncPaths paths, ModSyncConfig config, String manifestUrl,
            String fallbackProfileId, String loader, String mcVersion) {}

    private enum State { CHECKING, FAILED, CHOOSE, REVIEW, BLOCKED, MANUAL, DOWNLOADING, READY }

    /** One page of tick boxes: every undecided recommended, or every undecided optional file. */
    private record Page(Policy policy, List<SyncAction> actions) {}

    private static final Logger LOG = LoggerFactory.getLogger("ModSync");

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int RED = 0xFFFF5555;
    private static final int BLACK = 0xFF000000;
    private static final int HOVER = 0x30FFFFFF;
    private static final int LINE = 11;
    /** How often the downloads folder is looked at while waiting for the browser, in ticks. */
    private static final int MANUAL_POLL_TICKS = 20;

    // One worker for every sync screen: a sync is rare, and two at once would race on the
    // journal and the hash cache.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ModSync-Sync");
        t.setDaemon(true);
        return t;
    });

    private final Screen parent;
    private final Target target;
    private final Runnable connect;

    private State state = State.CHECKING;
    private SyncSession session;
    private String message = "";
    private final List<Line> lines = new ArrayList<>();
    private int scroll;
    // Read by the worker: a cancelled sync must not go on to write a journal.
    private volatile boolean left;

    private Set<String> selection = new LinkedHashSet<>();
    private OptionalChoices choices = OptionalChoices.empty();
    private final Deque<Page> pages = new ArrayDeque<>();
    private Page page;
    // Where the list starts on screen, as last rendered; click hit-testing reads it.
    private int listTop;
    private int filesTotal;
    private long bytesTotal;
    private final AtomicInteger filesDone = new AtomicInteger();
    private final Map<String, Long> bytesByFile = new ConcurrentHashMap<>();

    // The MANUAL step. The importer is used on the worker only; the rest on the render thread.
    private ManualImports imports;
    private List<ManifestEntry> manualRows = List.of();
    private final Set<String> opened = new LinkedHashSet<>();
    private final Map<String, String> refused = new ConcurrentHashMap<>();
    private final List<Path> watched = new CopyOnWriteArrayList<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile Set<String> arrived = Set.of();
    /** One-off feedback shown under the explanation, e.g. a drop that matched nothing. */
    private String manualNotice = "";
    private int ticks;

    private record Line(String text, int color) {}

    SyncScreen(Screen parent, Target target, Runnable connect) {
        super(Component.literal("ModSync"));
        this.parent = parent;
        this.target = target;
        this.connect = connect;
        check();
    }

    // ── Work ────────────────────────────────────────────────────────────────────

    private void check() {
        state = State.CHECKING;
        message = "Checking " + target.manifestUrl() + " ...";
        lines.clear();
        WORKER.execute(() -> {
            try {
                SyncSession prepared = SyncSession.prepare(target.paths(), target.config(),
                    target.manifestUrl(), target.fallbackProfileId(), target.loader(),
                    target.mcVersion(), "ModSync/" + ModSync.VERSION);
                onMain(() -> reviewed(prepared));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not check {}: {}", target.manifestUrl(), e.toString());
                onMain(() -> failed("Could not check this server's mods:", e.getMessage()));
            }
        });
    }

    private void reviewed(SyncSession prepared) {
        session = prepared;
        SyncPlan plan = prepared.plan();
        lines.clear();
        scroll = 0;
        if (!plan.canProceed()) {
            selection = new LinkedHashSet<>(plan.defaultSelection());
            state = State.BLOCKED;
            message = "This server needs files ModSync cannot get:";
            for (SyncAction a : plan.blocked()) {
                lines.add(new Line("✕ " + a.label() + " — " + a.reason(), RED));
            }
            addProtectedLine(plan);
            rebuild();
            return;
        }

        choices = prepared.choices();
        selection = new LinkedHashSet<>(choices.initialSelection(plan));
        pages.clear();
        for (Policy policy : List.of(Policy.RECOMMEND, Policy.OPTIONAL)) {
            List<SyncAction> undecided = choices.undecided(plan, policy);
            if (!undecided.isEmpty()) {
                pages.add(new Page(policy, undecided));
            }
        }
        nextPage();
    }

    /** Shows the next page of tick boxes, or the review once every page has been answered. */
    private void nextPage() {
        page = pages.poll();
        scroll = 0;
        if (page == null) {
            review();
            return;
        }
        state = State.CHOOSE;
        message = page.policy() == Policy.RECOMMEND
            ? "This server recommends these files. Untick any you do not want."
            : "This server offers these optional files. Tick any you want.";
        message += " \"Don't ask again\" keeps these choices until the server changes one of"
            + " the files.";
        rebuild();
    }

    /** Remembers the current page's ticks, then moves on. */
    private void answerPage(boolean quiet) {
        for (SyncAction a : page.actions()) {
            choices.record(a.entry(), selection.contains(a.path()), quiet);
        }
        try {
            session.saveChoices();
        } catch (IOException e) {
            // Only costs the player being asked again next time.
            LOG.warn("Could not save optional file choices: {}", e.toString());
        }
        nextPage();
    }

    private void review() {
        SyncPlan plan = session.plan();
        if (plan.changes().stream().noneMatch(a -> selection.contains(a.path()))) {
            // Up to date, or the only changes on offer were declined.
            joinNow();
            return;
        }
        state = State.REVIEW;
        message = summary(plan);
        lines.clear();
        for (SyncAction a : plan.changes()) {
            lines.add(describe(a));
        }
        addProtectedLine(plan);
        rebuild();
    }

    private void addProtectedLine(SyncPlan plan) {
        if (!plan.of(ActionKind.PROTECTED).isEmpty()) {
            lines.add(new Line(plan.of(ActionKind.PROTECTED).size()
                + " file(s) kept by your alwaysKeep rules", GREY));
        }
    }

    /** Asks for the browser downloads first, when there are any; otherwise downloads. */
    private void startSync() {
        if (session.plan().manual(selection).isEmpty()) {
            download();
            return;
        }
        imports = session.manualImports(Set.copyOf(selection));
        manualRows = imports.wanted();
        opened.clear();
        refused.clear();
        manualNotice = "";
        watched.clear();
        watched.addAll(session.downloadFolders());
        arrived = Set.of();
        state = State.MANUAL;
        scroll = 0;
        refreshManual();
        rebuild();
        poll();
    }

    /** Looks for newly arrived files off the render thread; never two scans at once. */
    private void poll() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        ManualImports i = imports;
        List<Path> folders = List.copyOf(watched);
        WORKER.execute(() -> {
            try {
                i.scan(folders);
                Set<String> done = doneIdentities(i);
                onMain(() -> manualProgress(done));
            } finally {
                scanning.set(false);
            }
        });
    }

    private void dropped(List<Path> files) {
        ManualImports i = imports;
        WORKER.execute(() -> {
            List<ManifestEntry> got = i.offer(files);
            Set<String> done = doneIdentities(i);
            onMain(() -> {
                manualNotice = got.isEmpty()
                    ? "None of the dropped files is one this pack is waiting for." : "";
                manualProgress(done);
            });
        });
    }

    private static Set<String> doneIdentities(ManualImports i) {
        Set<String> done = new LinkedHashSet<>();
        for (ManifestEntry e : i.wanted()) {
            if (i.isDone(e)) {
                done.add(e.path());
            }
        }
        return done;
    }

    private void manualProgress(Set<String> done) {
        if (state != State.MANUAL) {
            return;
        }
        arrived = Set.copyOf(done);
        if (manualRows.stream().allMatch(e -> arrived.contains(e.path())
                || !selection.contains(e.path()))) {
            download();
            return;
        }
        refreshManual();
        rebuild();
    }

    private void refreshManual() {
        long left = manualRows.stream()
            .filter(e -> selection.contains(e.path()) && !arrived.contains(e.path())).count();
        message = left + " file(s) have to be downloaded in your browser: their authors"
            + " only allow downloads from CurseForge's site. Click a file to open its page."
            + " ModSync picks the download up by itself from "
            + watched.stream().map(SyncScreen::shortPath).reduce((a, b) -> a + ", " + b)
                .orElse("modsync/import")
            + ", or drop the file onto this window."
            + (manualNotice.isEmpty() ? "" : " " + manualNotice);
        lines.clear();
        for (ManifestEntry e : manualRows) {
            lines.add(manualLine(e));
        }
    }

    private Line manualLine(ManifestEntry e) {
        if (!selection.contains(e.path())) {
            return new Line("○ " + e.label() + " — skipped", GREY);
        }
        if (arrived.contains(e.path())) {
            return new Line("✓ " + e.label() + " — got it", GREEN);
        }
        String why = refused.get(e.path());
        if (why != null) {
            return new Line("✕ " + e.label() + " — " + why, RED);
        }
        String name = e.manual().fileName() != null ? e.manual().fileName() : e.fileName();
        return new Line((opened.contains(e.path()) ? "… " : "↓ ") + e.label() + " — "
            + (opened.contains(e.path()) ? "waiting for " + name : "click to open "
            + hostOf(e.manual().url())), opened.contains(e.path()) ? YELLOW : WHITE);
    }

    /** Opens one file's page in the browser, if the allowlist lets it. */
    private void openPage(ManifestEntry e) {
        manualNotice = "";
        try {
            URI uri = session.allowlist().check(e.manual().url());
            Util.getPlatform().openUri(uri);
            opened.add(e.path());
        } catch (SandboxException ex) {
            // A manifest must not be able to send the player to an arbitrary site.
            refused.put(e.path(), ex.getMessage());
        }
        refreshManual();
    }

    private void openNext() {
        manualRows.stream()
            .filter(e -> selection.contains(e.path()) && !arrived.contains(e.path())
                && !refused.containsKey(e.path()))
            .filter(e -> !opened.contains(e.path()))
            .findFirst()
            .or(() -> manualRows.stream()
                .filter(e -> selection.contains(e.path()) && !arrived.contains(e.path())
                    && !refused.containsKey(e.path()))
                .findFirst())
            .ifPresent(this::openPage);
    }

    /** Lets the player add a folder their browser saves to, and remembers it. */
    private void chooseFolder() {
        String picked = TinyFileDialogs.tinyfd_selectFolderDialog(
            "Where does your browser save downloads?", System.getProperty("user.home"));
        if (picked == null || picked.isBlank()) {
            return;
        }
        Path folder = Path.of(picked).toAbsolutePath().normalize();
        if (!watched.contains(folder)) {
            watched.add(0, folder);
        }
        try {
            target.config().withDownloadFolder(folder.toString()).save(target.paths().config());
        } catch (IOException ex) {
            // Only costs choosing it again next time.
            LOG.warn("Could not remember the download folder: {}", ex.toString());
        }
        manualNotice = "";
        refreshManual();
        poll();
    }

    /** Drops every optional file still waiting, so a player is never stuck on one they skip. */
    private void skipOptional() {
        for (ManifestEntry e : manualRows) {
            if (!e.policy().isMandatory() && !arrived.contains(e.path())) {
                selection.remove(e.path());
            }
        }
        manualProgress(arrived);
    }

    private boolean anyOptionalWaiting() {
        return manualRows.stream().anyMatch(e -> !e.policy().isMandatory()
            && selection.contains(e.path()) && !arrived.contains(e.path()));
    }

    @Override
    public void tick() {
        super.tick();
        if (state == State.MANUAL && ++ticks % MANUAL_POLL_TICKS == 0) {
            poll();
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (state == State.MANUAL) {
            dropped(files);
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String shortPath(Path p) {
        String home = System.getProperty("user.home");
        String s = p.toString();
        return home != null && !home.isBlank() && s.startsWith(home)
            ? "~" + s.substring(home.length()) : s;
    }

    private void download() {
        SyncSession s = session;
        Set<String> accepted = Set.copyOf(selection);
        List<SyncAction> toFetch = s.plan().requiringDownload().stream()
            .filter(a -> accepted.contains(a.path())).toList();
        filesTotal = toFetch.size();
        bytesTotal = toFetch.stream().mapToLong(SyncAction::downloadBytes).sum();
        filesDone.set(0);
        bytesByFile.clear();
        state = State.DOWNLOADING;
        message = "";
        rebuild();

        DownloadProgress progress = new DownloadProgress() {
            @Override
            public void advanced(String label, long bytesSoFar, long expectedBytes) {
                bytesByFile.put(label, bytesSoFar);
            }

            @Override
            public void finished(String label) {
                filesDone.incrementAndGet();
            }
        };
        WORKER.execute(() -> {
            try {
                List<Downloader.Failure> failures = s.download(accepted, progress);
                if (!failures.isEmpty()) {
                    Downloader.Failure first = failures.get(0);
                    onMain(() -> failed(failures.size() + " file(s) could not be downloaded:",
                        first.entry().label() + ": " + first.reason()));
                    return;
                }
                if (left) {
                    return;
                }
                int ops = s.writeJournal(accepted);
                onMain(() -> {
                    if (ops == 0) {
                        joinNow();
                        return;
                    }
                    ModSyncClient.armApplierOnExit();
                    state = State.READY;
                    message = "";
                    rebuild();
                });
            } catch (IOException | RuntimeException e) {
                LOG.warn("Sync against {} failed: {}", target.manifestUrl(), e.toString());
                onMain(() -> failed("The sync failed:", e.getMessage()));
            }
        });
    }

    private void failed(String headline, String detail) {
        state = State.FAILED;
        message = headline;
        lines.clear();
        scroll = 0;
        lines.add(new Line(detail == null ? "Unknown error" : detail, RED));
        rebuild();
    }

    private void joinNow() {
        left = true;
        connect.run();
    }

    private void back() {
        left = true;
        Minecraft.getInstance().setScreen(parent);
    }

    /**
     * Runs on the render thread, unless the player has already left this screen. Goes through
     * {@code Minecraft.getInstance()} because the worker can finish before this screen has been
     * opened and given its {@code minecraft} field.
     */
    private void onMain(Runnable r) {
        Minecraft.getInstance().execute(() -> {
            if (!left) {
                r.run();
            }
        });
    }

    // ── Layout ──────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int y = height - 28;
        switch (state) {
            case CHECKING, DOWNLOADING -> addButtons(y, button("Cancel", this::back));
            case CHOOSE -> addButtons(y,
                button("Continue", () -> answerPage(false)),
                button("Don't ask again", () -> answerPage(true)),
                button("Cancel", this::back));
            case REVIEW -> addButtons(y,
                button("Sync", this::startSync),
                button("Join without syncing", this::joinNow),
                button("Cancel", this::back));
            case BLOCKED -> addButtons(y,
                button("Join without syncing", this::joinNow),
                button("Back", this::back));
            case MANUAL -> {
                List<Button> row = new ArrayList<>();
                row.add(button("Open next", this::openNext));
                row.add(button("Add folder…", this::chooseFolder));
                if (anyOptionalWaiting()) {
                    row.add(button("Skip optional", this::skipOptional));
                }
                row.add(button("Cancel", this::back));
                addButtons(y, row.toArray(new Button[0]));
            }
            case FAILED -> addButtons(y,
                button("Retry", this::check),
                button("Join without syncing", this::joinNow),
                button("Back", this::back));
            case READY -> addButtons(y,
                button("Quit game", () -> minecraft.stop()),
                button("Back to server list", this::back));
        }
    }

    private Button button(String label, Runnable action) {
        return Button.builder(Component.literal(label), b -> action.run()).build();
    }

    private void addButtons(int y, Button... buttons) {
        int w = Math.min(150, (width - 20) / buttons.length - 4);
        int total = buttons.length * w + (buttons.length - 1) * 4;
        int x = (width - total) / 2;
        for (Button b : buttons) {
            b.setX(x);
            b.setY(y);
            b.setWidth(w);
            addRenderableWidget(b);
            x += w + 4;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        //? if <1.20.2 {
        /*renderBackground(g);
        *///?}
        super.render(g, mouseX, mouseY, partialTick);

        String heading = session == null ? "ModSync"
            : "ModSync — " + session.plan().manifest().packName() + " "
                + session.plan().manifest().packVersion();
        g.drawCenteredString(font, heading, width / 2, 12, WHITE);

        int y = 30;
        switch (state) {
            case DOWNLOADING -> {
                long bytes = bytesByFile.values().stream().mapToLong(Long::longValue).sum();
                g.drawCenteredString(font, "Downloading " + filesDone.get() + " / " + filesTotal
                    + " files · " + mib(bytes) + " / " + mib(bytesTotal), width / 2, y, WHITE);
                return;
            }
            case READY -> {
                g.drawCenteredString(font, "Everything is downloaded.", width / 2, y, GREEN);
                g.drawCenteredString(font,
                    "ModSync swaps the files in when Minecraft closes. Restart, then join again.",
                    width / 2, y + LINE + 2, WHITE);
                return;
            }
            default -> {
                for (var part : font.split(Component.literal(message), width - 40)) {
                    g.drawString(font, part, 20, y, WHITE);
                    y += LINE;
                }
            }
        }

        int top = y + 6;
        listTop = top;
        int bottom = height - 36;
        int visible = visibleRows();
        int count = rowCount();
        scroll = Math.max(0, Math.min(scroll, count - visible));
        for (int i = scroll; i < Math.min(count, scroll + visible); i++) {
            int rowY = top + (i - scroll) * LINE;
            if (state == State.CHOOSE) {
                drawChoice(g, page.actions().get(i), rowY, mouseX, mouseY);
            } else {
                Line line = lines.get(i);
                g.drawString(font, font.plainSubstrByWidth(line.text(), width - 40), 20, rowY,
                    line.color());
            }
        }
        if (count > visible && visible > 0) {
            g.drawString(font, (scroll + 1) + "–" + Math.min(count, scroll + visible)
                + " of " + count + " (scroll for more)", 20, bottom, GREY);
        }
    }

    private int rowCount() {
        return state == State.CHOOSE ? page.actions().size() : lines.size();
    }

    private int visibleRows() {
        return Math.max(0, (height - 36 - listTop) / LINE);
    }

    private void drawChoice(GuiGraphics g, SyncAction a, int y, int mouseX, int mouseY) {
        boolean on = selection.contains(a.path());
        if (mouseX >= 18 && mouseX < width - 18 && mouseY >= y - 1 && mouseY < y + LINE - 1) {
            g.fill(18, y - 1, width - 18, y + LINE - 1, HOVER);
        }
        g.fill(20, y, 29, y + 9, GREY);
        g.fill(21, y + 1, 28, y + 8, BLACK);
        if (on) {
            g.fill(22, y + 2, 27, y + 7, GREEN);
        }
        String text = a.label() + " — " + choiceDetail(a);
        if (a.entry().desc() != null) {
            text += " — " + a.entry().desc();
        }
        g.drawString(font, font.plainSubstrByWidth(text, width - 54), 34, y, on ? WHITE : GREY);
    }

    // Only the (double, double, int) shape exists across the whole matrix; it changed after
    // 1.21.8.
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (state == State.MANUAL && button == 0 && mouseY >= listTop - 1
                && mouseX >= 18 && mouseX < width - 18) {
            int i = scroll + (int) ((mouseY - listTop + 1) / LINE);
            if (i < manualRows.size() && (int) ((mouseY - listTop + 1) / LINE) < visibleRows()) {
                ManifestEntry e = manualRows.get(i);
                if (selection.contains(e.path()) && !arrived.contains(e.path())) {
                    openPage(e);
                    return true;
                }
            }
            return false;
        }
        if (state != State.CHOOSE || button != 0 || mouseY < listTop - 1
                || mouseX < 18 || mouseX >= width - 18) {
            return false;
        }
        int row = (int) ((mouseY - listTop + 1) / LINE);
        int i = scroll + row;
        if (row >= visibleRows() || i >= page.actions().size()) {
            return false;
        }
        String path = page.actions().get(i).path();
        if (!selection.remove(path)) {
            selection.add(path);
        }
        return true;
    }

    //? if >=1.20.2 {
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * 3;
        return true;
    }
    //?} else {
    /*@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll -= (int) Math.signum(delta) * 3;
        return true;
    }
    *///?}

    @Override
    public void onClose() {
        back();
    }

    // ── Text ────────────────────────────────────────────────────────────────────

    private String summary(SyncPlan plan) {
        int install = 0;
        int update = 0;
        int remove = 0;
        long download = 0;
        for (SyncAction a : plan.changes()) {
            if (!selection.contains(a.path())) {
                continue;
            }
            download += a.downloadBytes();
            switch (a.kind()) {
                case INSTALL -> install++;
                case REPLACE -> update++;
                case RESTORE, MANUAL -> {
                    if (a.existing() == null) {
                        install++;
                    } else {
                        update++;
                    }
                }
                case QUARANTINE_FORBIDDEN, QUARANTINE_UNLISTED, QUARANTINE_DECLINED -> remove++;
                default -> { }
            }
        }
        long manual = plan.manual(selection).size();
        return "This server's pack needs changes: " + install + " to install, " + update
            + " to update, " + remove + " to move aside (nothing is deleted). "
            + mib(download) + " to download"
            + (manual > 0 ? ", plus " + manual + " file(s) you download in your browser" : "")
            + ". Minecraft has to restart afterwards.";
    }

    private static String choiceDetail(SyncAction a) {
        return switch (a.kind()) {
            case INSTALL -> mib(a.downloadBytes());
            case RESTORE -> a.existing() == null ? "already downloaded"
                : "update, already downloaded";
            case REPLACE -> "update, " + mib(a.downloadBytes());
            case MANUAL -> "download in your browser";
            default -> a.reason();
        };
    }

    private Line describe(SyncAction a) {
        boolean on = selection.contains(a.path());
        if (!on) {
            return new Line("○ " + a.label() + " — not selected", GREY);
        }
        return switch (a.kind()) {
            case INSTALL -> new Line("+ " + a.label() + " (" + mib(a.downloadBytes()) + ")", GREEN);
            case RESTORE -> new Line("+ " + a.label() + " (already downloaded)", GREEN);
            case REPLACE -> new Line("~ " + a.label() + " — " + a.reason(), YELLOW);
            case MANUAL -> new Line((a.existing() == null ? "+ " : "~ ") + a.label()
                + " (download in your browser)", YELLOW);
            case QUARANTINE_FORBIDDEN, QUARANTINE_UNLISTED, QUARANTINE_DECLINED ->
                new Line("- " + a.path() + " — " + a.reason(), RED);
            default -> new Line(a.label(), WHITE);
        };
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
