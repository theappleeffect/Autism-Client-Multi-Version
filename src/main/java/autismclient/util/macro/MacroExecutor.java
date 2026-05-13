package autismclient.util.macro;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import autismclient.modules.PackUtilModule;
import autismclient.util.PacketRegenerator;
import autismclient.util.PackUtilCompatManager;
import autismclient.util.PackUtilClientMessaging;
import autismclient.util.PackUtilCraftingHelper;
import autismclient.util.PackUtilInstaBreakRenderer;
import autismclient.util.PackUtilInventoryHelper;
import autismclient.util.PackUtilMacro;
import autismclient.util.PackUtilPacketNamer;
import autismclient.util.PackUtilPacketRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.tags.ItemTags;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class MacroExecutor {
    private static final Gson GSON = new Gson();
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final int MAX_CONCURRENT_MACROS = 2;
    private static final Object RUN_LOCK = new Object();
    private enum SharedClientResource { INVENTORY, GUI, INPUT, ROTATION, BARITONE, NETWORK, PACKET_DELAY, WORLD_ACTION }
    private static final EnumMap<SharedClientResource, ReentrantLock> SHARED_RESOURCE_LOCKS = new EnumMap<>(SharedClientResource.class);
    static {
        for (SharedClientResource resource : SharedClientResource.values()) {
            SHARED_RESOURCE_LOCKS.put(resource, new ReentrantLock(true));
        }
    }
    private static final AtomicLong NEXT_RUN_ID = new AtomicLong(1L);
    private static final java.util.LinkedHashMap<Long, RunState> ACTIVE_RUNS = new java.util.LinkedHashMap<>();
    private static final ThreadLocal<RunState> CURRENT_RUN = new ThreadLocal<>();
    private static volatile RunState primaryRun = null;
    private static PackUtilMacro currentMacro = null;
    private static Thread macroThread = null;

    private static final AtomicReference<String> lastReceivedPacket = new AtomicReference<>("");
    private static String currentStatus = "";

    private static volatile int currentStepIndex = -1;
    private static volatile int totalSteps = 0;

    private static final AtomicBoolean isWaitingForPacket = new AtomicBoolean(false);
    private static final AtomicReference<String> waitingPacketName = new AtomicReference<>("");
    private static final Object packetWaitLock = new Object();

    private static final AtomicBoolean isWaitingForChat = new AtomicBoolean(false);
    private static final AtomicReference<String> waitingChatPattern = new AtomicReference<>("");
    private static final AtomicBoolean waitingChatIsRegex = new AtomicBoolean(false);
    private static final AtomicReference<String> waitingChatPatternJson = new AtomicReference<>("");
    private static final java.util.concurrent.atomic.AtomicInteger waitingChatFuzzyPercent = new java.util.concurrent.atomic.AtomicInteger(100);
    private static final AtomicReference<String> lastMatchedChat = new AtomicReference<>("");
    private static final Object recentChatLock = new Object();
    private static final Deque<RecentChatMessage> recentChatMessages = new ArrayDeque<>();
    private static final int MAX_RECENT_CHAT_MESSAGES = 60;
    private static String recentChatServerKey = "";

    private static volatile CompletableFuture<Void> activePacketFuture = null;
    private static volatile CompletableFuture<Void> activeChatFuture = null;

    private static volatile CompletableFuture<Void> activeBaritoneGoalFuture = null;

    private static final AtomicBoolean isRotating = new AtomicBoolean(false);
    private static float targetYaw, targetPitch;
    private static double rotationSpeed;
    private static int rotationAlignedFrames;

    private static final java.util.concurrent.CopyOnWriteArrayList<MacroAction> persistentActions =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final java.util.concurrent.ConcurrentHashMap<MoveAction, int[]> backgroundMoves =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<RepeatPacketContext> REPEAT_PACKET_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> REPEAT_PACKET_SKIP = ThreadLocal.withInitial(() -> 0);

    private static final class RunState {
        final long id;
        final PackUtilMacro macro;
        final AtomicBoolean running = new AtomicBoolean(true);
        final CopyOnWriteArrayList<MacroAction> persistentActions = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<MoveAction, int[]> backgroundMoves = new ConcurrentHashMap<>();
        final Object packetWaitLock = new Object();
        final AtomicBoolean waitingForPacket = new AtomicBoolean(false);
        final AtomicReference<String> waitingPacketName = new AtomicReference<>("");
        volatile CompletableFuture<Void> packetFuture = null;
        final AtomicBoolean waitingForChat = new AtomicBoolean(false);
        volatile WaitForChatAction waitingChatAction = null;
        volatile CompletableFuture<Void> chatFuture = null;
        volatile CompletableFuture<Void> baritoneGoalFuture = null;
        volatile Thread macroThread = null;
        volatile String status = "";
        volatile int currentStepIndex = -1;
        volatile int totalSteps = 0;
        volatile int lastCompletedStep = 0;

        RunState(long id, PackUtilMacro macro) {
            this.id = id;
            this.macro = macro;
            this.totalSteps = macro != null ? macro.actions.size() : 0;
        }
    }

    public enum ChatSource { PLAYER, SERVER }

    public record RecentChatMessage(String sender, String message, String displayText, Component displayComponent, ChatSource source, long timestampMs) {}
    public record MacroRunSnapshot(PackUtilMacro macro, String name, String status, int currentStepIndex, int totalSteps, int lastCompletedStep) {}

    private record ChatCapture(String sender, String message, String displayText, Component displayComponent, ChatSource source) {}
    private record RepeatPacketContext(PackUtilMacro macro, int startIdx, int endIdx) {}

    private static boolean isCurrentRunActive() {
        RunState run = CURRENT_RUN.get();
        if (run != null) return run.running.get();
        return isRunning.get();
    }

    private static CopyOnWriteArrayList<MacroAction> currentPersistentActions() {
        RunState run = CURRENT_RUN.get();
        return run != null ? run.persistentActions : persistentActions;
    }

    private static ConcurrentHashMap<MoveAction, int[]> currentBackgroundMoves() {
        RunState run = CURRENT_RUN.get();
        return run != null ? run.backgroundMoves : backgroundMoves;
    }

    private static void setCurrentStatus(String status) {
        RunState run = CURRENT_RUN.get();
        if (run != null) run.status = status == null ? "" : status;
        currentStatus = status == null ? "" : status;
    }

    private static void setCurrentStep(int step, int total) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            run.currentStepIndex = step;
            run.totalSteps = total;
        }
        currentStepIndex = step;
        totalSteps = total;
    }

    private static void markStepCompleted(int stepOneBased) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            run.lastCompletedStep = Math.max(run.lastCompletedStep, stepOneBased);
        }
    }

    private static void stopCurrentRun() {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            run.running.set(false);
        } else {
            stop();
        }
    }

    private static EnumSet<SharedClientResource> resourcesForAction(MacroAction action) {
        EnumSet<SharedClientResource> resources = EnumSet.noneOf(SharedClientResource.class);
        if (action instanceof ClickAction
            || action instanceof InventoryAction
            || action instanceof ItemAction
            || action instanceof DropAction
            || action instanceof CraftAction
            || action instanceof StoreItemAction
            || action instanceof InventoryAuditAction
            || action instanceof XCarryAction
            || action instanceof SwapSlotsAction
            || action instanceof SelectSlotAction) {
            resources.add(SharedClientResource.INVENTORY);
            resources.add(SharedClientResource.GUI);
        }
        if (action instanceof OpenContainerAction
            || action instanceof CloseGuiAction
            || action instanceof SaveGuiAction
            || action instanceof RestoreGuiAction
            || action instanceof DesyncAction
            || action instanceof NbtBookAction) {
            resources.add(SharedClientResource.GUI);
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof SendPacketAction
            || action instanceof PacketAction
            || action instanceof PayloadAction
            || action instanceof PayAction
            || action instanceof SendChatAction
            || action instanceof DisconnectAction) {
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof DelayPacketsAction) {
            resources.add(SharedClientResource.NETWORK);
            resources.add(SharedClientResource.PACKET_DELAY);
        }
        if (action instanceof UseItemAction) {
            resources.add(SharedClientResource.INPUT);
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof MoveAction
            || action instanceof SneakAction
            || action instanceof SprintAction
            || action instanceof JumpAction) {
            resources.add(SharedClientResource.INPUT);
        }
        if (action instanceof RotateAction || action instanceof LookAtBlockAction) {
            resources.add(SharedClientResource.ROTATION);
        }
        if (action instanceof GoToAction || action instanceof MineAction || action instanceof InstaBreakAction) {
            resources.add(SharedClientResource.BARITONE);
            resources.add(SharedClientResource.INPUT);
            resources.add(SharedClientResource.ROTATION);
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof ToggleModuleAction) {
            resources.add(SharedClientResource.WORLD_ACTION);
        }
        return resources;
    }

    private static EnumSet<SharedClientResource> acquireClientResources(MacroAction action) throws InterruptedException {
        EnumSet<SharedClientResource> resources = resourcesForAction(action);
        EnumSet<SharedClientResource> acquired = EnumSet.noneOf(SharedClientResource.class);
        try {
            for (SharedClientResource resource : SharedClientResource.values()) {
                if (resources.contains(resource)) {
                    if (!isCurrentRunActive()) throw new InterruptedException("Macro stopped");
                    SHARED_RESOURCE_LOCKS.get(resource).lockInterruptibly();
                    acquired.add(resource);
                    if (!isCurrentRunActive()) throw new InterruptedException("Macro stopped");
                }
            }
        } catch (InterruptedException e) {
            releaseClientResources(acquired);
            throw e;
        }
        return acquired;
    }

    private static void releaseClientResources(EnumSet<SharedClientResource> resources) {
        if (resources == null || resources.isEmpty()) return;
        SharedClientResource[] ordered = SharedClientResource.values();
        for (int i = ordered.length - 1; i >= 0; i--) {
            SharedClientResource resource = ordered[i];
            if (resources.contains(resource)) {
                ReentrantLock lock = SHARED_RESOURCE_LOCKS.get(resource);
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }

    private static java.util.List<RunState> activeRunSnapshot() {
        synchronized (RUN_LOCK) {
            return new ArrayList<>(ACTIVE_RUNS.values());
        }
    }

    private static void finishCurrentRun() {
        RunState run = CURRENT_RUN.get();
        if (run == null) return;
        requestStop(run, false);

        synchronized (RUN_LOCK) {
            ACTIVE_RUNS.remove(run.id);
            primaryRun = ACTIVE_RUNS.values().stream().filter(active -> active.running.get()).findFirst().orElse(null);
            currentMacro = primaryRun != null ? primaryRun.macro : null;
            currentStatus = primaryRun != null ? primaryRun.status : "";
            currentStepIndex = primaryRun != null ? primaryRun.currentStepIndex : -1;
            totalSteps = primaryRun != null ? primaryRun.totalSteps : 0;
            isRunning.set(hasRunningRunsLocked());
        }
        if (!isRunning.get()) PackUtilInstaBreakRenderer.clear();
        CURRENT_RUN.remove();
    }

    private static boolean hasRunningRunsLocked() {
        for (RunState run : ACTIVE_RUNS.values()) {
            if (run.running.get()) return true;
        }
        return false;
    }

    private static boolean hasRunningRuns() {
        synchronized (RUN_LOCK) {
            return hasRunningRunsLocked();
        }
    }

    private static void requestStop(RunState run, boolean interruptThread) {
        if (run == null) return;
        run.running.set(false);
        run.persistentActions.clear();
        releaseBackgroundMoves(run.backgroundMoves, Minecraft.getInstance());
        run.backgroundMoves.clear();
        synchronized (run.packetWaitLock) {
            if (run.packetFuture != null) run.packetFuture.cancel(true);
            run.packetFuture = null;
            run.waitingForPacket.set(false);
            run.waitingPacketName.set("");
        }
        CompletableFuture<Void> chatFuture = run.chatFuture;
        if (chatFuture != null) chatFuture.cancel(true);
        run.chatFuture = null;
        run.waitingForChat.set(false);
        run.waitingChatAction = null;
        CompletableFuture<Void> baritoneFuture = run.baritoneGoalFuture;
        if (baritoneFuture != null) baritoneFuture.cancel(true);
        run.baritoneGoalFuture = null;
        Thread thread = run.macroThread;
        if (interruptThread && thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private static void releaseBackgroundMoves(ConcurrentHashMap<MoveAction, int[]> moves, Minecraft mc) {
        if (moves == null || moves.isEmpty() || mc == null) return;
        List<MoveAction> toRelease = new ArrayList<>(moves.keySet());
        mc.execute(() -> {
            for (MoveAction move : toRelease) {
                try {
                    move.release(mc);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static String macroKey(String macroName) {
        return macroName == null ? "" : macroName.trim().toLowerCase(Locale.ROOT);
    }

    private static String macroName(PackUtilMacro macro) {
        return macro != null && macro.name != null ? macro.name : "";
    }

    private static boolean matchesMacro(RunState run, PackUtilMacro macro, String macroName) {
        if (run == null || !run.running.get()) return false;
        if (macro != null && run.macro == macro) return true;
        String targetKey = macroKey(macroName);
        if (targetKey.isEmpty() && macro != null) targetKey = macroKey(macro.name);
        return !targetKey.isEmpty() && macroKey(macroName(run.macro)).equals(targetKey);
    }

    public static void execute(PackUtilMacro macro) {
        if (macro.actions.isEmpty()) {
            PackUtilClientMessaging.sendPrefixed("Â§cMacro has no actions!");
            return;
        }

        RunState runState;
        synchronized (RUN_LOCK) {
            String name = macro.name == null ? "" : macro.name;
            String nameKey = macroKey(name);
            for (RunState active : ACTIVE_RUNS.values()) {
                String activeName = active.macro != null && active.macro.name != null ? active.macro.name : "";
                if (active.running.get() && macroKey(activeName).equals(nameKey)) {
                    PackUtilClientMessaging.sendPrefixed("Â§eMacro already running: " + name);
                    return;
                }
            }
            if (ACTIVE_RUNS.size() >= MAX_CONCURRENT_MACROS) {
                PackUtilClientMessaging.sendPrefixed("Â§cOnly " + MAX_CONCURRENT_MACROS + " macros can run at the same time.");
                return;
            }
            runState = new RunState(NEXT_RUN_ID.getAndIncrement(), macro);
            ACTIVE_RUNS.put(runState.id, runState);
            if (primaryRun == null) primaryRun = runState;
            currentMacro = primaryRun.macro;
            isRunning.set(true);
        }
        PackUtilClientMessaging.sendPrefixed("Â§aStarted macro: " + macro.name);

        try {
            autismclient.util.PackUtilLANSync sync = autismclient.util.PackUtilLANSync.getInstance();
            if (sync.isInSession()) {
                sync.broadcastStepProgress(0, macro.actions.size(), macro.name);
            }
        } catch (Exception ignored) {}

        macroThread = new Thread(() -> run(runState), "PackUtil-Macro-" + runState.id);
        runState.macroThread = macroThread;
        macroThread.start();
    }

    private static void run(RunState runState) {
        CURRENT_RUN.set(runState);
        PackUtilMacro macro = runState.macro;
        Minecraft mc = Minecraft.getInstance();
        int loopCount = 0;
        int maxLoops = macro.loop ? (macro.loopCount == -1 ? Integer.MAX_VALUE : macro.loopCount) : 1;

        currentPersistentActions().clear();
        currentBackgroundMoves().clear();
        Thread persistentThread = new Thread(() -> {
            CURRENT_RUN.set(runState);
            try {
                while (isCurrentRunActive()) {
                    if (!currentPersistentActions().isEmpty() || !currentBackgroundMoves().isEmpty()) {
                        mc.execute(() -> {
                            ReentrantLock inputLock = SHARED_RESOURCE_LOCKS.get(SharedClientResource.INPUT);
                            inputLock.lock();
                            try {

                            for (MacroAction pa : currentPersistentActions()) {
                                try { pa.execute(mc); } catch (Exception ignored) {}
                            }

                            java.util.Iterator<java.util.Map.Entry<MoveAction, int[]>> it = currentBackgroundMoves().entrySet().iterator();
                            while (it.hasNext()) {
                                java.util.Map.Entry<MoveAction, int[]> entry = it.next();
                                MoveAction bma = entry.getKey();
                                int[] ticks = entry.getValue();
                                if (ticks[0] > 0) {
                                    bma.execute(mc);
                                    ticks[0]--;
                                } else {
                                    bma.release(mc);
                                    currentBackgroundMoves().remove(bma);
                                }
                            }
                            } finally {
                                inputLock.unlock();
                            }
                        });
                    }

                    try {
                        CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                        tf.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        if (!isCurrentRunActive()) break;
                    }
                }
            } finally {
                releaseBackgroundMoves(currentBackgroundMoves(), mc);
                currentBackgroundMoves().clear();
                CURRENT_RUN.remove();
            }
        }, "PackUtil-Persistent-Thread");
        persistentThread.setDaemon(true);
        persistentThread.start();

        try {
            while (isCurrentRunActive() && loopCount < maxLoops) {
                if (macro.actions.isEmpty()) {
                    PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cMacro stopped: no actions left.");
                    stopCurrentRun();
                    break;
                }
                setCurrentStatus("Loop " + (loopCount + 1) + (maxLoops == Integer.MAX_VALUE ? "" : "/" + maxLoops));

                PackUtilModule module = PackUtilModule.get();
                boolean burstMode = module.usePacketBurstMode();

                totalSteps = macro.actions.size();
                for (int i = 0; i < macro.actions.size(); i++) {
                    if (!isCurrentRunActive()) break;

                    setCurrentStep(i, macro.actions.size());
                    MacroAction action = macro.actions.get(i);

                    if (!action.isEnabled()) continue;

                    EnumSet<SharedClientResource> heldResources = acquireClientResources(action);
                    try {

                        java.util.concurrent.CompletableFuture<Void> postGuiFuture = null;
                        if (action instanceof WaitsForGui) {
                            WaitsForGui wfg = (WaitsForGui) action;
                            if (wfg.isWaitForGui()) {
                                if (wfg.isWaitForGuiChange()) {
                                    postGuiFuture = MacroConditionRegistry.waitForGuiChange(mc.screen);
                                } else {
                                    postGuiFuture = MacroConditionRegistry.waitForGui(wfg.getWaitGuiName());
                                }
                            }
                        }

                        if (burstMode && action instanceof SendPacketAction) {

                        java.util.List<SendPacketAction> packetBatch = new java.util.ArrayList<>();
                        int batchEnd = i;

                        while (batchEnd < macro.actions.size() &&
                               macro.actions.get(batchEnd) instanceof SendPacketAction) {
                            packetBatch.add((SendPacketAction) macro.actions.get(batchEnd));
                            batchEnd++;
                        }

                        executeBurstPackets(mc, packetBatch);

                        i = batchEnd - 1;
                        continue;
                    }

                    if (action instanceof DelayAction) {
                        DelayAction da = (DelayAction) action;
                        if (da.useTicks) {

                            for (int t = 0; t < da.delayTicks; t++) {
                                if (!isCurrentRunActive()) break;

                                CompletableFuture<Void> future = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(future);
                            }
                        } else {

                            Thread.sleep(da.delayMs);
                        }
                    }
                    else if (action instanceof WaitForPacketAction) {
                        java.util.List<String> targets = ((WaitForPacketAction) action).effectiveList();
                        if (targets.isEmpty()) {
                            awaitPacket("");
                        } else {
                            for (String target : targets) {
                                if (!isCurrentRunActive()) break;
                                awaitPacket(target);
                            }
                        }
                    }
                    else if (action instanceof WaitForHealthAction) {
                        WaitForHealthAction wh = (WaitForHealthAction) action;
                        setCurrentStatus(wh.waitingStatusText());
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForHealth(
                                wh.healthThreshold, wh.below);
                            waitForCondition(future);
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof WaitForBlockAction) {
                        WaitForBlockAction wba = (WaitForBlockAction) action;
                        setCurrentStatus(wba.getDisplayName());
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForBlock(wba);
                            waitForCondition(future);
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof WaitForGuiAction) {
                        WaitForGuiAction wga = (WaitForGuiAction) action;
                        if (wga.waitMode == WaitForGuiAction.WaitMode.CLOSE) {
                            setCurrentStatus("Waiting for GUI close: " + wga.guiTitle);
                            try {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForGuiClose(wga.guiTitle);
                                waitForCondition(future);
                            } catch (CancellationException e) { }
                        } else {
                            setCurrentStatus("Waiting for GUI: " + wga.guiTitle);
                            try {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForGui(wga.guiTitle);
                                waitForCondition(future);
                            } catch (CancellationException e) { }
                        }
                    }
                    else if (action instanceof WaitForCooldownAction) {
                        WaitForCooldownAction wca = (WaitForCooldownAction) action;
                        ItemTarget cooldownTarget = resolveItemTarget(wca.itemTarget, wca.itemName);
                        String cooldownLabel = describeItemTarget(cooldownTarget);
                        setCurrentStatus("Waiting for cooldown: " +
                            (!cooldownLabel.isEmpty() ? cooldownLabel : (wca.checkMainInteractionHand ? "Main Hand" : "Off Hand")));
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForCooldown(cooldownTarget, wca.checkMainInteractionHand);
                            waitForCondition(future);
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof WaitPosAction) {
                        WaitPosAction wpa = (WaitPosAction) action;
                        setCurrentStatus("Waiting for Pos: " + String.format("%.0f, %.0f, %.0f", wpa.x, wpa.y, wpa.z));
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForPos(
                                wpa.x, wpa.y, wpa.z, wpa.leeway, wpa.checkRotation, wpa.yaw, wpa.pitch, wpa.rotLeeway);
                            waitForCondition(future);
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof JumpAction) {
                        JumpAction ja = (JumpAction) action;
                        mc.execute(() -> ja.execute(mc));
                        if (ja.tap) {
                            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                            waitForCondition(tickFuture);
                        } else {
                            for (int t = 0; t < ja.durationTicks && isCurrentRunActive(); t++) {
                                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(tickFuture);
                            }
                        }
                        mc.execute(() -> ja.release(mc));
                    }
                    else if (action instanceof SneakAction) {
                        SneakAction sna = (SneakAction) action;
                        mc.execute(() -> sna.execute(mc));
                        if (sna.persistent && sna.sneak) {
                            currentPersistentActions().removeIf(p -> p instanceof SneakAction);
                            currentPersistentActions().add(sna);
                        } else if (!sna.sneak) {
                            currentPersistentActions().removeIf(p -> p instanceof SneakAction);
                        }
                    }
                    else if (action instanceof SprintAction) {
                        SprintAction spa = (SprintAction) action;
                        mc.execute(() -> spa.execute(mc));
                        if (spa.persistent && spa.sprint) {
                            currentPersistentActions().removeIf(p -> p instanceof SprintAction);
                            currentPersistentActions().add(spa);
                        } else if (!spa.sprint) {
                            currentPersistentActions().removeIf(p -> p instanceof SprintAction);
                        }
                    }
                    else if (action instanceof DisconnectAction) {
                        DisconnectAction da2 = (DisconnectAction) action;

                        try {
                            autismclient.util.PackUtilLANSync sync = autismclient.util.PackUtilLANSync.getInstance();
                            if (sync.isInSession()) {
                                sync.broadcastStepProgress(i + 1, macro.actions.size(), macro.name);
                            }
                        } catch (Exception ignored) {}
                        if (da2.mode == DisconnectAction.DisconnectMode.DISCONNECT) {
                            if (da2.delayMs > 0) Thread.sleep(da2.delayMs);
                            mc.execute(() -> da2.execute(mc));
                        } else if (da2.mode == DisconnectAction.DisconnectMode.AUTO_DISCONNECT) {

                            setCurrentStatus("Auto Disconnect: waiting for " + da2.trigger.name() + "...");
                            da2.execute(mc);
                            setCurrentStatus("Auto Disconnect: executed");
                        } else if (da2.mode == DisconnectAction.DisconnectMode.KICK_DUPE && da2.useNextAction) {

                            java.util.List<MacroAction> nextActs = new java.util.ArrayList<>();
                            int lastSkip = -1;
                            for (int j = i + 1; j < macro.actions.size(); j++) {
                                MacroAction candidate = macro.actions.get(j);

                                if (candidate instanceof DisconnectAction) break;
                                if (!candidate.isEnabled()) continue;

                                if (candidate instanceof DelayAction
                                    || candidate instanceof WaitForHealthAction
                                    || candidate instanceof WaitForBlockAction
                                    || candidate instanceof WaitForGuiAction
                                    || candidate instanceof WaitForCooldownAction
                                    || candidate instanceof WaitPosAction
                                    || candidate instanceof WaitForEntityAction
                                    || candidate instanceof WaitForSoundAction
                                    || candidate instanceof WaitForSlotChangeAction
                                    || candidate instanceof WaitForPacketAction
                                    || candidate instanceof WaitForChatAction
                                    || candidate instanceof GoToAction) {
                                    continue;
                                }
                                nextActs.add(candidate);
                                lastSkip = j;
                            }
                            da2.setNextActions(nextActs);
                            mc.execute(() -> da2.execute(mc));

                            if (lastSkip > i) i = lastSkip;
                        } else {

                            mc.execute(() -> da2.execute(mc));
                        }
                        stopCurrentRun();
                        break;
                    }
                    else if (action instanceof NbtBookAction nba) {
                        int totalBooks = Math.max(1, nba.bookCount);
                        long delayMs = Math.max(1, nba.delayTicks) * 50L;
                        for (int b = 0; b < totalBooks; b++) {
                            if (!isCurrentRunActive()) break;
                            final int bookIdx = b;
                            java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
                            mc.execute(() -> result.complete(nba.executeSingleBook(mc, bookIdx, totalBooks)));
                            Boolean success = result.get();
                            if (!success) break;

                            if (b < totalBooks - 1) {
                                Thread.sleep(delayMs);
                            }
                        }
                    }
                    else if (action instanceof GoToAction) {
                        GoToAction ga = (GoToAction) action;
                        runGoToAction(mc, ga);
                    }

                    else if (action instanceof MoveAction) {
                        MoveAction ma = (MoveAction) action;
                        if (ma.nonBlocking) {

                            mc.execute(() -> ma.execute(mc));
                            currentBackgroundMoves().put(ma, new int[]{ma.durationTicks - 1});

                        } else {

                            for (int t = 0; t < ma.durationTicks && isCurrentRunActive(); t++) {
                                mc.execute(() -> ma.execute(mc));
                                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(tickFuture);
                            }
                            mc.execute(() -> ma.release(mc));
                        }
                    }
                    else if (action instanceof LookAtBlockAction) {
                        LookAtBlockAction la = (LookAtBlockAction) action;
                        LookAtBlockAction.RotationTarget rotationTarget = resolveLookAtTargetOnClient(mc, la);
                        if (rotationTarget == null) {
                            continue;
                        }
                        if (la.smooth) {
                            startSmoothRotation(rotationTarget.yaw(), rotationTarget.pitch(), la.getRotationStep());
                            if (la.waitForCompletion) {
                                waitForSmoothRotationCompletion();
                            }
                        } else {
                            mc.execute(() -> {
                                if (mc.player == null) return;
                                mc.player.setYRot(rotationTarget.yaw());
                                mc.player.setXRot(rotationTarget.pitch());
                            });
                        }
                    }
                    else if (action instanceof RepeatAction) {
                        RepeatAction ra = (RepeatAction) action;
                        int startIdx = i + 1;
                        int endIdx = Math.min(startIdx + ra.stepCount, macro.actions.size());

                        for (int rep = 0; rep < ra.repeatCount && isCurrentRunActive(); rep++) {
                            setCurrentStatus("Repeat " + (rep + 1) + "/" + ra.repeatCount);
                            for (int j = startIdx; j < endIdx && isCurrentRunActive(); j++) {
                                MacroAction subAction = macro.actions.get(j);
                                if (!subAction.isEnabled()) continue;

                                REPEAT_PACKET_CONTEXT.set(new RepeatPacketContext(macro, j + 1, endIdx));
                                REPEAT_PACKET_SKIP.set(0);
                                try {
                                    executeSingleActionWithWaits(mc, subAction, module);
                                    j += Math.max(0, REPEAT_PACKET_SKIP.get());
                                } finally {
                                    REPEAT_PACKET_SKIP.set(0);
                                    REPEAT_PACKET_CONTEXT.remove();
                                }
                            }
                        }
                        i = endIdx - 1;
                    }
                    else if (action instanceof WaitForChatAction) {
                        WaitForChatAction wca = (WaitForChatAction) action;
                        setCurrentStatus("Waiting for chat: " + wca.pattern);
                        awaitChat(wca);
                    }
                    else if (action instanceof WaitForEntityAction) {
                        WaitForEntityAction wea = (WaitForEntityAction) action;
                        setCurrentStatus(wea.getDisplayName());
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForEntity(wea);
                            waitForCondition(future);
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForSoundAction) {
                        WaitForSoundAction wsa = (WaitForSoundAction) action;
                        String sndDesc = wsa.soundIds.isEmpty() ? "any" : wsa.soundIds.get(0);
                        setCurrentStatus("Waiting for sound: " + sndDesc);
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForSound(wsa);
                            waitForCondition(future);
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof MineAction) {
                        MineAction mna = (MineAction) action;
                        runMineAction(mc, mna);
                    }
                    else if (action instanceof InstaBreakAction) {
                        runInstaBreakAction(mc, (InstaBreakAction) action);
                    }

                    else if (action instanceof WaitForSlotChangeAction) {
                        WaitForSlotChangeAction wsca = (WaitForSlotChangeAction) action;
                        setCurrentStatus(wsca.getDisplayName());
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForSlotChange(wsca);
                            waitForCondition(future);
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForLanStepAction) {
                        WaitForLanStepAction wla = (WaitForLanStepAction) action;
                        setCurrentStatus(wla.getDisplayName());
                        try {
                            CompletableFuture<Void> future = MacroConditionRegistry.waitForLanStep(wla);
                            waitForCondition(future);
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForMacroStepAction) {
                        WaitForMacroStepAction wma = (WaitForMacroStepAction) action;
                        setCurrentStatus(wma.getDisplayName());
                        waitForMacroStep(wma);
                    }
                    else if (action instanceof StopMacroAction) {
                        setCurrentStatus("Stopping Macro");
                        StopMacroAction stopAction = (StopMacroAction) action;
                        if (stopAction.target != StopMacroAction.StopTarget.SELF) {
                            stopAction.execute(mc);
                            break;
                        }

                        try {
                            autismclient.util.PackUtilLANSync sync = autismclient.util.PackUtilLANSync.getInstance();
                            if (sync.isInSession()) {
                                sync.broadcastStepProgress(i + 1, macro.actions.size(), macro.name);
                            }
                        } catch (Exception ignored) {}
                        stopCurrentRun();
                        break;
                    }

                    else if (action instanceof TickSyncAction) {
                        TickSyncAction tsa = (TickSyncAction) action;

                        if (mc.level == null) continue;

                        java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                            preGeneratePackets(macro, i + 1, tsa.preGenCount);

                        long targetTick = mc.level.getGameTime() + tsa.tickOffset;
                        setCurrentStatus("Tick Sync -> " + targetTick + " (" + preGenerated.size() + " pkts)");

                        while (isCurrentRunActive() && mc.level.getGameTime() < targetTick) {
                            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                        }

                        if (isCurrentRunActive()) {
                            executePreGeneratedBurst(mc, preGenerated);
                            i += countPreGeneratedActions(macro, i + 1, preGenerated.size());
                            PackUtilClientMessaging.sendPrefixed("Tick sync: " + preGenerated.size() + " pkts sent");
                        }
                    }
                    else if (action instanceof RevisionSyncAction) {
                        RevisionSyncAction rsa = (RevisionSyncAction) action;

                        if (mc.player == null || mc.player.containerMenu == null) continue;

                        java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                            preGeneratePackets(macro, i + 1, rsa.preGenCount);

                        int baseRevision = mc.player.containerMenu.getStateId();
                        int targetRevision = baseRevision + rsa.revisionOffset;
                        setCurrentStatus("Rev Sync -> " + targetRevision + " (" + preGenerated.size() + " pkts)");

                        while (isCurrentRunActive()) {
                            if (mc.player == null || mc.player.containerMenu == null) break;
                            if (mc.player.containerMenu.getStateId() >= targetRevision) break;
                            java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
                        }

                        if (isCurrentRunActive() && mc.player != null && mc.player.containerMenu != null) {
                            executePreGeneratedBurst(mc, preGenerated);
                            i += countPreGeneratedActions(macro, i + 1, preGenerated.size());
                            PackUtilClientMessaging.sendPrefixed("Rev sync: " + preGenerated.size() + " pkts sent");
                        }
                    }
                    else if (action instanceof ServerTickSyncAction) {
                        ServerTickSyncAction stsa = (ServerTickSyncAction) action;

                        if (mc.getConnection() == null) continue;

                        java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                            preGeneratePackets(macro, i + 1, stsa.preGenCount);

                        int sampleWaitMs = 0;
                        while (isCurrentRunActive() && !ServerTickTracker.isReady() && sampleWaitMs < stsa.maxWaitMs) {

                            float progress = ServerTickTracker.getWarmupProgress();
                            setCurrentStatus(String.format("ServerSync warmup %.0f%% (%d samples, %dms)",
                                progress * 100, ServerTickTracker.getSampleCount(), ServerTickTracker.getTrackingTimeMs()));
                            Thread.sleep(50);
                            sampleWaitMs += 50;
                        }

                        if (!isCurrentRunActive()) continue;

                        long optimalTime = ServerTickTracker.getOptimalSendTime(stsa.bufferMs, stsa.ignorePing);
                        long msUntil = (optimalTime - System.nanoTime()) / 1_000_000L;
                        int ping = ServerTickTracker.getPingMs();
                        String pingStr = stsa.ignorePing ? " (no ping)" : " ping:" + ping + "ms";
                        setCurrentStatus("ServerSync in " + Math.max(0, msUntil) + "ms" + pingStr + " (" + preGenerated.size() + " pkts)");

                        long startWait = System.nanoTime();
                        long maxWaitNanos = stsa.maxWaitMs * 1_000_000L;

                        while (isCurrentRunActive()) {
                            long now = System.nanoTime();
                            if ((now - startWait) >= maxWaitNanos) break;
                            if (now >= optimalTime) break;

                            long remaining = optimalTime - now;
                            if (remaining > 2_000_000L) {
                                java.util.concurrent.locks.LockSupport.parkNanos(remaining - 2_000_000L);
                            } else {
                                Thread.onSpinWait();
                            }
                        }

                        if (isCurrentRunActive()) {
                            executePreGeneratedBurst(mc, preGenerated);
                            i += countPreGeneratedActions(macro, i + 1, preGenerated.size());
                            long actualDelay = (System.nanoTime() - optimalTime) / 1_000_000L;
                            PackUtilClientMessaging.sendPrefixed("ServerSync: " + preGenerated.size() + " pkts (+" + actualDelay + "ms)");
                        }
                    }
                    else if (action instanceof RotateAction) {
                        RotateAction ra = (RotateAction) action;
                        if (ra.smooth) {
                            startSmoothRotation(ra.yaw, ra.pitch, ra.getRotationStep());
                            if (ra.waitForCompletion) {
                                waitForSmoothRotationCompletion();
                            }
                        } else {
                            mc.execute(() -> action.execute(mc));
                        }
                    }
                    else if (action instanceof LookAtBlockAction la) {
                        LookAtBlockAction.RotationTarget rotationTarget = resolveLookAtTargetOnClient(mc, la);
                        if (rotationTarget == null) {
                            continue;
                        }
                        if (la.smooth) {
                            startSmoothRotation(rotationTarget.yaw(), rotationTarget.pitch(), la.getRotationStep());
                            if (la.waitForCompletion) {
                                waitForSmoothRotationCompletion();
                            }
                        } else {
                            mc.execute(() -> {
                                if (mc.player == null) return;
                                mc.player.setYRot(rotationTarget.yaw());
                                mc.player.setXRot(rotationTarget.pitch());
                            });
                        }
                    }
                    else if (action instanceof UseItemAction) {
                        UseItemAction ua = (UseItemAction) action;
                        if (ua.useMode == UseItemAction.UseMode.CUSTOM_HOLD) {

                            java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                            mc.execute(() -> {
                                try { ua.execute(mc); }
                                catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in UseItem: " + e.getMessage()); }
                                finally { uaLatch.countDown(); }
                            });
                            uaLatch.await(200, TimeUnit.MILLISECONDS);
                            if (ua.holdTicks > 0) {
                                for (int t = 0; t < ua.holdTicks && isCurrentRunActive(); t++) {
                                    CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                                    waitForCondition(tf);
                                }
                                java.util.concurrent.CountDownLatch relLatch = new java.util.concurrent.CountDownLatch(1);
                                mc.execute(() -> {
                                    try { ua.sendRelease(mc); }
                                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in UseItem release: " + e.getMessage()); }
                                    finally { relLatch.countDown(); }
                                });
                                relLatch.await(200, TimeUnit.MILLISECONDS);
                            }
                        } else {

                            int times = Math.max(1, ua.useCount);
                            for (int u = 0; u < times && isCurrentRunActive(); u++) {
                                java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                                mc.execute(() -> {
                                    try { ua.execute(mc); }
                                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in UseItem: " + e.getMessage()); }
                                    finally { uaLatch.countDown(); }
                                });
                                uaLatch.await(200, TimeUnit.MILLISECONDS);

                                if (u < times - 1 && isCurrentRunActive()) {
                                    CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                                    waitForCondition(tf);
                                }
                            }
                        }
                    }
                    else if (action instanceof ItemAction) {
                        ItemAction ia = (ItemAction) action;
                        if (ia.waitForItem) {
                            try {
                                waitForItemActionTargets(mc, ia);
                            } catch (java.util.concurrent.CancellationException e) { }
                        }
                        if (module != null && module.useInstantExecutionMode()) {
                            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                            mc.execute(() -> {
                                try { action.execute(mc); }
                                catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in ItemAction: " + e.getMessage()); }
                                finally { latch.countDown(); }
                            });
                            try { latch.await(100, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        } else {
                            mc.execute(() -> {
                                try { action.execute(mc); }
                                catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in ItemAction: " + e.getMessage()); }
                            });
                        }
                    }
                    else if (action instanceof CraftAction craftAction) {
                        runCraftAction(mc, craftAction);
                    }
                    else if (action instanceof SendPacketAction) {

                        if (module != null && module.useInstantExecutionMode()) {

                            try {
                                action.execute(mc);
                            } catch (Exception e) {
                                PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in SendPacket: " + e.getMessage());
                            }
                        } else {

                            mc.execute(() -> {
                                try {
                                    action.execute(mc);
                                } catch (Exception e) {
                                    PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in SendPacket: " + e.getMessage());
                                }
                            });
                        }
                    }
                    else if (action instanceof autismclient.util.macro.StoreItemAction) {
                        autismclient.util.macro.StoreItemAction sia = (autismclient.util.macro.StoreItemAction) action;
                        setCurrentStatus((sia.mode == autismclient.util.macro.StoreItemAction.Mode.LOOT ? "Looting" : "Storing")
                            + (sia.persistent ? " \u221e" : ""));
                        if (sia.persistent) {

                            while (isCurrentRunActive()) {
                                java.util.concurrent.CountDownLatch siLatch = new java.util.concurrent.CountDownLatch(1);
                                mc.execute(() -> {
                                    try { sia.doTransfer(mc); }
                                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cStore error: " + e.getMessage()); }
                                    finally { siLatch.countDown(); }
                                });
                                try { siLatch.await(2000, TimeUnit.MILLISECONDS); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

                                CompletableFuture<Void> siTick = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(siTick);
                            }
                        } else {
                            java.util.concurrent.CountDownLatch siLatch = new java.util.concurrent.CountDownLatch(1);
                            mc.execute(() -> {
                                try { sia.doTransfer(mc); }
                                catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cStore error: " + e.getMessage()); }
                                finally { siLatch.countDown(); }
                            });
                            try { siLatch.await(2000, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        }
                    }
                    else if (action instanceof ClickAction) {
                        ClickAction ca = (ClickAction) action;
                        int times = Math.max(1, ca.clickCount);
                        for (int c = 0; c < times && isCurrentRunActive(); c++) {
                            java.util.concurrent.CountDownLatch clickLatch = new java.util.concurrent.CountDownLatch(1);
                            mc.execute(() -> {
                                try { ca.execute(mc); }
                                catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in Click: " + e.getMessage()); }
                                finally { clickLatch.countDown(); }
                            });
                            try { clickLatch.await(200, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                            if (c < times - 1 && isCurrentRunActive()) {
                                CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(tf);
                            }
                        }
                    }
                    else if (action instanceof PayAction) {
                        runPayAction(mc, (PayAction) action);
                    }
                    else if (action instanceof XCarryAction xsa) {
                        if (xsa.mode == XCarryAction.Mode.PUT_IN
                                && xsa.transferMode == XCarryAction.TransferMode.CLICK) {
                            setCurrentStatus("XCarry Click");
                            runXCarryClickPutIn(mc, xsa);
                        } else {
                            setCurrentStatus(switch (xsa.mode) {
                                case PUT_IN -> "XCarry";
                                case TAKE_OUT -> "XCarry Out";
                                case DROP -> "XCarry Drop";
                            });
                            runOnClientThread(mc, () -> {
                                try {
                                    xsa.execute(mc);
                                } catch (Exception e) {
                                    PackUtilClientMessaging.sendPrefixed("ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§cXCarry: " + e.getMessage());
                                }
                            });
                        }
                    }
                    else if (action instanceof InventoryAuditAction auditAction
                            && (auditAction.mode == InventoryAuditAction.Mode.DUPE
                                || auditAction.mode == InventoryAuditAction.Mode.DUPE_SPAM)) {
                        setCurrentStatus("Dupe: " + auditAction.dupeVector.name() + " " + auditAction.openMode.name());
                        try {
                            auditAction.executeDupe(mc);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cDupe error: " + e.getMessage());
                        }
                    }
                    else {

                        if (module != null && module.useInstantExecutionMode()) {

                            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                            mc.execute(() -> {
                                try {
                                    action.execute(mc);
                                } catch (Exception e) {
                                    PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in action: " + e.getMessage());
                                } finally {
                                    latch.countDown();
                                }
                            });

                            try {
                                latch.await(100, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        } else {

                            mc.execute(() -> {
                                try {
                                    action.execute(mc);
                                } catch (Exception e) {
                                    PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cError in action: " + e.getMessage());
                                }
                            });
                        }
                    }

                    if (postGuiFuture != null && isCurrentRunActive()) {
                        try {
                            waitForCondition(postGuiFuture);
                        } catch (java.util.concurrent.CancellationException e) { }
                    }

                    if (isCurrentRunActive()) {
                        markStepCompleted(i + 1);
                        try {
                            autismclient.util.PackUtilLANSync sync = autismclient.util.PackUtilLANSync.getInstance();
                            if (sync.isInSession()) {
                                sync.broadcastStepProgress(i + 1, macro.actions.size(), macro.name);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (isCurrentRunActive()) {
                        if (module != null && module.useInstantExecutionMode()) {
                            int delayUs = module.getActionDelayUs();
                            if (delayUs > 0) {

                                java.util.concurrent.locks.LockSupport.parkNanos(delayUs * 1000L);
                            } else {

                                Thread.onSpinWait();
                            }
                        }
                    }
                    } finally {
                        releaseClientResources(heldResources);
                    }
                }

                if (macro.actions.isEmpty()) {
                    PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cMacro stopped: no actions left.");
                    stopCurrentRun();
                    break;
                }

                if (macro.loop && loopCount < maxLoops - 1 && isCurrentRunActive()) {
                    if (module != null && module.useInstantExecutionMode()) {

                        int delayUs = module.getActionDelayUs();
                        if (delayUs > 0) {
                            java.util.concurrent.locks.LockSupport.parkNanos(delayUs * 1000L);
                        } else {
                            Thread.onSpinWait();
                        }
                    } else if (module != null && module.useMsSleepMode()) {

                        int sleepMs = module.getMsSleepInterval();
                        Thread.sleep(sleepMs);
                    } else {

                        try {
                            CompletableFuture<Void> syncFuture = MacroConditionRegistry.waitForNextTick();
                            waitForCondition(syncFuture);
                        } catch (CancellationException e) {

                        }
                    }
                }

                loopCount++;
            }

            if (isCurrentRunActive()) {
                PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§aMacro finished: " + macro.name);
            }

        } catch (InterruptedException e) {

        } catch (Exception e) {
            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cMacro crashed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            finishCurrentRun();
            isRotating.set(false);
            rotationAlignedFrames = 0;
            if (!isRunning.get()) MacroConditionRegistry.cancelAll();

            try {
                autismclient.util.PackUtilLANSync sync = autismclient.util.PackUtilLANSync.getInstance();
                if (sync.isInSession()) {
                    sync.broadcastStepProgress(-1, 0, "");
                }
            } catch (Exception ignored) {}
        }
    }

    private static String normalizePacketKey(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean packetNameMatches(String expected, String candidate) {
        if (expected == null || expected.isEmpty() || candidate == null || candidate.isEmpty()) return false;

        String normalizedExpected = normalizePacketKey(expected);
        String normalizedCandidate = normalizePacketKey(candidate);
        if (normalizedExpected.isEmpty() || normalizedCandidate.isEmpty()) return false;
        if (normalizedExpected.equals(normalizedCandidate)) return true;

        if (normalizedCandidate.endsWith("packet")) {
            String strippedCandidate = normalizedCandidate.substring(0, normalizedCandidate.length() - "packet".length());
            if (normalizedExpected.equals(strippedCandidate)) return true;
        }

        return normalizedExpected.endsWith(normalizedCandidate) || normalizedCandidate.endsWith(normalizedExpected);
    }

    private static boolean matchesPacketTarget(String target, Packet<?> packet, String direction) {
        if (packet == null) return false;

        String expectedDirection = WaitForPacketAction.getDirection(target);
        if (!expectedDirection.isEmpty() && !expectedDirection.equalsIgnoreCase(direction)) return false;

        String expectedName = WaitForPacketAction.getPacketName(target);
        if (expectedName.isEmpty()) return true;

        String friendlyDirectional = PackUtilPacketNamer.getFriendlyName(packet, direction);
        if (packetNameMatches(expectedName, friendlyDirectional)) return true;

        String friendlyGeneric = PackUtilPacketNamer.getFriendlyName(packet);
        if (packetNameMatches(expectedName, friendlyGeneric)) return true;

        String simpleName = packet.getClass().getSimpleName();
        if (packetNameMatches(expectedName, simpleName)) return true;

        @SuppressWarnings("unchecked")
        String fullName = PackUtilPacketRegistry.getName((Class<? extends Packet<?>>) packet.getClass());
        return packetNameMatches(expectedName, fullName);
    }

    private static void awaitPacket(String target) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            String normalizedTarget = WaitForPacketAction.normalizeTarget(target);
            synchronized (run.packetWaitLock) {
                run.waitingPacketName.set(normalizedTarget);
                run.packetFuture = future;
                run.waitingForPacket.set(true);
            }
            try {
                setCurrentStatus(normalizedTarget.isEmpty()
                    ? "Waiting for packet: Any"
                    : "Waiting for packet: " + WaitForPacketAction.getDisplayLabel(normalizedTarget));
                waitForCondition(future);
            } finally {
                synchronized (run.packetWaitLock) {
                    if (run.packetFuture == future) {
                        run.waitingForPacket.set(false);
                        run.waitingPacketName.set("");
                        run.packetFuture = null;
                    }
                }
            }
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        String normalizedTarget = WaitForPacketAction.normalizeTarget(target);
        synchronized (packetWaitLock) {
            waitingPacketName.set(normalizedTarget);
            lastReceivedPacket.set("");
            activePacketFuture = future;
            isWaitingForPacket.set(true);
        }
        try {
            setCurrentStatus(normalizedTarget.isEmpty()
                ? "Waiting for packet: Any"
                : "Waiting for packet: " + WaitForPacketAction.getDisplayLabel(normalizedTarget));
            waitForCondition(future);
        } finally {
            synchronized (packetWaitLock) {
                if (activePacketFuture == future) {
                    isWaitingForPacket.set(false);
                    waitingPacketName.set("");
                    activePacketFuture = null;
                }
            }
        }
    }

    private static void awaitChat(WaitForChatAction wca) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            run.chatFuture = future;
            run.waitingChatAction = wca;
            run.waitingForChat.set(true);
            try {
                if (wca.timeoutMs > 0) future.completeOnTimeout(null, wca.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                waitForCondition(future);
            } finally {
                if (run.chatFuture == future) {
                    run.waitingForChat.set(false);
                    run.waitingChatAction = null;
                    run.chatFuture = null;
                }
            }
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        activeChatFuture = future;
        isWaitingForChat.set(true);
        waitingChatPattern.set(wca.pattern);
        waitingChatPatternJson.set(wca.patternJson == null ? "" : wca.patternJson);
        waitingChatIsRegex.set(wca.useRegex);
        waitingChatFuzzyPercent.set(WaitForChatAction.clampFuzzyPercent(wca.fuzzyPercent));
        lastMatchedChat.set("");
        try {
            if (wca.timeoutMs > 0) future.completeOnTimeout(null, wca.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            waitForCondition(future);
        } finally {
            isWaitingForChat.set(false);
            waitingChatPattern.set("");
            waitingChatPatternJson.set("");
            waitingChatIsRegex.set(false);
            waitingChatFuzzyPercent.set(100);
            activeChatFuture = null;
        }
    }

    private static void waitForCondition(CompletableFuture<Void> future) {

        Thread t = Thread.currentThread();
        future.whenComplete((v, e) -> java.util.concurrent.locks.LockSupport.unpark(t));
        while (!future.isDone() && isCurrentRunActive()) {

            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
            if (Thread.interrupted()) break;
        }
        if (!isCurrentRunActive()) {
            future.cancel(true);
        }
    }

    private static void waitForMacroStep(WaitForMacroStepAction action) {
        long startMs = System.currentTimeMillis();
        while (isCurrentRunActive()) {
            if (isMacroStepSatisfied(action)) return;
            if (action.timeoutMs > 0 && System.currentTimeMillis() - startMs >= action.timeoutMs) return;
            CompletableFuture<Void> tick = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tick);
        }
    }

    private static boolean isMacroStepSatisfied(WaitForMacroStepAction action) {
        String target = action.macroName == null ? "" : action.macroName.trim();
        if (target.isEmpty()) return true;
        synchronized (RUN_LOCK) {
            for (RunState run : ACTIVE_RUNS.values()) {
                String name = run.macro != null && run.macro.name != null ? run.macro.name : "";
                if (!name.equalsIgnoreCase(target)) continue;
                return switch (action.mode) {
                    case STARTED_STEP -> run.currentStepIndex + 1 >= Math.max(1, action.step);
                    case COMPLETED_STEP -> run.lastCompletedStep >= Math.max(1, action.step);
                    case FINISHED -> !run.running.get();
                };
            }
        }
        return action.mode == WaitForMacroStepAction.WaitMode.FINISHED;
    }

    private static void executeSingleActionWithWaits(Minecraft mc, MacroAction action, PackUtilModule module) throws InterruptedException {
        EnumSet<SharedClientResource> heldResources = acquireClientResources(action);
        try {

        java.util.concurrent.CompletableFuture<Void> postGuiFuture = null;
        if (action instanceof WaitsForGui) {
            WaitsForGui wfg = (WaitsForGui) action;
            if (wfg.isWaitForGui()) {
                if (wfg.isWaitForGuiChange()) {
                    postGuiFuture = MacroConditionRegistry.waitForGuiChange(mc.screen);
                } else {
                    postGuiFuture = MacroConditionRegistry.waitForGui(wfg.getWaitGuiName());
                }
            }
        }

        if (action instanceof DelayAction) {
            DelayAction da = (DelayAction) action;
            if (da.useTicks) {
                for (int t = 0; t < da.delayTicks && isCurrentRunActive(); t++) {
                    CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(tf);
                }
            } else {
                Thread.sleep(da.delayMs);
            }
        } else if (action instanceof WaitForPacketAction) {
            java.util.List<String> targets = ((WaitForPacketAction) action).effectiveList();
            if (targets.isEmpty()) {
                awaitPacket("");
            } else {
                for (String target : targets) {
                    if (!isCurrentRunActive()) break;
                    awaitPacket(target);
                }
            }
        } else if (action instanceof WaitForHealthAction) {
            WaitForHealthAction wh = (WaitForHealthAction) action;
            setCurrentStatus(wh.waitingStatusText());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForHealth(wh.healthThreshold, wh.below);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForBlockAction) {
            WaitForBlockAction wba = (WaitForBlockAction) action;
            setCurrentStatus(wba.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForBlock(wba);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForGuiAction) {
            WaitForGuiAction wga = (WaitForGuiAction) action;
            if (wga.waitMode == WaitForGuiAction.WaitMode.CLOSE) {
                setCurrentStatus("Waiting for GUI close: " + wga.guiTitle);
                try {
                    CompletableFuture<Void> future = MacroConditionRegistry.waitForGuiClose(wga.guiTitle);
                    waitForCondition(future);
                } catch (CancellationException e) {}
            } else {
                setCurrentStatus("Waiting for GUI: " + wga.guiTitle);
                try {
                    CompletableFuture<Void> future = MacroConditionRegistry.waitForGui(wga.guiTitle);
                    waitForCondition(future);
                } catch (CancellationException e) {}
            }
        } else if (action instanceof WaitForCooldownAction) {
            WaitForCooldownAction wca = (WaitForCooldownAction) action;
            ItemTarget cooldownTarget = resolveItemTarget(wca.itemTarget, wca.itemName);
            String cooldownLabel = describeItemTarget(cooldownTarget);
            setCurrentStatus("Waiting for cooldown: " +
                (!cooldownLabel.isEmpty() ? cooldownLabel : (wca.checkMainInteractionHand ? "Main Hand" : "Off Hand")));
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForCooldown(cooldownTarget, wca.checkMainInteractionHand);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitPosAction) {
            WaitPosAction wpa = (WaitPosAction) action;
            setCurrentStatus("Waiting for Pos: " + String.format("%.0f, %.0f, %.0f", wpa.x, wpa.y, wpa.z));
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForPos(
                    wpa.x, wpa.y, wpa.z, wpa.leeway, wpa.checkRotation, wpa.yaw, wpa.pitch, wpa.rotLeeway);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForChatAction) {
            WaitForChatAction wca = (WaitForChatAction) action;
            setCurrentStatus("Waiting for chat: " + wca.pattern);
            awaitChat(wca);
        } else if (action instanceof WaitForEntityAction) {
            WaitForEntityAction wea = (WaitForEntityAction) action;
            setCurrentStatus(wea.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForEntity(wea);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForSoundAction) {
            WaitForSoundAction wsa = (WaitForSoundAction) action;
            String sndDesc = wsa.soundIds.isEmpty() ? "any" : wsa.soundIds.get(0);
            setCurrentStatus("Waiting for sound: " + sndDesc);
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForSound(wsa);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForSlotChangeAction) {
            WaitForSlotChangeAction wsca = (WaitForSlotChangeAction) action;
            setCurrentStatus(wsca.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForSlotChange(wsca);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForLanStepAction) {
            WaitForLanStepAction wla = (WaitForLanStepAction) action;
            setCurrentStatus(wla.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForLanStep(wla);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForMacroStepAction) {
            WaitForMacroStepAction wma = (WaitForMacroStepAction) action;
            setCurrentStatus(wma.getDisplayName());
            waitForMacroStep(wma);
        } else if (action instanceof GoToAction) {
            GoToAction ga = (GoToAction) action;
            runGoToAction(mc, ga);
        } else if (action instanceof MoveAction) {
            MoveAction ma = (MoveAction) action;
            if (ma.nonBlocking) {
                mc.execute(() -> ma.execute(mc));
                currentBackgroundMoves().put(ma, new int[]{ma.durationTicks - 1});
            } else {
                for (int t = 0; t < ma.durationTicks && isCurrentRunActive(); t++) {
                    mc.execute(() -> ma.execute(mc));
                    CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(tickFuture);
                }
                mc.execute(() -> ma.release(mc));
            }
        } else if (action instanceof LookAtBlockAction) {
            LookAtBlockAction la = (LookAtBlockAction) action;
            LookAtBlockAction.RotationTarget rotationTarget = resolveLookAtTargetOnClient(mc, la);
            if (rotationTarget == null) {
                return;
            }
            if (la.smooth) {
                startSmoothRotation(rotationTarget.yaw(), rotationTarget.pitch(), la.getRotationStep());
                if (la.waitForCompletion) {
                    waitForSmoothRotationCompletion();
                }
            } else {
                mc.execute(() -> {
                    if (mc.player == null) return;
                    mc.player.setYRot(rotationTarget.yaw());
                    mc.player.setXRot(rotationTarget.pitch());
                });
            }
        } else if (action instanceof RotateAction) {
            RotateAction ra = (RotateAction) action;
            if (ra.smooth) {
                startSmoothRotation(ra.yaw, ra.pitch, ra.getRotationStep());
                if (ra.waitForCompletion) {
                    waitForSmoothRotationCompletion();
                }
            } else {
                mc.execute(() -> action.execute(mc));
            }
        } else if (action instanceof MineAction) {
            MineAction mna = (MineAction) action;
            runMineAction(mc, mna);
        } else if (action instanceof InstaBreakAction) {
            runInstaBreakAction(mc, (InstaBreakAction) action);
        } else if (action instanceof UseItemAction) {
            UseItemAction ua = (UseItemAction) action;
            if (ua.useMode == UseItemAction.UseMode.CUSTOM_HOLD) {

                java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try { ua.execute(mc); }
                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in UseItem: " + e.getMessage()); }
                    finally { uaLatch.countDown(); }
                });
                try { uaLatch.await(200, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (ua.holdTicks > 0) {
                    for (int t = 0; t < ua.holdTicks && isCurrentRunActive(); t++) {
                        CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                        waitForCondition(tf);
                    }
                    java.util.concurrent.CountDownLatch relLatch = new java.util.concurrent.CountDownLatch(1);
                    mc.execute(() -> {
                        try { ua.sendRelease(mc); }
                        catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in UseItem release: " + e.getMessage()); }
                        finally { relLatch.countDown(); }
                    });
                    try { relLatch.await(200, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            } else {

                int times = Math.max(1, ua.useCount);
                for (int u = 0; u < times && isCurrentRunActive(); u++) {
                    java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                    mc.execute(() -> {
                        try { ua.execute(mc); }
                        catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in UseItem: " + e.getMessage()); }
                        finally { uaLatch.countDown(); }
                    });
                    try { uaLatch.await(200, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (u < times - 1 && isCurrentRunActive()) {
                        CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                        waitForCondition(tf);
                    }
                }
            }
        } else if (action instanceof JumpAction) {
            JumpAction ja = (JumpAction) action;
            mc.execute(() -> ja.execute(mc));
            if (ja.tap) {
                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                waitForCondition(tickFuture);
            } else {
                for (int t = 0; t < ja.durationTicks && isCurrentRunActive(); t++) {
                    CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(tickFuture);
                }
            }
            mc.execute(() -> ja.release(mc));
        } else if (action instanceof SneakAction) {
            SneakAction sna = (SneakAction) action;
            mc.execute(() -> sna.execute(mc));
            if (sna.persistent && sna.sneak) {
                currentPersistentActions().removeIf(p -> p instanceof SneakAction);
                currentPersistentActions().add(sna);
            } else if (!sna.sneak) {
                currentPersistentActions().removeIf(p -> p instanceof SneakAction);
            }
        } else if (action instanceof SprintAction) {
            SprintAction spa = (SprintAction) action;
            mc.execute(() -> spa.execute(mc));
            if (spa.persistent && spa.sprint) {
                currentPersistentActions().removeIf(p -> p instanceof SprintAction);
                currentPersistentActions().add(spa);
            } else if (!spa.sprint) {
                currentPersistentActions().removeIf(p -> p instanceof SprintAction);
            }
        } else if (action instanceof NbtBookAction nba) {
            int totalBooks = Math.max(1, nba.bookCount);
            long delayMs = Math.max(1, nba.delayTicks) * 50L;
            for (int b = 0; b < totalBooks; b++) {
                if (!isCurrentRunActive()) break;
                final int bookIdx = b;
                java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
                mc.execute(() -> result.complete(nba.executeSingleBook(mc, bookIdx, totalBooks)));
                try {
                    Boolean success = result.get();
                    if (!success) break;
                } catch (java.util.concurrent.ExecutionException e) {
                    break;
                }
                if (b < totalBooks - 1) {
                    Thread.sleep(delayMs);
                }
            }
        } else if (action instanceof ItemAction) {
            ItemAction ia = (ItemAction) action;
            if (ia.waitForItem) {
                try {
                    waitForItemActionTargets(mc, ia);
                } catch (java.util.concurrent.CancellationException e) {}
            }
            if (module != null && module.useInstantExecutionMode()) {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try { action.execute(mc); }
                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in ItemAction: " + e.getMessage()); }
                    finally { latch.countDown(); }
                });
                try { latch.await(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else {
                mc.execute(() -> {
                    try { action.execute(mc); }
                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in ItemAction: " + e.getMessage()); }
                });
            }
        } else if (action instanceof SendPacketAction) {
            if (module != null && module.useInstantExecutionMode()) {
                try {
                    action.execute(mc);
                } catch (Exception e) {
                    PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in SendPacket: " + e.getMessage());
                }
            } else {
                mc.execute(() -> {
                    try {
                        action.execute(mc);
                    } catch (Exception e) {
                        PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in SendPacket: " + e.getMessage());
                    }
                });
            }
        } else if (action instanceof ClickAction) {
            ClickAction ca = (ClickAction) action;
            int times = Math.max(1, ca.clickCount);
            for (int c = 0; c < times && isCurrentRunActive(); c++) {
                java.util.concurrent.CountDownLatch clickLatch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try { ca.execute(mc); }
                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in Click: " + e.getMessage()); }
                    finally { clickLatch.countDown(); }
                });
                try { clickLatch.await(200, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (c < times - 1 && isCurrentRunActive()) {
                    CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(tf);
                }
            }
        } else if (action instanceof PayAction) {
            runPayAction(mc, (PayAction) action);
        } else if (action instanceof XCarryAction xsa) {
            if (xsa.mode == XCarryAction.Mode.PUT_IN
                    && xsa.transferMode == XCarryAction.TransferMode.CLICK) {
                setCurrentStatus("XCarry Click");
                runXCarryClickPutIn(mc, xsa);
            } else {
                setCurrentStatus(switch (xsa.mode) {
                    case PUT_IN -> "XCarry";
                    case TAKE_OUT -> "XCarry Out";
                    case DROP -> "XCarry Drop";
                });
                runOnClientThread(mc, () -> {
                    try {
                        xsa.execute(mc);
                    } catch (Exception e) {
                        PackUtilClientMessaging.sendPrefixed("Ã‚Â§cXCarry: " + e.getMessage());
                    }
                });
            }
        } else if (action instanceof CraftAction) {
            runCraftAction(mc, (CraftAction) action);
        } else if (action instanceof autismclient.util.macro.StoreItemAction) {
            autismclient.util.macro.StoreItemAction sia = (autismclient.util.macro.StoreItemAction) action;
            setCurrentStatus((sia.mode == autismclient.util.macro.StoreItemAction.Mode.LOOT ? "Looting" : "Storing")
                + (sia.persistent ? " \u221e" : ""));
            if (sia.persistent) {
                while (isCurrentRunActive()) {
                    java.util.concurrent.CountDownLatch siLatch = new java.util.concurrent.CountDownLatch(1);
                    mc.execute(() -> {
                        try { sia.doTransfer(mc); }
                        catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cStore error: " + e.getMessage()); }
                        finally { siLatch.countDown(); }
                    });
                    try { siLatch.await(2000, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    CompletableFuture<Void> siTick = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(siTick);
                }
            } else {
                java.util.concurrent.CountDownLatch siLatch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try { sia.doTransfer(mc); }
                    catch (Exception e) { PackUtilClientMessaging.sendPrefixed("Ã‚Â§cStore error: " + e.getMessage()); }
                    finally { siLatch.countDown(); }
                });
                try { siLatch.await(2000, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        } else if (action instanceof InventoryAuditAction auditAction
                && (auditAction.mode == InventoryAuditAction.Mode.DUPE
                    || auditAction.mode == InventoryAuditAction.Mode.DUPE_SPAM)) {
            setCurrentStatus("Dupe: " + auditAction.dupeVector.name() + " " + auditAction.openMode.name());
            try {
                auditAction.executeDupe(mc);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                PackUtilClientMessaging.sendPrefixed("Ã‚Â§cDupe error: " + e.getMessage());
            }
        } else if (action instanceof TickSyncAction tsa) {
            if (mc.level == null) return;
            java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                preGeneratePacketsForRepeat(tsa.preGenCount, mc);
            long targetTick = mc.level.getGameTime() + tsa.tickOffset;
            setCurrentStatus("Tick Sync -> " + targetTick + " (" + preGenerated.size() + " pkts)");
            while (isCurrentRunActive() && mc.level.getGameTime() < targetTick) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            if (isCurrentRunActive()) {
                executePreGeneratedBurst(mc, preGenerated);
                RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
                if (context != null) REPEAT_PACKET_SKIP.set(countPreGeneratedActions(context.macro(), context.startIdx(), preGenerated.size(), context.endIdx()));
                PackUtilClientMessaging.sendPrefixed("Tick sync: " + preGenerated.size() + " pkts sent");
            }
        } else if (action instanceof RevisionSyncAction rsa) {
            if (mc.player == null || mc.player.containerMenu == null) return;
            java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                preGeneratePacketsForRepeat(rsa.preGenCount, mc);
            int baseRevision = mc.player.containerMenu.getStateId();
            int targetRevision = baseRevision + rsa.revisionOffset;
            setCurrentStatus("Rev Sync -> " + targetRevision + " (" + preGenerated.size() + " pkts)");
            while (isCurrentRunActive()) {
                if (mc.player == null || mc.player.containerMenu == null) break;
                if (mc.player.containerMenu.getStateId() >= targetRevision) break;
                java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
            }
            if (isCurrentRunActive() && mc.player != null && mc.player.containerMenu != null) {
                executePreGeneratedBurst(mc, preGenerated);
                RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
                if (context != null) REPEAT_PACKET_SKIP.set(countPreGeneratedActions(context.macro(), context.startIdx(), preGenerated.size(), context.endIdx()));
                PackUtilClientMessaging.sendPrefixed("Rev sync: " + preGenerated.size() + " pkts sent");
            }
        } else if (action instanceof ServerTickSyncAction stsa) {
            if (mc.getConnection() == null) return;
            java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                preGeneratePacketsForRepeat(stsa.preGenCount, mc);
            int sampleWaitMs = 0;
            while (isCurrentRunActive() && !ServerTickTracker.isReady() && sampleWaitMs < stsa.maxWaitMs) {
                float progress = ServerTickTracker.getWarmupProgress();
                setCurrentStatus(String.format("ServerSync warmup %.0f%% (%d samples, %dms)",
                    progress * 100, ServerTickTracker.getSampleCount(), ServerTickTracker.getTrackingTimeMs()));
                Thread.sleep(50);
                sampleWaitMs += 50;
            }
            if (!isCurrentRunActive()) return;
            long optimalTime = ServerTickTracker.getOptimalSendTime(stsa.bufferMs, stsa.ignorePing);
            long msUntil = (optimalTime - System.nanoTime()) / 1_000_000L;
            int ping = ServerTickTracker.getPingMs();
            String pingStr = stsa.ignorePing ? " (no ping)" : " ping:" + ping + "ms";
            setCurrentStatus("ServerSync in " + Math.max(0, msUntil) + "ms" + pingStr + " (" + preGenerated.size() + " pkts)");
            long startWait = System.nanoTime();
            long maxWaitNanos = stsa.maxWaitMs * 1_000_000L;
            while (isCurrentRunActive()) {
                long now = System.nanoTime();
                if ((now - startWait) >= maxWaitNanos) break;
                if (now >= optimalTime) break;
                long remaining = optimalTime - now;
                if (remaining > 2_000_000L) {
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining - 2_000_000L);
                } else {
                    Thread.onSpinWait();
                }
            }
            if (isCurrentRunActive()) {
                executePreGeneratedBurst(mc, preGenerated);
                RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
                if (context != null) REPEAT_PACKET_SKIP.set(countPreGeneratedActions(context.macro(), context.startIdx(), preGenerated.size(), context.endIdx()));
                long actualDelay = (System.nanoTime() - optimalTime) / 1_000_000L;
                PackUtilClientMessaging.sendPrefixed("ServerSync: " + preGenerated.size() + " pkts (+" + actualDelay + "ms)");
            }
        } else if (action instanceof StopMacroAction) {
            StopMacroAction stopAction = (StopMacroAction) action;
            if (stopAction.target == StopMacroAction.StopTarget.SELF) stopCurrentRun();
            else stopAction.execute(mc);
        } else {

            if (module != null && module.useInstantExecutionMode()) {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try {
                        action.execute(mc);
                    } catch (Exception e) {
                        PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in action: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    latch.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                mc.execute(() -> {
                    try {
                        action.execute(mc);
                    } catch (Exception e) {
                        PackUtilClientMessaging.sendPrefixed("Ã‚Â§cError in action: " + e.getMessage());
                    }
                });
            }
        }

        if (postGuiFuture != null && isCurrentRunActive()) {
            try {
                waitForCondition(postGuiFuture);
            } catch (java.util.concurrent.CancellationException e) {}
        }

        if (isCurrentRunActive() && module != null && module.useInstantExecutionMode()) {
            int delayUs = module.getActionDelayUs();
            if (delayUs > 0) java.util.concurrent.locks.LockSupport.parkNanos(delayUs * 1000L);
            else Thread.onSpinWait();
        }
        } finally {
            releaseClientResources(heldResources);
        }
    }

    private static java.util.List<net.minecraft.network.protocol.Packet<?>> preGeneratePacketsForRepeat(
            int count, Minecraft mc) {
        RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
        if (context == null || context.macro() == null) return new java.util.ArrayList<>();
        return preGeneratePackets(context.macro(), context.startIdx(), count, context.endIdx());
    }

    private static void runXCarryClickPutIn(Minecraft mc, XCarryAction xsa) throws InterruptedException {
        if (mc == null || mc.player == null || mc.gameMode == null) return;

        AtomicReference<net.minecraft.world.inventory.AbstractContainerMenu> containerHandlerRef = new AtomicReference<>();
        AtomicReference<autismclient.util.PackUtilContainerTarget> containerTargetRef = new AtomicReference<>();
        AtomicReference<List<Integer>> containerSlotIdsRef = new AtomicReference<>(List.of());
        AtomicBoolean usingContainer = new AtomicBoolean(false);

        runOnClientThread(mc, () -> {
            if (mc.player == null) return;
            net.minecraft.world.inventory.AbstractContainerMenu current = mc.player.containerMenu;
            if (current != null && current != mc.player.inventoryMenu) {
                usingContainer.set(true);
                containerHandlerRef.set(current);
                containerTargetRef.set(autismclient.util.PackUtilSharedState.get().getLastContainerTarget());
                containerSlotIdsRef.set(new ArrayList<>(xsa.collectContainerTransferSlots(current)));
            }
        });

        if (usingContainer.get() && containerTargetRef.get() == null) {
            PackUtilClientMessaging.sendPrefixed("XCarry click mode: missing container target, using fast mode.");
            runOnClientThread(mc, () -> xsa.execute(mc));
            return;
        }

        if (usingContainer.get()) {
            for (int slotId : containerSlotIdsRef.get()) {
                if (!isCurrentRunActive()) return;
                setCurrentStatus("XCarry Click: collect");
                runOnClientThread(mc, () -> {
                    if (mc.player == null || mc.gameMode == null) return;
                    net.minecraft.world.inventory.AbstractContainerMenu handler = containerHandlerRef.get();
                    if (handler == null || mc.player.containerMenu != handler) return;
                    if (slotId < 0 || slotId >= handler.slots.size()) return;
                    if (handler.slots.get(slotId).getItem().isEmpty()) return;
                    mc.gameMode.handleContainerInput(handler.containerId, slotId, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
                });
                waitOneTick();
            }

            runOnClientThread(mc, () -> {
                if (mc.player == null || mc.getConnection() == null) return;
                net.minecraft.world.inventory.AbstractContainerMenu handler = containerHandlerRef.get();
                if (handler == null) return;
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(handler.containerId));
            });
            waitOneTick();

            runOnClientThread(mc, () -> {
                if (mc.player != null) {
                    mc.player.containerMenu = mc.player.inventoryMenu;
                }
            });
            waitOneTick();
        }

        while (isCurrentRunActive()) {
            XCarryAction.PutInMove move = callOnClientThread(mc, () -> {
                if (mc.player == null) return null;
                return xsa.findNextPutInMove(mc.player.inventoryMenu);
            }, null);
            if (move == null) break;

            setCurrentStatus("XCarry Click -> " + move.targetSlotId);
            runOnClientThread(mc, () -> {
                if (mc.player == null || mc.gameMode == null) return;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                mc.player.containerMenu = handler;
                if (!handler.getCarried().isEmpty()) return;
                if (move.sourceSlotId < 0 || move.sourceSlotId >= handler.slots.size()) return;
                if (move.targetSlotId < 0 || move.targetSlotId >= handler.slots.size()) return;
                if (handler.slots.get(move.sourceSlotId).getItem().isEmpty()) return;
                if (!handler.slots.get(move.targetSlotId).getItem().isEmpty()) return;
                mc.gameMode.handleContainerInput(handler.containerId, move.sourceSlotId, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            });

            boolean pickedUp = waitForXCarryCondition(mc, 400L, () -> {
                if (mc.player == null) return false;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                if (move.sourceSlotId < 0 || move.sourceSlotId >= handler.slots.size()) return false;
                return !handler.getCarried().isEmpty()
                        && handler.slots.get(move.sourceSlotId).getItem().isEmpty();
            });
            if (!pickedUp) {
                rollbackXCarryCursor(mc, move.sourceSlotId);
                break;
            }

            waitOneTick();

            runOnClientThread(mc, () -> {
                if (mc.player == null || mc.gameMode == null) return;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                mc.player.containerMenu = handler;
                if (handler.getCarried().isEmpty()) return;
                if (move.targetSlotId < 0 || move.targetSlotId >= handler.slots.size()) return;
                if (!handler.slots.get(move.targetSlotId).getItem().isEmpty()) return;
                mc.gameMode.handleContainerInput(handler.containerId, move.targetSlotId, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            });

            boolean placed = waitForXCarryCondition(mc, 500L, () -> {
                if (mc.player == null) return false;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                if (move.targetSlotId < 0 || move.targetSlotId >= handler.slots.size()) return false;
                return handler.getCarried().isEmpty()
                        && !handler.slots.get(move.targetSlotId).getItem().isEmpty();
            });
            if (!placed) {
                rollbackXCarryCursor(mc, move.sourceSlotId);
                break;
            }

            waitOneTick();
        }

        runOnClientThread(mc, () -> {
            if (mc.player != null) {
                boolean active = XCarryAction.hasStoredItems(mc.player.inventoryMenu, xsa.carryCursor);
                autismclient.util.PackUtilSharedState.get().setXCarryForced(active);
                autismclient.util.PackUtilSharedState.get().setXCarryActive(active);
            }
        });

        if (usingContainer.get()) {
            runOnClientThread(mc, () -> {
                if (mc.player == null) return;
                net.minecraft.world.inventory.AbstractContainerMenu containerHandler = containerHandlerRef.get();
                if (containerHandler != null) {
                    mc.player.containerMenu = containerHandler;
                }
                autismclient.util.PackUtilContainerTarget containerTarget = containerTargetRef.get();
                if (containerTarget != null) {
                    XCarryAction.sendOpenTarget(mc, containerTarget);
                }
            });
            waitOneTick();
        }
    }

    private static void rollbackXCarryCursor(Minecraft mc, int sourceSlotId) throws InterruptedException {
        runOnClientThread(mc, () -> {
            if (mc.player == null || mc.gameMode == null) return;
            net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
            mc.player.containerMenu = handler;
            if (handler.getCarried().isEmpty()) return;
            if (sourceSlotId < 0 || sourceSlotId >= handler.slots.size()) return;
            mc.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        });
        waitForXCarryCondition(mc, 300L, () -> mc.player != null && mc.player.inventoryMenu.getCarried().isEmpty());
    }

    private static boolean waitForXCarryCondition(Minecraft mc, long timeoutMs, java.util.function.Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (isCurrentRunActive()) {
            if (Boolean.TRUE.equals(callOnClientThread(mc, condition, Boolean.FALSE))) {
                return true;
            }
            if (System.nanoTime() >= deadline) break;
            waitOneTick();
        }
        return Boolean.TRUE.equals(callOnClientThread(mc, condition, Boolean.FALSE));
    }

    private static void waitOneTick() {
        CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
        waitForCondition(tickFuture);
    }

    private static void runInstaBreakAction(Minecraft mc, InstaBreakAction action) throws InterruptedException {
        if (mc == null || mc.player == null || mc.level == null || mc.getConnection() == null || mc.gameMode == null) {
            PackUtilClientMessaging.sendPrefixed("§cInstaBreak: missing world or connection");
            return;
        }

        BlockPos target = action.blockPos == null ? BlockPos.ZERO : action.blockPos;
        Direction direction = action.direction == null ? Direction.UP : action.direction;
        int delayTicks = Math.max(0, action.delayTicks);
        int targetTimes = Math.max(0, action.times);
        int completed = 0;
        int ticks = delayTicks;
        boolean wasSolid = !isInstaBreakAir(mc, target);

        if (!ensureInstaBreakPickaxe(mc, action)) return;

        PackUtilInstaBreakRenderer.setTarget(target);
        try {
            primeInstaBreakTarget(mc, target, direction);
            while (isCurrentRunActive() && isActionStillInCurrentMacro(action)) {
                boolean isAir = isInstaBreakAir(mc, target);
                if (wasSolid && isAir) {
                    completed++;
                    setCurrentStatus("InstaBreak " + completed + "/" + (targetTimes == 0 ? "∞" : targetTimes));
                    if (targetTimes > 0 && completed >= targetTimes) return;
                    wasSolid = false;
                } else if (!isAir) {
                    wasSolid = true;
                }

                if (!isAir && ticks >= delayTicks) {
                    ticks = 0;
                    if (!ensureInstaBreakPickaxe(mc, action)) return;
                    sendInstaBreakAttempt(mc, target, direction);
                } else {
                    ticks++;
                }

                setCurrentStatus("InstaBreak " + target.getX() + "," + target.getY() + "," + target.getZ()
                    + " " + completed + "/" + (targetTimes == 0 ? "∞" : targetTimes));
                waitOneTick();
            }
        } finally {
            PackUtilInstaBreakRenderer.clearTarget(target);
        }
    }

    private static boolean isInstaBreakAir(Minecraft mc, BlockPos pos) throws InterruptedException {
        return callOnClientThread(mc, () -> mc.level == null || mc.level.isOutsideBuildHeight(pos) || mc.level.getBlockState(pos).isAir(), true);
    }

    private static boolean isActionStillInCurrentMacro(MacroAction action) {
        RunState run = CURRENT_RUN.get();
        if (run == null || run.macro == null || run.macro.actions == null) return true;
        return run.macro.actions.contains(action);
    }

    private static boolean ensureInstaBreakPickaxe(Minecraft mc, InstaBreakAction action) throws InterruptedException {
        return callOnClientThread(mc, () -> {
            if (mc.player == null) return false;
            if (mc.player.getMainHandItem().is(ItemTags.PICKAXES)) return true;
            if (!action.autoPickaxe) return false;

            int preferred = Math.max(0, Math.min(8, mc.player.getInventory().getSelectedSlot()));
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getItem(slot).is(ItemTags.PICKAXES)) {
                    PackUtilInventoryHelper.selectHotbarSlot(mc, slot);
                    return true;
                }
            }

            for (int slot = 9; slot < 36; slot++) {
                if (mc.player.getInventory().getItem(slot).is(ItemTags.PICKAXES)) {
                    if (!PackUtilInventoryHelper.swapInventorySlots(mc, slot, preferred)) return false;
                    PackUtilInventoryHelper.selectHotbarSlot(mc, preferred);
                    return true;
                }
            }

            return false;
        }, false);
    }

    private static void sendInstaBreakAttempt(Minecraft mc, BlockPos pos, Direction direction) throws InterruptedException {
        runOnClientThread(mc, () -> {
            if (mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction));
        });
    }

    private static void primeInstaBreakTarget(Minecraft mc, BlockPos pos, Direction direction) throws InterruptedException {
        runOnClientThread(mc, () -> {
            if (mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;
            mc.gameMode.startDestroyBlock(pos, direction);
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction));
        });
    }

    private static void runOnClientThread(Minecraft mc, Runnable runnable) throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(2000, TimeUnit.MILLISECONDS);
    }

    private static <T> T callOnClientThread(Minecraft mc, java.util.function.Supplier<T> supplier, T fallback) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>(fallback);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                result.set(supplier.get());
            } finally {
                latch.countDown();
            }
        });
        latch.await(2000, TimeUnit.MILLISECONDS);
        return result.get();
    }

    private static void waitForItemInSpecificHandlerSlot(Minecraft mc, int slotId, ItemTarget target) throws InterruptedException {
        while (isCurrentRunActive()) {
            AtomicBoolean matched = new AtomicBoolean(false);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mc.execute(() -> {
                try {
                    matched.set(handlerSlotMatchesItem(mc, slotId, target));
                } finally {
                    latch.countDown();
                }
            });
            latch.await(200, TimeUnit.MILLISECONDS);
            if (matched.get()) return;
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static void waitForAnyItemInSpecificHandlerSlot(Minecraft mc, int slotId) throws InterruptedException {
        while (isCurrentRunActive()) {
            AtomicBoolean matched = new AtomicBoolean(false);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mc.execute(() -> {
                try {
                    matched.set(handlerSlotHasAnyItem(mc, slotId));
                } finally {
                    latch.countDown();
                }
            });
            latch.await(200, TimeUnit.MILLISECONDS);
            if (matched.get()) return;
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static boolean handlerSlotMatchesItem(Minecraft mc, int slotId, ItemTarget target) {
        if (mc.player == null || mc.player.containerMenu == null) return false;
        int handlerSlot = autismclient.util.PackUtilInventoryHelper.resolveConfiguredHandlerSlot(mc, slotId);
        if (handlerSlot < 0 || handlerSlot >= mc.player.containerMenu.slots.size()) return false;
        var slot = mc.player.containerMenu.slots.get(handlerSlot);
        if (slot == null || slot.getItem().isEmpty()) return false;
        ItemTarget resolvedTarget = target == null ? ItemTarget.slotOnly(slotId) : target.copy();
        if (!resolvedTarget.hasSlot()) resolvedTarget.slot = slotId;
        return resolvedTarget.matches(slot.getItem(), slotId);
    }

    private static boolean handlerSlotHasAnyItem(Minecraft mc, int slotId) {
        if (mc.player == null || mc.player.containerMenu == null) return false;
        int handlerSlot = autismclient.util.PackUtilInventoryHelper.resolveConfiguredHandlerSlot(mc, slotId);
        if (handlerSlot < 0 || handlerSlot >= mc.player.containerMenu.slots.size()) return false;
        var slot = mc.player.containerMenu.slots.get(handlerSlot);
        return slot != null && !slot.getItem().isEmpty();
    }

    private static void waitForItemActionTargets(Minecraft mc, ItemAction ia) throws InterruptedException {
        if (ia == null) return;

        List<ItemTarget> targets = resolveItemTargets(ia.itemTargets, ia.itemNames);
        if (!targets.isEmpty()) {
            for (ItemTarget target : targets) {
                waitForItemActionEntry(mc, target);
            }
            return;
        }

        if (ia.useSlot && ia.targetSlot >= 0) {
            setCurrentStatus("Waiting for slot " + ia.targetSlot);
            waitForAnyItemInSpecificHandlerSlot(mc, ia.targetSlot);
        }
    }

    private static void waitForItemActionEntry(Minecraft mc, ItemTarget target) throws InterruptedException {
        ItemTarget resolvedTarget = target == null ? new ItemTarget() : target.copy();
        String itemLabel = describeItemTarget(resolvedTarget);
        int slot = resolvedTarget.hasSlot() ? resolvedTarget.slot : -1;

        if (slot >= 0 && resolvedTarget.hasIdentity()) {
            setCurrentStatus("Waiting for " + itemLabel);
            waitForItemInSpecificHandlerSlot(mc, slot, resolvedTarget);
            return;
        }

        if (slot >= 0) {
            setCurrentStatus("Waiting for slot " + slot);
            waitForAnyItemInSpecificHandlerSlot(mc, slot);
            return;
        }

        if (resolvedTarget.hasIdentity()) {
            setCurrentStatus("Waiting for item in GUI: " + itemLabel);
            CompletableFuture<Void> waitFuture = MacroConditionRegistry.waitForItemInHandler(resolvedTarget);
            waitForCondition(waitFuture);
        }
    }

    private static ItemTarget resolveItemTarget(ItemTarget target, String legacyEntry) {
        if (target != null && (target.hasSlot() || target.hasIdentity())) return target.copy();
        return ItemTarget.fromLegacyEntry(legacyEntry);
    }

    private static List<ItemTarget> resolveItemTargets(List<ItemTarget> targets, List<String> legacyEntries) {
        List<ItemTarget> resolved = new ArrayList<>();
        if (targets != null) {
            for (ItemTarget target : targets) {
                if (target == null || (!target.hasSlot() && !target.hasIdentity())) continue;
                resolved.add(target.copy());
            }
        }
        if (!resolved.isEmpty()) return resolved;
        if (legacyEntries == null) return resolved;
        for (String entry : legacyEntries) {
            ItemTarget parsed = ItemTarget.fromLegacyEntry(entry);
            if (parsed.hasSlot() || parsed.hasIdentity()) resolved.add(parsed);
        }
        return resolved;
    }

    private static String describeItemTarget(ItemTarget target) {
        if (target == null) return "";
        String summary = target.summaryText();
        if (summary != null && !summary.isBlank()) return summary;
        String legacy = target.toLegacyEntry();
        return legacy == null ? "" : legacy;
    }

    private static void runCraftAction(Minecraft mc, CraftAction craftAction) throws InterruptedException {
        if (craftAction == null || !craftAction.hasEntries()) {
            setCurrentStatus("Crafting");
            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cNo craft recipes selected.");
            return;
        }

        for (int index = 0; index < craftAction.entries.size(); index++) {
            CraftAction.CraftEntry entry = craftAction.entries.get(index);
            if (entry == null || !entry.hasRecipe()) continue;

            setCurrentStatus(entry.useMaxAmount
                ? "Crafting " + entry.resultName + " [Max] (" + (index + 1) + "/" + craftAction.entries.size() + ")"
                : "Crafting " + entry.resultName + " x" + Math.max(1, entry.amount) + " (" + (index + 1) + "/" + craftAction.entries.size() + ")");

            PackUtilCraftingHelper.CraftableRecipeOption option = PackUtilCraftingHelper.findCraftableRecipe(mc, entry.recipeKey);
            if (option == null) option = PackUtilCraftingHelper.findCraftableRecipe(mc, entry.recipeId);
            if (option == null) {
                setCurrentStatus("Recipe not found");
                PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cRecipe not found: " + (entry.resultName == null || entry.resultName.isBlank() ? "unknown" : entry.resultName));
                return;
            }

            int desiredAmount = PackUtilCraftingHelper.getEffectiveRequestedOutput(option, entry.amount, entry.useMaxAmount);
            if (desiredAmount <= 0) {
                setCurrentStatus("No space or materials");
                PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cNo space or materials for " + entry.resultName + ".");
                return;
            }

            PackUtilCraftingHelper.CraftExecutionResult result = PackUtilCraftingHelper.executeCraftImmediately(
                mc,
                entry.recipeKey,
                entry.recipeId,
                desiredAmount
            );

            setCurrentStatus(result.message);
            if (!result.success) {
                PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§c" + result.message);
                return;
            }
            if (isCurrentRunActive()) {
                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                waitForCondition(tickFuture);
            }
        }
    }

    private static void runGoToAction(Minecraft mc, GoToAction goToAction) throws InterruptedException {
        setCurrentStatus(String.format("Going to %.0f,%.0f,%.0f", goToAction.x, goToAction.y, goToAction.z));

        if (!PackUtilCompatManager.isBaritoneAvailable()) {
            PackUtilClientMessaging.sendPrefixed("Baritone not installed; skipping goto action.");
            return;
        }

        int goalX = (int) Math.floor(goToAction.x);
        int goalY = (int) Math.floor(goToAction.y);
        int goalZ = (int) Math.floor(goToAction.z);

        if (!runBaritoneStart(mc, () -> PackUtilCompatManager.startBaritoneGoTo(mc, goalX, goalY, goalZ))) {
            PackUtilClientMessaging.sendPrefixed("Failed to start Baritone goto.");
            return;
        }

        if (!goToAction.waitForArrival) {
            setCurrentStatus("Goto started");
            return;
        }

        long startMs = System.currentTimeMillis();
        boolean sawBaritoneActivity = false;

        while (isCurrentRunActive()) {
            boolean goalActive = PackUtilCompatManager.isBaritoneGoalActive();
            boolean baritoneBusy = PackUtilCompatManager.isBaritoneBusy();
            if (goalActive || baritoneBusy) sawBaritoneActivity = true;

            if (sawBaritoneActivity && !goalActive && !baritoneBusy) {
                setCurrentStatus("Goto finished");
                return;
            }

            if (!sawBaritoneActivity && System.currentTimeMillis() - startMs >= 1500 && !goalActive && !baritoneBusy) {
                setCurrentStatus("Goto finished");
                return;
            }

            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static void runMineAction(Minecraft mc, MineAction mineAction) throws InterruptedException {
        String mineDesc = mineAction.targetBlocks.isEmpty() ? "none" : mineAction.targetBlocks.get(0);
        setCurrentStatus("Mining: " + mineDesc);

        if (mineAction.targetBlocks.isEmpty()) return;
        if (!PackUtilCompatManager.isBaritoneAvailable()) {
            PackUtilClientMessaging.sendPrefixed("Baritone not installed; skipping mine action.");
            return;
        }

        java.util.List<String> blockNames = new java.util.ArrayList<>();
        for (String id : mineAction.targetBlocks) {
            if (id == null || id.isBlank()) continue;
            blockNames.add(id.startsWith("minecraft:") ? id.substring(10) : id);
        }
        if (blockNames.isEmpty()) return;

        final int startItemCount = mineAction.stopMinedCount ? countMineTargetItems(mc, mineAction) : 0;
        if (!runBaritoneStart(mc, () -> PackUtilCompatManager.startBaritoneMine(mc, blockNames))) {
            PackUtilClientMessaging.sendPrefixed("Failed to start Baritone mine.");
            return;
        }

        long mineStartMs = System.currentTimeMillis();
        boolean sawBaritoneActivity = false;

        while (isCurrentRunActive()) {
            boolean mineActive = PackUtilCompatManager.isBaritoneMineActive();
            boolean baritoneBusy = PackUtilCompatManager.isBaritoneBusy();
            if (mineActive || baritoneBusy) sawBaritoneActivity = true;

            String stopReason = null;
            int minedDelta = 0;

            if (mineAction.stopMinedCount) {
                minedDelta = Math.max(0, countMineTargetItems(mc, mineAction) - startItemCount);
                setCurrentStatus("Mining: " + mineDesc + " (" + minedDelta + "/" + mineAction.minedCountTarget + ")");
                if (minedDelta >= mineAction.minedCountTarget) {
                    stopReason = "Mine target reached";
                }
            }

            if (stopReason == null && mineAction.stopInventoryFull && isInventoryFull(mc)) {
                stopReason = "Mine stop: inventory full";
            }

            if (stopReason == null && mineAction.stopSlotsUsed) {
                int used = countUsedMainInventorySlots(mc);
                if (used >= mineAction.slotsUsedThreshold) {
                    stopReason = "Mine stop: slots used";
                }
            }

            if (stopReason == null && mineAction.stopAfterTime
                && System.currentTimeMillis() - mineStartMs >= mineAction.timeoutSeconds * 1000L) {
                stopReason = "Mine timeout";
            }

            if (stopReason != null) {
                setCurrentStatus(stopReason);
                requestBaritoneStop(mc);
                waitForBaritoneIdle();
                return;
            }

            if (sawBaritoneActivity && !mineActive && !baritoneBusy) {
                setCurrentStatus("Mine finished");
                return;
            }

            if (!sawBaritoneActivity && System.currentTimeMillis() - mineStartMs >= 1500 && !mineActive && !baritoneBusy) {
                setCurrentStatus("Mine finished");
                return;
            }

            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static boolean runBaritoneStart(Minecraft mc, java.util.function.BooleanSupplier starter) throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean(false);

        mc.execute(() -> {
            try {
                started.set(starter.getAsBoolean());
            } finally {
                latch.countDown();
            }
        });

        latch.await(2, TimeUnit.SECONDS);
        return started.get();
    }

    private static void requestBaritoneStop(Minecraft mc) {
        mc.execute(() -> PackUtilCompatManager.stopBaritone(mc));
    }

    private static void waitForBaritoneIdle() {
        long waitStartMs = System.currentTimeMillis();
        while (isCurrentRunActive() && PackUtilCompatManager.isBaritoneBusy() && System.currentTimeMillis() - waitStartMs < 3000L) {
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return false;

        for (int slot = 9; slot < 36; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    private static int countUsedMainInventorySlots(Minecraft mc) {
        if (mc.player == null) return 0;

        int used = 0;
        for (int slot = 9; slot < 36; slot++) {
            if (!mc.player.getInventory().getItem(slot).isEmpty()) used++;
        }
        return used;
    }

    private static int countMineTargetItems(Minecraft mc, MineAction mineAction) {
        if (mc.player == null) return 0;

        java.util.Set<net.minecraft.world.item.Item> targetItems = new java.util.LinkedHashSet<>();
        for (String rawId : mineAction.targetBlocks) {
            if (rawId == null || rawId.isBlank()) continue;
            try {
                net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(rawId);
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                    targetItems.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id));
                }
                if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) {
                    net.minecraft.world.item.Item blockItem = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id).asItem();
                    if (blockItem != net.minecraft.world.item.Items.AIR) targetItems.add(blockItem);
                }
            } catch (Exception ignored) {
            }
        }

        if (targetItems.isEmpty()) return 0;

        int total = 0;
        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && targetItems.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void runPayAction(Minecraft mc, PayAction payAction) throws InterruptedException {
        payAction.delayMs = PayAction.normalizeDelay(payAction.delayMs);
        java.util.List<String> targets = new java.util.ArrayList<>();
        for (String player : payAction.players) {
            if (player == null) continue;
            String trimmed = player.trim();
            if (!trimmed.isEmpty() && !targets.contains(trimmed)) targets.add(trimmed);
        }
        if (targets.isEmpty() || mc.getConnection() == null) return;

        long resolvedAmount = Math.max(0L, payAction.resolvedAmount());
        String amountValue = String.valueOf(resolvedAmount);
        String amountLabel = PayAction.formatAmount(resolvedAmount);
        int sent = 0;

        for (String player : targets) {
            if (!isCurrentRunActive()) break;

            String template = (payAction.commandTemplate == null || payAction.commandTemplate.isBlank())
                ? "/pay <player> <amount>"
                : payAction.commandTemplate;
            String command = template
                .replace("<player>", player)
                .replace("{player}", player)
                .replace("<amount>", amountValue)
                .replace("{amount}", amountValue)
                .trim();
            if (command.isEmpty()) continue;

            setCurrentStatus("Paying " + player + " " + amountLabel + " (" + (sent + 1) + "/" + targets.size() + ")");

            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mc.execute(() -> {
                try {
                    if (mc.getConnection() == null) return;
                    if (command.startsWith("/") && command.length() > 1) {
                        mc.getConnection().sendCommand(command.substring(1));
                    } else {
                        mc.getConnection().sendChat(command);
                    }
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }

            sent++;
            if (sent < targets.size() && payAction.delayEnabled && payAction.delayMs > 0) Thread.sleep(payAction.delayMs);
        }
    }

    public static void stop() {
        String macroName = (currentMacro != null && currentMacro.name != null) ? currentMacro.name : "";
        for (RunState run : activeRunSnapshot()) {
            requestStop(run, true);
        }
        synchronized (RUN_LOCK) {
            ACTIVE_RUNS.clear();
            primaryRun = null;
        }
        isRunning.set(false);
        persistentActions.clear();
        releaseBackgroundMoves(backgroundMoves, Minecraft.getInstance());
        backgroundMoves.clear();
        MacroConditionRegistry.cancelAll();
        isWaitingForChat.set(false);
        isWaitingForPacket.set(false);
        synchronized (packetWaitLock) {
            waitingPacketName.set("");
            CompletableFuture<Void> pf = activePacketFuture;
            if (pf != null) { pf.cancel(true); activePacketFuture = null; }
        }
        CompletableFuture<Void> cf = activeChatFuture;
        if (cf != null) { cf.cancel(true); activeChatFuture = null; }
        CompletableFuture<Void> bf = activeBaritoneGoalFuture;
        if (bf != null) { bf.cancel(true); activeBaritoneGoalFuture = null; }
        PackUtilCompatManager.stopBaritone(Minecraft.getInstance());
        PackUtilInstaBreakRenderer.clear();

        try {
            autismclient.util.PackUtilLANSync sync = autismclient.util.PackUtilLANSync.getInstance();
            if (sync.isInSession()) {
                sync.broadcastStepProgress(-1, 0, "");
            }
        } catch (Exception ignored) {}

        macroThread = null;
        currentMacro = null;
        setCurrentStatus("");
        isRotating.set(false);
        rotationAlignedFrames = 0;
        if (!macroName.isEmpty()) {
            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cMacro stopped: " + macroName);
        } else {
            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cMacro stopped.");
        }
    }

    public static boolean isRunning() {
        boolean running = hasRunningRuns();
        isRunning.set(running);
        return running;
    }

    public static boolean isMacroRunning(String macroName) {
        if (macroName == null) return false;
        String targetKey = macroKey(macroName);
        synchronized (RUN_LOCK) {
            for (RunState run : ACTIVE_RUNS.values()) {
                if (run.running.get() && macroKey(macroName(run.macro)).equals(targetKey)) return true;
            }
        }
        return false;
    }

    public static boolean isMacroRunning(PackUtilMacro macro) {
        if (macro == null) return false;
        synchronized (RUN_LOCK) {
            for (RunState run : ACTIVE_RUNS.values()) {
                if (matchesMacro(run, macro, macro.name)) return true;
            }
        }
        return false;
    }

    public static List<MacroRunSnapshot> getActiveRunSnapshots() {
        synchronized (RUN_LOCK) {
            ArrayList<MacroRunSnapshot> snapshots = new ArrayList<>(ACTIVE_RUNS.size());
            for (RunState run : ACTIVE_RUNS.values()) {
                if (!run.running.get()) continue;
                PackUtilMacro macro = run.macro;
                String name = macro != null && macro.name != null ? macro.name : "";
                snapshots.add(new MacroRunSnapshot(
                    macro,
                    name,
                    run.status == null ? "" : run.status,
                    run.currentStepIndex,
                    run.totalSteps,
                    run.lastCompletedStep
                ));
            }
            return snapshots;
        }
    }

    public static void stopMacro(String macroName) {
        if (macroName == null || macroName.isBlank()) return;
        String targetKey = macroKey(macroName);
        boolean stopped = false;
        for (RunState run : activeRunSnapshot()) {
            if (run.running.get() && macroKey(macroName(run.macro)).equals(targetKey)) {
                requestStop(run, true);
                stopped = true;
            }
        }
        if (stopped) {
            PackUtilInstaBreakRenderer.clear();
            synchronized (RUN_LOCK) {
                primaryRun = ACTIVE_RUNS.values().stream().filter(run -> run.running.get()).findFirst().orElse(null);
                currentMacro = primaryRun != null ? primaryRun.macro : null;
                currentStatus = primaryRun != null ? primaryRun.status : "";
                currentStepIndex = primaryRun != null ? primaryRun.currentStepIndex : -1;
                totalSteps = primaryRun != null ? primaryRun.totalSteps : 0;
                isRunning.set(hasRunningRunsLocked());
            }
            PackUtilClientMessaging.sendPrefixed("Â§cMacro stopped: " + macroName);
        }
    }

    public static void stopMacro(PackUtilMacro macro) {
        if (macro == null) return;
        boolean stopped = false;
        String displayName = macroName(macro);
        for (RunState run : activeRunSnapshot()) {
            if (matchesMacro(run, macro, displayName)) {
                requestStop(run, true);
                stopped = true;
            }
        }
        if (stopped) {
            PackUtilInstaBreakRenderer.clear();
            synchronized (RUN_LOCK) {
                primaryRun = ACTIVE_RUNS.values().stream().filter(run -> run.running.get()).findFirst().orElse(null);
                currentMacro = primaryRun != null ? primaryRun.macro : null;
                currentStatus = primaryRun != null ? primaryRun.status : "";
                currentStepIndex = primaryRun != null ? primaryRun.currentStepIndex : -1;
                totalSteps = primaryRun != null ? primaryRun.totalSteps : 0;
                isRunning.set(hasRunningRunsLocked());
            }
            PackUtilClientMessaging.sendPrefixed("Â§cMacro stopped: " + (displayName.isBlank() ? "macro" : displayName));
        }
    }

    public static String getCurrentMacroName() {
        PackUtilMacro m = currentMacro;
        return (m != null && m.name != null) ? m.name : "";
    }

    public static PackUtilMacro getCurrentMacro() {
        return currentMacro;
    }

    public static String getCurrentStatus() {
        RunState run = primaryRun;
        return run != null ? run.status : currentStatus;
    }

    public static int getCurrentStepIndex() {
        RunState run = primaryRun;
        return run != null ? run.currentStepIndex : currentStepIndex;
    }

    public static int getTotalSteps() {
        RunState run = primaryRun;
        return run != null ? run.totalSteps : totalSteps;
    }

    public static List<RecentChatMessage> getRecentChatMessages() {
        synchronized (recentChatLock) {
            return new ArrayList<>(recentChatMessages);
        }
    }

    public static void onPacketSent(Packet<?> packet) {
        onPacketObserved(packet, "C2S");
    }

    public static void onPacketReceived(Packet<?> packet) {
        onPacketObserved(packet, "S2C");
    }

    public static void onPacketObserved(Packet<?> packet, String direction) {
        syncRecentChatServerContext();
        if ("S2C".equals(direction)) {
            ChatCapture capture = extractIncomingChat(packet);
            if (capture != null && capture.message() != null && !capture.message().isBlank()) {
                recordRecentChat(capture);
                for (RunState run : activeRunSnapshot()) {
                    if (run.running.get() && run.waitingForChat.get() && matchesChatAction(run.waitingChatAction, capture)) {
                        CompletableFuture<Void> f = run.chatFuture;
                        if (f != null) f.complete(null);
                    }
                }
                if (isCurrentRunActive() && isWaitingForChat.get() && matchesWaitingChat(capture)) {
                    lastMatchedChat.set(capture.displayText());
                    CompletableFuture<Void> f = activeChatFuture;
                    if (f != null) f.complete(null);
                }
            }
        }

        if (!isCurrentRunActive()) return;

        for (RunState run : activeRunSnapshot()) {
            if (!run.running.get() || !run.waitingForPacket.get()) continue;
            CompletableFuture<Void> f = null;
            synchronized (run.packetWaitLock) {
                if (run.waitingForPacket.get() && matchesPacketTarget(run.waitingPacketName.get(), packet, direction)) {
                    f = run.packetFuture;
                }
            }
            if (f != null) f.complete(null);
        }

        if (isWaitingForPacket.get()) {
            CompletableFuture<Void> f = null;
            synchronized (packetWaitLock) {
                if (!isWaitingForPacket.get()) return;

                String target = waitingPacketName.get();
                if (matchesPacketTarget(target, packet, direction)) {
                    lastReceivedPacket.set(direction + " " + PackUtilPacketNamer.getFriendlyName(packet, direction));
                    f = activePacketFuture;
                }
            }
            if (f != null) f.complete(null);
        }
    }

    private static void recordRecentChat(ChatCapture capture) {
        RecentChatMessage message = new RecentChatMessage(
            capture.sender() == null ? "" : capture.sender(),
            capture.message(),
            capture.displayText(),
            capture.displayComponent(),
            capture.source(),
            System.currentTimeMillis()
        );
        synchronized (recentChatLock) {
            recentChatMessages.removeIf(existing -> existing.displayText().equals(message.displayText()));
            recentChatMessages.addFirst(message);
            while (recentChatMessages.size() > MAX_RECENT_CHAT_MESSAGES) {
                recentChatMessages.removeLast();
            }
        }
    }

    private static void syncRecentChatServerContext() {
        String currentServerKey = getCurrentMultiplayerServerKey();
        if (currentServerKey.isEmpty()) return;
        synchronized (recentChatLock) {
            if (recentChatServerKey.isEmpty()) {
                recentChatServerKey = currentServerKey;
                return;
            }
            if (!recentChatServerKey.equals(currentServerKey)) {
                recentChatMessages.clear();
                recentChatServerKey = currentServerKey;
            }
        }
    }

    private static String getCurrentMultiplayerServerKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isLocalServer()) return "";

        ServerData entry = mc.getCurrentServer();
        if (entry != null && entry.ip != null && !entry.ip.isBlank()) {
            return normalizeServerKey(entry.ip);
        }

        if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
            SocketAddress address = mc.getConnection().getConnection().getRemoteAddress();
            if (address instanceof InetSocketAddress inet) {
                String host = inet.getHostString();
                if ((host == null || host.isBlank()) && inet.getAddress() != null) {
                    host = inet.getAddress().getHostAddress();
                }
                if (host != null && !host.isBlank()) {
                    return normalizeServerKey(host + ":" + inet.getPort());
                }
            } else if (address != null) {
                String raw = address.toString();
                if (raw != null && !raw.isBlank()) {
                    return normalizeServerKey(raw);
                }
            }
        }

        return "";
    }

    private static String normalizeServerKey(String address) {
        if (address == null) return "";
        String trimmed = address.replaceFirst("^/", "").trim().toLowerCase(Locale.ROOT);
        return trimmed;
    }

    private static boolean matchesWaitingChat(ChatCapture capture) {
        String patternJson = waitingChatPatternJson.get();
        if (!waitingChatIsRegex.get() && patternJson != null && !patternJson.isBlank() && capture.displayComponent() != null) {
            String captureJson = serializeTextComponent(capture.displayComponent());
            if (patternJson.equals(captureJson)) return true;
        }
        String pattern = waitingChatPattern.get();
        if (pattern == null || pattern.isBlank()) return true;
        if (waitingChatIsRegex.get()) {
            try {
                return capture.message().matches(pattern) || capture.displayText().matches(pattern);
            } catch (Exception ignored) {
                return false;
            }
        }
        int threshold = waitingChatFuzzyPercent.get();
        return fuzzyChatMatch(pattern, capture.message(), threshold)
            || fuzzyChatMatch(pattern, capture.displayText(), threshold);
    }

    private static boolean matchesChatAction(WaitForChatAction action, ChatCapture capture) {
        if (action == null || capture == null) return false;
        if (!action.useRegex && action.patternJson != null && !action.patternJson.isBlank() && capture.displayComponent() != null) {
            String captureJson = serializeTextComponent(capture.displayComponent());
            if (action.patternJson.equals(captureJson)) return true;
        }
        String pattern = action.pattern;
        if (pattern == null || pattern.isBlank()) return true;
        if (action.useRegex) {
            try {
                return capture.message().matches(pattern) || capture.displayText().matches(pattern);
            } catch (Exception ignored) {
                return false;
            }
        }
        int threshold = WaitForChatAction.clampFuzzyPercent(action.fuzzyPercent);
        return fuzzyChatMatch(pattern, capture.message(), threshold)
            || fuzzyChatMatch(pattern, capture.displayText(), threshold);
    }

    private static ChatCapture extractIncomingChat(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket gameMessagePacket) {
            String raw = gameMessagePacket.content().getString();
            if (raw == null || raw.isBlank()) return null;
            return buildChatCapture("", raw, raw, gameMessagePacket.content(), ChatSource.PLAYER);
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket profilelessChatMessagePacket) {
            Object message = invokeFirstNoArg(profilelessChatMessagePacket, "message", "getMessage");
            Object chatType = invokeFirstNoArg(profilelessChatMessagePacket, "chatType", "getChatType");
            String text = extractTextValue(message);
            if (text == null || text.isBlank()) return null;
            String sender = extractChatName(chatType);
            return buildChatCapture(sender, text, null, extractTextComponent(message), ChatSource.PLAYER);
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket chatMessagePacket) {
            Object body = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "body", "getBody"),
                getRecordComponentValue(chatMessagePacket, 4),
                findRecordComponentByMethodNames(chatMessagePacket, 2, "content", "timestamp", "salt")
            );
            Object serializedParameters = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "serializedParameters", "getSerializedParameters"),
                getRecordComponentValue(chatMessagePacket, 7),
                findRecordComponentByMethodNames(chatMessagePacket, 2, "name", "targetName", "type")
            );
            Object senderValue = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "sender", "getSender"),
                getRecordComponentValue(chatMessagePacket, 1),
                findRecordComponentByValueType(chatMessagePacket, UUID.class)
            );
            Object unsignedContent = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "unsignedContent", "getUnsignedContent"),
                getRecordComponentValue(chatMessagePacket, 5),
                findRecordTextComponent(chatMessagePacket, body, serializedParameters, senderValue)
            );

            String message = extractTextValue(unsignedContent);
            Component messageComponent = extractTextComponent(unsignedContent);
            if ((message == null || message.isBlank()) && body != null) {
                Object bodyContent = firstNonNull(
                    invokeFirstNoArg(body, "content", "getContent"),
                    getRecordComponentValue(body, 0),
                    findRecordTextComponent(body)
                );
                message = extractTextValue(bodyContent);
                if (messageComponent == null) messageComponent = extractTextComponent(bodyContent);
            }
            if (message == null || message.isBlank()) return null;

            String sender = senderValue instanceof UUID uuid ? resolvePlayerName(uuid) : null;
            if (sender == null || sender.isBlank()) sender = extractChatName(serializedParameters);
            return buildChatCapture(sender, message, null, messageComponent, ChatSource.PLAYER);
        }

        return null;
    }

    private static ChatCapture buildChatCapture(String sender, String message, String displayText, Component displayComponent, ChatSource source) {
        String cleanMessage = sanitizeChatText(message);
        if (cleanMessage.isBlank()) return null;
        String cleanSender = sanitizeChatText(sender);
        String cleanDisplay = sanitizeChatText(displayText);
        Component rendered = literalizeTextComponent(displayComponent);
        String display = cleanDisplay;
        if (rendered == null || rendered.getString().isBlank()) {
            rendered = cleanSender.isBlank()
                    ? Component.literal(cleanMessage)
                    : Component.literal("<" + cleanSender + "> ").append(Component.literal(cleanMessage));
        }
        if (display == null || display.isBlank()) {
            display = sanitizeChatText(rendered.getString());
        }
        ChatSource resolvedSource = source != null ? source : ChatSource.PLAYER;
        return new ChatCapture(cleanSender, cleanMessage, sanitizeChatText(display), rendered, resolvedSource);
    }

    private static String sanitizeChatText(String text) {
        if (text == null) return "";
        String stripped = stripLegacyFormatting(text).replace('\n', ' ').replace('\r', ' ');
        StringBuilder out = new StringBuilder(stripped.length());
        for (int offset = 0; offset < stripped.length();) {
            int cp = stripped.codePointAt(offset);
            offset += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT
                    || type == Character.CONTROL
                    || cp == 0x200B
                    || cp == 0x200C
                    || cp == 0x200D
                    || cp == 0x2060
                    || cp == 0xFE0E
                    || cp == 0xFE0F) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().trim();
    }

    private static String stripLegacyFormatting(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch == '\u00A7' || ch == '&') && i + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(i + 1));
                if ((next >= '0' && next <= '9')
                        || (next >= 'a' && next <= 'f')
                        || (next >= 'k' && next <= 'o')
                        || next == 'r'
                        || next == 'x') {
                    i++;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static boolean isLikelyPlayerSender(String sender) {
        String cleanSender = sanitizeChatText(sender);
        if (cleanSender.isBlank()) return false;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                for (var entry : mc.getConnection().getListedOnlinePlayers()) {
                    String playerName = entry != null && entry.getProfile() != null ? extractTextValue(entry.getProfile()) : null;
                    if (playerName != null && cleanSender.equalsIgnoreCase(playerName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                for (var player : mc.level.players()) {
                    String playerName = player != null && player.getGameProfile() != null ? extractTextValue(player.getGameProfile()) : null;
                    if (playerName != null && cleanSender.equalsIgnoreCase(playerName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean fuzzyChatMatch(String pattern, String candidate, int thresholdPercent) {
        String normalizedPattern = normalizeChatText(pattern);
        String normalizedCandidate = normalizeChatText(candidate);
        if (normalizedPattern.isBlank()) return true;
        if (normalizedCandidate.isBlank()) return false;
        if (normalizedCandidate.contains(normalizedPattern)) return true;

        List<String> patternTokens = tokenizeChat(normalizedPattern);
        List<String> candidateTokens = tokenizeChat(normalizedCandidate);
        if (patternTokens.isEmpty()) return true;

        if (thresholdPercent >= 100) {
            return allPatternTokensPresent(patternTokens, normalizedCandidate);
        }

        double tokenScore = computeTokenCoverage(patternTokens, candidateTokens);
        double charScore = similarityRatio(normalizedPattern.replace(" ", ""), normalizedCandidate.replace(" ", ""));
        double score = Math.max(tokenScore, tokenScore * 0.7 + charScore * 0.3);
        return score + 1.0e-9 >= (Math.max(40, Math.min(100, thresholdPercent)) / 100.0);
    }

    private static String normalizeChatForCompare(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) out.append(ch);
            else if (Character.isWhitespace(ch)) out.append(' ');
            else if (ch == '\'' || ch == '"' || ch == '`' || ch == '-' || ch == '_' || ch == '\u2019') {

            } else {
                out.append(' ');
            }
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    public static String normalizeChatText(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean lastWasSpace = true;
        for (int offset = 0; offset < normalized.length();) {
            int cp = normalized.codePointAt(offset);
            offset += Character.charCount(cp);

            cp = foldStyledLatinCodePoint(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.FORMAT
                    || type == Character.CONTROL
                    || type == Character.PRIVATE_USE
                    || type == Character.SURROGATE) {
                continue;
            }

            if (Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(cp);
                lastWasSpace = false;
            } else if (Character.isWhitespace(cp)) {
                if (!lastWasSpace) out.append(' ');
                lastWasSpace = true;
            } else if (isJoinerPunctuation(cp)) {

            } else {
                if (!lastWasSpace) out.append(' ');
                lastWasSpace = true;
            }
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == ' ') out.setLength(len - 1);
        return out.toString();
    }

    private static boolean isJoinerPunctuation(int cp) {
        return switch (cp) {
            case '\'', '"', '`', '-', '_', '\u2019', '\u2018', '\u201B', '\u2032', '\u02BC', '\uFF07',
                 '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> true;
            default -> false;
        };
    }

    private static int foldStyledLatinCodePoint(int cp) {
        return switch (cp) {
            case '\u1D00' -> 'a';
            case '\u0299' -> 'b';
            case '\u1D04' -> 'c';
            case '\u1D05' -> 'd';
            case '\u1D07' -> 'e';
            case '\uA730' -> 'f';
            case '\u0262' -> 'g';
            case '\u029C' -> 'h';
            case '\u026A' -> 'i';
            case '\u1D0A' -> 'j';
            case '\u1D0B' -> 'k';
            case '\u029F' -> 'l';
            case '\u1D0D' -> 'm';
            case '\u0274' -> 'n';
            case '\u1D0F' -> 'o';
            case '\u1D18' -> 'p';
            case '\u01EB' -> 'q';
            case '\u0280' -> 'r';
            case '\uA731' -> 's';
            case '\u1D1B' -> 't';
            case '\u1D1C' -> 'u';
            case '\u1D20' -> 'v';
            case '\u1D21' -> 'w';
            case '\u02E3' -> 'x';
            case '\u028F' -> 'y';
            case '\u1D22' -> 'z';
            case '\u00DF' -> 's';
            case '\u00E6' -> 'a';
            case '\u0153' -> 'o';
            default -> cp;
        };
    }

    private static List<String> tokenizeChat(String normalized) {
        if (normalized == null || normalized.isBlank()) return List.of();
        return List.of(normalized.split(" "));
    }

    private static boolean allPatternTokensPresent(List<String> patternTokens, String normalizedCandidate) {
        for (String token : patternTokens) {
            if (!normalizedCandidate.contains(token)) return false;
        }
        return true;
    }

    private static double computeTokenCoverage(List<String> patternTokens, List<String> candidateTokens) {
        if (patternTokens.isEmpty()) return 1.0;
        if (candidateTokens.isEmpty()) return 0.0;
        double total = 0.0;
        for (String patternToken : patternTokens) {
            double best = 0.0;
            for (String candidateToken : candidateTokens) {
                best = Math.max(best, tokenSimilarity(patternToken, candidateToken));
                if (best >= 1.0) break;
            }
            total += best;
        }
        return total / patternTokens.size();
    }

    private static double tokenSimilarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        if (left.isBlank() || right.isBlank()) return 0.0;
        if (left.contains(right) || right.contains(left)) {
            return (double) Math.min(left.length(), right.length()) / Math.max(left.length(), right.length());
        }
        return similarityRatio(left, right);
    }

    private static double similarityRatio(String left, String right) {
        if (left.equals(right)) return 1.0;
        int maxLen = Math.max(left.length(), right.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(left, right);
        return Math.max(0.0, 1.0 - (distance / (double) maxLen));
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] temp = previous;
            previous = current;
            current = temp;
        }
        return previous[right.length()];
    }

    private static String extractChatName(Object value) {
        Object direct = firstNonNull(
            invokeFirstNoArg(value, "name", "getName", "targetName", "getTargetName"),
            getRecordComponentValue(value, 0),
            findRecordComponentByMethodNames(value, 2, "name", "targetName", "type")
        );
        return sanitizeChatText(extractTextValue(direct));
    }

    private static String resolvePlayerName(UUID uuid) {
        if (uuid == null) return "";
        try {
            if (Minecraft.getInstance().getConnection() != null) {
                var entry = Minecraft.getInstance().getConnection().getPlayerInfo(uuid);
                if (entry != null && entry.getProfile() != null) {
                    String profileName = extractTextValue(entry.getProfile());
                    if (profileName != null && !profileName.isBlank()) return profileName;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Minecraft.getInstance().level != null) {
                for (var player : Minecraft.getInstance().level.players()) {
                    if (uuid.equals(player.getUUID()) && player.getGameProfile() != null) {
                        String profileName = extractTextValue(player.getGameProfile());
                        if (profileName != null && !profileName.isBlank()) return profileName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static Object invokeFirstNoArg(Object target, String... names) {
        if (target == null || names == null) return null;
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object getRecordComponentValue(Object target, int index) {
        if (target == null) return null;
        try {
            RecordComponent[] components = target.getClass().getRecordComponents();
            if (components == null || index < 0 || index >= components.length) return null;
            Method accessor = components[index].getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findRecordComponentByValueType(Object target, Class<?> type) {
        if (target == null || type == null) return null;
        try {
            RecordComponent[] components = target.getClass().getRecordComponents();
            if (components == null) return null;
            for (RecordComponent component : components) {
                if (type.isAssignableFrom(component.getType())) {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return accessor.invoke(target);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findRecordComponentByMethodNames(Object target, int fallbackIndex, String... names) {
        Object direct = invokeFirstNoArg(target, names);
        return direct != null ? direct : getRecordComponentValue(target, fallbackIndex);
    }

    private static Object findRecordTextComponent(Object target, Object... excludedValues) {
        if (target == null) return null;
        try {
            RecordComponent[] components = target.getClass().getRecordComponents();
            if (components == null) return null;
            outer:
            for (RecordComponent component : components) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                Object value = accessor.invoke(target);
                if (value == null) continue;
                for (Object excluded : excludedValues) {
                    if (excluded == value) continue outer;
                }
                if (value instanceof Component || value instanceof CharSequence || value instanceof Optional<?>) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Component extractTextComponent(Object value) {
        if (value == null) return null;
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? extractTextComponent(optional.get()) : null;
        }
        if (value instanceof Component text) return literalizeTextComponent(text);
        if (value instanceof CharSequence chars) return Component.literal(chars.toString());
        if (value instanceof UUID uuid) return Component.literal(resolvePlayerName(uuid));
        try {
            Method getComponent = value.getClass().getMethod("getText");
            getComponent.setAccessible(true);
            Object result = getComponent.invoke(value);
            Component text = extractTextComponent(result);
            if (text != null) return text;
        } catch (Throwable ignored) {
        }
        try {
            Method getString = value.getClass().getMethod("getString");
            getString.setAccessible(true);
            Object result = getString.invoke(value);
            if (result != null) return Component.literal(String.valueOf(result));
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Component literalizeTextComponent(Component text) {
        if (text == null) return null;
        MutableComponent literalized = Component.empty();
        try {
            text.visit((style, string) -> {
                if (string != null && !string.isEmpty()) {
                    literalized.append(Component.literal(string).setStyle(copyVisualStyle(style)));
                }
                return Optional.empty();
            }, Style.EMPTY);
        } catch (Throwable ignored) {
        }
        if (!literalized.getString().isEmpty()) {
            return literalized;
        }
        return Component.literal(text.getString());
    }

    private static Style copyVisualStyle(Style style) {
        if (style == null || style.isEmpty()) return Style.EMPTY;
        Style safe = Style.EMPTY;
        if (style.getFont() != null) safe = safe.withFont(style.getFont());
        if (style.getColor() != null) safe = safe.withColor(style.getColor());
        if (style.isBold()) safe = safe.withBold(true);
        if (style.isItalic()) safe = safe.withItalic(true);
        if (style.isUnderlined()) safe = safe.withUnderlined(true);
        if (style.isStrikethrough()) safe = safe.withStrikethrough(true);
        if (style.isObfuscated()) safe = safe.withObfuscated(true);
        return safe;
    }

    public static String serializeTextComponent(Component text) {
        if (text == null) return "";
        try {
            Component safeComponent = literalizeTextComponent(text);
            return safeComponent == null ? "" : GSON.toJson(ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, safeComponent).getOrThrow());
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static Component deserializeTextComponent(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return literalizeTextComponent(ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow().copy());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractTextValue(Object value) {
        if (value == null) return null;
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? extractTextValue(optional.get()) : null;
        }
        if (value instanceof Component text) return text.getString();
        if (value instanceof CharSequence chars) return chars.toString();
        if (value instanceof UUID uuid) return resolvePlayerName(uuid);
        try {
            Method getString = value.getClass().getMethod("getString");
            getString.setAccessible(true);
            Object result = getString.invoke(value);
            if (result != null) return String.valueOf(result);
        } catch (Throwable ignored) {
        }
        try {
            Method getName = value.getClass().getMethod("getName");
            getName.setAccessible(true);
            Object result = getName.invoke(value);
            if (result instanceof CharSequence chars) return chars.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void onRender(float tickDelta) {
        if (isCurrentRunActive() && isRotating.get()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            float currentYaw = mc.player.getYRot();
            float currentPitch = mc.player.getXRot();

            double deltaYaw = net.minecraft.util.Mth.wrapDegrees(targetYaw - currentYaw);
            double deltaPitch = targetPitch - currentPitch;
            double diagonalDistance = Math.hypot(deltaYaw, deltaPitch);
            if (diagonalDistance <= 0.35) {
                mc.player.setYRot(targetYaw);
                mc.player.setXRot(targetPitch);
                rotationAlignedFrames++;
                if (rotationAlignedFrames >= 2) {
                    isRotating.set(false);
                }
                return;
            }
            rotationAlignedFrames = 0;

            double frameStep = Math.min(diagonalDistance, rotationSpeed * Math.max(tickDelta, 1.0f));
            double stepYaw = (deltaYaw / diagonalDistance) * frameStep;
            double stepPitch = (deltaPitch / diagonalDistance) * frameStep;

            mc.player.setYRot(currentYaw + (float) stepYaw);
            mc.player.setXRot(currentPitch + (float) stepPitch);
        }
    }

    private static void startSmoothRotation(float yaw, float pitch, double speed) {
        targetYaw = yaw;
        targetPitch = pitch;
        rotationSpeed = speed;
        rotationAlignedFrames = 0;
        isRotating.set(true);
    }

    private static void waitForSmoothRotationCompletion() {
        while (isCurrentRunActive() && isRotating.get()) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
    }

    private static LookAtBlockAction.RotationTarget resolveLookAtTargetOnClient(Minecraft mc, LookAtBlockAction action) {
        if (mc == null || action == null) return null;
        java.util.concurrent.atomic.AtomicReference<LookAtBlockAction.RotationTarget> targetRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                targetRef.set(action.resolveRotationTarget(mc));
            } finally {
                latch.countDown();
            }
        });
        while (isCurrentRunActive()) {
            try {
                if (latch.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return targetRef.get();
    }

    public static void stopAll() {
        stop();
    }

    private static void executeBurstPackets(Minecraft mc, java.util.List<SendPacketAction> batch) {
        if (mc.getConnection() == null) {
            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cNo network connection for burst!");
            return;
        }

        java.util.List<autismclient.util.PackUtilSharedState.QueuedPacket> allQueuedPackets = new java.util.ArrayList<>();
        for (SendPacketAction action : batch) {
            allQueuedPackets.addAll(action.getPackets());
        }

        if (allQueuedPackets.isEmpty()) {
            return;
        }

        java.util.List<Packet<?>> regeneratedPackets = new java.util.ArrayList<>(allQueuedPackets.size());
        for (autismclient.util.PackUtilSharedState.QueuedPacket qp : allQueuedPackets) {
            if (qp.packet == null) continue;
            regeneratedPackets.add(qp.packet);
        }

        if (regeneratedPackets.isEmpty()) {
            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§cBurst: All packets failed to regenerate!");
            return;
        }

        net.minecraft.client.multiplayer.ClientPacketListener handler = mc.getConnection();

        for (Packet<?> packet : regeneratedPackets) {
            Packet<?> toSend = packet;

            if (packet instanceof ServerboundContainerClickPacket) {
                toSend = PacketRegenerator.regenerate(packet);
                if (toSend == null) {
                    continue;
                }
            }

            handler.send(toSend);
        }

        if (regeneratedPackets.size() > 1) {
            PackUtilClientMessaging.sendPrefixed("Ãƒâ€šÃ‚Â§aÃƒÂ¢Ã…Â¡Ã‚Â¡ Burst sent " + regeneratedPackets.size() + " packets");
        }
    }

    private static java.util.List<Packet<?>> preGeneratePackets(PackUtilMacro macro, int startIdx, int count) {
        java.util.List<Packet<?>> result = new java.util.ArrayList<>();
        int maxIdx = count < 0 ? macro.actions.size() : Math.min(startIdx + count, macro.actions.size());
        return preGeneratePackets(macro, startIdx, count, maxIdx);
    }

    private static java.util.List<Packet<?>> preGeneratePackets(PackUtilMacro macro, int startIdx, int count, int endIdx) {
        java.util.List<Packet<?>> result = new java.util.ArrayList<>();
        int maxIdx = Math.min(endIdx, count < 0 ? macro.actions.size() : Math.min(startIdx + count, macro.actions.size()));

        Minecraft mc = Minecraft.getInstance();

        for (int j = startIdx; j < maxIdx; j++) {
            MacroAction act = macro.actions.get(j);
            if (!(act instanceof SendPacketAction)) break;

            SendPacketAction spa = (SendPacketAction) act;
            for (autismclient.util.PackUtilSharedState.QueuedPacket qp : spa.getPackets()) {
                if (qp.packet == null) continue;

                result.add(qp.packet);
            }
        }

        return result;
    }

    private static void executePreGeneratedBurst(Minecraft mc, java.util.List<Packet<?>> packets) {
        if (packets.isEmpty() || mc.getConnection() == null) return;

        net.minecraft.client.multiplayer.ClientPacketListener handler = mc.getConnection();

        for (Packet<?> p : packets) {
            Packet<?> toSend = p;

            if (p instanceof ServerboundContainerClickPacket) {
                toSend = PacketRegenerator.regenerate(p);
                if (toSend == null) {
                    continue;
                }
            }

            handler.send(toSend);
        }

        PackUtilModule module = PackUtilModule.get();
        if (module.shouldForceChannelFlush()) {
            try {
                net.minecraft.network.Connection connection = handler.getConnection();
                if (connection != null) {

                    connection.flushChannel();
                }
            } catch (Exception e) {

            }
        }
    }

    private static int countPreGeneratedActions(PackUtilMacro macro, int startIdx, int packetCount) {
        return countPreGeneratedActions(macro, startIdx, packetCount, macro.actions.size());
    }

    private static int countPreGeneratedActions(PackUtilMacro macro, int startIdx, int packetCount, int endIdx) {
        int actionsToSkip = 0;
        int packetsRemaining = packetCount;

        for (int j = startIdx; j < endIdx && packetsRemaining > 0; j++) {
            MacroAction act = macro.actions.get(j);
            if (!(act instanceof SendPacketAction)) break;

            SendPacketAction spa = (SendPacketAction) act;
            int actionPackets = spa.getPackets().size();
            packetsRemaining -= actionPackets;
            actionsToSkip++;
        }

        return actionsToSkip;
    }
}

