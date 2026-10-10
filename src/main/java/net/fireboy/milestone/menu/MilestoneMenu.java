package net.fireboy.milestone.menu;

import net.fireboy.milestone.milestone.MilestoneGameplayManager;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.milestone.StationConstructionManager;
import net.fireboy.milestone.milestone.StationSchematicClaims;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.registry.ModMenus;
import net.fireboy.milestone.task.ItemSubmissionTaskDefinition;
import net.fireboy.milestone.task.MilestoneTaskCatalog;
import net.fireboy.milestone.task.MilestoneTaskDefinition;
import net.fireboy.milestone.task.MilestoneTaskType;
import net.fireboy.milestone.task.TaskAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Mission Station interface. A single public station serves all discovered
 * Milestones; submission/secret tasks are still assigned and validated per UUID.
 * No Core chunk is loaded just to open a task or submit an item.
 */
public final class MilestoneMenu extends AbstractContainerMenu {
    public static final int BUTTON_SUBMIT_MAIN_HAND = 0;
    public static final int BUTTON_PREVIOUS = 1;
    public static final int BUTTON_NEXT = 2;
    public static final int BUTTON_CLAIM_SCHEMATIC = 3;
    public static final int BUTTON_VERIFY_STRUCTURE = 4;

    public static final int SUBMISSION_SLOT_X = 150;
    public static final int SUBMISSION_SLOT_Y = 47;
    public static final int PLAYER_INVENTORY_X = 8;
    public static final int PLAYER_INVENTORY_Y = 155;
    public static final int HOTBAR_Y = 213;

    private static final int SUBMISSION_MENU_SLOT = 0;
    private static final int PLAYER_MAIN_START = 1;
    private static final int PLAYER_MAIN_END = 28;
    private static final int PLAYER_HOTBAR_START = 28;
    private static final int PLAYER_HOTBAR_END = 37;

    private static final int DATA_STATE = 0;
    private static final int DATA_TYPE = 1;
    private static final int DATA_CONTRIBUTORS = 2;
    private static final int DATA_MINIMUM = 3;
    private static final int DATA_TASK_INDEX = 4;
    private static final int DATA_TASK_COMPLETE = 5;
    private static final int DATA_SELECTED_INDEX = 6;
    private static final int DATA_AVAILABLE_COUNT = 7;
    private static final int DATA_VERIFIED = 8;
    private static final int DATA_CORE_TIER = 9;
    private static final int DATA_COUNT = 10;

    private final BlockPos stationPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final SimpleContainer submissionContainer;
    private final Player menuPlayer;
    private final ServerLevel serverLevel;
    private final List<UUID> availableIds;
    private final int[] selection;
    private boolean processingSubmission;

    /** Client-side constructor used by the network menu factory. */
    public MilestoneMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos(), ContainerLevelAccess.NULL,
                new SimpleContainerData(DATA_COUNT), new SimpleContainer(1),
                null, List.of(), new int[]{0});
        MilestoneContentConfig.installClient(buffer.readUtf(65535));
    }

    /** Server-side constructor, called only from an actual Mission Station. */
    public MilestoneMenu(int containerId, Inventory playerInventory, ServerLevel level,
                         BlockPos stationPos, List<UUID> discoveredIds) {
        this(containerId, playerInventory, level, stationPos, List.copyOf(discoveredIds),
                new int[]{0});
    }

    private MilestoneMenu(int containerId, Inventory playerInventory, ServerLevel level,
                          BlockPos stationPos, List<UUID> ids, int[] selection) {
        this(containerId, playerInventory, stationPos,
                ContainerLevelAccess.create(level, stationPos),
                createServerData(level, ids, selection, playerInventory.player),
                new SimpleContainer(1), level, ids, selection);
    }

    private MilestoneMenu(int containerId, Inventory playerInventory,
                          BlockPos stationPos, ContainerLevelAccess access, ContainerData data,
                          SimpleContainer submissionContainer, ServerLevel serverLevel,
                          List<UUID> availableIds, int[] selection) {
        super(ModMenus.MILESTONE.get(), containerId);
        this.stationPos = stationPos.immutable();
        this.access = access;
        this.data = data;
        this.submissionContainer = submissionContainer;
        this.menuPlayer = playerInventory.player;
        this.serverLevel = serverLevel;
        this.availableIds = availableIds;
        this.selection = selection;

        checkContainerDataCount(data, DATA_COUNT);
        checkContainerSize(submissionContainer, 1);
        addDataSlots(data);
        submissionContainer.addListener(this::slotsChanged);

        addSlot(new Slot(submissionContainer, 0, SUBMISSION_SLOT_X, SUBMISSION_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                ItemSubmissionTaskDefinition task = itemSubmissionTask();
                return task != null && !taskComplete() && stack.is(task.item());
            }

            @Override
            public int getMaxStackSize(ItemStack stack) {
                ItemSubmissionTaskDefinition task = itemSubmissionTask();
                if (task == null || taskComplete() || !stack.is(task.item())) return 0;
                return Math.min(task.count(), stack.getMaxStackSize());
            }

            @Override
            public boolean isActive() {
                return itemSubmissionTask() != null && !taskComplete();
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9,
                        PLAYER_INVENTORY_X + column * 18, PLAYER_INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column,
                    PLAYER_INVENTORY_X + column * 18, HOTBAR_Y));
        }
    }

    private static ContainerData createServerData(ServerLevel level, List<UUID> ids,
                                                   int[] selection, Player player) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                if (index == DATA_SELECTED_INDEX) return selection[0];
                if (index == DATA_AVAILABLE_COUNT) return ids.size();
                MilestoneRecord record = ids.isEmpty() ? null
                        : MilestoneManager.get(level.getServer(), ids.get(selection[0])).orElse(null);
                if (record == null) return index == DATA_TASK_INDEX ? -1 : 0;

                if (index == DATA_VERIFIED) return StationConstructionManager.isVerified(
                        level.getServer(), record) ? 1 : 0;
                TaskAssignment assignment = record.assignment(player.getUUID()).orElse(null);
                return switch (index) {
                    case DATA_STATE -> record.state().ordinal();
                    case DATA_TYPE -> record.type().ordinal();
                    case DATA_CORE_TIER -> record.coreTier();
                    case DATA_CONTRIBUTORS -> record.contributorCount();
                    case DATA_MINIMUM -> record.type().minimumContributors();
                    case DATA_TASK_INDEX -> assignment == null ? -1
                            : MilestoneTaskCatalog.indexOf(record, assignment.taskId());
                    case DATA_TASK_COMPLETE -> assignment != null && assignment.completed() ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) { }

            @Override
            public int getCount() { return DATA_COUNT; }
        };
    }

    public BlockPos stationPos() { return stationPos; }
    public int selectedIndex() { return data.get(DATA_SELECTED_INDEX); }
    public int availableCount() { return data.get(DATA_AVAILABLE_COUNT); }

    public MilestoneState milestoneState() {
        int ordinal = data.get(DATA_STATE);
        MilestoneState[] values = MilestoneState.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MilestoneState.DORMANT;
    }

    public MilestoneType milestoneType() {
        int ordinal = data.get(DATA_TYPE);
        MilestoneType[] values = MilestoneType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MilestoneType.GENERIC;
    }

    public int contributorCount() { return data.get(DATA_CONTRIBUTORS); }
    public int minimumContributors() { return data.get(DATA_MINIMUM); }
    public boolean taskComplete() { return data.get(DATA_TASK_COMPLETE) != 0; }
    public boolean structureVerified() { return data.get(DATA_VERIFIED) != 0; }

    public MilestoneTaskDefinition taskDefinition() {
        if (availableCount() == 0 || !structureVerified()) return null;
        return MilestoneTaskCatalog.byIndex(milestoneType(), data.get(DATA_CORE_TIER), data.get(DATA_TASK_INDEX));
    }

    public MilestoneTaskType taskType() {
        MilestoneTaskDefinition definition = taskDefinition();
        return definition == null ? null : definition.type();
    }

    public MilestoneType.RewardThreshold nextThreshold() {
        return availableCount() == 0 ? null
                : milestoneType().nextThresholdAfter(contributorCount());
    }

    private ItemSubmissionTaskDefinition itemSubmissionTask() {
        MilestoneTaskDefinition definition = taskDefinition();
        return definition instanceof ItemSubmissionTaskDefinition itemTask ? itemTask : null;
    }

    private UUID selectedMilestoneId() {
        return availableIds.isEmpty() ? null : availableIds.get(selection[0]);
    }

    @Override
    public void slotsChanged(net.minecraft.world.Container container) {
        super.slotsChanged(container);
        if (container == submissionContainer) trySubmitFromSlot();
    }

    private void trySubmitFromSlot() {
        if (processingSubmission || !(menuPlayer instanceof ServerPlayer serverPlayer)
                || serverLevel == null || selectedMilestoneId() == null || !stillValid(serverPlayer)) return;
        ItemSubmissionTaskDefinition task = itemSubmissionTask();
        if (task == null || taskComplete()) return;

        ItemStack stack = submissionContainer.getItem(0);
        if (stack.isEmpty() || !stack.is(task.item()) || stack.getCount() < task.count()) return;

        processingSubmission = true;
        try {
            MilestoneGameplayManager.submitFromStation(
                    serverLevel, selectedMilestoneId(), serverPlayer, stack);
            if (taskComplete() && !submissionContainer.getItem(0).isEmpty()) {
                ItemStack remainder = submissionContainer.removeItemNoUpdate(0);
                serverPlayer.getInventory().placeItemBackInInventory(remainder);
            }
            submissionContainer.setChanged();
            broadcastChanges();
        } finally {
            processingSubmission = false;
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverLevel == null
                || !stillValid(serverPlayer)) return false;

        if (id == BUTTON_PREVIOUS || id == BUTTON_NEXT) {
            if (availableIds.size() <= 1) return false;
            // Return any partial offering before switching to a different task.
            ItemStack pending = submissionContainer.removeItemNoUpdate(0);
            if (!pending.isEmpty()) serverPlayer.getInventory().placeItemBackInInventory(pending);
            selection[0] = Math.floorMod(selection[0] + (id == BUTTON_NEXT ? 1 : -1),
                    availableIds.size());
            MilestoneRecord record = MilestoneManager.get(serverLevel.getServer(),
                    selectedMilestoneId()).orElse(null);
            if (record != null) {
                ServerLevel owningLevel = MilestoneManager.levelFor(serverLevel.getServer(), record);
                if (owningLevel != null
                        && StationConstructionManager.isVerified(serverLevel.getServer(), record)) {
                    MilestoneGameplayManager.ensureAssignment(owningLevel, record, serverPlayer);
                }
            }
            broadcastChanges();
            return true;
        }

        if (id == BUTTON_CLAIM_SCHEMATIC || id == BUTTON_VERIFY_STRUCTURE) {
            MilestoneRecord record = selectedMilestoneId() == null ? null
                    : MilestoneManager.get(serverLevel.getServer(), selectedMilestoneId()).orElse(null);
            if (record == null || record.discoveredBy() == null) return false;
            if (id == BUTTON_CLAIM_SCHEMATIC) {
                StationSchematicClaims.claim(serverPlayer, record);
            } else {
                String result = StationConstructionManager.verify(serverLevel, stationPos, record);
                serverPlayer.sendSystemMessage(Component.literal(result)
                        .withStyle(StationConstructionManager.isVerified(
                                serverLevel.getServer(), record)
                                ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                if (StationConstructionManager.isVerified(serverLevel.getServer(), record)) {
                    ServerLevel owning = MilestoneManager.levelFor(serverLevel.getServer(), record);
                    if (owning != null) MilestoneGameplayManager.ensureAssignment(owning, record, serverPlayer);
                }
            }
            broadcastChanges();
            return true;
        }
        if (id != BUTTON_SUBMIT_MAIN_HAND || selectedMilestoneId() == null
                || !(taskDefinition() instanceof ItemSubmissionTaskDefinition)) return false;
        MilestoneGameplayManager.submitFromStation(serverLevel, selectedMilestoneId(),
                serverPlayer, serverPlayer.getMainHandItem());
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == SUBMISSION_MENU_SLOT) {
            if (!moveItemStackTo(stack, PLAYER_MAIN_START, PLAYER_HOTBAR_END, true)) return ItemStack.EMPTY;
        } else if (slots.get(SUBMISSION_MENU_SLOT).mayPlace(stack)) {
            if (!moveItemStackTo(stack, SUBMISSION_MENU_SLOT, SUBMISSION_MENU_SLOT + 1, false))
                return ItemStack.EMPTY;
        } else if (index >= PLAYER_MAIN_START && index < PLAYER_MAIN_END) {
            if (!moveItemStackTo(stack, PLAYER_HOTBAR_START, PLAYER_HOTBAR_END, false))
                return ItemStack.EMPTY;
        } else if (index >= PLAYER_HOTBAR_START && index < PLAYER_HOTBAR_END) {
            if (!moveItemStackTo(stack, PLAYER_MAIN_START, PLAYER_MAIN_END, false))
                return ItemStack.EMPTY;
        } else return ItemStack.EMPTY;

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, submissionContainer);
    }

    @Override
    public boolean stillValid(Player player) {
        return AbstractContainerMenu.stillValid(access, player, ModBlocks.MISSION_STATION.get());
    }
}
